package io.github.dsheirer.gui.setup;

import com.formdev.flatlaf.FlatLightLaf;
import io.github.dsheirer.database.*;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationImporter;
import io.github.dsheirer.database.upgrade.*;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.gui.whatsnew.WhatsNewDialog;
import io.github.dsheirer.jmbe.*;
import io.github.dsheirer.jmbe.github.GitHub;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService;
import io.github.dsheirer.source.tuner.TunerHardwareDiscovery;
import io.github.dsheirer.stats.StatsWebServerService;
import io.github.dsheirer.vector.calibrate.*;
import io.github.dsheirer.web.auth.WebAccessService;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import static io.github.dsheirer.gui.setup.SetupProgress.State.*;

/** Pre-receiver Swing setup session. No receiver services or device controllers are constructed here. */
public final class SetupWizard extends JDialog
{
    public record Result(UserPreferences preferences, PortableDataRootLock lock, boolean startChannels) {}
    private final Path root;
    private final Path database;
    private final SdrTrunkDatabaseBootstrap.Options options;
    private final boolean forced;
    private PortableDataRootLock lock;
    private final boolean ownsLock;
    private UserPreferences preferences;
    private SetupProgress progress;
    private SetupStep step = SetupStep.SOURCE;
    private final EnumMap<SetupStep, JButton> steps = new EnumMap<>(SetupStep.class);
    private final JPanel page = new SetupPage();
    private final JLabel title = new JLabel();
    private final JTextArea danger = text("");
    private final JTextArea console = text("");
    private JScrollPane diagnostics;
    private final JProgressBar meter = new JProgressBar();
    private final JButton back = new JButton("Back");
    private final JButton next = new JButton("Continue");
    private final JButton exit = new JButton("Exit setup");
    private final JButton cancel = new JButton("Cancel operation");
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "setup preparation"); thread.setDaemon(true); return thread;
    });
    private final SetupJobOutput output = new SetupJobOutput();
    private final Timer refresh;
    private Timer countdown;
    private boolean busy;
    private boolean finished;
    private boolean limitedVisit;
    private boolean webAdjusted;
    private boolean rrVerified;
    private boolean administratorConfigured;
    private boolean vaultDeferred;
    private Runnable accept = () -> {};
    private Runnable cancellation;
    private long generation;
    private String migrationReport = "";
    private String selectedScope = "";
    private volatile String operation = "";
    private volatile int completed;
    private volatile int total;
    private List<String> autoStart = List.of();
    private boolean autoStartNeedsJmbe;
    private boolean startChannels = true;
    private StatsWebServerService liveServer;

    /** Retry an actual listener bind race in the same shell, before tuner activation or output workers start. */
    public static boolean ensureListener(UserPreferences preferences, StatsWebServerService server) throws Exception
    {
        if(server.getRuntimeState().running()) return true;
        SetupWizard[] holder = new SetupWizard[1];
        Path root = io.github.dsheirer.portable.PortableApplicationPaths.getDataRoot();
        SetupProgress progress = SetupProgress.read(SdrTrunkDatabasePath.getDatabasePath(root));
        progress.setComplete(false);
        progress.set(SetupStep.WEB, NEEDS_ATTENTION);
        progress.set(SetupStep.REVIEW, PENDING);
        progress.save(SdrTrunkDatabasePath.getDatabasePath(root));
        SwingUtilities.invokeAndWait(() -> {
            SetupWizard wizard = new SetupWizard(new String[]{"--setup-wizard"}, root, null);
            holder[0] = wizard; wizard.preferences = preferences; wizard.progress = progress; wizard.liveServer = server;
            wizard.showPage(SetupStep.WEB);
            wizard.fail(server.getRuntimeState().statusMessage());
            wizard.setVisible(true);
        });
        SetupWizard wizard = holder[0];
        wizard.worker.shutdown();
        SwingUtilities.invokeAndWait(() -> { wizard.refresh.stop(); wizard.dispose(); });
        return wizard.finished && server.getRuntimeState().running();
    }

    public static final class Cancelled extends RuntimeException {}

    /** Called on the main thread, before constructing SDRTrunk or any receiving service. */
    public static Result run(String[] args, Path root, PortableDataRootLock existingLock) throws Exception
    {
        if(GraphicsEnvironment.isHeadless()) throw new IllegalStateException("--setup-wizard is graphical only");
        FlatLightLaf.setup();
        SetupWizard[] holder = new SetupWizard[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new SetupWizard(args, root, existingLock));
        SetupWizard wizard = holder[0];
        try
        {
            //A valid completed profile does not display a window unless required preparation changed.
            boolean current = false;
            boolean inspectionFailed = false;
            if(Files.isRegularFile(wizard.database))
            {
                try { current = !ApplicationMigrationService.readMigrationPlan(wizard.database).source().requiresMigration(); }
                catch(java.io.IOException | java.sql.SQLException e) { inspectionFailed = true; }
            }
            if(current)
            {
                wizard.initialize(false);
                if(!wizard.needsVisit()) { wizard.finished = true; return new Result(wizard.preferences, wizard.lock, true); }
                wizard.progress.setComplete(false);
                wizard.save();
            }
            boolean showInspectionFailure = inspectionFailed;
            SwingUtilities.invokeAndWait(() -> {
                if(wizard.preferences != null)
                {
                    ThemeManager.getInstance().initialize(wizard.preferences);
                    SwingUtilities.updateComponentTreeUI(wizard);
                    wizard.showPage(wizard.initialStep());
                }
                else wizard.showPage(SetupStep.SOURCE);
                if(showInspectionFailure) wizard.fail("The installed database could not be validated. Use Inspect required changes to retry. No files have been changed.");
                wizard.setVisible(true);
            });
            return wizard.finished ? new Result(wizard.preferences, wizard.lock, wizard.startChannels) : null;
        }
        finally
        {
            wizard.worker.shutdown();
            SwingUtilities.invokeAndWait(() -> { wizard.refresh.stop(); wizard.stopCountdown(); wizard.dispose(); });
            if(!wizard.finished && wizard.ownsLock && wizard.lock != null) wizard.lock.close();
        }
    }

    private SetupWizard(String[] args, Path dataRoot, PortableDataRootLock existingLock)
    {
        super((Frame)null, "sdrtrunk-vce · Setup", true);
        root = dataRoot.toAbsolutePath().normalize();
        database = SdrTrunkDatabasePath.getDatabasePath(root);
        options = SdrTrunkDatabaseBootstrap.Options.parse(args);
        if(Files.isRegularFile(database) && options.upgradeData() != null)
            throw new IllegalArgumentException("This profile already has a database. Use File → Import SQLite Database after setup for explicitly confirmed replacement.");
        if(!Files.isRegularFile(database) && options.upgradeCurrent())
            throw new IllegalArgumentException("--upgrade-current requires an existing portable database.");
        forced = Arrays.asList(args).contains("--setup-wizard");
        lock = existingLock;
        ownsLock = existingLock == null;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { leave(); } });
        JPanel shell = new JPanel(new BorderLayout(20, 0));
        JPanel rail = new JPanel(new BorderLayout());
        rail.setPreferredSize(new Dimension(235, 600));
        rail.add(new RadioIllustration(), BorderLayout.NORTH);
        JPanel lineage = new JPanel(new GridLayout(0, 1, 0, 4));
        lineage.setBorder(new EmptyBorder(12, 12, 12, 8));
        for(SetupStep id: SetupStep.values())
        {
            JButton button = new JButton(id.title());
            button.setHorizontalAlignment(SwingConstants.LEFT);
            button.addActionListener(e -> { if(!busy && preferences != null) showPage(id); });
            steps.put(id, button); lineage.add(button);
        }
        rail.add(lineage, BorderLayout.CENTER);
        shell.add(rail, BorderLayout.WEST);
        JPanel body = new JPanel(new BorderLayout(8, 14));
        body.setBorder(new EmptyBorder(24, 0, 20, 24));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 25f));
        body.add(title, BorderLayout.NORTH);
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        body.add(scroll, BorderLayout.CENTER);
        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        danger.setForeground(new Color(185, 38, 52));
        danger.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(185,38,52)),
            new EmptyBorder(8,8,8,8)));
        danger.getAccessibleContext().setAccessibleName("Setup error. Needs attention.");
        danger.setVisible(false); footer.add(danger);
        meter.setStringPainted(true); meter.setVisible(false); footer.add(meter);
        meter.setAlignmentX(Component.LEFT_ALIGNMENT);
        console.setRows(4);
        JScrollPane log = new JScrollPane(console);
        diagnostics = log; log.setVisible(false);
        log.setAlignmentX(Component.LEFT_ALIGNMENT);
        log.setPreferredSize(new Dimension(450, 80));
        log.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        footer.add(log);
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        navigation.setAlignmentX(Component.LEFT_ALIGNMENT);
        exit.addActionListener(e -> leave());
        back.addActionListener(e -> { if(!busy && step.ordinal() > 0) showPage(SetupStep.values()[step.ordinal()-1]); });
        next.addActionListener(e -> { if(!busy) attempt(accept); });
        getRootPane().setDefaultButton(next);
        next.setMnemonic(java.awt.event.KeyEvent.VK_N);
        back.setMnemonic(java.awt.event.KeyEvent.VK_B);
        exit.setMnemonic(java.awt.event.KeyEvent.VK_X);
        cancel.setVisible(false);
        cancel.addActionListener(e -> {
            if(cancellation != null) { cancel.setEnabled(false); operation = "Cancelling — waiting for a safe boundary…"; cancellation.run(); }
        });
        navigation.add(exit); navigation.add(cancel); navigation.add(back); navigation.add(next);
        footer.add(navigation);
        footer.add(text("Exit preserves accepted settings. Password drafts are never saved."));
        body.add(footer, BorderLayout.SOUTH); shell.add(body, BorderLayout.CENTER);
        setContentPane(shell);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1080, screen.width - 40), Math.min(800, screen.height - 60));
        setLocationRelativeTo(null);
        refresh = new Timer(150, e -> {
            String value = output.snapshot();
            if(!console.getText().equals(value)) { console.setText(value); console.setCaretPosition(console.getDocument().getLength()); }
            meter.setIndeterminate(total <= 0); meter.setMaximum(Math.max(1,total)); meter.setValue(completed);
            meter.setString(operation);
        });
        refresh.start();
    }

    private void initialize(boolean newPreferences) throws Exception
    {
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        InitialAdminSetup.initializeNewProfile(database);
        InitialAdminSetup.isPasswordRequired(database); //A persisted administrator is authoritative after interruption.
        SdrTrunkDatabaseBootstrap.prepareVault(root);
        if(lock == null) lock = PortableDataRootLock.acquire(root);
        SqlitePreferencesFactory.install(database);
        preferences = new UserPreferences();
        progress = SetupProgress.read(database);
        limitedVisit = progress.isComplete() && !forced;
        if(newPreferences) preferences.getApplicationPreference().setStatsLoggingEnabled(true);
        var app = preferences.getApplicationPreference();
        if(!app.isStatsWebServerEnabled())
        {
            app.setStatsWebServerNetworkAccessEnabled(false);
            app.setStatsWebServerHttpsEnabled(true);
            app.setStatsWebServerEnabled(true);
            webAdjusted = true;
            progress.set(SetupStep.WEB, NEEDS_ATTENTION);
        }
        revalidateSettings();
        if(limitedVisit)
        {
            if(progress.get(SetupStep.RADIO_REFERENCE) == PENDING) progress.set(SetupStep.RADIO_REFERENCE, DEFERRED);
            progress.set(SetupStep.HARDWARE, DEFERRED);
        }
        if(preferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded())
            preferences.getEncryptionKeyPreference().getVaultService().tryAutoUnlockSavedPassword();
        //Read names only. No channel/controller objects and no credentials are projected into the review.
        try(var connection = SdrTrunkDatabase.open(database);
            var query = connection.prepareStatement("SELECT name, decoder_type FROM configuration_channel WHERE auto_start = 1 ORDER BY auto_start_order, name");
            var rows = query.executeQuery())
        {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            while(rows.next()) { names.add(rows.getString(1)); autoStartNeedsJmbe |= SetupReadiness.requiresJmbe(rows.getString(2)); }
            autoStart = List.copyOf(names);
        }
        if(limitedVisit && !autoStartNeedsJmbe && progress.get(SetupStep.JMBE) == PENDING)
            progress.set(SetupStep.JMBE, DEFERRED);
        save();
    }

    private void revalidateSettings() throws Exception
    {
        ready(SetupStep.SOURCE, true);
        administratorConfigured = new WebAccessService(database).isPrimaryAdminConfigured();
        ready(SetupStep.ADMINISTRATOR, administratorConfigured);
        if(!webAdjusted) ready(SetupStep.WEB, preferences.getApplicationPreference().isStatsWebServerEnabled());
        Path library = preferences.getJmbeLibraryPreference().getPathJmbeLibrary();
        ready(SetupStep.JMBE, library != null && JmbeLibraryMetadata.isSupported(library));
        var rr = preferences.getRadioReferencePreference();
        ready(SetupStep.RADIO_REFERENCE, rr.isStoreCredentials() && present(rr.getUserName()) && present(rr.getPassword()));
        if(progress.isImported() || progress.isComplete()) ready(SetupStep.ACTIVITY, true);
        ready(SetupStep.CALIBRATION, CalibrationManager.getInstance(preferences).isCalibrated());
        //Hardware inventory is deliberately never persisted or inferred from configuration.
        progress.set(SetupStep.HARDWARE, PENDING);
    }

    private void ready(SetupStep id, boolean valid)
    {
        if(valid && !progress.isDone(id) && progress.get(id) != NEEDS_ATTENTION &&
            (id == SetupStep.SOURCE || id == SetupStep.ADMINISTRATOR || progress.isImported() || progress.isComplete()))
            progress.set(id, progress.isImported() ? CARRIED_OVER : COMPLETE);
        if(!valid && progress.get(id) != DEFERRED) progress.set(id, PENDING);
    }

    private boolean needsVisit()
    {
        if(preferences == null) return true;
        return forced || !progress.isComplete() || !progress.isDone(SetupStep.ADMINISTRATOR) || !progress.isDone(SetupStep.WEB) ||
            !CalibrationManager.getInstance(preferences).isCalibrated() &&
                !preferences.getVectorCalibrationPreference().isHideCalibrationDialog() ||
            WhatsNewDialog.getPendingReleaseNotes().isPresent() || vaultLocked() || jmbeNeeded();
    }

    private SetupStep initialStep()
    {
        return SetupReadiness.initialStep(progress, forced, limitedVisit, jmbeNeeded(),
            !CalibrationManager.getInstance(preferences).isCalibrated() &&
                !preferences.getVectorCalibrationPreference().isHideCalibrationDialog());
    }

    private void showPage(SetupStep id)
    {
        if(busy) return;
        stopCountdown();
        step = id; generation++;
        page.removeAll(); danger.setVisible(false);
        diagnostics.setVisible(false);
        title.setText((id.ordinal()+1) + ". " + id.title());
        next.setText(id == SetupStep.REVIEW ? "Finish & launch" : "Continue");
        accept = () -> completeAndContinue();
        switch(id)
        {
            case SOURCE -> sourcePage();
            case ADMINISTRATOR -> administratorPage();
            case WEB -> webPage();
            case JMBE -> jmbePage();
            case RADIO_REFERENCE -> radioReferencePage();
            case ACTIVITY -> activityPage();
            case HARDWARE -> hardwarePage();
            case CALIBRATION -> calibrationPage();
            case REVIEW -> reviewPage();
        }
        updateNavigation(); page.revalidate(); page.repaint();
        SwingUtilities.invokeLater(() -> { if(!busy) next.requestFocusInWindow(); });
    }

    private void sourcePage()
    {
        if(preferences != null)
        {
            paragraph("Your selected database is installed. Going Back does not change or replace it. After setup, use File → Import SQLite Database for explicitly confirmed replacement.");
            paragraph(database.toString());
            if(!migrationReport.isBlank())
            {
                paragraph(migrationReport);
                button("Copy Message", () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(migrationReport), null));
            }
            return;
        }
        if(Files.isRegularFile(database))
        {
            paragraph("This installation needs database inspection or an upgrade. The Application Migrator inspects first, retains a safety backup, changes only a staged copy, and installs it only after validation.");
            button("Inspect required changes", () -> job("Inspecting database…", null,
                () -> ApplicationMigrationService.readMigrationPlan(database), plan -> {
                    paragraph(ApplicationMigrationService.describePlan(plan));
                    if(plan.source().requiresMigration()) button("Back up and upgrade / Retry", () -> migrate(null, false, false, plan));
                    else button("Resume installed profile", () -> job("Loading installed profile…", null,
                        () -> { initialize(false); return true; }, ignored -> showPage(initialStep())));
                    page.revalidate();
                }));
            next.setEnabled(false); accept = () -> {};
            return;
        }
        paragraph("Choose a starting point. Your source installation and files will remain unchanged.");
        ButtonGroup group = new ButtonGroup();
        JRadioButton fresh = card(group, "Start fresh — recommended for new users", "Create an empty profile, then set up access, digital audio and performance.", true);
        JRadioButton folder = card(group, "Copy a previous VCE installation / data folder", "Copy configuration plus the vault, JMBE library and optional modules. Supported portable paths are remapped to this destination. Recordings and other external output files are not copied.", false);
        JRadioButton sqlite = card(group, "Import a SQLite database only", "Copy only data inside the .sqlite file. No vault, JMBE JAR or modules are copied. Stored paths are preserved, not remapped, and may still point to old output folders.", false);
        JRadioButton xml = card(group, "Import legacy XML", "Import supported configuration from an older XML playlist. This does not recover newer SQLite-only changes or external files.", false);
        JTextField source = field("Source folder or file", "");
        button("Browse…", () -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(folder.isSelected() ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            if(chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) source.setText(chooser.getSelectedFile().toString());
        });
        List<Path> nearby = PreviousBuildLocator.discover();
        if(!nearby.isEmpty())
        {
            JComboBox<Path> locations = new JComboBox<>(nearby.toArray(Path[]::new));
            append(locations); button("Use nearby installation", () -> { folder.setSelected(true); source.setText(locations.getSelectedItem().toString()); });
        }
        LegacyXmlConfigurationImporter.discoverPlaylist(io.github.dsheirer.portable.PortableApplicationPaths.getLegacyApplicationRoot()).ifPresent(found ->
            button("Use found legacy XML", () -> { xml.setSelected(true); source.setText(found.toString()); }));
        if(options.upgradeData() != null) { source.setText(options.upgradeData().toString()); (Files.isDirectory(options.upgradeData()) ? folder : sqlite).setSelected(true); }
        if(options.importXml() != null) { source.setText(options.importXml().toString()); xml.setSelected(true); }
        accept = () -> {
            if(fresh.isSelected()) { migrate(null, true, false, null); return; }
            if(source.getText().isBlank()) throw new IllegalArgumentException("Choose the source folder or file first.");
            Path input = Path.of(source.getText());
            if(xml.isSelected()) { migrate(input, false, true, null); return; }
            var selection = PreviousBuildLocator.resolveSelection(input).orElseThrow(() -> new IllegalArgumentException("No supported portable database found at this location."));
            if(folder.isSelected() != selection.portableProfile()) throw new IllegalArgumentException("The selected path does not match the chosen folder/file import scope.");
            job("Inspecting source…", null, () -> ApplicationMigrationService.readMigrationPlan(selection.database()), plan -> {
                page.removeAll();
                paragraph("Confirm source: " + selection.path());
                paragraph(ApplicationMigrationService.describePlan(plan));
                selectedScope = selection.portableProfile() ? "Full folder: portable assets copied and supported paths remapped." : "SQLite only: external assets not copied; stored output paths unchanged.";
                paragraph(selectedScope);
                button("Confirm import", () -> migrate(input, false, false, plan));
                button("Choose a different source", () -> showPage(SetupStep.SOURCE));
                accept = () -> migrate(input, false, false, plan);
                page.revalidate();
            });
        };
    }

    private void migrate(Path source, boolean fresh, boolean xml, DatabaseMigrationChain.PreflightReport approved)
    {
        job("Preparing your profile…", null, () -> {
            String report;
            if(fresh) { SdrTrunkDatabaseBootstrap.createFresh(database); report = "New profile created."; }
            else if(xml) { LegacyXmlConfigurationImporter.importPlaylist(source, database); report = "Legacy XML imported. The source was not changed."; }
            else
            {
                ApplicationMigrationService service = new ApplicationMigrationService();
                if(source == null)
                    report = ApplicationMigrationSuccessDialog.currentDatabaseReport(service.migrateCurrent(root, approved, output::accept));
                else
                {
                    var selected = PreviousBuildLocator.resolveSelection(source).orElseThrow();
                    var result = service.importPrevious(selected, root, approved, output::accept);
                    report = ApplicationMigrationSuccessDialog.previousImportReport(result);
                }
            }
            initialize(fresh || xml);
            return report;
        }, report -> {
            migrationReport = report;
            progress.setComplete(false);
            if(!persist()) return;
            ThemeManager.getInstance().initialize(preferences);
            SwingUtilities.updateComponentTreeUI(this);
            showPage(SetupStep.SOURCE);
            if(!fresh && !xml)
            {
                int[] seconds = {10}; next.setText("Continue in 10 seconds");
                countdown = new Timer(1000, e -> {
                    if(--seconds[0] == 0) { stopCountdown(); completeAndContinue(); }
                    else next.setText("Continue in " + seconds[0] + " seconds");
                });
                countdown.start();
            }
        });
    }

    private void administratorPage()
    {
        boolean exists = administratorConfigured;
        paragraph(exists ? "Your administrator account is carried over. Leave the fields blank to keep it. Changing its password requires the current password; otherwise use the established account recovery workflow." : "Create the administrator password used to manage this receiver, including aliases. Use 7–256 characters.");
        JPasswordField current = exists ? password("Current administrator password") : new JPasswordField();
        JPasswordField value = password("New administrator password");
        JPasswordField confirmation = password("Confirm new password");
        accept = () -> {
            char[] old = current.getPassword(), first = value.getPassword(), second = confirmation.getPassword();
            current.setText(""); value.setText(""); confirmation.setText("");
            if(exists && first.length == 0 && second.length == 0) { Arrays.fill(old, '\0'); completeAndContinue(); return; }
            if(first.length < 7 || first.length > 256 || !Arrays.equals(first, second))
            {
                Arrays.fill(old,'\0'); Arrays.fill(first,'\0'); Arrays.fill(second,'\0');
                throw new IllegalArgumentException("Use matching passwords of 7–256 characters.");
            }
            Arrays.fill(second,'\0');
            job("Saving administrator…", null, () -> {
                try
                {
                    WebAccessService access = new WebAccessService(database);
                    if(exists && access.authenticate(WebAccessService.PRIMARY_ADMIN_USERNAME, old).isEmpty())
                        throw new IllegalArgumentException("Current administrator authentication failed.");
                    InitialAdminSetup.provision(database, first);
                    return true;
                }
                finally { Arrays.fill(old,'\0'); Arrays.fill(first,'\0'); }
            }, ignored -> { administratorConfigured = true; progress.set(SetupStep.ADMINISTRATOR, COMPLETE); completeAndContinue(); });
        };
    }

    private void webPage()
    {
        var app = preferences.getApplicationPreference();
        paragraph("The web interface is required for alias editing. HTTPS protects sign-in and administrative access.");
        if(webAdjusted) paragraph("Adjusted during import/upgrade: your disabled web server is now enabled for this computer only. Network-facing access was not enabled.");
        else if(progress.get(step) == NEEDS_ATTENTION) paragraph("Review required: web access was adjusted or did not pass its last startup check. Confirm the effective settings below.");
        ButtonGroup group = new ButtonGroup();
        card(group, "This computer only — recommended", "Listen on localhost. Other computers cannot connect to this listener.", !app.isStatsWebServerAnyIpEnabled());
        JRadioButton network = card(group, "Other devices", "Listen on network interfaces reachable through the host firewall. This is not a guaranteed LAN-only boundary. No firewall rules or router ports will be opened.", app.isStatsWebServerAnyIpEnabled());
        JSpinner port = new JSpinner(new SpinnerNumberModel(app.getStatsWebServerPort(),1024,65535,1));
        labelled("HTTPS port (default 8090)", port);
        paragraph("The current HTTPS certificate policy is preserved. The final page tests the listener and reports certificate or port conflicts before setup completes.");
        accept = () -> {
            boolean changed = !app.isStatsWebServerHttpsEnabled() || app.getStatsWebServerPort() != (Integer)port.getValue() || app.isStatsWebServerAnyIpEnabled() != network.isSelected();
            app.setStatsWebServerHttpsEnabled(true);
            app.setStatsWebServerPort((Integer)port.getValue());
            app.setStatsWebServerNetworkAccessEnabled(network.isSelected());
            app.setStatsWebServerEnabled(true);
            if(changed) progress.set(step, COMPLETE);
            if(liveServer != null) { progress.set(step, COMPLETE); if(persist()) showPage(SetupStep.REVIEW); }
            else completeAndContinue();
        };
    }

    private void jmbePage()
    {
        Path library = preferences.getJmbeLibraryPreference().getPathJmbeLibrary();
        paragraph("JMBE provides digital voice decoding. A saved path counts as complete only when the library is present and compatible.");
        boolean compatible = JmbeLibraryMetadata.isSupported(library);
        paragraph(compatible ? "Compatible existing library: " + library : "No compatible library has been confirmed.");
        if(compatible) button("Keep existing library", () -> { progress.set(step, COMPLETE); completeAndContinue(); });
        JCheckBox consent = new JCheckBox("I agree to download and compile JMBE on this computer.");
        paragraph("JMBE source is provided for educational use. Voice-codec algorithms may be subject to patents or other restrictions in your jurisdiction. Review the project's licensing and applicable restrictions before proceeding. Creation downloads a compiler/creator and runs its build tools.");
        append(consent);
        button("Create JMBE / Retry", () -> {
            if(!consent.isSelected()) throw new IllegalArgumentException("Consent is required before downloading and compiling JMBE.");
            AtomicBoolean stopped = new AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<JmbeCreator> creator = new java.util.concurrent.atomic.AtomicReference<>();
            job("Finding JMBE creator…", () -> { stopped.set(true); if(creator.get() != null) creator.get().cancel(); }, () -> {
                var release = GitHub.getLatestRelease(JmbeCreator.GITHUB_JMBE_RELEASES_URL);
                if(stopped.get()) throw new InterruptedException();
                if(release == null) throw new IllegalStateException("JMBE release lookup failed. Check the connection and retry.");
                Path target = root.resolve("jmbe").resolve("jmbe-" + release.getVersion() + ".jar");
                JmbeCreator task = new JmbeCreator(release, target); creator.set(task);
                if(stopped.get()) task.cancel();
                Path installed = task.run(message -> { operation = message; output.accept(message); });
                preferences.getJmbeLibraryPreference().installLibrary(installed);
                return installed;
            }, installed -> { progress.set(step, COMPLETE); persist(); showPage(step); });
        });
        button("Choose existing JMBE library…", () -> {
            JFileChooser chooser = new JFileChooser();
            if(chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path chosen = chooser.getSelectedFile().toPath();
            job("Validating and installing JMBE…", null, () -> {
                if(!JmbeLibraryMetadata.isSupported(chosen)) throw new IllegalArgumentException("That file is not a compatible JMBE library.");
                preferences.getJmbeLibraryPreference().installLibrary(chosen); return true;
            }, ignored -> { progress.set(step, COMPLETE); persist(); showPage(step); });
        });
        defer("Set up later");
        accept = () -> { if(!progress.isDone(step)) throw new IllegalArgumentException("Create or select a compatible library, or choose Set up later."); completeAndContinue(); };
    }

    private void radioReferencePage()
    {
        var rr = preferences.getRadioReferencePreference();
        paragraph("RadioReference is optional. Stored credentials are carried over without revealing your password. Storing credentials does not verify the connection or subscription.");
        JTextArea verification = text(rrVerified ? "Connection verified in this session." :
            present(rr.getUserName()) && present(rr.getPassword()) ?
                (progress.get(step) == CARRIED_OVER ? "Carried over" : "Stored credentials") + " — connection not tested in this session." : "No complete stored credentials.");
        append(verification);
        JTextField username = field("RadioReference username", rr.getUserName() == null ? "" : rr.getUserName());
        JPasswordField secret = password("Password (leave blank to retain the stored password)");
        javax.swing.event.DocumentListener edited = new javax.swing.event.DocumentListener()
        {
            private void changed() { rrVerified=false; verification.setText("Edited credentials — connection not verified."); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        };
        username.getDocument().addDocumentListener(edited); secret.getDocument().addDocumentListener(edited);
        button("Test connection / Retry", () -> {
            String name = username.getText().trim();
            char[] entered = secret.getPassword();
            char[] credential = entered.length > 0 ? entered : (rr.getPassword() == null ? new char[0] : rr.getPassword().toCharArray());
            job("Testing RadioReference connection…", null, () -> {
                try(RadioReferenceDirectoryService service = new RadioReferenceDirectoryService())
                {
                    var result = service.login(name, credential);
                    if(!result.authenticated()) throw new IllegalArgumentException("Connection not verified. Check credentials, subscription and connection, then retry.");
                    return result.premium();
                }
                finally { Arrays.fill(credential,'\0'); }
            }, premium -> { rrVerified = true; verification.setText(premium ? "Connection verified: premium access available." : "Connection verified: premium subscription is expired."); page.revalidate(); });
        });
        defer("Set up later");
        accept = () -> {
            boolean verified = rrVerified;
            char[] entered = secret.getPassword(); secret.setText("");
            try
            {
                if(username.getText().isBlank() || entered.length == 0 && !present(rr.getPassword()))
                    throw new IllegalArgumentException("Enter both username and password, or choose Set up later.");
                boolean changed = !username.getText().trim().equals(rr.getUserName()) || entered.length > 0 || !rr.isStoreCredentials();
                rr.setUserName(username.getText().trim());
                if(entered.length > 0) rr.setPassword(new String(entered));
                rr.setStoreCredentials(true); rrVerified = verified;
                if(changed) progress.set(step, COMPLETE); completeAndContinue();
            }
            finally { Arrays.fill(entered,'\0'); }
        };
    }

    private void activityPage()
    {
        var app = preferences.getApplicationPreference();
        boolean initiallyOff = !app.isStatsLoggingEnabled();
        boolean storedDetailed = app.isStatsDetailedHistoryEnabled();
        paragraph("Statistics and activity history are separate from audio recordings and ordinary application log files. Imported Off settings are preserved.");
        ButtonGroup group = new ButtonGroup();
        JRadioButton off = card(group,"Off", "Do not collect statistics or detailed activity history.",!app.isStatsLoggingEnabled());
        card(group,"Summary statistics — recommended", "Collect compact summaries. Detailed history stays off.",app.isStatsLoggingEnabled() && !app.isStatsDetailedHistoryEnabled());
        JRadioButton detailed = card(group,"Summaries plus detailed history", "Also retain detailed activity under the existing retention limits. This uses additional database space.",app.isStatsLoggingEnabled() && app.isStatsDetailedHistoryEnabled());
        JSpinner days = new JSpinner(new SpinnerNumberModel(app.getStatsLoggingRetentionDays(),1,365,1));
        labelled("Time-based data retention in days (default 30)",days);
        accept = () -> {
            //Reviewing an imported Off choice must not rewrite its dormant detailed-history preference.
            boolean saveDetailed = initiallyOff && off.isSelected() ? storedDetailed : detailed.isSelected();
            boolean changed = app.isStatsLoggingEnabled() == off.isSelected() || app.isStatsDetailedHistoryEnabled() != saveDetailed || app.getStatsLoggingRetentionDays() != (Integer)days.getValue();
            app.setStatsLoggingEnabled(!off.isSelected());
            app.setStatsDetailedHistoryEnabled(saveDetailed);
            app.setStatsLoggingRetentionDays((Integer)days.getValue()); if(changed) progress.set(step, COMPLETE); completeAndContinue();
        };
    }

    private void hardwarePage()
    {
        paragraph("Physical tuner discovery only. Detected does not mean opened, configured or tested. No tuning, configuration changes or channel startup occur here. Missing hardware is not a setup error.");
        JTextArea inventory = text("Scanning…"); append(inventory);
        Runnable scan = () -> {
            AtomicBoolean stopped = new AtomicBoolean();
            job("Discovering physical tuners…", () -> stopped.set(true),
                () -> new TunerHardwareDiscovery().scan(stopped::get), result -> {
                    StringBuilder description = new StringBuilder();
                    for(var device: result.devices()) description.append("Detected: ").append(device.model()).append(" — ").append(device.identity()).append('\n');
                    if(result.devices().isEmpty()) description.append("No physical tuners detected. You can connect hardware later.\n");
                    for(String error: result.errors()) description.append(error).append('\n');
                    inventory.setText(description.toString());
                    progress.set(step, stopped.get() ? DEFERRED : COMPLETE);
                    if(persist() && stopped.get()) showPage(nextStep());
                });
        };
        button("Rescan",scan); defer("Skip");
        accept = () -> { if(progress.get(step) == NEEDS_ATTENTION) deferCurrent(); else completeAndContinue(); };
        SwingUtilities.invokeLater(() -> { if(step == SetupStep.HARDWARE && !busy) scan.run(); });
    }

    private void calibrationPage()
    {
        CalibrationManager manager = CalibrationManager.getInstance(preferences);
        int pending = manager.getUncalibrated().size();
        JTextArea warning = text("Close other applications before benchmarking. Pause downloads, games, backups and other CPU-heavy work; leave this wizard open. Background activity can distort the results.");
        warning.setFont(warning.getFont().deriveFont(Font.BOLD));
        warning.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(198,135,48),2),new EmptyBorder(12,12,12,12)));
        append(warning); append(Box.createVerticalStrut(16));
        paragraph(pending + " pending tests · " + (manager.getCalibrationTypes().size()-pending) + " valid results reused. " + manager.getPendingReason());
        ButtonGroup group = new ButtonGroup();
        JRadioButton now = card(group,"Benchmark now — recommended", "Choose the fastest compatible DSP implementation for this computer. Only outstanding tests run; completed valid results are preserved on retry.",true);
        card(group,"Skip this time", "Use available/default implementations for now. Pending tests can be offered at a later launch.",false);
        button("Run benchmark / Retry", this::benchmark);
        defer("Skip this time");
        accept = () -> { if(pending == 0) completeAndContinue(); else if(now.isSelected()) benchmark(); else deferCurrent(); };
    }

    private void benchmark()
    {
        CalibrationManager manager = CalibrationManager.getInstance(preferences);
        CalibrationRunner runner = new CalibrationRunner();
        job("Preparing benchmark…",runner::cancel,() -> runner.run(manager.getUncalibrated(), value -> {
            completed=value.completed(); total=value.total(); operation=value.operation();
        },output), result -> {
            if(result.cancelled()) { progress.set(step,DEFERRED); persist(); showPage(step); return; }
            if(result.failed()>0) { progress.set(step,NEEDS_ATTENTION); persist(); fail("Some tests failed. Retry runs only outstanding tests; valid results were preserved."); return; }
            progress.set(step,COMPLETE); persist(); showPage(step);
        });
    }

    private boolean vaultLocked()
    {
        if(preferences == null || vaultDeferred || !preferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded()) return false;
        var vault = preferences.getEncryptionKeyPreference().getVaultService();
        return vault.hasVault() && !vault.isUnlocked() && vault.isPromptOnLaunch();
    }

    private boolean jmbeNeeded()
    {
        return autoStartNeedsJmbe && !progress.isDone(SetupStep.JMBE) &&
            preferences.getJmbeLibraryPreference().getAlertIfMissingLibraryRequired();
    }

    private void reviewPage()
    {
        paragraph("Review before launching. Accepted settings are already saved; receiving and streaming have not started.");
        for(SetupStep id: SetupStep.values()) if(id != SetupStep.REVIEW) paragraph(id.title() + " — " + label(progress.get(id)));
        if(!selectedScope.isBlank()) paragraph(selectedScope);
        var dirs = preferences.getDirectoryPreference();
        paragraph("Effective output folders\nRecordings: " + dirs.getDirectoryRecording() + "\nScreenshots: " + dirs.getDirectoryScreenCapture() + "\nEvent logs: " + dirs.getDirectoryEventLog() + "\nApplication logs: " + dirs.getDirectoryApplicationLog() + "\nStreaming: " + dirs.getDirectoryStreaming());
        var app = preferences.getApplicationPreference();
        paragraph("Web address: " + (app.isStatsWebServerHttpsEnabled() ? "https" : "http") + "://localhost:" + app.getStatsWebServerPort() + "/" +
            (app.isStatsWebServerAnyIpEnabled() ? "\nOther devices: use this computer's reachable address; host firewall restrictions still apply." : " — this computer only"));
        paragraph("Existing auto-start selections: " + (autoStart.isEmpty() ? "None" : String.join(", ",autoStart)));
        JCheckBox launchChannels = new JCheckBox("Start configured channels after setup", startChannels);
        if(!autoStart.isEmpty()) append(launchChannels);
        if(vaultLocked())
        {
            paragraph("The encryption vault is locked. Unlock it or explicitly continue without it for this run.");
            JPasswordField secret = password("Vault password");
            button("Unlock vault", () -> {
                char[] value=secret.getPassword(); secret.setText("");
                job("Unlocking vault…",null,() -> {
                    try { preferences.getEncryptionKeyPreference().getVaultService().unlock(value,false); return true; }
                    finally { Arrays.fill(value,'\0'); }
                }, ignored -> showPage(step));
            });
            button("Continue without vault for this run", () -> { preferences.getEncryptionKeyPreference().getVaultService().disableForRun(); vaultDeferred=true; showPage(step); });
        }
        WhatsNewDialog.getPendingReleaseNotes().ifPresent(notes -> {
            paragraph("What's new in this release"); append(WhatsNewDialog.createReleaseNotesView(notes));
        });
        accept = () -> {
            startChannels = launchChannels.isSelected();
            if(!progress.isDone(SetupStep.ADMINISTRATOR)) throw new IllegalArgumentException("Complete administrator setup before launching.");
            if(vaultLocked()) throw new IllegalArgumentException("Unlock the vault or choose to continue without it for this run.");
            job("Testing HTTPS listener and saving settings…", null, () -> {
                save();
                if(liveServer != null)
                {
                    var state=liveServer.reloadActiveListener();
                    if(!state.running()) throw new IllegalArgumentException(state.statusMessage());
                }
                else try(StatsWebServerService server = new StatsWebServerService(preferences))
                {
                    var state=server.getRuntimeState();
                    if(!state.running()) throw new IllegalArgumentException(state.statusMessage());
                }
                return true;
            }, ignored -> {
                progress.set(SetupStep.REVIEW,COMPLETE); progress.setComplete(true);
                if(!persist()) return;
                WhatsNewDialog.getPendingReleaseNotes().ifPresent(WhatsNewDialog::markShown);
                finished=true; dispose();
            });
        };
    }

    private <T> void job(String description, Runnable cancelAction, Callable<T> work, Consumer<T> success)
    {
        if(busy) return;
        SetupProgress.State previous = progress == null ? PENDING : progress.get(step);
        busy=true; cancellation=cancelAction; long attempt=++generation;
        if(progress != null) { progress.set(step,RUNNING); persist(); }
        danger.setVisible(false); operation=description; completed=0; total=0; output.accept(description);
        meter.setVisible(true); cancel.setVisible(cancelAction!=null); cancel.setEnabled(true); updateNavigation();
        cancel.setText(step == SetupStep.HARDWARE ? "Skip" : "Cancel operation");
        diagnostics.setVisible(true);
        worker.submit(() -> {
            T result=null; Throwable failure=null;
            try { result=work.call(); } catch(Exception | LinkageError e) { failure=e; }
            T value=result; Throwable problem=failure;
            SwingUtilities.invokeLater(() -> {
                if(attempt!=generation || !isDisplayable()) return;
                busy=false; cancellation=null; cancel.setVisible(false); meter.setVisible(false); updateNavigation();
                if(problem!=null)
                {
                    boolean stopped=problem instanceof InterruptedException;
                    if(progress!=null) { progress.set(step,stopped?DEFERRED:NEEDS_ATTENTION); persist(); }
                    fail(stopped ? "Operation cancelled. You can retry or defer this step." : safeFailure(problem));
                }
                else
                {
                    if(progress != null && progress.get(step) == RUNNING) progress.set(step, previous);
                    attempt(() -> success.accept(value));
                    updateNavigation();
                }
            });
        });
    }

    private String safeFailure(Throwable failure)
    {
        //Remote/subprocess exceptions may contain passwords, request URLs or provider responses.
        if(step==SetupStep.RADIO_REFERENCE) return "RadioReference connection failed. Check credentials, subscription and connection, then retry or set up later.";
        if(step==SetupStep.JMBE) return "JMBE preparation failed. Your previous library is preserved. Check the connection and build output; Retry, choose an existing library, or set up later.";
        if(step==SetupStep.ADMINISTRATOR) return "Administrator setup failed. Check the current password and password rules, then retry.";
        if(step==SetupStep.REVIEW) return "Launch readiness check failed: " + (failure instanceof IllegalArgumentException ? failure.getMessage() : "check web port, certificate configuration and database access, then retry.");
        return "Setup could not complete this step: " + failure.getClass().getSimpleName() + ". Check the selected source and available disk space, then retry. No incomplete migration is promoted.";
    }

    private void completeAndContinue()
    {
        if(progress==null) return;
        if(!progress.isDone(step)) progress.set(step,COMPLETE);
        if(persist()) showPage(nextStep());
    }
    private SetupStep nextStep()
    {
        if(!limitedVisit) return progress.next(step);
        //A previously configured installation returns only for newly required work, not every optional page.
        for(SetupStep candidate: List.of(SetupStep.ADMINISTRATOR, SetupStep.WEB, SetupStep.JMBE, SetupStep.CALIBRATION))
        {
            if(candidate.ordinal() <= step.ordinal() || progress.isDone(candidate)) continue;
            if(candidate == SetupStep.JMBE && !jmbeNeeded()) continue;
            if(candidate == SetupStep.CALIBRATION && preferences.getVectorCalibrationPreference().isHideCalibrationDialog()) continue;
            return candidate;
        }
        return SetupStep.REVIEW;
    }
    private void deferCurrent() { progress.set(step,DEFERRED); if(persist()) showPage(nextStep()); }
    private void defer(String caption) { button(caption,this::deferCurrent); }
    private void save() throws Exception { Preferences.userRoot().flush(); progress.save(database); }
    private boolean persist()
    {
        try { save(); return true; } catch(Exception e) { fail("Settings could not be saved. Check database access and free disk space; retry before continuing."); return false; }
    }
    private void leave()
    {
        if(busy) { fail("Wait for this operation to finish, or cancel it and wait for acknowledgement before exiting."); return; }
        if(preferences==null || persist()) dispose();
    }
    private void stopCountdown() { if(countdown!=null) { countdown.stop(); countdown=null; } }
    private void updateNavigation()
    {
        enableTree(page,!busy);
        back.setEnabled(!busy && preferences!=null && step.ordinal()>0);
        next.setEnabled(!busy && !(step==SetupStep.SOURCE && preferences==null && Files.isRegularFile(database)));
        exit.setEnabled(!busy);
        for(var entry:steps.entrySet())
        {
            SetupStep id=entry.getKey(); JButton button=entry.getValue();
            SetupProgress.State state=progress==null?PENDING:progress.get(id);
            button.setText("<html>"+(progress!=null && progress.isDone(id)?"✓ ":"")+(id.ordinal()+1)+". "+id.title()+"<br><small>"+label(state)+"</small></html>");
            button.setToolTipText(label(state)); button.getAccessibleContext().setAccessibleDescription(label(state));
            button.setEnabled(!busy && preferences!=null && (liveServer==null || id==SetupStep.WEB || id==SetupStep.REVIEW) &&
                (id==step || progress.isDone(id) || state==DEFERRED || state==NEEDS_ATTENTION));
            button.setFont(button.getFont().deriveFont(id==step?Font.BOLD:Font.PLAIN));
        }
    }
    private static void enableTree(Component component,boolean enabled)
    {
        component.setEnabled(enabled);
        if(component instanceof Container container) for(Component child:container.getComponents()) enableTree(child,enabled);
    }
    private void attempt(Runnable action) { try { action.run(); } catch(Exception e) { fail(e instanceof IllegalArgumentException ? e.getMessage() : "This action failed. Check settings and retry."); } }
    private void fail(String message)
    {
        Color color = ThemeManager.getInstance().isDarkMode() ? new Color(255,135,145) : new Color(174,28,42);
        danger.setForeground(color);
        danger.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(color),new EmptyBorder(8,8,8,8)));
        danger.setText(message); danger.setVisible(true); danger.requestFocusInWindow();
        if(step == SetupStep.REVIEW) next.setText("Retry launch");
        updateNavigation();
    }
    private void paragraph(String message) { append(text(message)); append(Box.createVerticalStrut(12)); }
    private void append(JComponent component) { component.setAlignmentX(Component.LEFT_ALIGNMENT); page.add(component); }
    private void append(Component component) { page.add(component); }
    private JButton button(String caption,Runnable action)
    {
        JButton button=new JButton(caption); button.addActionListener(e -> attempt(action)); append(button); append(Box.createVerticalStrut(10)); return button;
    }
    private JTextField field(String caption,String value) { JTextField field=new JTextField(value,28); labelled(caption,field); return field; }
    private JPasswordField password(String caption) { JPasswordField field=new JPasswordField(28); labelled(caption,field); return field; }
    private void labelled(String caption,JComponent field)
    {
        JLabel label=new JLabel(caption); label.setLabelFor(field); append(label);
        field.getAccessibleContext().setAccessibleName(caption); field.setMaximumSize(new Dimension(Integer.MAX_VALUE,34)); append(field); append(Box.createVerticalStrut(12));
    }
    private JRadioButton card(ButtonGroup group,String caption,String description,boolean selected)
    {
        JPanel card=new JPanel(new BorderLayout(4,6)); card.setBorder(BorderFactory.createCompoundBorder(UIManager.getBorder("TextField.border"),new EmptyBorder(12,12,12,12)));
        JRadioButton radio=new JRadioButton("<html>"+caption+"</html>",selected); radio.setFont(radio.getFont().deriveFont(Font.BOLD)); group.add(radio);
        radio.getAccessibleContext().setAccessibleName(caption);
        radio.getAccessibleContext().setAccessibleDescription(description);
        JTextArea explanation=text(description);
        var select=new java.awt.event.MouseAdapter() { public void mouseClicked(java.awt.event.MouseEvent event) { if(radio.isEnabled()) { radio.setSelected(true); radio.requestFocusInWindow(); } } };
        card.addMouseListener(select); explanation.addMouseListener(select);
        card.add(radio,BorderLayout.NORTH); card.add(explanation,BorderLayout.CENTER);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE,Integer.MAX_VALUE)); append(card); append(Box.createVerticalStrut(10)); return radio;
    }
    private static JTextArea text(String value)
    {
        JTextArea area=new SetupText(value); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setOpaque(false);
        area.setFont(UIManager.getFont("Label.font")); area.setAlignmentX(Component.LEFT_ALIGNMENT); return area;
    }
    private static boolean present(String value) { return value!=null && !value.isBlank(); }
    private static String label(SetupProgress.State value)
    {
        return switch(value) { case PENDING->"Pending"; case CARRIED_OVER->"Carried over"; case COMPLETE->"Complete"; case NEEDS_ATTENTION->"Needs attention"; case RUNNING->"Running"; case DEFERRED->"Deferred"; };
    }
}
