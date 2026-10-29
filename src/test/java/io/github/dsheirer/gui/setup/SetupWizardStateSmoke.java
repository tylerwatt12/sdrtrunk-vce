package io.github.dsheirer.gui.setup;

import com.formdev.flatlaf.FlatLightLaf;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;

/** Real Swing state checks with a disposable profile; never starts a receiver or contacts a provider. */
public final class SetupWizardStateSmoke
{
    public static void main(String[] args) throws Exception
    {
        if(args.length != 1) throw new IllegalArgumentException("Supply a new wizard-smoke- disposable data root");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if(!root.getFileName().toString().startsWith("wizard-smoke-") || Files.exists(root))
            throw new IllegalArgumentException("Use a new wizard-smoke- disposable data root");
        System.setProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY, root.toString());
        SdrTrunkDatabaseStartup.createGlobalDatabase(SdrTrunkDatabasePath.getDatabasePath(root));
        SqlitePreferencesFactory.install(SdrTrunkDatabasePath.getDatabasePath(root));
        UserPreferences preferences = new UserPreferences();
        SetupWizard[] holder = {null};
        int status = 0;
        try
        {
            onEdt(() -> {
                FlatLightLaf.setup();
                var constructor = SetupWizard.class.getDeclaredConstructor(String[].class, Path.class,
                    PortableDataRootLock.class);
                constructor.setAccessible(true);
                SetupWizard wizard = constructor.newInstance(new String[]{"--setup-wizard"}, root, null);
                holder[0] = wizard;
                put(wizard, "preferences", preferences);
                put(wizard, "progress", new SetupProgress(true, false));
                wizard.setModal(false);
                wizard.setVisible(true);
            });
            SetupWizard wizard = holder[0];
            discardedRadioReferenceDraft(wizard);
            failedRadioReferenceTest(wizard);
            refusedRadioReferenceTest(wizard);
            reviewedWebAccess(wizard, preferences);
            launchChoiceSurvivesRebuild(wizard);
            listenerRecoveryNavigation(wizard);
            System.out.println("Wizard state smoke passed: 6 scenarios");
        }
        catch(Throwable failure) { status = 2; failure.printStackTrace(); }
        finally
        {
            if(holder[0] != null)
                onEdt(() -> {
                    call(holder[0], "closeRadioReferenceSetup", new Class<?>[0]);
                    SetupWizardStateSmoke.<Timer>field(holder[0], "refresh").stop();
                    SetupWizardStateSmoke.<ExecutorService>field(holder[0], "worker").shutdownNow();
                    holder[0].dispose();
                });
            SqlitePreferencesFactory.shutdown();
            System.exit(status);
        }
    }

    private static void discardedRadioReferenceDraft(SetupWizard wizard) throws Exception
    {
        onEdt(() -> {
            show(wizard, SetupStep.RADIO_REFERENCE);
            input(wizard, "RadioReference username").setText("disposable-unsaved-account");
            input(wizard, "Password").setText("disposable-unsaved-password");
            JPanel verification = descendants(wizard).stream().filter(component -> component instanceof JPanel &&
                component.getAccessibleContext() != null && "RadioReference connection status".equals(
                    component.getAccessibleContext().getAccessibleName())).map(JPanel.class::cast).findFirst().orElseThrow();
            //Simulate only the provider result; no account request or session is created.
            call(wizard, "showRadioReferenceResult", new Class<?>[]{JPanel.class, boolean.class}, verification, true);
            require(text(wizard, "Connection verified: premium access available"), "Verified fixture result missing");
            SetupWizardStateSmoke.<JButton>field(wizard, "back").doClick();
            show(wizard, SetupStep.RADIO_REFERENCE);
            require(input(wizard, "RadioReference username").getText().isBlank(), "Unsaved account draft was retained");
            require(!text(wizard, "Connection verified:"), "Discarded account draft left stale verification");
            require(text(wizard, "No account saved."), "Reopened account status does not match saved credentials");
        });
        System.out.println("PASS: discarded RadioReference verification");
    }

    private static void failedRadioReferenceTest(SetupWizard wizard) throws Exception
    {
        onEdt(() -> {
            show(wizard, SetupStep.RADIO_REFERENCE);
            //Empty credentials are rejected before any provider gateway or network request is opened.
            require(input(wizard, "RadioReference username").getText().isBlank(), "Failure fixture must have no account");
            button(wizard, "Test connection").doClick();
        });
        awaitIdle(wizard);
        onEdt(() -> {
            require(!text(wizard, "Checking account access…"), "Failed connection retained its pending status");
            require(SetupWizardStateSmoke.<JTextComponent>field(wizard, "danger").isVisible(), "Failed connection hid its error");
            require(!SetupWizardStateSmoke.<Boolean>field(wizard, "rrVerified"), "Failed connection remained verified");
        });
        System.out.println("PASS: failed RadioReference pending status");
    }

    private static void reviewedWebAccess(SetupWizard wizard, UserPreferences preferences) throws Exception
    {
        onEdt(() -> {
            preferences.getApplicationPreference().setStatsWebServerNetworkAccessEnabled(false);
            preferences.getApplicationPreference().setStatsWebServerHttpsEnabled(true);
            preferences.getApplicationPreference().setStatsWebServerEnabled(true);
            put(wizard, "webAdjusted", true);
            show(wizard, SetupStep.WEB);
            require(text(wizard, "Other devices cannot connect."), "Automatic local-only notice missing");
            descendants(wizard).stream().filter(component -> component instanceof JRadioButton radio &&
                "Other devices".equals(radio.getAccessibleContext().getAccessibleName()))
                .map(JRadioButton.class::cast).findFirst().orElseThrow().setSelected(true);
            wizard.getRootPane().getDefaultButton().doClick();
            show(wizard, SetupStep.WEB);
            require(preferences.getApplicationPreference().isStatsWebServerAnyIpEnabled(), "Web choice was not saved");
            require(!text(wizard, "Other devices cannot connect."), "Reviewed network choice retained local-only notice");
        });
        System.out.println("PASS: reviewed web access notice");
    }

    private static void refusedRadioReferenceTest(SetupWizard wizard) throws Exception
    {
        onEdt(() -> {
            show(wizard, SetupStep.RADIO_REFERENCE);
            Path database = field(wizard, "database");
            Path missing = database.getParent().getParent().resolve("missing/database/sdrtrunk.sqlite");
            try
            {
                put(wizard, "database", missing);
                button(wizard, "Test connection").doClick();
                require(!SetupWizardStateSmoke.<Boolean>field(wizard, "busy"), "Save-refused connection remained busy");
                require(!text(wizard, "Checking account access…"), "Save-refused connection retained its pending status");
                JTextComponent error = field(wizard, "danger");
                require(error.isVisible() && error.getText().contains("Settings could not be saved."),
                    "Save-refused connection replaced its specific save error");
                require(!Files.exists(missing), "Save refusal created a replacement database");
            }
            finally { put(wizard, "database", database); }
        });
        System.out.println("PASS: save-refused RadioReference pending status");
    }

    private static void launchChoiceSurvivesRebuild(SetupWizard wizard) throws Exception
    {
        onEdt(() -> {
            put(wizard, "autoStart", List.of("Disposable channel"));
            put(wizard, "startChannels", true);
            show(wizard, SetupStep.REVIEW);
            JCheckBox launch = launchChoice(wizard);
            require(launch.isSelected(), "Launch fixture must initially start selected");
            launch.doClick();
            show(wizard, SetupStep.REVIEW);
            require(!launchChoice(wizard).isSelected(), "Review rebuild discarded unchecked channel launch choice");
        });
        System.out.println("PASS: review launch choice rebuild");
    }

    private static void listenerRecoveryNavigation(SetupWizard wizard) throws Exception
    {
        onEdt(() -> {
            put(wizard, "listenerRecovery", true);
            put(wizard, "autoStart", List.of("Disposable channel"));
            show(wizard, SetupStep.REVIEW);
            require(descendants(wizard).stream().noneMatch(component -> component instanceof JCheckBox box &&
                box.isShowing() && "Start these channels when VCE opens".equals(box.getText())),
                "Listener recovery exposes a channel launch choice it cannot return");
            require(!text(wizard, "Channels selected to start:"), "Listener recovery shows irrelevant channel launch summary");
            SetupWizardStateSmoke.<JButton>field(wizard, "back").doClick();
            require(field(wizard, "step") == SetupStep.WEB, "Recovery Back escaped Web/Review");
            require(!SetupWizardStateSmoke.<JButton>field(wizard, "back").isEnabled(), "Recovery Web Back escapes into other setup steps");
            EnumMap<SetupStep, JButton> steps = field(wizard, "steps");
            for(var entry : steps.entrySet())
                if(entry.getKey() != SetupStep.WEB && entry.getKey() != SetupStep.REVIEW)
                    require(!entry.getValue().isEnabled(), "Recovery enables unrelated step " + entry.getKey());
        });
        System.out.println("PASS: listener recovery navigation and launch controls");
    }

    private static JCheckBox launchChoice(SetupWizard wizard)
    {
        return descendants(wizard).stream().filter(component -> component instanceof JCheckBox box &&
            box.isShowing() && "Start these channels when VCE opens".equals(box.getText()))
            .map(JCheckBox.class::cast).findFirst().orElseThrow();
    }

    private static JTextComponent input(SetupWizard wizard, String name)
    {
        return descendants(wizard).stream().filter(component -> component instanceof JTextComponent &&
            component.isShowing() && name.equals(component.getAccessibleContext().getAccessibleName()))
            .map(JTextComponent.class::cast).findFirst().orElseThrow();
    }

    private static AbstractButton button(SetupWizard wizard, String caption)
    {
        return descendants(wizard).stream().filter(component -> component instanceof AbstractButton button &&
            button.isShowing() && caption.equals(button.getText())).map(AbstractButton.class::cast).findFirst().orElseThrow();
    }

    private static boolean text(SetupWizard wizard, String value)
    {
        return descendants(wizard).stream().anyMatch(component -> component.isShowing() &&
            (component instanceof JTextComponent content && content.getText().contains(value) ||
                component instanceof JLabel label && label.getText() != null && label.getText().contains(value)));
    }

    private static List<Component> descendants(Container parent)
    {
        List<Component> result = new ArrayList<>();
        for(Component component : parent.getComponents())
        {
            result.add(component);
            if(component instanceof Container child) result.addAll(descendants(child));
        }
        return result;
    }

    private static void show(SetupWizard wizard, SetupStep step) throws Exception
    { call(wizard, "showPage", new Class<?>[]{SetupStep.class}, step); }

    @SuppressWarnings("unchecked")
    private static <T> T field(SetupWizard wizard, String name) throws ReflectiveOperationException
    {
        var field = SetupWizard.class.getDeclaredField(name); field.setAccessible(true); return (T)field.get(wizard);
    }

    private static void put(SetupWizard wizard, String name, Object value) throws ReflectiveOperationException
    {
        var field = SetupWizard.class.getDeclaredField(name); field.setAccessible(true); field.set(wizard, value);
    }

    private static void call(SetupWizard wizard, String name, Class<?>[] types, Object... arguments) throws Exception
    {
        var method = SetupWizard.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { method.invoke(wizard, arguments); }
        catch(InvocationTargetException failure)
        {
            if(failure.getCause() instanceof Error error) throw error;
            if(failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private static void awaitIdle(SetupWizard wizard) throws Exception
    {
        for(int attempt = 0; attempt < 250; attempt++)
        {
            boolean[] idle = {false};
            onEdt(() -> idle[0] = !SetupWizardStateSmoke.<Boolean>field(wizard, "busy"));
            if(idle[0]) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Fixture operation did not finish");
    }

    private static void require(boolean condition, String message)
    { if(!condition) throw new AssertionError(message); }

    @FunctionalInterface private interface EdtAction { void run() throws Exception; }

    private static void onEdt(EdtAction action) throws Exception
    {
        Throwable[] problem = {null};
        SwingUtilities.invokeAndWait(() -> { try { action.run(); } catch(Throwable failure) { problem[0] = failure; } });
        if(problem[0] instanceof Error error) throw error;
        if(problem[0] instanceof Exception exception) throw exception;
    }
}
