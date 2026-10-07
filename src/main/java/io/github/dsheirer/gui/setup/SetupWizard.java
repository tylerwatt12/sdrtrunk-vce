package io.github.dsheirer.gui.setup;

import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import io.github.dsheirer.gui.ApplicationIcon;
import io.github.dsheirer.gui.ScrollMorePanel;
import io.github.dsheirer.gui.theme.Theme;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import io.github.dsheirer.database.*;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationImporter;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService;
import io.github.dsheirer.gui.configuration.LegacyPlaylistImportDialog;
import io.github.dsheirer.gui.configuration.SqliteDatabaseImportDialog;
import io.github.dsheirer.gui.CopyableErrorDialog;
import io.github.dsheirer.database.upgrade.*;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.gui.whatsnew.WhatsNewDialog;
import io.github.dsheirer.jmbe.*;
import io.github.dsheirer.jmbe.github.GitHub;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.application.WebCertificateMode;
import io.github.dsheirer.preference.record.RecordingMode;
import io.github.dsheirer.preference.radioreference.RadioReferencePreference;
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
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import static io.github.dsheirer.gui.setup.SetupProgress.State.*;

/** Pre-receiver Swing setup session. No receiver services or device controllers are constructed here. */
public final class SetupWizard extends JDialog
{
    public record Result(UserPreferences preferences, PortableDataRootLock lock, boolean startChannels,
                         SqliteDatabaseImportDialog.PreparedImport replacement, boolean openWebAfterSetup)
    {
        public Result(UserPreferences preferences, PortableDataRootLock lock, boolean startChannels)
        { this(preferences, lock, startChannels, null, false); }
    }
    private final Path root;
    private final Path database;
    private final Path managedRecordingCatalog;
    private final SdrTrunkDatabaseBootstrap.Options options;
    private final boolean forced;
    private final boolean databaseImportRequested;
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
    private final JButton detailsToggle = new JButton("Show details");
    private final JButton copyError = new JButton("Copy error");
    private final JProgressBar meter = new JProgressBar();
    private final JButton back = new JButton("Back");
    private final JButton next = new JButton("Continue");
    private final JButton exit = new JButton("Exit setup");
    private final JButton themeToggle = new JButton();
    private Theme selectedTheme;
    private final JButton cancel = new JButton("Cancel operation");
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "setup preparation"); thread.setDaemon(true); return thread;
    });
    private final SetupJobOutput output = new SetupJobOutput();
    private final Timer refresh;
    private Timer countdown;
    private boolean busy;
    private boolean finished;
    private boolean firstRunGuideEligible;
    private boolean certificateGuideVisible;
    private boolean limitedVisit;
    private boolean webAdjusted;
    private boolean rrVerified;
    private boolean rrPremium;
    private RadioReferenceDirectoryService rrSetupService;
    private SetupRadioReferenceRegion rrSetupRegion;
    private boolean administratorConfigured;
    private boolean vaultDeferred;
    private boolean editJmbe;
    private boolean jmbeInstalled;
    private boolean themeRefreshPending;
    private BooleanSupplier canContinue = () -> true;
    private Runnable accept = () -> {};
    private Runnable cancellation;
    private long generation;
    private String migrationReport = "";
    private String errorReport = "";
    private String selectedScope = "";
    private volatile String operation = "";
    private volatile long operationStartedNanos;
    private volatile int completed;
    private volatile int total;
    private List<String> autoStart = List.of();
    private boolean autoStartNeedsJmbe;
    private boolean startChannels = true;
    private boolean sourceCommitted;
    private boolean restartRequired;
    private SqliteDatabaseImportDialog.PreparedImport replacement;
    private StatsWebServerService liveServer;
    private DatabaseMigrationChain.PreflightReport startupMigrationPlan;
    private boolean managedRecordingCatalogNeedsUpgrade;
    private String managedRecordingCatalogReport = "";
    private boolean createUpgradeBackup = true;

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
        wizard.closeRadioReferenceSetup();
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
            Throwable inspectionFailure = null;
            try
            {
                wizard.managedRecordingCatalogNeedsUpgrade =
                    ManagedRecordingCatalogMigrator.inspectForStartup(wizard.managedRecordingCatalog).needsMigration();
            }
            catch(java.io.IOException | java.sql.SQLException ignored)
            {
                //The optional catalog retains its existing unavailable-catalog fallback for unknown or damaged files.
            }
            if(Files.isRegularFile(wizard.database))
            {
                try
                {
                    wizard.startupMigrationPlan = ApplicationMigrationService.readStartupPlan(wizard.database);
                    current = !wizard.startupMigrationPlan.requiresMigration();
                }
                catch(java.io.IOException | java.sql.SQLException e) { inspectionFailure = e; }
            }
            if(current)
            {
                wizard.initialize(false);
                boolean setupVisitNeeded = wizard.needsVisit();
                if(!wizard.managedRecordingCatalogNeedsUpgrade && !setupVisitNeeded)
                {
                    wizard.finished = true;
                    return new Result(wizard.preferences, wizard.lock, true);
                }
                if(setupVisitNeeded)
                {
                    wizard.progress.setComplete(false);
                    wizard.save();
                }
            }
            Throwable showInspectionFailure = inspectionFailure;
            SwingUtilities.invokeAndWait(() -> {
                if(wizard.preferences != null)
                {
                    ThemeManager.getInstance().initialize(wizard.preferences);
                    SwingUtilities.updateComponentTreeUI(wizard);
                    wizard.showPage(wizard.databaseImportRequested || wizard.managedRecordingCatalogNeedsUpgrade ?
                        SetupStep.SOURCE : wizard.initialStep());
                }
                else wizard.showPage(SetupStep.SOURCE);
                if(showInspectionFailure != null) wizard.fail(
                    "We couldn’t check your saved settings. Choose Check my settings to try again. Receiving hasn’t started.",
                    CopyableErrorDialog.message(showInspectionFailure));
                if(wizard.databaseImportRequested && wizard.preferences != null)
                {
                    SwingUtilities.invokeLater(wizard::promptForDatabaseReplacement);
                }
                wizard.setVisible(true);
            });
            return wizard.finished ? new Result(wizard.preferences, wizard.lock, wizard.startChannels,
                wizard.replacement, wizard.replacement == null) : null;
        }
        finally
        {
            wizard.closeRadioReferenceSetup();
            wizard.worker.shutdown();
            SwingUtilities.invokeAndWait(() -> { wizard.refresh.stop(); wizard.stopCountdown(); wizard.dispose(); });
            if(!wizard.finished && wizard.ownsLock && wizard.lock != null) wizard.lock.close();
        }
    }

    private SetupWizard(String[] args, Path dataRoot, PortableDataRootLock existingLock)
    {
        super((Frame)null, "VCE · Setup", true);
        ApplicationIcon.apply(this);
        ApplicationIcon.applyTaskbarIcon();
        root = dataRoot.toAbsolutePath().normalize();
        database = SdrTrunkDatabasePath.getDatabasePath(root);
        managedRecordingCatalog = database.resolveSibling("managed-recordings.sqlite");
        options = SdrTrunkDatabaseBootstrap.Options.parse(args);
        createUpgradeBackup = options.createUpgradeBackup();
        if(Files.isRegularFile(database) && options.upgradeData() != null)
            throw new IllegalArgumentException("This profile already has a database. Use File → Import SQLite Database for explicitly confirmed replacement.");
        if(!Files.isRegularFile(database) && options.upgradeCurrent())
            throw new IllegalArgumentException("--upgrade-current requires an existing portable database.");
        databaseImportRequested = Arrays.asList(args).contains("--import-sqlite");
        forced = Arrays.asList(args).contains("--setup-wizard") || databaseImportRequested;
        lock = existingLock;
        ownsLock = existingLock == null;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { leave(); } });
        if(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER))
            Desktop.getDesktop().setQuitHandler((event, response) -> {
                response.cancelQuit();
                SwingUtilities.invokeLater(this::leave);
            });
        JPanel shell = new JPanel(new BorderLayout(28, 0));
        JPanel rail = new JPanel(new BorderLayout());
        rail.setPreferredSize(new Dimension(235, 600));
        rail.add(new RadioIllustration(), BorderLayout.NORTH);
        JPanel lineage = new JPanel(new GridLayout(0, 1, 0, 5));
        lineage.setBorder(new EmptyBorder(16, 12, 16, 12));
        for(SetupStep id: SetupStep.values())
        {
            JButton button = new JButton(id.title());
            button.setHorizontalAlignment(SwingConstants.LEFT);
            button.setMargin(new Insets(8,12,8,12));
            button.putClientProperty("JButton.buttonType", "roundRect");
            button.addPropertyChangeListener("UI",e -> refreshNavigationTheme());
            button.addActionListener(e -> { if(!busy && preferences != null) showPage(id); });
            steps.put(id, button); lineage.add(button);
        }
        JScrollPane stepsScroll = new JScrollPane(lineage,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        stepsScroll.setBorder(BorderFactory.createEmptyBorder());
        stepsScroll.getVerticalScrollBar().setUnitIncrement(18);
        rail.add(stepsScroll, BorderLayout.CENTER);
        shell.add(rail, BorderLayout.WEST);
        JPanel body = new JPanel(new BorderLayout(8, 22));
        body.setBorder(new EmptyBorder(30, 0, 22, 30));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 27f));
        JPanel heading = new JPanel(new BorderLayout(16,0));
        heading.add(title, BorderLayout.CENTER);
        IconFontSwing.register(FontAwesome.getIconFont());
        WizardStyles.secondary(themeToggle);
        themeToggle.addActionListener(e -> attempt(this::toggleTheme));
        heading.add(themeToggle, BorderLayout.EAST);
        body.add(heading, BorderLayout.NORTH);
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBorder(new EmptyBorder(0,0,8,8));
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        body.add(new ScrollMorePanel(scroll), BorderLayout.CENTER);
        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,WizardStyles.border()),new EmptyBorder(16,0,0,0)));
        WizardNotice.styleError(danger);
        danger.addPropertyChangeListener("UI", e -> WizardNotice.styleError(danger));
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
        WizardStyles.quiet(detailsToggle); WizardStyles.quiet(copyError);
        detailsToggle.setVisible(false);
        detailsToggle.addActionListener(e -> {
            diagnostics.setVisible(!diagnostics.isVisible());
            detailsToggle.setText(diagnostics.isVisible() ? "Hide details" : "Show details");
            footer.revalidate();
        });
        copyError.setVisible(false);
        copyError.addActionListener(e -> {
            try
            {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(errorReport), null);
                copyError.setText("Copied");
            }
            catch(RuntimeException failure)
            {
                Toolkit.getDefaultToolkit().beep();
                copyError.setText("Copy error");
            }
        });
        JPanel diagnosticActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        diagnosticActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        diagnosticActions.setBorder(new EmptyBorder(20, 0, 0, 0));
        diagnosticActions.add(detailsToggle); diagnosticActions.add(copyError);
        footer.add(diagnosticActions);
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        navigation.setAlignmentX(Component.LEFT_ALIGNMENT);
        exit.addActionListener(e -> leave());
        back.addActionListener(e -> { if(!busy && step.ordinal() > 0) showPage(SetupStep.values()[step.ordinal()-1]); });
        next.addActionListener(e -> { if(!busy) attempt(accept); });
        WizardStyles.primary(next); WizardStyles.secondary(back);
        WizardStyles.quiet(exit); WizardStyles.secondary(cancel);
        getRootPane().setDefaultButton(next);
        next.setMnemonic(java.awt.event.KeyEvent.VK_N);
        back.setMnemonic(java.awt.event.KeyEvent.VK_B);
        exit.setMnemonic(java.awt.event.KeyEvent.VK_X);
        cancel.setVisible(false);
        cancel.addActionListener(e -> {
            if(cancellation != null) cancellation.run();
        });
        navigation.add(exit); navigation.add(cancel); navigation.add(back); navigation.add(next);
        footer.add(navigation);
        JTextArea exitNote=text("You can exit and return later. Completed steps are saved.");
        exitNote.addPropertyChangeListener("UI",e -> exitNote.setForeground(WizardStyles.muted()));
        exitNote.setForeground(WizardStyles.muted()); footer.add(exitNote);
        body.add(footer, BorderLayout.SOUTH); shell.add(body, BorderLayout.CENTER);
        setContentPane(shell);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1080, screen.width - 40), Math.min(800, screen.height - 60));
        setLocationRelativeTo(null);
        refresh = new Timer(150, e -> {
            String value = output.snapshot();
            if(!console.getText().equals(value)) { console.setText(value); console.setCaretPosition(console.getDocument().getLength()); }
            meter.setIndeterminate(total <= 0); meter.setMaximum(Math.max(1,total)); meter.setValue(completed);
            String elapsed = busy && operationStartedNanos > 0 ?
                " · " + elapsedLabel(System.nanoTime() - operationStartedNanos) : "";
            meter.setString(operation + elapsed);
        });
        refresh.start();
    }

    private void initialize(boolean newPreferences) throws Exception
    {
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
        InitialAdminSetup.initializeNewProfile(database);
        InitialAdminSetup.isPasswordRequired(database); //A persisted administrator is authoritative after interruption.
        SdrTrunkDatabaseBootstrap.prepareVault(root);
        if(lock == null) lock = PortableDataRootLock.acquire(root);
        SqlitePreferencesFactory.install(database);
        preferences = new UserPreferences();
        progress = SetupProgress.read(database);
        if(sourceCommitted)
        {
            var rr = preferences.getRadioReferencePreference();
            SetupReadiness.prepareMigratedRadioReferenceLocation(progress,
                rr.getPreferredCountryId(), rr.getPreferredStateId());
        }
        firstRunGuideEligible = !progress.isComplete();
        limitedVisit = progress.isComplete() && !forced;
        if(newPreferences) preferences.getApplicationPreference().setStatsLoggingEnabled(true);
        var app = preferences.getApplicationPreference();
        if(selectedTheme != null) app.setTheme(selectedTheme);
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
            if(!sourceCommitted && progress.get(SetupStep.RADIO_REFERENCE) == PENDING)
                progress.set(SetupStep.RADIO_REFERENCE, DEFERRED);
            progress.set(SetupStep.HARDWARE, DEFERRED);
        }
        if(preferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded())
            preferences.getEncryptionKeyPreference().getVaultService().tryAutoUnlockSavedPassword();
        readAutoStart();
        if(limitedVisit && !autoStartNeedsJmbe && progress.get(SetupStep.JMBE) == PENDING)
            progress.set(SetupStep.JMBE, DEFERRED);
        save();
    }

    private void readAutoStart() throws Exception
    {
        autoStartNeedsJmbe = false;
        //Read names only. No channel/controller objects and no credentials are projected into the review.
        try(var connection = SdrTrunkDatabase.open(database);
            var query = connection.prepareStatement("SELECT name, decoder_type FROM configuration_channel WHERE auto_start = 1 ORDER BY auto_start_order, name");
            var rows = query.executeQuery())
        {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            while(rows.next())
            {
                names.add(autoStartLabel(rows.getString(1)));
                autoStartNeedsJmbe |= SetupReadiness.requiresJmbe(rows.getString(2));
            }
            autoStart = List.copyOf(names);
        }
    }

    static String autoStartLabel(String name)
    {
        return name == null || name.isBlank() ? "(Unnamed channel)" : name;
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
        SetupReadiness.prepareRadioReference(progress,
            rr.isStoreCredentials() && present(rr.getUserName()) && present(rr.getPassword()),
            rr.getPreferredCountryId(), rr.getPreferredStateId());
        if(progress.isImported() || progress.isComplete()) ready(SetupStep.ACTIVITY, true);
        SetupReadiness.carryOverRecordingChoice(progress,
            preferences.getRecordPreference().getConfiguredRecordingMode());
        ready(SetupStep.CALIBRATION, CalibrationManager.getInstance(preferences).isCalibrated());
        SetupReadiness.prepareHardwareDiscovery(progress);
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
        closeRadioReferenceSetup();
        if(managedRecordingCatalogNeedsUpgrade) id = SetupStep.SOURCE;
        stopCountdown();
        certificateGuideVisible = false;
        back.setVisible(true);
        step = id; generation++;
        page.removeAll(); danger.setVisible(false); errorReport = ""; copyError.setVisible(false);
        copyError.setText("Copy error");
        diagnostics.setVisible(false);
        detailsToggle.setVisible(false); detailsToggle.setText("Show details");
        title.setText(id.title());
        next.setText(id == SetupStep.REVIEW ? "Finish & launch" : "Continue");
        canContinue = () -> true;
        accept = () -> completeAndContinue();
        switch(id)
        {
            case SOURCE -> sourcePage();
            case ADMINISTRATOR -> administratorPage();
            case WEB -> webPage();
            case JMBE -> jmbePage();
            case RADIO_REFERENCE -> radioReferencePage();
            case ACTIVITY -> activityPage();
            case RECORDINGS -> recordingsPage();
            case HARDWARE -> hardwarePage();
            case CALIBRATION -> calibrationPage();
            case REVIEW -> reviewPage();
        }
        updateNavigation(); page.revalidate(); page.repaint();
        SwingUtilities.invokeLater(() -> { if(!busy) next.requestFocusInWindow(); });
    }

    private void sourcePage()
    {
        // Finish validating or updating the main profile before changing its separate recording catalog.
        if(managedRecordingCatalogNeedsUpgrade && preferences != null)
        {
            notice("Managed recordings need an update", "The catalog needs a one-time update. Its recording entries will be preserved.", false);
            details("Catalog to update", managedRecordingCatalog.toString());
            upgradeBackupChoice();
            next.setText("Update");
            accept = () -> confirmMigration(this::upgradeManagedRecordingCatalog);
            return;
        }
        if(!managedRecordingCatalogReport.isBlank())
        {
            notice("Managed recordings", managedRecordingCatalogReport, false);
        }
        if(preferences != null)
        {
            if(forced && !sourceCommitted && liveServer == null)
            {
                existingSourcePage();
                return;
            }
            notice("Your settings are in place", "Continue using this installation’s saved settings. You can review or change them in the following steps.", true);
            details("Where my settings are saved", database + "\n\nTo replace this database later, use File → Import SQLite Database from the main application. Returning to this page never replaces your data.");
            if(!migrationReport.isBlank())
            {
                details("View the full import report",migrationReport);
                button("Copy Message", () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(migrationReport), null));
            }
            return;
        }
        if(Files.isRegularFile(database))
        {
            if(startupMigrationPlan == null)
            {
                paragraph("Check this installation’s saved settings before continuing.");
                next.setText("Check my settings");
                accept = () -> job("Checking your saved settings…", null,
                    () -> ApplicationMigrationService.readStartupPlan(database), plan -> {
                        startupMigrationPlan = plan;
                        showPage(SetupStep.SOURCE);
                    });
                return;
            }
            if(!startupMigrationPlan.requiresMigration())
            {
                notice("Your settings are ready", "Continue using this installation’s saved settings.", true);
                accept = () -> job("Loading your settings…", null, () -> {
                    initialize(false); return true;
                }, ignored -> showPage(initialStep()));
                return;
            }
            notice("Your settings need an update", "This build needs to update the database before receiving starts.", false);
            migrationDetails(startupMigrationPlan);
            upgradeBackupChoice();
            next.setText("Update");
            accept = () -> confirmMigration(() -> migrate(null, false, false, null));
            return;
        }
        paragraph("Welcome! Is this your first time using VCE, or are you bringing settings from an older installation?");
        ButtonGroup group = new ButtonGroup();
        WizardChoiceCard fresh = choice(group, "Start fresh — recommended for new users", "Begin with no saved channels. We’ll help you set up digital voice, access and decoding performance.", true);
        WizardChoiceCard folder = choice(group, "Copy a previous VCE installation / data folder", "Bring your saved settings and digital voice tools into this installation. Your old installation stays unchanged; recordings and logs are not copied.", false);
        WizardChoiceCard sqlite = choice(group, "Import a SQLite database only", "Use settings from a .sqlite file. Extra files such as digital voice tools and your encryption vault are not included. Output folders may still point to the old location.", false);
        WizardChoiceCard xml = choice(group, "Import legacy XML", "Bring in channels and other supported settings from an older XML playlist. Use SQLite or an installation folder if it has newer changes.", false);
        JPanel folderDetails=stack(), sqliteDetails=stack(), xmlDetails=stack();
        JTextField folderPath=sourceField(folderDetails,"Previous installation or data folder",true);
        JTextField sqlitePath=sourceField(sqliteDetails,"SQLite database file",false);
        JTextField xmlPath=sourceField(xmlDetails,"XML playlist file",false);
        List<Path> nearby = PreviousBuildLocator.discover();
        if(!nearby.isEmpty())
        {
            JComboBox<Path> locations = new JComboBox<>(nearby.toArray(Path[]::new));
            locations.setMaximumSize(new Dimension(Integer.MAX_VALUE,38));
            locations.getAccessibleContext().setAccessibleName("Nearby installations");
            addTo(folderDetails,text("We found these nearby installations:")); addTo(folderDetails,locations);
            addTo(folderDetails,action("Use this installation", () -> folderPath.setText(locations.getSelectedItem().toString())));
        }
        LegacyXmlConfigurationImporter.discoverPlaylist(io.github.dsheirer.portable.PortableApplicationPaths.getLegacyApplicationRoot()).ifPresent(found ->
            addTo(xmlDetails,action("Use the XML playlist we found", () -> xmlPath.setText(found.toString()))));
        folder.setDetails(folderDetails); sqlite.setDetails(sqliteDetails); xml.setDetails(xmlDetails);
        Runnable selectionChanged = () -> { next.setText(fresh.isSelected() ? "Start setup" : xml.isSelected() ? "Import XML" : "Review import"); page.revalidate(); page.repaint(); };
        for(var item:List.of(fresh,folder,sqlite,xml)) item.radio().addItemListener(e -> selectionChanged.run());
        if(options.upgradeData() != null) {
            boolean directory=Files.isDirectory(options.upgradeData());
            (directory ? folderPath : sqlitePath).setText(options.upgradeData().toString());
            (directory ? folder : sqlite).radio().setSelected(true);
        }
        if(options.importXml() != null) { xmlPath.setText(options.importXml().toString()); xml.radio().setSelected(true); }
        selectionChanged.run();
        accept = () -> {
            if(fresh.isSelected()) { migrate(null, true, false, null); return; }
            JTextField source=folder.isSelected() ? folderPath : sqlite.isSelected() ? sqlitePath : xmlPath;
            if(source.getText().isBlank()) throw new IllegalArgumentException("Choose the source folder or file first.");
            Path input = Path.of(source.getText());
            if(xml.isSelected()) { migrate(input, false, true, null); return; }
            var selection = PreviousBuildLocator.resolveSelection(input).orElseThrow(() -> new IllegalArgumentException("No supported portable database found at this location."));
            if(folder.isSelected() != selection.portableProfile()) throw new IllegalArgumentException("The selected path does not match the chosen folder/file import scope.");
            job("Inspecting source…", null, () -> ApplicationMigrationService.readMigrationApproval(
                selection.database(), root.getParent()), approval -> {
                DatabaseMigrationChain.PreflightReport plan = approval.plan();
                page.removeAll();
                notice("Ready to bring your settings over", "Your previous installation will stay unchanged. We’ll check the copied settings before using them.", false);
                paragraph("Import from: " + selection.path());
                selectedScope = selection.portableProfile() ? "Your settings, encryption vault, digital voice library and optional tools will be copied when available. Folders inside the old data folder will point to this installation. Recordings and logs are not copied; shared folders outside it stay unchanged." : "Only the database contents will be copied. Your vault, digital voice library and other files stay where they are. Saved output folders will not be changed — review them before starting reception.";
                notice("What will be carried over",selectedScope,false);
                migrationDetails(plan);
                button("Choose a different source", () -> showPage(SetupStep.SOURCE));
                next.setText("Confirm import");
                accept = () -> migrate(input, false, false, approval);
                page.revalidate();
            });
        };
    }

    private void upgradeManagedRecordingCatalog()
    {
        job("Updating managed recordings…", null,
            () -> ManagedRecordingCatalogMigrator.migrate(managedRecordingCatalog, createUpgradeBackup), result -> {
                managedRecordingCatalogNeedsUpgrade = false;
                managedRecordingCatalogReport = result.migrated() ?
                    "The catalog is ready. " + (result.backup() == null ? "No recovery copy was requested." :
                        "Recovery copy: " + result.backup()) : "The catalog is already current.";
                showPage(SetupStep.SOURCE);
            }, failure -> {
                showPage(SetupStep.SOURCE);
                fail("Managed recordings could not be updated. Try again, or continue receiving with managed recordings unavailable.",
                    CopyableErrorDialog.message(failure));
                button("Continue without managed recordings", () -> {
                    managedRecordingCatalogNeedsUpgrade = false;
                    managedRecordingCatalogReport = "Managed recordings are unavailable. Receiving can continue; the saved recording preference is unchanged. Try updating the catalog again on a later launch.";
                    showPage(SetupStep.SOURCE);
                });
            });
    }

    private void upgradeBackupChoice()
    {
        JCheckBox backup = new JCheckBox("Create a recovery backup first", createUpgradeBackup);
        backup.addActionListener(e -> createUpgradeBackup = backup.isSelected());
        append(backup);
        paragraph("A backup can take time for a large database. Without it, there is no recovery copy for the previous build.");
    }

    private void migrate(Path source, boolean fresh, boolean xml,
                         ApplicationMigrationService.ApprovedMigrationPlan approved)
    {
        job("Preparing your profile…", null, () -> {
            String report;
            if(fresh) { SdrTrunkDatabaseBootstrap.createFresh(database); report = "New profile created."; }
            else if(xml) { LegacyXmlConfigurationImporter.importPlaylist(source, database); report = "Legacy XML imported. The source was not changed."; }
            else
            {
                ApplicationMigrationService service = new ApplicationMigrationService();
                if(source == null)
                    report = ApplicationMigrationSuccessDialog.currentDatabaseReport(
                        service.migrateCurrent(root, createUpgradeBackup, this::migrationProgress));
                else
                {
                    var selected = PreviousBuildLocator.resolveSelection(source).orElseThrow();
                    var result = service.importPrevious(selected, root, approved, output::accept);
                    report = ApplicationMigrationSuccessDialog.previousImportReport(result);
                }
            }
            //The database/profile operation is already committed at this point. If the following readiness refresh
            //fails, recovery must not offer to import again into the now-occupied destination.
            sourceCommitted = true;
            initialize(fresh || xml);
            return report;
        }, report -> {
            migrationReport = report;
            progress.setComplete(false);
            if(!persist()) return;
            ThemeManager.getInstance().initialize(preferences);
            SwingUtilities.updateComponentTreeUI(this);
            showPage(SetupStep.SOURCE);
            if(!fresh && !xml && !managedRecordingCatalogNeedsUpgrade)
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

    private void confirmMigration(Runnable update)
    {
        int selected = JOptionPane.showOptionDialog(this,
            (createUpgradeBackup ? "A recovery copy will be kept before updating. " :
                "No recovery copy will be kept. ") +
                "The previous VCE version cannot reopen the updated database. Continue?",
            "Update saved data?", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
            new Object[]{"Update", "Cancel"}, "Cancel");
        if(selected == 0)
        {
            update.run();
        }
    }

    /** Same first step and lineage; replacement is an explicit action, never a side effect of Back. */
    private void existingSourcePage()
    {
        paragraph("Your saved settings are already here. Keep using them, or choose an import to review before making any changes.");
        ButtonGroup group = new ButtonGroup();
        WizardChoiceCard keep = choice(group, "Keep my current settings — recommended",
            "Review your setup without importing or replacing anything.", true);
        WizardChoiceCard sqlite = choice(group, "Replace settings from a SQLite database",
            "Replace this installation’s database, including channels, aliases, accounts and preferences. We’ll show a warning and save a recovery copy first. Extra files are not copied, and saved output folders are not changed.", false);
        WizardChoiceCard xml = choice(group, "Import a legacy XML playlist",
            "Add supported channels, aliases and streaming settings to this profile. Existing configuration stays in place; conflicting imported names are renamed. This does not replace your database.", false);
        details("Where my settings are saved", database.toString());
        Runnable selectionChanged = () -> next.setText(keep.isSelected() ? "Continue" : "Choose file & review");
        for(var card : List.of(keep, sqlite, xml)) card.radio().addItemListener(e -> selectionChanged.run());
        accept = () -> {
            if(keep.isSelected()) { completeAndContinue(); return; }
            if(!persist()) return;
            if(sqlite.isSelected())
            {
                promptForDatabaseReplacement();
            }
            else
            {
                var selected = LegacyPlaylistImportDialog.choose(this, database, root);
                if(selected == null) return;
                job("Importing your playlist…", null,
                    () -> {
                        var result = new LegacyPlaylistImportService(database).execute(selected);
                        //The merge is already committed, even if a subsequent readiness read fails.
                        sourceCommitted = true;
                        migrationReport = LegacyPlaylistImportDialog.resultMessage(result);
                        readAutoStart();
                        revalidateSettings();
                        return result;
                    }, result -> {
                        progress.set(SetupStep.SOURCE, COMPLETE);
                        showPage(SetupStep.SOURCE);
                        notice("Your playlist was imported", "Your existing settings were kept. Continue to review your setup before receiving starts.", true);
                        persist();
                    });
            }
        };
    }

    private void promptForDatabaseReplacement()
    {
        if(busy || preferences == null || !persist())
        {
            return;
        }
        var selected = SqliteDatabaseImportDialog.choose(this, database, root);
        if(selected != null)
        {
            //Return to the startup boundary. No old preferences or setup callbacks may write after replacement.
            replacement = selected;
            finished = true;
            dispose();
        }
    }

    private void administratorPage()
    {
        boolean exists = administratorConfigured;
        paragraph(exists ? "Your administrator account is already set up. Leave these fields blank to keep your password. To change it, enter your current password first." : "Choose a password to manage your channels, aliases and receiver settings. Use at least 7 characters (up to 256).");
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
        paragraph("Use the web interface to edit aliases, listen to calls, view spectrum and more. Choose where you want to access it.");
        if(webAdjusted) notice("Web access is now available on this computer", "Your previous installation had web access turned off. We’ve enabled it locally so you can edit aliases. Other devices still cannot connect.", false);
        else if(progress.get(step) == NEEDS_ATTENTION) notice("Please review your web access", "These settings changed or could not be started last time. Check them before continuing.", false);
        ButtonGroup group = new ButtonGroup();
        card(group, "This computer only — recommended", "Open the receiver in a browser on this computer. Other devices cannot connect.", !app.isStatsWebServerAnyIpEnabled());
        WizardChoiceCard networkChoice = choice(group, "Other devices", "Also connect from a phone, tablet or another computer that can reach this receiver.", app.isStatsWebServerAnyIpEnabled());
        JPanel networkDetails=stack();
        addTo(networkDetails,text("Your firewall controls who can reach this computer. This choice is not limited to your home network; we do not change firewall rules or open router ports."));
        networkChoice.setDetails(networkDetails);
        JRadioButton network=networkChoice.radio();
        JSpinner port = WizardStyles.integerSpinner(app.getStatsWebServerPort(),
            ApplicationPreference.MIN_STATS_WEB_SERVER_PORT, ApplicationPreference.MAX_STATS_WEB_SERVER_PORT);
        labelled("Web port", port);
        paragraph("8090 works for most installations. Change it only if another application already uses this port.");
        details("About secure access", "Connections use HTTPS to protect your sign-in. Your existing certificate settings are kept. We’ll check that web access works before you finish setup.");
        accept = () -> {
            commitNumber(port, "Enter a port from " + ApplicationPreference.MIN_STATS_WEB_SERVER_PORT + " to " +
                ApplicationPreference.MAX_STATS_WEB_SERVER_PORT + ".");
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
        boolean compatible = JmbeLibraryMetadata.isSupported(library);
        if(compatible && !editJmbe)
        {
            notice("Digital voice is ready", jmbeInstalled ? "JMBE was installed successfully. You can now listen to supported digital voice channels. Choose Continue to move on." : "A compatible JMBE library is already available. You’re ready to listen to supported digital voice channels.",true);
            details("Installed library details", "Version: " + JmbeLibraryMetadata.getVersion(library) + "\nLocation: " + library);
            button("Change digital voice setup…", () -> { editJmbe=true; showPage(step); });
            accept = () -> { progress.set(step,progress.get(step)==CARRIED_OVER ? CARRIED_OVER : COMPLETE); completeAndContinue(); };
            return;
        }
        paragraph("Want to hear digital voice? JMBE adds audio support for systems such as P25 and DMR. We recommend setting it up now; analog listening does not need it.");
        ButtonGroup group=new ButtonGroup();
        WizardChoiceCard create=choice(group,"Set up digital voice — recommended", "We’ll download the JMBE tools, build the voice library and install it for you. This may take a few minutes and needs an internet connection.",true);
        JPanel createDetails=stack();
        addTo(createDetails,text("JMBE source is provided for educational use. Voice-codec algorithms may be subject to patents or other restrictions in your jurisdiction. Review the project’s licensing and applicable restrictions before proceeding. Setup downloads and runs build tools on this computer."));
        JCheckBox consent = new JCheckBox("I agree to download and build JMBE on this computer.");
        WizardStyles.bodyFont(consent, Font.PLAIN); addTo(createDetails,consent); create.setDetails(createDetails);
        WizardChoiceCard existing=choice(group,"Use a JMBE file I already have", "Choose a compatible JMBE .jar file. We’ll check it and copy it into this installation.",false);
        JPanel existingDetails=stack();
        JTextField selectedFile=sourceField(existingDetails,"JMBE library file",false); existing.setDetails(existingDetails);
        if(compatible) button("Keep current setup", () -> { editJmbe=false; progress.set(step,COMPLETE); if(persist()) showPage(step); });
        defer("Set up later");
        Runnable changed=() -> {
            next.setText(create.isSelected() ? "Set up digital voice" : "Use this file");
            canContinue=() -> create.isSelected() ? consent.isSelected() : !selectedFile.getText().isBlank();
            updateNavigation();
        };
        create.radio().addItemListener(e -> changed.run()); existing.radio().addItemListener(e -> changed.run());
        consent.addItemListener(e -> changed.run()); changedText(selectedFile,changed);
        accept = () -> {
            if(create.isSelected())
            {
                if(!consent.isSelected()) throw new IllegalArgumentException("Please agree to the download and build before continuing.");
                createDigitalVoice();
            }
            else
            {
                Path chosen=Path.of(selectedFile.getText().trim());
                job("Checking your JMBE file…", null, () -> {
                    preferences.getJmbeLibraryPreference().installLibrary(chosen); return true;
                }, ignored -> digitalVoiceReady());
            }
        };
        changed.run();
    }

    private void createDigitalVoice()
    {
        AtomicBoolean stopped=new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<JmbeCreator> creator=new java.util.concurrent.atomic.AtomicReference<>();
        job("Finding the digital voice tools…", () -> { stopped.set(true); if(creator.get()!=null) creator.get().cancel(); }, () -> {
            var release=GitHub.getLatestRelease(JmbeCreator.GITHUB_JMBE_RELEASES_URL);
            if(stopped.get()) throw new InterruptedException();
            if(release==null) throw new IllegalStateException("Release lookup failed");
            Path target=root.resolve("jmbe").resolve("jmbe-"+release.getVersion()+".jar");
            JmbeCreator task=new JmbeCreator(release,target); creator.set(task);
            if(stopped.get()) task.cancel();
            Path installed=task.run(message -> {
                String stage=JmbeSetupMessages.stage(message);
                if(stage!=null) operation=stage;
                output.accept(message);
            });
            preferences.getJmbeLibraryPreference().installLibrary(installed); return true;
        }, ignored -> digitalVoiceReady());
    }
    private void digitalVoiceReady()
    {
        jmbeInstalled=true; editJmbe=false; progress.set(step,COMPLETE);
        if(persist()) showPage(step);
    }

    private void radioReferencePage()
    {
        var rr = preferences.getRadioReferencePreference();
        paragraph("Save your RadioReference account to look up radio systems and import channels.");
        JPanel account = stack();
        JPanel verification = new JPanel(new BorderLayout()) {
            public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE,getPreferredSize().height); }
        };
        verification.setOpaque(false);
        verification.getAccessibleContext().setAccessibleName("RadioReference connection status");
        if(rrVerified) showRadioReferenceResult(verification, rrPremium);
        else verification.add(text(present(rr.getUserName()) && present(rr.getPassword()) ?
            "Saved account. Test the connection to check access." : "No account saved."));
        addTo(account, verification);
        JTextField username = new JTextField(rr.getUserName() == null ? "" : rr.getUserName(), 28);
        labelled(account, "RadioReference username", username, 16);
        JPasswordField secret = new JPasswordField(28);
        labelled(account, "Password", secret, 16);
        SetupRadioReferenceRegion region = new SetupRadioReferenceRegion(rr.getPreferredCountryId(),
            rr.getPreferredStateId(), worker);
        rrSetupRegion = region;
        javax.swing.event.DocumentListener edited = new javax.swing.event.DocumentListener()
        {
            private void changed() { rrVerified=false; disconnectRadioReferenceSetup(region); verification.removeAll(); verification.add(text("Account details changed. Test the connection again.")); verification.revalidate(); verification.repaint(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        };
        username.getDocument().addDocumentListener(edited); secret.getDocument().addDocumentListener(edited);
        JButton testConnection = action("Test connection", () -> {
            rrVerified=false;
            disconnectRadioReferenceSetup(region);
            verification.removeAll(); verification.add(text("Checking account access…")); verification.revalidate(); verification.repaint();
            String name = username.getText().trim();
            char[] entered = secret.getPassword();
            char[] credential = entered.length > 0 ? entered : (rr.getPassword() == null ? new char[0] : rr.getPassword().toCharArray());
            job("Testing RadioReference connection…", null, () -> {
                RadioReferenceDirectoryService service = new RadioReferenceDirectoryService();
                try
                {
                    var result = service.login(name, credential);
                    if(!result.authenticated()) throw new IllegalArgumentException("Connection not verified. Check your account details and internet connection, then retry.");
                    return service;
                }
                catch(Exception | LinkageError failure) { service.close(); throw failure; }
                finally { Arrays.fill(credential,'\0'); }
            }, service -> {
                rrSetupService = service;
                boolean premium = service.status().premium();
                showRadioReferenceResult(verification, premium);
                if(premium) region.connected(new SetupRadioReferenceRegion.Directory() {
                    public List<RadioReferenceDirectoryService.DirectoryOption> countries() throws Exception
                    {
                        return service.countries("", RadioReferenceDirectoryService.MAXIMUM_RESULT_LIMIT).items();
                    }
                    public List<RadioReferenceDirectoryService.DirectoryOption> states(int countryId) throws Exception
                    {
                        return service.states(countryId, "", RadioReferenceDirectoryService.MAXIMUM_RESULT_LIMIT).items();
                    }
                });
                else region.disconnected(true);
            });
        });
        testConnection.setAlignmentX(Component.LEFT_ALIGNMENT);
        account.add(testConnection);
        append(new WizardSection("Account", account));
        append(Box.createVerticalStrut(12));
        append(new WizardSection("Location", region)); append(Box.createVerticalStrut(8));
        defer("Set up later");
        accept = () -> {
            boolean verified = rrVerified;
            boolean changed = saveRadioReferenceAccount(username, secret, region, rr);
            rrVerified = verified;
            if(changed) progress.set(step, COMPLETE); completeAndContinue();
        };
    }

    /** Save a validated location before clearing the password field invalidates its lookup session. */
    static boolean saveRadioReferenceAccount(JTextField username, JPasswordField secret,
                                              SetupRadioReferenceRegion region, RadioReferencePreference rr)
    {
        char[] entered = secret.getPassword();
        try
        {
            if(username.getText().isBlank() || entered.length == 0 && !present(rr.getPassword()))
                throw new IllegalArgumentException("Enter both username and password, or choose Set up later.");
            region.save(rr);
            boolean changed = !username.getText().trim().equals(rr.getUserName()) || entered.length > 0 || !rr.isStoreCredentials();
            rr.setUserName(username.getText().trim());
            if(entered.length > 0) rr.setPassword(new String(entered));
            rr.setStoreCredentials(true);
            secret.setText("");
            return changed;
        }
        finally { Arrays.fill(entered,'\0'); }
    }

    private void disconnectRadioReferenceSetup(SetupRadioReferenceRegion region)
    {
        region.disconnected(false);
        RadioReferenceDirectoryService service = rrSetupService;
        rrSetupService = null;
        if(service != null) worker.execute(service::close);
    }

    private void closeRadioReferenceSetup()
    {
        if(rrSetupRegion != null) { rrSetupRegion.close(); rrSetupRegion = null; }
        RadioReferenceDirectoryService service = rrSetupService;
        rrSetupService = null;
        if(service != null) worker.execute(service::close);
    }

    private void showRadioReferenceResult(JPanel verification, boolean premium)
    {
        rrVerified=true; rrPremium=premium;
        verification.removeAll();
        verification.add(new WizardNotice(premium ? "Connection verified: premium access available" :
            "Connection verified: premium access unavailable", premium ?
            "Your account is ready to look up radio systems and import channels." :
            "Channel imports and location lookup need a premium subscription. Check your subscription, or choose Set up later.",
            premium ? WizardNotice.Tone.SUCCESS : WizardNotice.Tone.WARNING));
        verification.revalidate(); verification.repaint(); page.revalidate();
    }

    private void activityPage()
    {
        var app = preferences.getApplicationPreference();
        boolean initiallyOff = !app.isStatsLoggingEnabled();
        boolean storedDetailed = app.isStatsDetailedHistoryEnabled();
        paragraph("Choose how much listening activity to keep. These settings do not change your audio recordings or application log files.");
        ButtonGroup group = new ButtonGroup();
        JRadioButton off = card(group,"Off", "Do not collect statistics or detailed activity history.",!app.isStatsLoggingEnabled());
        card(group,"Summary statistics", "Keep useful activity totals without a detailed event-by-event history.",app.isStatsLoggingEnabled() && !app.isStatsDetailedHistoryEnabled());
        JRadioButton detailed = card(group,"Summaries plus detailed history", "Also keep individual activity events for troubleshooting and review. This uses more storage.",app.isStatsLoggingEnabled() && app.isStatsDetailedHistoryEnabled());
        JSpinner days = WizardStyles.integerSpinner(app.getStatsLoggingRetentionDays(),1,365);
        JPanel retention=stack();
        addTo(retention,text("Keep time-based activity for this many days (30 recommended):"));
        days.getAccessibleContext().setAccessibleName("Activity retention in days"); addTo(retention,days);
        append(retention); retention.setVisible(!off.isSelected());
        off.addItemListener(e -> { retention.setVisible(!off.isSelected()); page.revalidate(); });
        accept = () -> {
            if(!off.isSelected()) commitNumber(days,"Enter a number of days from 1 to 365.");
            int retentionDays=off.isSelected() ? app.getStatsLoggingRetentionDays() : (Integer)days.getValue();
            //Reviewing an imported Off choice must not rewrite its dormant detailed-history preference.
            boolean saveDetailed = initiallyOff && off.isSelected() ? storedDetailed : detailed.isSelected();
            boolean changed = app.isStatsLoggingEnabled() == off.isSelected() || app.isStatsDetailedHistoryEnabled() != saveDetailed || app.getStatsLoggingRetentionDays() != retentionDays;
            app.setStatsLoggingEnabled(!off.isSelected());
            app.setStatsDetailedHistoryEnabled(saveDetailed);
            app.setStatsLoggingRetentionDays(retentionDays); if(changed) progress.set(step, COMPLETE); completeAndContinue();
        };
    }

    private void recordingsPage()
    {
        RecordingMode savedMode = preferences.getRecordPreference().getRecordingMode();
        boolean managedSelected = selectManagedByDefault(progress, savedMode);
        paragraph("Make VCE your call recording library. Managed Recordings organizes new calls as they arrive, " +
            "lets you search and play them in the browser, and can remove recordings after the age you choose.");
        ButtonGroup group = new ButtonGroup();
        JRadioButton managed = card(group, "Managed Recordings · Recommended",
            "Organized folders, searchable call details, browser playback, and optional age retention. " +
                "Choose this for the full VCE recording experience.", managedSelected);
        card(group, "Basic Recordings",
            "Keep placing new recordings in one flat folder for other software that watches it, such as " +
                "Trunking Recorder. Previously saved Managed calls stay searchable in VCE.", !managedSelected);
        if(progress.isImported() || progress.isDone(step))
        {
            notice("Your current choice is preserved",
                "Changing this setting affects future calls only. Earlier Managed recordings remain in the library.",
                false);
        }
        accept = () -> {
            preferences.getRecordPreference().setRecordingMode(managed.isSelected() ?
                RecordingMode.MANAGED : RecordingMode.CLASSIC);
            completeAndContinue();
        };
    }

    static boolean selectManagedByDefault(SetupProgress progress, RecordingMode savedMode)
    {
        //Only a fresh, unfinished profile gets the recommendation as a default. Imported and previously configured
        //profiles always present their current saved mode without changing it.
        return (!progress.isImported() && !progress.isComplete() &&
            progress.get(SetupStep.RECORDINGS) == PENDING) || savedMode == RecordingMode.MANAGED;
    }

    private void hardwarePage()
    {
        paragraph("Let’s see which radios are connected. We’re only looking for devices — nothing will start receiving yet.");
        JPanel inventory=stack(); append(inventory);
        addTo(inventory,text("Looking for connected radios…"));
        Runnable scan = () -> {
            AtomicBoolean stopped = new AtomicBoolean();
            job("Looking for connected radios…", () -> stopped.set(true),
                () -> new TunerHardwareDiscovery().scan(stopped::get), result -> {
                    inventory.removeAll();
                    for(var device: result.devices()) addTo(inventory,noticePanel("Detected · " + device.model(),device.identity() == null ? "Connected radio" : device.identity(),true));
                    if(result.devices().isEmpty()) addTo(inventory,noticePanel("No radios found yet", "That’s okay — you can connect one later. If a radio is already plugged in, check its cable and driver, then choose Rescan.",false));
                    for(String info:result.notices()) addTo(inventory,noticePanel("Optional radio support",info,false));
                    for(String error:result.errors()) addTo(inventory,new WizardNotice("A radio could not be checked",error,WizardNotice.Tone.ERROR));
                    addTo(inventory,text("Detected means the radio was found, not that reception has been tested. You’ll choose how to use it after setup."));
                    inventory.revalidate(); inventory.repaint();
                    progress.set(step, stopped.get() ? DEFERRED : COMPLETE);
                    if(persist() && stopped.get()) showPage(nextStep());
                });
        };
        button("Rescan",scan);
        accept = () -> { if(progress.get(step) == NEEDS_ATTENTION) deferCurrent(); else completeAndContinue(); };
        SwingUtilities.invokeLater(() -> { if(step == SetupStep.HARDWARE && !busy) scan.run(); });
    }

    private void calibrationPage()
    {
        CalibrationManager manager = CalibrationManager.getInstance(preferences);
        int pending = manager.getUncalibrated().size();
        if(pending==0)
        {
            notice("Decoding is optimized", "The best available decoding methods have been saved for this computer. These choices will be used when you start listening.",true);
            paragraph(manager.getCalibrationTypes().size()+" saved checks are up to date. There’s nothing else to run.");
            accept=this::completeAndContinue;
            return;
        }
        paragraph("Help VCE decode signals efficiently on this computer. We’ll compare a few processing methods and save the fastest supported choices. This is not a computer score, and it won’t change your channels.");
        notice("Before you start", "Close other applications and pause downloads, games and backups. Leave this wizard open. A quiet computer gives more reliable results.",false);
        paragraph(pending + " checks to run · " + (manager.getCalibrationTypes().size()-pending) + " saved checks reused");
        details("Why are these checks needed?", manager.getPendingReason() + "\n\nOnly new, changed or missing checks run. Valid results are kept if you stop or retry. A different processor or Java version may require all checks again.");
        next.setText(progress.get(step)==NEEDS_ATTENTION ? "Try remaining checks" : "Optimize now");
        defer("Skip this time");
        paragraph("Recommended now. You can skip this time and use the available defaults; we’ll offer unfinished checks on a later launch.");
        accept = this::benchmark;
    }

    private void benchmark()
    {
        CalibrationManager manager = CalibrationManager.getInstance(preferences);
        CalibrationRunner runner = new CalibrationRunner();
        job("Preparing to optimize decoding…",runner::cancel,() -> runner.run(manager.getUncalibrated(), value -> {
            completed=value.completed(); total=value.total();
            operation=completed==total ? "Saving your decoding choices…" : "Checking decoding methods · " + (completed+1) + " of " + total;
        },output), result -> {
            if(result.cancelled()) { progress.set(step,DEFERRED); persist(); showPage(step); detailsToggle.setVisible(true); notice("Optimization stopped", "Completed checks are saved. You can run the remaining checks now or skip this time.",false); return; }
            if(result.failed()>0) { progress.set(step,NEEDS_ATTENTION); persist(); showPage(step); detailsToggle.setVisible(true); fail("Some checks couldn’t finish. Your successful results are saved. Try the remaining checks again, or skip this time."); return; }
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
        paragraph("You’re almost ready. Review your choices below. Nothing will start receiving until you choose Finish & launch.");
        JPanel summary=new JPanel(new GridLayout(0,2,24,12)); summary.setOpaque(false);
        for(SetupStep id: SetupStep.values()) if(id != SetupStep.REVIEW)
        {
            JLabel name=new JLabel(id.title()); name.setFont(text("").getFont()); summary.add(name);
            JLabel state=new JLabel(label(progress.get(id))); state.setFont(text("").getFont().deriveFont(Font.BOLD)); summary.add(state);
        }
        summary.setMaximumSize(new Dimension(Integer.MAX_VALUE,summary.getPreferredSize().height));
        append(summary); append(Box.createVerticalStrut(24));
        if(!selectedScope.isBlank()) paragraph(selectedScope);
        var dirs = preferences.getDirectoryPreference();
        paragraph("New recordings: " + (preferences.getRecordPreference().getRecordingMode() == RecordingMode.MANAGED ?
            "Managed Recordings · organized and searchable" : "Basic Recordings · flat folder"));
        details("Recording and other output folders", "Recordings: " + dirs.getDirectoryRecording() +
            "\nManaged recordings: " + dirs.getDirectoryManagedRecording() +
            "\nEvent logs: " + dirs.getDirectoryEventLog() + "\nApplication logs: " +
            dirs.getDirectoryApplicationLog() + "\nStreaming: " + dirs.getDirectoryStreaming());
        var app = preferences.getApplicationPreference();
        paragraph("Web address: " + (app.isStatsWebServerHttpsEnabled() ? "https" : "http") + "://localhost:" + app.getStatsWebServerPort() + "/" +
            (app.isStatsWebServerAnyIpEnabled() ? "\nOther devices: use this computer's reachable address; host firewall restrictions still apply." : " — this computer only"));
        paragraph("Channels selected to start: " + (autoStart.isEmpty() ? "None — you can add channels after setup." : String.join(", ",autoStart)));
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
                else try(StatsWebServerService server = StatsWebServerService.forListenerValidation(preferences))
                {
                    var state=server.getRuntimeState();
                    if(!state.running()) throw new IllegalArgumentException(state.statusMessage());
                }
                return true;
            }, ignored -> {
                if(firstRunGuideEligible && liveServer == null &&
                    app.getStatsWebServerCertificateMode() == WebCertificateMode.AUTOMATIC)
                    showCertificateGuide();
                else finishAndLaunch();
            });
        };
    }

    private void showCertificateGuide()
    {
        var app = preferences.getApplicationPreference();
        String address = "https://127.0.0.1:" + app.getStatsWebServerPort() + "/";
        certificateGuideVisible = true;
        title.setText("Setup complete");
        page.removeAll();
        danger.setVisible(false);
        diagnostics.setVisible(false);
        detailsToggle.setVisible(false);
        copyError.setVisible(false);
        back.setVisible(false);
        next.setText("Launch VCE & open browser");
        paragraph("Your browser will open on this computer when you launch VCE. If it shows a certificate warning, follow the pictured steps.");
        paragraph("This computer's VCE web address: " + address);
        if(app.isStatsWebServerAnyIpEnabled())
            paragraph("On another device, use this computer's reachable address with port " + app.getStatsWebServerPort() +
                ". Check the address you intended to open before continuing there.");
        append(new BrowserCertificateGuide(address));
        accept = this::finishAndLaunch;
        updateNavigation();
        page.revalidate(); page.repaint();
        SwingUtilities.invokeLater(() -> {
            page.scrollRectToVisible(new Rectangle(0,0,1,1));
            next.requestFocusInWindow();
        });
    }

    private void finishAndLaunch()
    {
        SetupProgress.State previousReview = progress.get(SetupStep.REVIEW);
        boolean previouslyComplete = progress.isComplete();
        progress.set(SetupStep.REVIEW, COMPLETE);
        progress.setComplete(true);
        if(!persist())
        {
            progress.set(SetupStep.REVIEW, previousReview);
            progress.setComplete(previouslyComplete);
            updateNavigation();
            return;
        }
        WhatsNewDialog.getPendingReleaseNotes().ifPresent(WhatsNewDialog::markShown);
        finished = true;
        dispose();
    }

    private <T> void job(String description, Runnable cancelAction, Callable<T> work, Consumer<T> success)
    {
        job(description, cancelAction, work, success, null);
    }

    private <T> void job(String description, Runnable cancelAction, Callable<T> work, Consumer<T> success,
                         Consumer<Throwable> failureHandler)
    {
        String cancelCaption = step == SetupStep.HARDWARE ? "Skip discovery" : "Cancel operation";
        Runnable cancellationRequest = cancelAction == null ? null : () -> {
            cancel.setEnabled(false);
            operation = "Stopping safely…";
            cancelAction.run();
        };
        if(busy) return;
        SetupProgress.State previous = progress == null ? PENDING : progress.get(step);
        busy=true; cancellation=cancellationRequest; long attempt=++generation;
        if(progress != null) { progress.set(step,RUNNING); persist(); }
        danger.setVisible(false); errorReport=""; copyError.setVisible(false); copyError.setText("Copy error");
        operation=description; operationStartedNanos=System.nanoTime(); completed=0; total=0;
        output.clear(); output.accept(description);
        meter.setVisible(true); cancel.setVisible(cancellationRequest!=null); cancel.setEnabled(true); updateNavigation();
        cancel.setText(cancelCaption);
        diagnostics.setVisible(false); detailsToggle.setVisible(true); detailsToggle.setText("Show details");
        worker.submit(() -> {
            T result=null; Throwable failure=null;
            try { result=work.call(); } catch(Exception | LinkageError e) { failure=e; }
            T value=result; Throwable problem=failure;
            SwingUtilities.invokeLater(() -> {
                if(attempt!=generation || !isDisplayable()) return;
                busy=false; cancellation=null; cancel.setVisible(false); meter.setVisible(false); updateNavigation();
                if(problem!=null)
                {
                    boolean stopped=problem instanceof InterruptedException ||
                        problem instanceof CancellationException;
                    if(progress!=null) { progress.set(step,stopped?DEFERRED:NEEDS_ATTENTION); persist(); }
                    if(failureHandler != null)
                    {
                        attempt(() -> failureHandler.accept(problem));
                        updateNavigation();
                        return;
                    }
                    if(step == SetupStep.SOURCE &&
                        problem instanceof ApplicationMigrationService.LiveDatabaseRecoveryException recovery)
                    {
                        sourceCommitted = true;
                        restartRequired = true;
                        showPage(SetupStep.SOURCE);
                        fail("The active database could not be confirmed after migration. Exit setup and do not " +
                            "retry or start receiving until the retained safety backup has been restored.",
                            recovery.getMessage());
                        return;
                    }
                    if(step == SetupStep.SOURCE && sourceCommitted)
                    {
                        restartRequired = true;
                        showPage(SetupStep.SOURCE);
                        fail("Your profile was created or imported, but setup couldn’t refresh the review. Exit " +
                            "setup and reopen it to load your settings. Do not run the import again.",
                            CopyableErrorDialog.message(problem));
                        return;
                    }
                    String summary = stopped ? "Stopped safely. You can try again or set this up later." :
                        safeFailure(problem);
                    String detail = stopped ? summary : safeFailureDetail(problem, summary);
                    output.accept("Error: " + detail);
                    fail(summary, detail);
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

    private void migrationProgress(String value)
    {
        if(value == null || value.isBlank())
        {
            return;
        }
        output.accept(value);
        if(!value.startsWith("Migration plan:") && !value.startsWith("Migration scope:"))
        {
            operation = value.lines().findFirst().orElse(value);
        }
    }

    static String elapsedLabel(long elapsedNanos)
    {
        long seconds = Math.max(0, TimeUnit.NANOSECONDS.toSeconds(elapsedNanos));
        return String.format("%d:%02d elapsed", seconds / 60, seconds % 60);
    }

    private String safeFailure(Throwable failure)
    {
        //Remote/subprocess exceptions may contain passwords, request URLs or provider responses.
        if(step==SetupStep.RADIO_REFERENCE) return "RadioReference connection failed. Check your account details and internet connection, then retry or choose Set up later.";
        if(step==SetupStep.JMBE) return "Digital voice setup couldn’t finish. Check your internet connection or the JMBE file you selected, then try again. Any working library is still safe. You can also set this up later.";
        if(step==SetupStep.ADMINISTRATOR) return "Administrator setup failed. Check the current password and password rules, then retry.";
        if(step==SetupStep.REVIEW) return "Web access couldn’t start. Another application may be using this port, or the security settings may need attention. Return to Web access, check the port and try again.";
        if(step==SetupStep.SOURCE) return "Your saved data could not be updated or imported. Use Copy error when reporting this problem.";
        return "This step couldn’t finish. Try again, or return to it later. Any completed setup choices are still saved.";
    }

    private String safeFailureDetail(Throwable failure, String summary)
    {
        //Source inspection and migration errors contain local database diagnostics. Other setup jobs can contain
        //credentials, provider responses, or request URLs and retain their deliberately value-free summary.
        return step == SetupStep.SOURCE ? CopyableErrorDialog.message(failure) : summary;
    }

    private void completeAndContinue()
    {
        if(progress==null) return;
        if(!progress.isDone(step)) progress.set(step,COMPLETE);
        if(persist()) showPage(nextStep());
    }
    private SetupStep nextStep()
    {
        return SetupReadiness.nextStep(progress, step, limitedVisit, jmbeNeeded(),
            !preferences.getVectorCalibrationPreference().isHideCalibrationDialog(), sourceCommitted);
    }
    private void deferCurrent() { progress.set(step,DEFERRED); if(persist()) showPage(nextStep()); }
    private void defer(String caption) { WizardStyles.quiet(button(caption,this::deferCurrent)); }
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
    private void refreshNavigationTheme()
    {
        if(themeRefreshPending) return;
        themeRefreshPending=true;
        SwingUtilities.invokeLater(() -> { themeRefreshPending=false; updateNavigation(); });
    }
    private void toggleTheme()
    {
        if(busy) return;
        boolean dark = preferences != null ? preferences.getApplicationPreference().getTheme().isDark() : selectedTheme == Theme.DARK;
        selectedTheme = dark ? Theme.LIGHT : Theme.DARK;
        if(preferences == null)
        {
            //Preview before choosing a source must not create a database or initialize portable preferences.
            if(selectedTheme.isDark()) FlatDarkLaf.setup(); else FlatLightLaf.setup();
            SwingUtilities.updateComponentTreeUI(this);
        }
        else
        {
            preferences.getApplicationPreference().setTheme(selectedTheme);
            persist();
        }
        updateNavigation();
    }
    private void updateNavigation()
    {
        enableTree(page,!busy && !restartRequired);
        back.setEnabled(!busy && !restartRequired && !certificateGuideVisible && preferences!=null && step.ordinal()>0);
        next.setEnabled(!busy && !restartRequired && canContinue.getAsBoolean());
        exit.setEnabled(!busy);
        boolean dark = preferences != null ? preferences.getApplicationPreference().getTheme().isDark() : selectedTheme == Theme.DARK;
        themeToggle.setEnabled(!busy && !restartRequired);
        themeToggle.setText(dark ? "Light mode" : "Dark mode");
        themeToggle.setToolTipText(dark ? "Switch to light mode" : "Switch to dark mode");
        themeToggle.getAccessibleContext().setAccessibleName(themeToggle.getToolTipText());
        themeToggle.setIcon(IconFontSwing.buildIcon(dark ? FontAwesome.SUN_O : FontAwesome.MOON_O,18,WizardStyles.foreground()));
        for(var entry:steps.entrySet())
        {
            SetupStep id=entry.getKey(); JButton button=entry.getValue();
            SetupProgress.State state=progress==null?PENDING:progress.get(id);
            String ink=colorHex(id==step || progress!=null && progress.isDone(id) ? WizardStyles.foreground() : WizardStyles.muted());
            button.setText("<html><font color='"+ink+"'>"+(progress!=null && progress.isDone(id)?"✓ ":"")+(id.ordinal()+1)+". "+id.title()+"<br><small>"+label(state)+"</small></font></html>");
            button.setToolTipText(label(state)); button.getAccessibleContext().setAccessibleDescription(label(state));
            button.setEnabled(!busy && !restartRequired && !certificateGuideVisible &&
                (!managedRecordingCatalogNeedsUpgrade || id==SetupStep.SOURCE) &&
                (id==step || preferences!=null && (liveServer==null || id==SetupStep.WEB || id==SetupStep.REVIEW) &&
                (progress.isDone(id) || state==DEFERRED || state==NEEDS_ATTENTION)));
            button.setFont(button.getFont().deriveFont(id==step?Font.BOLD:Font.PLAIN));
            Color fill=id==step ? WizardStyles.surface() : UIManager.getColor("Panel.background");
            button.setBackground(fill);
            button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0,id==step?3:0,0,0,WizardStyles.accent()),new EmptyBorder(8,12,8,8)));
            //Unread steps are unavailable, not visually washed out: the state is still useful navigation context.
            button.putClientProperty("FlatLaf.style", "disabledText: " + colorHex(WizardStyles.muted()));
        }
    }
    private static void enableTree(Component component,boolean enabled)
    {
        if(component instanceof SetupRadioReferenceRegion region) { region.setSetupEnabled(enabled); return; }
        component.setEnabled(enabled);
        if(component instanceof Container container) for(Component child:container.getComponents()) enableTree(child,enabled);
    }
    private void attempt(Runnable action) { try { action.run(); } catch(Exception e) { fail(e instanceof IllegalArgumentException ? e.getMessage() : "This action failed. Check settings and retry."); } }
    private void fail(String message)
    {
        fail(message, message);
    }
    private void fail(String message, String details)
    {
        WizardNotice.styleError(danger);
        danger.setText(message); danger.setVisible(true); danger.requestFocusInWindow();
        boolean hasTechnicalDetails = details != null && !details.isBlank() && !details.equals(message);
        errorReport = message + (hasTechnicalDetails ? "\n\nTechnical details:\n" + details : "");
        if(hasTechnicalDetails && !output.snapshot().contains(details))
        {
            output.accept("Error: " + details);
        }
        if(hasTechnicalDetails)
        {
            detailsToggle.setVisible(true);
        }
        copyError.setText("Copy error"); copyError.setVisible(true);
        if(step == SetupStep.REVIEW) next.setText(certificateGuideVisible ? "Launch VCE & open browser" : "Retry launch");
        else if(step == SetupStep.JMBE) next.setText("Try again");
        updateNavigation();
    }
    private void paragraph(String message) { append(text(message)); append(Box.createVerticalStrut(20)); }
    private void append(JComponent component) { component.setAlignmentX(Component.LEFT_ALIGNMENT); page.add(component); }
    private void append(Component component) { page.add(component); }
    private JButton button(String caption,Runnable action)
    {
        JButton button=action(caption,action); append(button); append(Box.createVerticalStrut(14)); return button;
    }
    private JButton action(String caption,Runnable action)
    {
        JButton button=new JButton(caption); WizardStyles.secondary(button);
        button.addActionListener(e -> attempt(action)); return button;
    }
    private JTextField field(String caption,String value) { JTextField field=new JTextField(value,28); labelled(caption,field); return field; }
    private JPasswordField password(String caption) { JPasswordField field=new JPasswordField(28); labelled(caption,field); return field; }
    private void labelled(String caption,JComponent field)
    {
        labelled(page, caption, field, 20);
    }
    private void labelled(JPanel owner,String caption,JComponent field,int spacing)
    {
        JLabel label=new JLabel(caption); label.setLabelFor(field); WizardStyles.bodyFont(label,Font.BOLD);
        label.setAlignmentX(Component.LEFT_ALIGNMENT); owner.add(label); owner.add(Box.createVerticalStrut(8));
        field.getAccessibleContext().setAccessibleName(caption);
        if(!(field instanceof JSpinner)) { WizardStyles.bodyFont(field,Font.PLAIN); field.setMaximumSize(new Dimension(560,40)); }
        field.setAlignmentX(Component.LEFT_ALIGNMENT); owner.add(field); owner.add(Box.createVerticalStrut(spacing));
    }
    private JRadioButton card(ButtonGroup group,String caption,String description,boolean selected)
    {
        return choice(group,caption,description,selected).radio();
    }
    private WizardChoiceCard choice(ButtonGroup group,String caption,String description,boolean selected)
    {
        WizardChoiceCard card=new WizardChoiceCard(group,caption,description,selected);
        append(card); append(Box.createVerticalStrut(14)); return card;
    }
    private static JPanel stack()
    {
        JPanel panel=new JPanel(); panel.setOpaque(false); panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT); return panel;
    }
    private static void addTo(JPanel panel,JComponent child)
    {
        child.setAlignmentX(Component.LEFT_ALIGNMENT); panel.add(child); panel.add(Box.createVerticalStrut(12));
    }
    private JTextField sourceField(JPanel panel,String caption,boolean directory)
    {
        JTextField field=new JTextField(28); WizardStyles.bodyFont(field,Font.PLAIN);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE,40));
        field.getAccessibleContext().setAccessibleName(caption);
        JLabel label=new JLabel(caption); label.setLabelFor(field); addTo(panel,label); addTo(panel,field);
        addTo(panel,action("Browse…",() -> {
            JFileChooser chooser=new JFileChooser();
            chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION) field.setText(chooser.getSelectedFile().toString());
        }));
        return field;
    }
    private static void changedText(JTextField field,Runnable changed)
    {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { changed.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { changed.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { changed.run(); }
        });
    }
    private static void commitNumber(JSpinner spinner,String message)
    {
        try { spinner.commitEdit(); }
        catch(java.text.ParseException e) { throw new IllegalArgumentException(message); }
    }
    private void notice(String heading,String message,boolean success)
    {
        append(noticePanel(heading,message,success)); append(Box.createVerticalStrut(20));
    }
    private JPanel noticePanel(String heading,String message,boolean success)
    {
        return new WizardNotice(heading,message,success ? WizardNotice.Tone.SUCCESS : WizardNotice.Tone.INFO);
    }
    private void details(String caption,String message)
    {
        append(new WizardDisclosure(caption,message)); append(Box.createVerticalStrut(16));
    }
    private void migrationDetails(DatabaseMigrationChain.PreflightReport plan)
    {
        if(plan.requiresMigration())
        {
            notice("What stays", "Usable supported channels, aliases, and streaming settings are migrated " +
                "independently. A broken item in one area won't discard usable items in another.", false);
            if(plan.steps().stream().flatMap(step -> step.effects().stream())
                .anyMatch(SetupWizard::resetsReceiverActivity))
                paragraph("Activity history and statistics will start fresh. Radio-system and site information will be learned again as receiving resumes.");
            if(plan.steps().stream().flatMap(step -> step.effects().stream())
                .anyMatch(effect -> effect.kind() == DatabaseMigrationEffect.Kind.DROP))
                paragraph("Unusable components may be skipped, and retired state may be removed. Expand the " +
                    "details below to see the full list of changes.");
        }
        details("Technical details",ApplicationMigrationService.describePlan(plan));
    }
    private static boolean resetsReceiverActivity(DatabaseMigrationEffect effect)
    {
        if(effect.kind() != DatabaseMigrationEffect.Kind.RESET)
        {
            return false;
        }
        return Set.of("trunked-identity evidence", "P25 affiliation history",
            "receiver-derived activity and counters", "receiver activity and call history",
            "learned site observations", "signal-quality observations",
            "radio-system and receiver-channel identity cache", "activity metric boundaries")
            .contains(effect.subject());
    }
    private static String colorHex(Color color) { return String.format("#%02x%02x%02x",color.getRed(),color.getGreen(),color.getBlue()); }
    private static JTextArea text(String value)
    {
        return WizardStyles.prose(value);
    }
    private static boolean present(String value) { return value!=null && !value.isBlank(); }
    private static String label(SetupProgress.State value)
    {
        return switch(value) { case PENDING->"Pending"; case CARRIED_OVER->"Carried over"; case COMPLETE->"Complete"; case NEEDS_ATTENTION->"Needs attention"; case RUNNING->"Running"; case DEFERRED->"Deferred"; };
    }
}
