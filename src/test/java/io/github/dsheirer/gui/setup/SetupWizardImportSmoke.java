package io.github.dsheirer.gui.setup;

import io.github.dsheirer.database.InitialAdminSetup;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;

/** Disposable startup/input checks. Never constructs a receiver, scans hardware, or relaunches production. */
public final class SetupWizardImportSmoke
{
    public static void main(String[] args) throws Exception
    {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if(!root.getFileName().toString().startsWith("wizard-smoke-") || Files.exists(root))
            throw new IllegalArgumentException("Use a new wizard-smoke- disposable root");
        String mode = System.getProperty("wizard.exercise", "folder");
        System.setProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY, root.toString());
        Path database = SdrTrunkDatabasePath.getDatabasePath(root);
        Path xml = root.resolveSibling(root.getFileName() + "-playlist.xml");
        Files.writeString(xml, "<playlist version=\"4\"><alias name=\"Smoke alias\" list=\"Smoke\"><id type=\"talkgroup\" protocol=\"DMR\" value=\"200\"/></alias></playlist>");
        boolean startup = List.of("complete", "administrator", "web").contains(mode);
        Path previous = root.resolveSibling(root.getFileName() + "-previous").resolve("data");
        Path sourceDatabase = startup ? database : SdrTrunkDatabasePath.getDatabasePath(previous);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        if(!mode.equals("administrator")) InitialAdminSetup.provision(sourceDatabase, "disposable-password".toCharArray());
        var progress = new SetupProgress(true, false);
        progress.set(SetupStep.HARDWARE, SetupProgress.State.DEFERRED);
        progress.set(SetupStep.JMBE, SetupProgress.State.NEEDS_ATTENTION);
        progress.set(SetupStep.CALIBRATION, SetupProgress.State.DEFERRED);
        progress.save(sourceDatabase);
        if(startup)
        {
            SqlitePreferencesFactory.install(database);
            UserPreferences preferences = new UserPreferences();
            preferences.getApplicationPreference().setStatsWebServerEnabled(!mode.equals("web"));
            preferences.getApplicationPreference().setStatsWebServerHttpsEnabled(true);
            preferences.getVectorCalibrationPreference().setHideCalibrationDialog(false);
            java.util.prefs.Preferences.userRoot().flush();
        }
        else
        {
            var importer = new LegacyPlaylistImportService(sourceDatabase);
            importer.execute(importer.prepare(xml));
        }
        Path selected = mode.equals("xml") ? xml : mode.equals("sqlite") ? sourceDatabase : previous;
        byte[] sourceBefore = Files.readAllBytes(sourceDatabase), xmlBefore = Files.readAllBytes(xml);
        int status = 0;
        try
        {
            boolean repair = mode.equals("administrator") || mode.equals("web");
            for(int visit = 0; visit < (repair ? 2 : 1); visit++)
            {
                if(!mode.equals("complete"))
                {
                    Thread driver = new Thread(() -> {
                        try { exercise(root, mode, selected); }
                        catch(Throwable failure) { failure.printStackTrace(); System.exit(2); }
                    }, "wizard startup smoke driver");
                    driver.setDaemon(true); driver.start();
                }
                var result = SetupWizard.run(new String[]{"--setup-wizard"}, root, null);
                if(mode.equals("complete") && (result == null || !SetupProgress.read(database).isComplete() ||
                    Arrays.stream(Window.getWindows()).anyMatch(Window::isShowing)))
                    throw new AssertionError("Optional outstanding work reopened completed setup");
                if(repair && !SetupProgress.read(database).isComplete())
                    throw new AssertionError("Exiting a required repair made completed setup incomplete");
                if(result != null) result.lock().close();
            }
            if(!startup)
            {
                if(!Arrays.equals(sourceBefore, Files.readAllBytes(sourceDatabase)) || !Arrays.equals(xmlBefore, Files.readAllBytes(xml)))
                    throw new AssertionError("The selected source changed");
                if(mode.equals("sqlite"))
                {
                    if(Files.exists(root)) throw new AssertionError("A rejected standalone database wrote to the destination");
                }
                else if(aliasCount(database) != 1 || SetupProgress.read(database).isComplete() ||
                    !SetupProgress.read(database).isDone(SetupStep.SOURCE))
                    throw new AssertionError("Import lost configuration or bypassed setup review");
            }
            System.out.println("Wizard startup smoke passed: " + mode);
        }
        catch(Throwable failure) { status = 2; failure.printStackTrace(); }
        finally { SqlitePreferencesFactory.shutdown(); System.exit(status); }
    }

    private static void exercise(Path root, String mode, Path selected) throws Exception
    {
        SetupWizard[] holder = {null};
        await(() -> {
            for(Window window : Window.getWindows())
                if(window instanceof SetupWizard wizard && wizard.isShowing()) { holder[0] = wizard; return true; }
            return false;
        });
        SetupWizard wizard = holder[0];
        if(mode.equals("administrator") || mode.equals("web"))
        {
            SetupStep required = mode.equals("administrator") ? SetupStep.ADMINISTRATOR : SetupStep.WEB;
            await(() -> components(wizard).stream().anyMatch(c -> c instanceof JLabel label && label.isShowing() && label.getText().equals(required.title())));
            var limited = SetupWizard.class.getDeclaredField("limitedVisit"); limited.setAccessible(true);
            if(!limited.getBoolean(wizard)) throw new AssertionError("Required repair expanded into a full setup visit");
            click(wizard, "Exit setup"); return;
        }
        SwingUtilities.invokeAndWait(() -> components(wizard).stream()
            .filter(c -> c instanceof JRadioButton radio && radio.isShowing() && radio.getAccessibleContext().getAccessibleName().equals("Bring my settings"))
            .map(JRadioButton.class::cast).findFirst().orElseThrow().setSelected(true));
        choose(wizard, null);
        if(Files.exists(root)) throw new AssertionError("Cancelling the chooser created settings");
        choose(wizard, selected);
        String action = mode.equals("xml") ? "Import playlist" : mode.equals("sqlite") ? "Check settings" : "Import settings";
        awaitAction(wizard, action);
        if(Files.exists(root)) throw new AssertionError("Source review created the destination");
        capture(wizard, root.resolveSibling(root.getFileName() + "-review.png"));
        if(mode.equals("sqlite")) { click(wizard, "Exit setup"); return; }
        click(wizard, "Back");
        if(Files.exists(root)) throw new AssertionError("Back applied an unconfirmed import");
        primary(wizard);
        awaitAction(wizard, action);
        click(wizard, action);
        await(() -> Files.isRegularFile(SdrTrunkDatabasePath.getDatabasePath(root)) &&
            !wizard.getRootPane().getDefaultButton().getText().equals(action) &&
            components(wizard).stream().anyMatch(c -> c instanceof JButton button && button.isShowing() &&
                button.isEnabled() && button.getText().contains("1. " + SetupStep.SOURCE.title())));
        SwingUtilities.invokeAndWait(() -> components(wizard).stream().filter(c -> c instanceof JButton button &&
            button.isShowing() && button.isEnabled() && button.getText().contains("1. " + SetupStep.SOURCE.title()))
            .map(JButton.class::cast).findFirst().orElseThrow().doClick());
        if(components(wizard).stream().anyMatch(c -> c instanceof JRadioButton && c.isShowing()))
            throw new AssertionError("Back offered to repeat the committed import");
        click(wizard, "Exit setup");
    }

    private static void choose(SetupWizard wizard, Path selected) throws Exception
    {
        SwingUtilities.invokeLater(() -> wizard.getRootPane().getDefaultButton().doClick());
        await(() -> Arrays.stream(Window.getWindows()).flatMap(w -> components(w).stream()).anyMatch(c -> c instanceof JFileChooser && c.isShowing()));
        SwingUtilities.invokeAndWait(() -> {
            for(Window window : Window.getWindows()) for(Component c : components(window))
                if(c instanceof JFileChooser chooser && chooser.isShowing())
                { if(selected == null) chooser.cancelSelection(); else { chooser.setSelectedFile(selected.toFile()); chooser.approveSelection(); } return; }
        });
    }
    private static void awaitAction(SetupWizard wizard, String label) throws Exception
    { await(() -> wizard.getRootPane().getDefaultButton().isEnabled() && wizard.getRootPane().getDefaultButton().getText().equals(label)); }
    private static void primary(SetupWizard wizard) throws Exception
    { SwingUtilities.invokeAndWait(() -> wizard.getRootPane().getDefaultButton().doClick()); }
    private static void click(SetupWizard wizard, String title) throws Exception
    { SwingUtilities.invokeAndWait(() -> components(wizard).stream().filter(c -> c instanceof JButton b && b.isShowing() && b.isEnabled() && b.getText().equals(title)).map(JButton.class::cast).findFirst().orElseThrow().doClick()); }
    private static List<Component> components(Container parent)
    {
        var result = new ArrayList<Component>();
        for(Component c : parent.getComponents()) { result.add(c); if(c instanceof Container child) result.addAll(components(child)); }
        return result;
    }
    private static void await(BooleanSupplier condition) throws Exception
    {
        for(int attempt = 0; attempt < 400; attempt++)
        {
            boolean[] ready = {false}; SwingUtilities.invokeAndWait(() -> ready[0] = condition.getAsBoolean());
            if(ready[0]) return; Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for startup setup");
    }
    private static long aliasCount(Path database) throws Exception
    {
        try(var connection = SdrTrunkDatabase.open(database); var query = connection.createStatement(); var rows = query.executeQuery("SELECT COUNT(*) FROM alias"))
        { rows.next(); return rows.getLong(1); }
    }
    private static void capture(SetupWizard wizard, Path path) throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            wizard.validate(); var image = new java.awt.image.BufferedImage(wizard.getWidth(), wizard.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); wizard.paintAll(graphics); graphics.dispose();
            try { javax.imageio.ImageIO.write(image, "png", path.toFile()); }
            catch(Exception failure) { throw new RuntimeException(failure); }
        });
    }
}
