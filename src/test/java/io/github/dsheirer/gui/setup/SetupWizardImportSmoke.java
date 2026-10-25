package io.github.dsheirer.gui.setup;

import io.github.dsheirer.database.InitialAdminSetup;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService;
import io.github.dsheirer.database.upgrade.ApplicationMigrationService;
import io.github.dsheirer.portable.PortableApplicationPaths;
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
import javax.swing.SwingUtilities;

/** Disposable graphical integration harness. NEVER constructs SDRTrunk, starts channels, or relaunches production. */
public final class SetupWizardImportSmoke
{
    public static void main(String[] args) throws Exception
    {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if(!root.getFileName().toString().startsWith("wizard-smoke-")) throw new IllegalArgumentException("Disposable root required");
        String mode = System.getProperty("wizard.exercise", "existing");
        System.setProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY, root.toString());
        Path database = SdrTrunkDatabasePath.getDatabasePath(root);
        Path xml = root.resolveSibling(root.getFileName() + "-playlist.xml");
        Path source = root.resolveSibling(root.getFileName() + "-source.sqlite");
        if(!mode.equals("post-replacement"))
        {
            if(Files.exists(database)) throw new IllegalArgumentException("Use a new disposable root");
            SdrTrunkDatabaseStartup.createGlobalDatabase(database);
            InitialAdminSetup.provision(database, "disposable-password".toCharArray());
            var complete = new SetupProgress(true, false);
            complete.set(SetupStep.HARDWARE, SetupProgress.State.DEFERRED);
            complete.save(database);
            Files.writeString(xml, "<playlist version=\"4\"><alias name=\"Smoke alias\" list=\"Smoke\"><id type=\"talkgroup\" protocol=\"DMR\" value=\"200\"/></alias></playlist>");
            var importer = new LegacyPlaylistImportService(database);
            importer.execute(importer.prepare(xml));
            SdrTrunkDatabaseStartup.createGlobalDatabase(source);
            InitialAdminSetup.provision(source, "source-password".toCharArray());
            new SetupProgress(true, false).save(source);
        }
        byte[] sourceBefore = Files.readAllBytes(source);
        byte[] xmlBefore = Files.readAllBytes(xml);
        Thread driver = new Thread(() -> {
            try { exercise(root, mode, database, xml, source); }
            catch(Throwable e) { e.printStackTrace(); System.exit(2); }
        }, "wizard import smoke driver");
        driver.setDaemon(true);
        driver.start();
        int status = 0;
        try
        {
            var result = SetupWizard.run(mode.equals("post-replacement") ? new String[0] : new String[]{"--setup-wizard"}, root, null);
            if(mode.equals("sqlite"))
            {
                if(result == null || result.replacement() == null) throw new AssertionError("Confirmed replacement not returned to startup");
                //Exercise the same pre-receiver boundary, without starting a real receiver process.
                java.util.prefs.Preferences.userRoot().flush();
                SqlitePreferencesFactory.shutdown();
                var migration = new ApplicationMigrationService().replaceCurrentDatabase(result.replacement().sourceDatabase(),
                    root, result.replacement().approval(), null);
                if(aliasCount(database) != 0 || aliasCount(migration.safetyBackup()) != 1)
                    throw new AssertionError("Replacement/backup scope changed");
                if(SetupProgress.read(database).isComplete()) throw new AssertionError("Replacement bypasses review");
            }
            else if(!mode.equals("post-replacement") && aliasCount(database) != (mode.equals("xml") ? 2 : 1))
                throw new AssertionError("Unexpected merge or cancelled-import side effect");
            if(result != null) result.lock().close();
            if(!Arrays.equals(sourceBefore, Files.readAllBytes(source)) || !Arrays.equals(xmlBefore, Files.readAllBytes(xml)))
                throw new AssertionError("Import source changed");
            System.out.println("Wizard import smoke passed: " + mode);
        }
        catch(Throwable e) { status = 2; e.printStackTrace(); }
        finally { SqlitePreferencesFactory.shutdown(); System.exit(status); }
    }

    private static void exercise(Path root, String mode, Path database, Path xml, Path source) throws Exception
    {
        SetupWizard[] target = {null};
        await(() -> {
            for(Window window : Window.getWindows())
                if(window instanceof SetupWizard wizard && wizard.isShowing()) { target[0] = wizard; return true; }
            return false;
        });
        SetupWizard wizard = target[0];
        if(mode.equals("post-replacement"))
        {
            if(SetupProgress.read(database).isComplete()) throw new AssertionError("Normal restart bypasses review");
            SwingUtilities.invokeAndWait(() -> {
                if(hasButton(wizard, "Choose different settings")) throw new AssertionError("Restart offered another import");
            });
            click(wizard, "Exit setup");
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            if(!wizard.getRootPane().getDefaultButton().getText().equals("Continue") ||
                !hasButton(wizard, "Choose different settings")) throw new AssertionError("Existing settings are not ready to continue");
        });
        capture(wizard, root.resolveSibling(root.getFileName() + "-light.png"));
        click(wizard, "Dark mode");
        capture(wizard, root.resolveSibling(root.getFileName() + "-dark.png"));
        choose(wizard, null); //Cancel the file chooser.
        await(() -> !hasChooser());
        Path selected = mode.equals("xml") ? xml : source;
        String confirm = mode.equals("xml") ? "Import playlist" : "Replace settings and restart";
        choose(wizard, selected);
        awaitReview(wizard, confirm);
        capture(wizard, root.resolveSibling(root.getFileName() + "-review.png"));
        if(mode.equals("existing"))
        {
            Path invalid = root.resolveSibling(root.getFileName() + "-invalid.sqlite");
            Files.writeString(invalid, "This is not saved VCE settings.");
            choose(wizard, invalid);
            awaitReview(wizard, "Check settings");
            click(wizard, "Check settings");
            awaitReview(wizard, "Check settings");
            if(aliasCount(database) != 1) throw new AssertionError("An invalid selection applied the previous review");
        }
        click(wizard, "Back");
        awaitReview(wizard, "Continue");
        if(aliasCount(database) != 1) throw new AssertionError("Leaving review changed configuration");
        if(mode.equals("existing")) { click(wizard, "Exit setup"); return; }
        choose(wizard, selected);
        awaitReview(wizard, confirm);
        click(wizard, confirm);
        if(mode.equals("sqlite")) return;
        await(() -> components(wizard).stream().anyMatch(c -> c instanceof WizardNotice notice &&
            c.isShowing() && "Settings ready".equals(notice.getAccessibleContext().getAccessibleName())));
        capture(wizard, root.resolveSibling(root.getFileName() + "-result.png"));
        for(int i = 0; i < SetupStep.values().length && !onSource(wizard); i++) click(wizard, "Back");
        SwingUtilities.invokeAndWait(() -> {
            if(!onSource(wizard) || hasButton(wizard, "Choose different settings") || hasButton(wizard, "Import playlist"))
                throw new AssertionError("Back offered to repeat the committed import");
        });
        click(wizard, "Exit setup");
    }

    private static void choose(SetupWizard wizard, Path source) throws Exception
    {
        SwingUtilities.invokeLater(() -> button(wizard, "Choose different settings").doClick());
        await(SetupWizardImportSmoke::hasChooser);
        SwingUtilities.invokeAndWait(() -> {
            for(Window window : Window.getWindows()) for(Component component : components(window))
                if(component instanceof JFileChooser chooser && chooser.isShowing())
                {
                    if(source == null) chooser.cancelSelection();
                    else { chooser.setSelectedFile(source.toFile()); chooser.approveSelection(); }
                    return;
                }
        });
    }
    private static boolean hasChooser()
    { return Arrays.stream(Window.getWindows()).flatMap(w -> components(w).stream()).anyMatch(c -> c instanceof JFileChooser && c.isShowing()); }
    private static void awaitReview(SetupWizard wizard, String action) throws Exception
    {
        await(() -> !hasChooser() && wizard.getRootPane().getDefaultButton().isEnabled() &&
            wizard.getRootPane().getDefaultButton().getText().equals(action));
        SwingUtilities.invokeAndWait(() -> {
            if(Arrays.stream(Window.getWindows()).anyMatch(window -> window != wizard && window.isShowing()))
                throw new AssertionError("Settings review opened another window");
        });
    }
    private static boolean onSource(SetupWizard wizard)
    { return components(wizard).stream().anyMatch(c -> c instanceof JLabel label && label.isShowing() && label.getText().equals(SetupStep.SOURCE.title())); }
    private static boolean hasButton(SetupWizard wizard, String title)
    { return components(wizard).stream().anyMatch(c -> c instanceof JButton button && button.isShowing() && button.getText().equals(title)); }
    private static JButton button(SetupWizard wizard, String title)
    { return components(wizard).stream().filter(c -> c instanceof JButton b && b.isShowing() && b.isEnabled() && b.getText().equals(title)).map(JButton.class::cast).findFirst().orElseThrow(() -> new AssertionError("Button unavailable: " + title)); }
    private static void click(SetupWizard wizard, String title) throws Exception
    {
        SwingUtilities.invokeAndWait(() -> button(wizard, title).doClick());
    }
    private static List<Component> components(Container parent)
    {
        var result = new ArrayList<Component>();
        for(Component component : parent.getComponents()) { result.add(component); if(component instanceof Container child) result.addAll(components(child)); }
        return result;
    }
    private static void await(BooleanSupplier condition) throws Exception
    {
        for(int i = 0; i < 400; i++)
        {
            boolean[] ready = {false}; SwingUtilities.invokeAndWait(() -> ready[0] = condition.getAsBoolean());
            if(ready[0]) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for wizard UI");
    }
    private static long aliasCount(Path database) throws Exception
    {
        try(var connection = SdrTrunkDatabase.open(database); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM alias"))
        { rows.next(); return rows.getLong(1); }
    }
    private static void capture(SetupWizard wizard, Path path) throws Exception
    {
        SwingUtilities.invokeAndWait(wizard::validate);
        SwingUtilities.invokeAndWait(() -> {
            var image = new java.awt.image.BufferedImage(wizard.getWidth(), wizard.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); wizard.paintAll(graphics); graphics.dispose();
            try { javax.imageio.ImageIO.write(image, "png", path.toFile()); }
            catch(Exception e) { throw new RuntimeException(e); }
        });
    }
}
