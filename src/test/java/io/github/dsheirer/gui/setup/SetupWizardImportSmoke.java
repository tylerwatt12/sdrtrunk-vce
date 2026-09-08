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
import javax.swing.JRadioButton;
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
            new SetupProgress(true, false).save(database);
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
                if(components(wizard).stream().anyMatch(c -> c instanceof JRadioButton radio && radio.isShowing() &&
                    radio.getAccessibleContext().getAccessibleName().startsWith("Keep my current"))) throw new AssertionError("Restart offered another import");
            });
            click(wizard, "Exit setup");
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            List<JRadioButton> choices = components(wizard).stream().filter(c -> c instanceof JRadioButton && c.isShowing())
                .map(c -> (JRadioButton)c).toList();
            if(choices.size() != 3 || !choices.getFirst().isSelected()) throw new AssertionError("Existing-profile choices/default wrong");
            if(choices.stream().anyMatch(c -> c.getAccessibleContext().getAccessibleName().contains("Start fresh") || c.getAccessibleContext().getAccessibleName().contains("previous VCE")))
                throw new AssertionError("Destructive new-install actions offered on existing profile");
        });
        capture(wizard, root.resolveSibling(root.getFileName() + "-light.png"));
        click(wizard, "Dark mode");
        capture(wizard, root.resolveSibling(root.getFileName() + "-dark.png"));
        if(mode.equals("existing"))
        {
            select(wizard, "Replace settings");
            if(aliasCount(database) != 1) throw new AssertionError("Selecting an option changed configuration");
            select(wizard, "Keep my current");
            click(wizard, "Exit setup");
            return;
        }
        select(wizard, mode.equals("sqlite") ? "Replace settings" : "Import a legacy");
        choose(wizard, null); //Cancel the file chooser.
        await(() -> !hasChooser());
        choose(wizard, mode.equals("sqlite") ? source : xml);
        awaitButton("Cancel");
        clickVisibleDialogButton("Cancel"); //Cancel the actual preview too.
        await(() -> wizard.isShowing() && !hasChooser());
        if(aliasCount(database) != 1) throw new AssertionError("Cancelled preview changed configuration");
        choose(wizard, mode.equals("sqlite") ? source : xml);
        String confirm = mode.equals("sqlite") ? "Replace Database and Restart" : "Import playlist";
        awaitButton(confirm);
        clickVisibleDialogButton(confirm);
        if(mode.equals("sqlite")) return;
        await(() -> components(wizard).stream().anyMatch(c -> c instanceof javax.swing.text.JTextComponent text &&
            c.isShowing() && text.getText().endsWith("Your playlist was imported")));
        SwingUtilities.invokeAndWait(() -> {
            if(components(wizard).stream().anyMatch(c -> c instanceof JRadioButton && c.isShowing()))
                throw new AssertionError("Completed import still offers source replacement");
        });
        capture(wizard, root.resolveSibling(root.getFileName() + "-result.png"));
        click(wizard, "Exit setup");
    }

    private static void choose(SetupWizard wizard, Path source) throws Exception
    {
        SwingUtilities.invokeLater(() -> wizard.getRootPane().getDefaultButton().doClick());
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
    private static void awaitButton(String title) throws Exception
    { await(() -> Arrays.stream(Window.getWindows()).filter(w -> !(w instanceof SetupWizard)).flatMap(w -> components(w).stream()).anyMatch(c -> c instanceof JButton b && b.isShowing() && b.getText().equals(title))); }
    private static void clickVisibleDialogButton(String title) throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            for(Window window : Window.getWindows()) if(!(window instanceof SetupWizard))
                for(Component c : components(window)) if(c instanceof JButton button && button.isShowing() && button.getText().equals(title)) { button.doClick(); return; }
            throw new AssertionError("Dialog button unavailable: " + title);
        });
    }
    private static void select(SetupWizard wizard, String prefix) throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            for(Component c : components(wizard)) if(c instanceof JRadioButton radio && radio.isShowing() && radio.getAccessibleContext().getAccessibleName().startsWith(prefix)) { radio.setSelected(true); return; }
            throw new AssertionError("Choice unavailable: " + prefix);
        });
    }
    private static void click(SetupWizard wizard, String title) throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            for(Component c : components(wizard)) if(c instanceof JButton button && button.isShowing() && button.getText().equals(title)) { button.doClick(); return; }
            throw new AssertionError("Button unavailable: " + title);
        });
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
