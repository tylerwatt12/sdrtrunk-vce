/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.application.update.UpdateCheckResult;
import io.github.dsheirer.application.update.UpdateCheckService;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticService;
import io.github.dsheirer.audio.broadcast.AudioStreamingManager;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.channel.quality.ControlChannelQualityRegistry;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.controller.channel.ChannelSelectionManager;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.database.SdrTrunkDatabaseBootstrap;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.upgrade.ApplicationMigrationProgressDialog;
import io.github.dsheirer.database.upgrade.ApplicationMigrationService;
import io.github.dsheirer.database.upgrade.ApplicationMigrationSuccessDialog;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.configuration.SqliteDatabaseImportDialog.PreparedImport;
import io.github.dsheirer.gui.diagnostic.BasebandRecordingDialog;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.gui.preference.encryption.ViewEncryptionKeyPreferenceEditorRequest;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.gui.viewer.ViewRecordingViewerRequest;
import io.github.dsheirer.gui.whatsnew.WhatsNewDialog;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.log.ApplicationLog;
import io.github.dsheirer.map.MapSnapshotService;
import io.github.dsheirer.metadata.site.SiteControlChannelLearner;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.module.log.EventLogger;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.monitor.ResourceMonitor;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultService;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.stats.activity.ReceiverActivityService;
import io.github.dsheirer.record.AudioRecordingManager;
import io.github.dsheirer.remote.RemoteConnectivityService;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.preference.record.RecordingMode;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.sdrplay.api.SDRPlayLibraryHelper;
import io.github.dsheirer.stats.StatsWebServerService;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.web.network.WebNetworkAddressDiscovery;
import io.github.dsheirer.stats.WebServerRuntimeState;
import io.github.dsheirer.vector.calibrate.CalibrationManager;
import io.github.dsheirer.web.http.EmbeddedHttpServerPolicy;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.Inet4Address;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JSeparator;
import javax.swing.Timer;
import java.awt.datatransfer.StringSelection;
import javax.swing.WindowConstants;

public class SDRTrunk
{
    private static final Logger mLog = LoggerFactory.getLogger(SDRTrunk.class);
    private Preferences mPreferences;

    private static final String PREFERENCE_RESOURCE_STATUS_VISIBLE = "sdrtrunk.resource.status.visible";
    private static final String PREFERENCE_UPDATE_FOOTER_MIGRATION =
        "sdrtrunk.resource.status.update.icon.migration.1";
    private static final String BASE_WINDOW_NAME = "sdrtrunk.main.window";
    private static final String WINDOW_FRAME_IDENTIFIER = BASE_WINDOW_NAME + ".thin.shell.frame";
    private static final String APPLICATION_MIGRATOR_TITLE = "VCE Application Migrator";
    private static final long SITE_METADATA_SHUTDOWN_DRAIN_MILLISECONDS = 8_000L;
    private static final long CALL_COORDINATOR_SHUTDOWN_STOP_MILLISECONDS = 8_000L;
    private static final long STATISTICS_SHUTDOWN_DRAIN_MILLISECONDS = 2_000L;
    private static final long STATISTICS_SHUTDOWN_STOP_MILLISECONDS = 8_000L;

    private boolean mResourceStatusVisible;
    private AudioCallCoordinator mAudioCallCoordinator;
    private LogicalCallDiagnosticService mLogicalCallDiagnosticService;
    private ReceiverActivityService mReceiverActivityService;
    private DecodeEventViewService mDecodeEventViewService;
    private StatsWebServerService mStatsWebServerService;
    private RemoteConnectivityService mRemoteConnectivityService;
    private AudioRecordingManager mAudioRecordingManager;
    private ManagedRecordingCatalog mManagedRecordingCatalog;
    private ScheduledFuture<?> mManagedRecordingRetention;
    private ScheduledExecutorService mManagedRecordingMaintenance;
    private AudioStreamingManager mAudioStreamingManager;
    private ControlChannelQualityRegistry mControlChannelQualityRegistry;
    private IconModel mIconModel;
    private ConfigurationManager mConfigurationManager;
    private JFrame mMainGui;
    private BasebandRecordingDialog mBasebandRecordingDialog;
    private MapSnapshotService mMapSnapshotService;
    private JavaFxWindowManager mJavaFxWindowManager;
    private UserPreferences mUserPreferences;
    private TunerManager mTunerManager;
    private ApplicationLog mApplicationLog;
    private ResourceMonitor mResourceMonitor;
    private JFXPanel mResourceStatusPanel;
    private UpdateCheckService mUpdateCheckService;
    private volatile UpdateCheckResult mUpdateCheckResult = UpdateCheckResult.notChecked();
    private final AtomicBoolean mUpdateCheckInProgress = new AtomicBoolean();
    private volatile boolean mManualUpdateFeedbackRequested;
    private JMenuItem mCheckForUpdatesMenuItem;
    private JButton mUserPreferencesShortcutButton;
    private JButton mWebInterfaceButton;
    private JTextField mNetworkAccessUrlField;
    private JButton mCopyNetworkAccessUrlButton;
    private JLabel mNetworkAccessWarningLabel;
    private Timer mNetworkAccessRefreshTimer;
    private final AtomicBoolean mNetworkAccessRefreshInProgress = new AtomicBoolean();
    private String mDisplayedNetworkAccessUrl;
    private boolean mOpenWebAfterSetup;
    private JMenuItem mEncryptionKeysItem;
    private boolean mShutdownProcessed;
    private volatile boolean mDatabaseReplacementInProgress;
    private PortableDataRootLock mDataRootLock;
    private final boolean mStartConfiguredChannels;
    private final boolean mGuiAvailable;

    private SDRTrunk(UserPreferences userPreferences, PortableDataRootLock dataRootLock,
                     boolean startConfiguredChannels, boolean openWebAfterSetup)
    {
        mUserPreferences = userPreferences;
        mDataRootLock = dataRootLock;
        mStartConfiguredChannels = startConfiguredChannels;
        mOpenWebAfterSetup = openWebAfterSetup;
        mGuiAvailable = !GraphicsEnvironment.isHeadless();
        mPreferences = Preferences.userNodeForPackage(SDRTrunk.class);
        mUpdateCheckService = new UpdateCheckService();
        mIconModel = new IconModel();

        if(mGuiAvailable)
        {
            //Install the stored look-and-feel before realizing the first Swing component.
            ThemeManager.getInstance().initialize(mUserPreferences);
            mMainGui = new JFrame();
            MyEventBus.getGlobalEventBus().register(this);
        }

        mApplicationLog = new ApplicationLog(mUserPreferences);
        mApplicationLog.start();
        if(mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded())
        {
            mUserPreferences.getEncryptionKeyPreference().getVaultService().tryAutoUnlockSavedPassword();
        }

        //Note: invoke this early in the application lifecycle, before the TunerManager causes the sdrplay classes
        //to be loaded since the jextract auto-generated code attempts to load the library by name and that can fail
        //when the library was not installed into a normal/default location, particularly on windows OS systems.
        if(SDRPlayLibraryHelper.LOADED)
        {
            mLog.info("SDRPlay API native library preemptively loaded");
        }

        ThreadPool.logSettings();

        if(mGuiAvailable)
        {
            mResourceMonitor = new ResourceMonitor(mUserPreferences);
            // Register icon fonts only for local Swing windows.
            IconFontSwing.register(FontAwesome.getIconFont());
        }

        mTunerManager = new TunerManager(mUserPreferences);

        AliasModel aliasModel = new AliasModel();
        EventLogManager eventLogManager = new EventLogManager(aliasModel, mUserPreferences);
        mConfigurationManager = new ConfigurationManager(mUserPreferences, mTunerManager, aliasModel, eventLogManager, mIconModel);
        try
        {
            mRemoteConnectivityService = new RemoteConnectivityService(mConfigurationManager, mUserPreferences);
            mConfigurationManager.getChannelProcessingManager().setP25RemoteBitstreamService(mRemoteConnectivityService);
        }
        catch(IOException exception)
        {
            mLog.error("Remote-link settings are unavailable; remote connectivity is disabled", exception);
        }

        if(mGuiAvailable)
        {
            mJavaFxWindowManager = new JavaFxWindowManager(mUserPreferences);
        }

        CalibrationManager.getInstance(mUserPreferences);

        new ChannelSelectionManager(mConfigurationManager.getChannelModel());

        mReceiverActivityService = new ReceiverActivityService(mUserPreferences);

        mLogicalCallDiagnosticService = new LogicalCallDiagnosticService();

        try
        {
            Path catalogPath = SdrTrunkDatabasePath.getDatabasePath(mUserPreferences)
                .resolveSibling("managed-recordings.sqlite");
            mManagedRecordingCatalog = new ManagedRecordingCatalog(catalogPath,
                mUserPreferences.getDirectoryPreference().getDirectoryManagedRecording());
        }
        catch(Exception exception)
        {
            //A damaged or unavailable optional catalog must not prevent Classic recording or live reception.
            mLog.error("Managed recordings catalog is unavailable", exception);
            if(mUserPreferences.getRecordPreference().getRecordingMode() == RecordingMode.MANAGED)
            {
                mUserPreferences.getRecordPreference().setRecordingMode(RecordingMode.CLASSIC);
                mLog.warn("Managed recordings were reset to Classic because the catalog is unavailable");
            }
        }

        mAudioRecordingManager = mManagedRecordingCatalog != null ?
            new AudioRecordingManager(mUserPreferences, mReceiverActivityService::receiveRecordedCall,
                mManagedRecordingCatalog) :
            new AudioRecordingManager(mUserPreferences, mReceiverActivityService::receiveRecordedCall);

        mAudioStreamingManager = new AudioStreamingManager(mConfigurationManager.getBroadcastModel(), BroadcastFormat.MP3,
            mUserPreferences, mReceiverActivityService::receiveStreamedCall);

        mDecodeEventViewService = new DecodeEventViewService(
            mConfigurationManager.getChannelProcessingManager(), mConfigurationManager.getAliasModel());
        mConfigurationManager.getChannelProcessingManager().addChannelDecodeEventListener(
            mDecodeEventViewService.getDecodeEventListener());

        mStatsWebServerService = new StatsWebServerService(mUserPreferences,
            mConfigurationManager.getChannelProcessingManager(), mReceiverActivityService,
            mConfigurationManager.getAliasAdministrationService(), mDecodeEventViewService, mTunerManager,
            mConfigurationManager.getScanListModel(), mConfigurationManager.getChannelAdministrationService(),
            mConfigurationManager.getRadioReferenceDirectoryService(),
            mConfigurationManager.getRadioReferenceImportService(),
            mConfigurationManager.getStreamingAdministrationService(), mRemoteConnectivityService);
        mStatsWebServerService.setManagedRecordingCatalog(mManagedRecordingCatalog);

        if(mGuiAvailable && !mStatsWebServerService.getRuntimeState().running())
        {
            try
            {
                if(!io.github.dsheirer.gui.setup.SetupWizard.ensureListener(mUserPreferences, mStatsWebServerService))
                    throw new io.github.dsheirer.gui.setup.SetupWizard.Cancelled();
                mOpenWebAfterSetup = true;
            }
            catch(io.github.dsheirer.gui.setup.SetupWizard.Cancelled e) { throw e; }
            catch(Exception e) { throw new IllegalStateException("Web listener setup could not complete", e); }
        }
        mTunerManager.start();
        mAudioRecordingManager.start();
        if(mManagedRecordingCatalog != null)
        {
            mManagedRecordingMaintenance = Executors.newSingleThreadScheduledExecutor(
                new NamingThreadFactory("managed recording retention"));
            mManagedRecordingRetention = mManagedRecordingMaintenance.scheduleWithFixedDelay(() ->
            {
                try
                {
                    Integer retentionDays = mUserPreferences.getRecordPreference().getManagedRetentionDays();
                    if(retentionDays != null)
                    {
                        mManagedRecordingCatalog.pruneOlderThan(System.currentTimeMillis() -
                            TimeUnit.DAYS.toMillis(retentionDays));
                    }
                }
                catch(Exception exception)
                {
                    mLog.warn("Managed recording age retention failed", exception);
                }
            }, 1, 24, TimeUnit.HOURS);
        }
        mAudioStreamingManager.start();

        if(mJavaFxWindowManager != null)
        {
            mJavaFxWindowManager.setStatsWebServerService(mStatsWebServerService);
        }
        mControlChannelQualityRegistry = new ControlChannelQualityRegistry();
        mAudioCallCoordinator = new AudioCallCoordinator(mAudioRecordingManager, mAudioStreamingManager,
            mStatsWebServerService::receive, mReceiverActivityService::receiveResolvedCall,
            mLogicalCallDiagnosticService);
        mStatsWebServerService.setLogicalCallDiagnostics(mLogicalCallDiagnosticService, mAudioCallCoordinator);

        mStatsWebServerService.setReceiverHealthOutputSources(mAudioCallCoordinator, mAudioRecordingManager,
            mAudioStreamingManager, mConfigurationManager.getBroadcastModel());

        mConfigurationManager.getChannelProcessingManager().addAudioCallListener(mAudioCallCoordinator);
        mConfigurationManager.getChannelProcessingManager().addChannelDecodeEventListener(
            mReceiverActivityService.getDecodeEventListener());
        mConfigurationManager.getChannelProcessingManager().addControlChannelQualityListener(
            mReceiverActivityService.getControlChannelQualityListener());
        mConfigurationManager.getChannelProcessingManager().addControlChannelQualityListener(
            mControlChannelQualityRegistry);
        mConfigurationManager.getChannelProcessingManager().addSiteMetadataListener(mReceiverActivityService);
        mConfigurationManager.getChannelProcessingManager().addProtocolSiteMetadataListener(mReceiverActivityService);
        mConfigurationManager.getChannelProcessingManager().addSiteMetadataListener(mConfigurationManager.getBroadcastModel());
        mConfigurationManager.getChannelProcessingManager().addSiteMetadataListener(new SiteControlChannelLearner(mConfigurationManager));

        mMapSnapshotService = new MapSnapshotService(aliasModel);
        mConfigurationManager.getChannelProcessingManager().addDecodeEventListener(mMapSnapshotService);
        mStatsWebServerService.setMapSnapshotService(mMapSnapshotService);

        mConfigurationManager.init();
        if(mRemoteConnectivityService != null)
        {
            mRemoteConnectivityService.start();
        }

        if(!mGuiAvailable)
        {
            mLog.info("starting main application headless");
            SqlitePreferencesFactory.setShutdownCoordinator(this::processShutdown);
            startPostLaunchExperience();
        }
        else
        {
            mLog.info("starting main application gui");

            //Initialize the GUI
            initGUI();
            // SIGTERM follows the portable preferences shutdown hook, not the Swing window listener. Keep the
            // same tuner-settings quiescence boundary for a deployed GUI receiver as for headless operation.
            SqlitePreferencesFactory.setShutdownCoordinator(this::processShutdown);
            EventQueue.invokeLater(() -> {
                try
                {
                    ThemeManager.getInstance().registerSwing(mMainGui);
                    mMainGui.setVisible(true);
                    checkForUpdates(false);
                }
                catch(Exception e)
                {
                    mLog.error("Unable to finish initial GUI setup; continuing with post-launch startup", e);
                }

                try
                {
                    startPostLaunchExperience();
                }
                catch(Exception e)
                {
                    mLog.error("Post-launch startup failed; starting configured channels without the startup dialog", e);
                    EncryptionKeyVaultService vaultService = getLockedLaunchVault();

                    if(vaultService != null)
                    {
                        vaultService.disableForRun();
                    }

                    if(mStartConfiguredChannels)
                    {
                        startChannelsWithoutDialog(mConfigurationManager.getChannelModel().getAutoStartChannels());
                    }
                }
                if(mOpenWebAfterSetup)
                {
                    openWebInterface(false);
                }
            });
        }
    }

    private void startPostLaunchExperience()
    {
        if(!mStartConfiguredChannels) return;
        List<Channel> channels = mConfigurationManager.getChannelModel().getAutoStartChannels();
        EncryptionKeyVaultService vaultService = getLockedLaunchVault();

        if(!mGuiAvailable)
        {
            if(vaultService != null)
            {
                vaultService.disableForRun();
            }

            startChannelsWithoutDialog(channels);
            return;
        }

        //Graphical launch readiness, release information and calibration finish before receiver construction.
        startChannelsWithoutDialog(channels);
    }

    private void startChannelsWithoutDialog(List<Channel> channels)
    {
        for(Channel channel: channels)
        {
            try
            {
                mLog.info("Auto-starting channel " + channel.getName());
                mConfigurationManager.getChannelProcessingManager().start(channel);
            }
            catch(ChannelException | RuntimeException e)
            {
                mLog.error("Channel: " + channel.getName() + " auto-start failed: " + e.getMessage(), e);
            }
        }
    }

    private EncryptionKeyVaultService getLockedLaunchVault()
    {
        if(!mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded())
        {
            return null;
        }

        EncryptionKeyVaultService vaultService = mUserPreferences.getEncryptionKeyPreference().getVaultService();

        if(!vaultService.hasVault() || vaultService.isUnlocked() || !vaultService.isPromptOnLaunch())
        {
            return null;
        }

        return vaultService;
    }

    /**
     * Initialize the contents of the frame.
     */
    private void initGUI()
    {
        mMainGui.setLayout(new MigLayout("insets 6 6 6 6, fillx", "[grow,fill]", "[][][shrink 0]"));
        ApplicationIcon.apply(mMainGui);

        /**
         * Setup main JFrame window
         */
        mMainGui.setTitle(ApplicationInfo.getDisplayName());

        Point location = mUserPreferences.getSwingPreference().getLocation(WINDOW_FRAME_IDENTIFIER);
        Dimension dimension = mUserPreferences.getSwingPreference().getDimension(WINDOW_FRAME_IDENTIFIER);
        mMainGui.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        mMainGui.addWindowListener(new ShutdownMonitor());
        registerQuitHandler();

        if(dimension != null)
        {
            mMainGui.setSize(dimension);
        }
        else
        {
            mMainGui.setSize(new Dimension(680, 210));
        }
        mMainGui.setMinimumSize(new Dimension(480, 190));

        //Center only after the first-use size is known. Centering a zero-sized frame places its upper-left corner at
        //the screen center and leaves most of the expanded window off-screen.
        if(location != null)
        {
            mMainGui.setLocation(location);
        }
        else
        {
            mMainGui.setLocationRelativeTo(null);
        }

        if(dimension != null && mUserPreferences.getSwingPreference().getMaximized(WINDOW_FRAME_IDENTIFIER, false))
        {
            mMainGui.setExtendedState(Frame.MAXIMIZED_BOTH);
        }
        mMainGui.add(getMainControlPanel(), "cell 0 0,growx");
        mMainGui.add(getNetworkAccessPanel(), "cell 0 1,growx");
        mNetworkAccessRefreshTimer = new Timer(30_000, event -> refreshNetworkAccessStatus());
        mNetworkAccessRefreshTimer.start();
        refreshNetworkAccessStatus();

        mResourceMonitor.start();
        mResourceStatusVisible = initializeResourceStatusVisibility();
        if(mResourceStatusVisible)
        {
            mMainGui.add(getResourceStatusPanel(), "cell 0 2,growx");
        }

        /**
         * Menu items
         */
        JMenuBar menuBar = new JMenuBar();
        mMainGui.setJMenuBar(menuBar);

        JMenu fileMenu = new JMenu("File");
        menuBar.add(fileMenu);

        JMenuItem importSqliteDatabaseMenu = new JMenuItem("Import SQLite Database…");
        importSqliteDatabaseMenu.addActionListener(event -> restartIntoSetupWizard(
            "Restart to import a SQLite database? Receiving and streaming will stop while you choose, review, " +
                "and migrate the database. Your active database will not be replaced unless you approve the " +
                "migration plan.", "--setup-wizard", "--import-sqlite"));
        fileMenu.add(importSqliteDatabaseMenu);
        fileMenu.add(new JSeparator());

        JMenuItem exitMenu = new JMenuItem("Exit");
        exitMenu.addActionListener(event -> requestGuiShutdown());

        fileMenu.add(exitMenu);

        JMenu viewMenu = new JMenu("View");

        mEncryptionKeysItem = new JMenuItem("Encryption Keys");
        mEncryptionKeysItem.setIcon(IconFontSwing.buildIcon(FontAwesome.KEY, 12));
        mEncryptionKeysItem.addActionListener(e -> MyEventBus.getGlobalEventBus().post(new ViewEncryptionKeyPreferenceEditorRequest()));
        mEncryptionKeysItem.setVisible(mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded());
        mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager().loadedProperty()
            .addListener((observable, oldValue, loaded) -> EventQueue.invokeLater(() -> {
                mEncryptionKeysItem.setVisible(loaded);

                if(!loaded)
                {
                    mUserPreferences.getEncryptionKeyPreference().getVaultService().lock();
                }
            }));
        viewMenu.add(mEncryptionKeysItem);

        viewMenu.add(new JSeparator());

        JMenuItem viewApplicationLogsMenu = new JMenuItem("Application Log Files");
        viewApplicationLogsMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER_OPEN_O, 12));
        viewApplicationLogsMenu.addActionListener(arg0 ->
                openFileExplorer(mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog().toFile()));
        viewMenu.add(viewApplicationLogsMenu);

        JMenuItem viewRecordingsMenuItem = new JMenuItem("Audio Recordings");
        viewRecordingsMenuItem.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER_OPEN_O, 12));
        viewRecordingsMenuItem.addActionListener(arg0 ->
                openFileExplorer(mUserPreferences.getDirectoryPreference().getDirectoryRecording().toFile()));
        viewMenu.add(viewRecordingsMenuItem);

        JMenuItem viewEventLogsMenu = new JMenuItem("Channel Event Log Files");
        viewEventLogsMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER_OPEN_O, 12));
        viewEventLogsMenu.addActionListener(arg0 ->
                openFileExplorer(mUserPreferences.getDirectoryPreference().getDirectoryEventLog().toFile()));
        viewMenu.add(viewEventLogsMenu);

        JMenuItem recordingViewerMenu = new JMenuItem("Message Recording Viewer (.bits)");
        recordingViewerMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.BRAILLE, 12));
        recordingViewerMenu.addActionListener(e -> MyEventBus.getGlobalEventBus().post(new ViewRecordingViewerRequest()));
        viewMenu.add(recordingViewerMenu);

        viewMenu.add(new JSeparator());
        viewMenu.add(new ResourceStatusVisibleMenuItem());

        menuBar.add(viewMenu);

        JMenu diagnosticsMenu = new JMenu("Diagnostics");
        JMenuItem basebandRecordingItem = new JMenuItem("Baseband Recording (Debug)…");
        basebandRecordingItem.addActionListener(event -> {
            if(mBasebandRecordingDialog == null || !mBasebandRecordingDialog.isDisplayable())
            {
                mBasebandRecordingDialog = new BasebandRecordingDialog(mMainGui, mTunerManager, mUserPreferences);
            }
            mBasebandRecordingDialog.setVisible(true);
            mBasebandRecordingDialog.toFront();
        });
        diagnosticsMenu.add(basebandRecordingItem);
        menuBar.add(diagnosticsMenu);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem setupWizardItem = new JMenuItem("Setup Wizard…");
        setupWizardItem.addActionListener(event -> restartIntoSetupWizard(
            "Restart into Setup Wizard? Receiving and streaming will stop until you finish setup. " +
                "Your existing profile and accepted settings will be preserved.", "--setup-wizard"));
        helpMenu.add(setupWizardItem);
        helpMenu.add(new JSeparator());
        if(WhatsNewDialog.hasCurrent())
        {
            JMenuItem whatsNewItem = new JMenuItem("What's New");
            whatsNewItem.addActionListener(event -> WhatsNewDialog.showCurrent(mMainGui));
            helpMenu.add(whatsNewItem);
            helpMenu.add(new JSeparator());
        }

        mCheckForUpdatesMenuItem = new JMenuItem("Check for Updates");
        mCheckForUpdatesMenuItem.setIcon(IconFontSwing.buildIcon(FontAwesome.DOWNLOAD, 12));
        mCheckForUpdatesMenuItem.addActionListener(event -> {
            if(mUpdateCheckResult.isUpdateAvailable())
            {
                openUpdateReleasePage(mUpdateCheckResult.releaseUri());
            }
            else
            {
                checkForUpdates(true);
            }
        });
        helpMenu.add(mCheckForUpdatesMenuItem);
        menuBar.add(helpMenu);
        ensureShellFitsContent();
    }

    private void ensureShellFitsContent()
    {
        Dimension insets = new Dimension(mMainGui.getInsets().left + mMainGui.getInsets().right,
            mMainGui.getInsets().top + mMainGui.getInsets().bottom);
        int preferredHeight = mMainGui.getRootPane().getPreferredSize().height + insets.height;
        int minimumHeight = Math.max(190, preferredHeight);
        mMainGui.setMinimumSize(new Dimension(480, minimumHeight));
        if(mMainGui.getHeight() < minimumHeight)
        {
            mMainGui.setSize(mMainGui.getWidth(), minimumHeight);
        }
    }

    private boolean initializeResourceStatusVisibility()
    {
        if(!mPreferences.getBoolean(PREFERENCE_UPDATE_FOOTER_MIGRATION, false))
        {
            mPreferences.putBoolean(PREFERENCE_RESOURCE_STATUS_VISIBLE, true);
            mPreferences.putBoolean(PREFERENCE_UPDATE_FOOTER_MIGRATION, true);
            return true;
        }

        return mPreferences.getBoolean(PREFERENCE_RESOURCE_STATUS_VISIBLE, true);
    }

    private void checkForUpdates(boolean manual)
    {
        if(manual)
        {
            mManualUpdateFeedbackRequested = true;
        }

        if(!mUpdateCheckInProgress.compareAndSet(false, true))
        {
            if(manual)
            {
                mCheckForUpdatesMenuItem.setText("Checking for Updates...");
                mCheckForUpdatesMenuItem.setEnabled(false);
            }

            return;
        }

        if(manual)
        {
            mCheckForUpdatesMenuItem.setText("Checking for Updates...");
            mCheckForUpdatesMenuItem.setEnabled(false);
        }

        ThreadPool.CACHED.execute(() -> {
            UpdateCheckResult result = mUpdateCheckService.check();
            mUpdateCheckResult = result;
            mUpdateCheckInProgress.set(false);
            boolean showNonAvailableResult = mManualUpdateFeedbackRequested;
            mManualUpdateFeedbackRequested = false;

            if(result.state() == UpdateCheckResult.State.UNAVAILABLE)
            {
                mLog.warn("Unable to check for updates: {}", result.detail());
            }

            EventQueue.invokeLater(() -> updateCheckMenuItem(result, showNonAvailableResult));
        });
    }

    private void updateCheckMenuItem(UpdateCheckResult result, boolean showNonAvailableResult)
    {
        mCheckForUpdatesMenuItem.setEnabled(true);

        if(result.isUpdateAvailable())
        {
            mCheckForUpdatesMenuItem.setText("Update Available — " + result.version());
        }
        else if(showNonAvailableResult && result.state() == UpdateCheckResult.State.CURRENT)
        {
            mCheckForUpdatesMenuItem.setText("Check for Updates — Up to Date");
        }
        else if(showNonAvailableResult)
        {
            mCheckForUpdatesMenuItem.setText("Check for Updates — Unable to Check");
        }
        else
        {
            mCheckForUpdatesMenuItem.setText("Check for Updates");
        }
    }

    private void openUpdateReleasePage(URI releaseUri)
    {
        EventQueue.invokeLater(() -> {
            try
            {
                if(releaseUri != null && Desktop.isDesktopSupported() &&
                    Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                {
                    Desktop.getDesktop().browse(releaseUri);
                }
            }
            catch(Exception e)
            {
                mLog.error("Unable to open update release page", e);
            }
        });
    }

    /** Opens a local directory with the platform file browser. */
    private void openFileExplorer(File directory)
    {
        try
        {
            Desktop.getDesktop().open(directory);
        }
        catch(Exception e)
        {
            mLog.error("Couldn't open file explorer", e);
            JOptionPane.showMessageDialog(mMainGui,
                    "Can't launch file explorer - files are located at: " + directory,
                    "Can't launch file explorer",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Runs only at the pre-receiver startup boundary, after the wizard has detached all callbacks. */
    private static int replaceSetupDatabase(PreparedImport prepared, Path dataRoot, PortableDataRootLock lock)
    {
        //No receiver services have been constructed. Keep the root lock until promotion and restart are complete.
        if(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER))
            Desktop.getDesktop().setQuitHandler((event, response) -> response.cancelQuit());
        try
        {
            java.util.prefs.Preferences.userRoot().flush();
            SqlitePreferencesFactory.shutdown();
            ApplicationMigrationService.MigrationResult migration = ApplicationMigrationProgressDialog.run(null,
                APPLICATION_MIGRATOR_TITLE, progress -> new ApplicationMigrationService()
                    .replaceCurrentDatabase(prepared.sourceDatabase(), dataRoot, prepared.approval(), progress));
            ApplicationMigrationSuccessDialog.show(null, APPLICATION_MIGRATOR_TITLE,
                ApplicationMigrationSuccessDialog.replacementImportReport(migration, prepared.sourceDatabase()));
        }
        catch(Exception | LinkageError e)
        {
            CopyableErrorDialog.show(null, "SQLite Database Import Failed",
                "The import could not finish safely. VCE will close without starting reception. Any completed " +
                    "safety backup remains available in database/backups.", exceptionMessage(e));
            //Do not release the lock or reopen stale preferences after an uncertain failure. The caller exits.
            return 1;
        }
        try
        {
            lock.close();
            //The staged database contains an unfinished review; normal startup must return to the wizard.
            ApplicationRelauncher.relaunch();
        }
        catch(IOException e)
        {
            CopyableErrorDialog.show(null, "Restart Required",
                "Your database was imported, but VCE could not restart. Start it manually to review the " +
                    "imported settings before receiving.", exceptionMessage(e));
        }
        return 0;
    }

    private void flushConfigurationForDatabaseReplacement() throws Exception
    {
        if(Platform.isFxApplicationThread())
        {
            mConfigurationManager.flushConfiguration();
            return;
        }

        FutureTask<Void> task = new FutureTask<>(() -> {
            mConfigurationManager.flushConfiguration();
            return null;
        });
        Platform.runLater(task);

        try
        {
            task.get();
        }
        catch(ExecutionException e)
        {
            Throwable cause = e.getCause();

            if(cause instanceof Exception exception)
            {
                throw exception;
            }

            if(cause instanceof Error error)
            {
                throw error;
            }

            throw e;
        }
    }

    /** Stops receiver/database owners before starting any setup-time migration or replacement UI. */
    private void restartIntoSetupWizard(String confirmation, String... arguments)
    {
        if(mDatabaseReplacementInProgress)
        {
            return;
        }
        if(JOptionPane.showConfirmDialog(mMainGui, confirmation, "Restart for setup",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
        {
            return;
        }
        try
        {
            mDatabaseReplacementInProgress = true;
            mMainGui.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            flushConfigurationForDatabaseReplacement();
            processShutdown(false);
            releaseDataRootLock();
            ApplicationRelauncher.relaunch(arguments);
            System.exit(0);
        }
        catch(Exception e)
        {
            mDatabaseReplacementInProgress = false;
            mMainGui.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            CopyableErrorDialog.show(mMainGui, "Restart Required",
                "Automatic restart failed. Close this process and start again with --setup-wizard.",
                exceptionMessage(e));
        }
    }

    private static String exceptionMessage(Throwable throwable)
    {
        Throwable cause = throwable;

        while(cause != null)
        {
            if(cause instanceof ApplicationMigrationService.LiveDatabaseRecoveryException)
            {
                return cause.getMessage() != null && !cause.getMessage().isBlank() ?
                    cause.getMessage() : cause.getClass().getSimpleName();
            }
            if(cause.getCause() == null)
            {
                break;
            }
            cause = cause.getCause();
        }

        return cause.getMessage() != null && !cause.getMessage().isBlank() ?
            cause.getMessage() : cause.getClass().getSimpleName();
    }

    private void requestGuiShutdown()
    {
        if(mDatabaseReplacementInProgress)
        {
            Toolkit.getDefaultToolkit().beep();
            return;
        }

        try
        {
            processShutdown();
            System.exit(0);
        }
        catch(RuntimeException exception)
        {
            mLog.error("Receiver shutdown was not safe to complete", exception);
            JOptionPane.showMessageDialog(mMainGui,
                "Receiver shutdown could not finish safely. Review the application log, then try again.",
                "Shutdown Paused", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void processShutdown()
    {
        processShutdown(true);
    }

    private synchronized void processShutdown(boolean releaseDataRootLock)
    {
        if(mShutdownProcessed)
        {
            return;
        }

        mShutdownProcessed = true;
        boolean databaseBoundarySafe = true;
        mLog.info("Application shutdown started ...");
        try
        {
            if(mStatsWebServerService != null)
            {
                mStatsWebServerService.close();
            }
            // Tuner settings and recording-tuner catalog changes use a short delayed SQLite coalescer. Once the
            // admin worker is quiescent, commit its last acknowledged mutation before any receiver teardown.
            ApplicationSettingsStore.flushPendingWritesNow();
        }
        catch(Exception unsafeShutdown)
        {
            mShutdownProcessed = false;
            mLog.error("Receiver shutdown paused before tuner settings were safe and durable", unsafeShutdown);
            throw new IllegalStateException("Tuner maintenance did not quiesce or its settings could not be saved",
                unsafeShutdown);
        }
        if(mGuiAvailable)
        {
            if(mNetworkAccessRefreshTimer != null)
            {
                mNetworkAccessRefreshTimer.stop();
            }
            MyEventBus.getGlobalEventBus().unregister(this);
            mUserPreferences.getSwingPreference().setLocation(WINDOW_FRAME_IDENTIFIER, mMainGui.getLocation());
            mUserPreferences.getSwingPreference().setDimension(WINDOW_FRAME_IDENTIFIER, mMainGui.getSize());
            mUserPreferences.getSwingPreference().setMaximized(WINDOW_FRAME_IDENTIFIER,
                (mMainGui.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH);
            mUserPreferences.getSwingPreference().flush();
            if(mBasebandRecordingDialog != null)
            {
                mBasebandRecordingDialog.dispose();
            }
            mJavaFxWindowManager.shutdown();
        }
        mLog.info("Stopping channels ...");
        if(mControlChannelQualityRegistry != null)
        {
            mConfigurationManager.getChannelProcessingManager().removeControlChannelQualityListener(
                mControlChannelQualityRegistry);
        }
        ChannelProcessingManager channelProcessingManager =
            mConfigurationManager.getChannelProcessingManager();
        channelProcessingManager.close();
        if(mRemoteConnectivityService != null)
        {
            mRemoteConnectivityService.close();
        }
        if(mMapSnapshotService != null)
        {
            mMapSnapshotService.close();
        }
        boolean siteMetadataDrained = channelProcessingManager.awaitSiteMetadataDrain(
            SITE_METADATA_SHUTDOWN_DRAIN_MILLISECONDS, TimeUnit.MILLISECONDS);
        databaseBoundarySafe &= siteMetadataDrained;

        if(!siteMetadataDrained)
        {
            mLog.warn("Site-metadata observers did not drain before shutdown");
        }

        if(mAudioCallCoordinator != null)
        {
            //Channel close emits terminal call events. Resolve and hand those calls to statistics/output consumers
            //before disposing the activity writer or the downstream recording and streaming queues.
            boolean callCoordinatorStopped = mAudioCallCoordinator.disposeAndAwait(
                CALL_COORDINATOR_SHUTDOWN_STOP_MILLISECONDS, TimeUnit.MILLISECONDS);
            databaseBoundarySafe &= callCoordinatorStopped;

            if(!callCoordinatorStopped)
            {
                mLog.warn("Logical-call resolver did not fully stop before shutdown");
            }
        }
        if(mReceiverActivityService != null)
        {
            channelProcessingManager.removeChannelDecodeEventListener(
                mReceiverActivityService.getDecodeEventListener());
            channelProcessingManager.removeControlChannelQualityListener(
                mReceiverActivityService.getControlChannelQualityListener());
            channelProcessingManager.removeSiteMetadataListener(mReceiverActivityService);
            channelProcessingManager.removeProtocolSiteMetadataListener(mReceiverActivityService);
        }
        if(mDecodeEventViewService != null)
        {
            channelProcessingManager.removeChannelDecodeEventListener(
                mDecodeEventViewService.getDecodeEventListener());
            mDecodeEventViewService.close();
        }
        EventLogger.flushPendingWrites();
        if(mAudioStreamingManager != null)
        {
            mAudioStreamingManager.stop();
        }
        if(mManagedRecordingRetention != null)
        {
            mManagedRecordingRetention.cancel(false);
            mManagedRecordingRetention = null;
        }
        if(mManagedRecordingMaintenance != null)
        {
            mManagedRecordingMaintenance.shutdown();
            try
            {
                if(!mManagedRecordingMaintenance.awaitTermination(30, TimeUnit.SECONDS))
                {
                    mLog.warn("Managed recording retention is still finishing during shutdown");
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        }
        mAudioRecordingManager.stop();
        if(mManagedRecordingCatalog != null)
        {
            try
            {
                mManagedRecordingCatalog.close();
            }
            catch(Exception exception)
            {
                mLog.warn("Managed recordings catalog did not close cleanly", exception);
            }
        }

        if(mReceiverActivityService != null)
        {
            //Resolved calls can still be completing their recording and streaming work during the final drains.
            //Keep the statistics writer alive until those local output confirmations have crossed the observer
            //barrier. Disposing the service then drains the already-mapped database records.
            boolean statisticsDrained = mReceiverActivityService.awaitObservationDrain(
                STATISTICS_SHUTDOWN_DRAIN_MILLISECONDS, TimeUnit.MILLISECONDS);
            boolean statisticsStopped = mReceiverActivityService.disposeAndAwait(
                STATISTICS_SHUTDOWN_STOP_MILLISECONDS, TimeUnit.MILLISECONDS);
            databaseBoundarySafe &= statisticsDrained && statisticsStopped;

            if(!statisticsDrained || !statisticsStopped)
            {
                mLog.warn("Statistics service did not fully drain and stop before shutdown [observations={}, " +
                    "workers={}]", statisticsDrained, statisticsStopped);
            }
        }

        if(!releaseDataRootLock && !databaseBoundarySafe)
        {
            throw new IllegalStateException(
                "Database-owning observers did not fully stop before SQLite database replacement");
        }

        if(mLogicalCallDiagnosticService != null)
        {
            mLogicalCallDiagnosticService.close();
        }

        if(mControlChannelQualityRegistry != null)
        {
            mControlChannelQualityRegistry.clear();
        }
        if(mResourceMonitor != null)
        {
            mResourceMonitor.stop();
        }

        mLog.info("Stopping tuners ...");
        mTunerManager.stop();
        mLog.info("Shutdown complete.");
        mApplicationLog.stop();
        SqlitePreferencesFactory.shutdown();

        if(releaseDataRootLock && databaseBoundarySafe)
        {
            releaseDataRootLock();
        }
        else if(releaseDataRootLock)
        {
            mLog.warn("Retaining the portable-data lock until process termination because shutdown did not " +
                "establish a safe database boundary");
        }
    }

    private void releaseDataRootLock()
    {
        if(mDataRootLock != null)
        {
            try
            {
                mDataRootLock.close();
            }
            catch(IOException e)
            {
                mLog.error("Unable to release the portable data lock", e);
            }

            mDataRootLock = null;
        }
    }

    private void registerQuitHandler()
    {
        if(!Desktop.isDesktopSupported())
        {
            return;
        }

        Desktop desktop = Desktop.getDesktop();

        if(!desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER))
        {
            return;
        }

        desktop.setQuitHandler((quitEvent, quitResponse) -> {
            if(mDatabaseReplacementInProgress)
            {
                Toolkit.getDefaultToolkit().beep();
                quitResponse.cancelQuit();
                return;
            }

            try
            {
                processShutdown();
                quitResponse.performQuit();
            }
            catch(Exception e)
            {
                mLog.error("Error while processing application quit", e);
                quitResponse.cancelQuit();
            }
        });
    }

    private JPanel getMainControlPanel()
    {
        JPanel panel = new JPanel(new MigLayout("insets 2 6 2 6", "[][][grow,fill]", "[]"));
        panel.add(getUserPreferencesShortcutButton());
        panel.add(getWebInterfaceButton());
        panel.add(new JPanel(), "grow");
        return panel;
    }

    private JPanel getNetworkAccessPanel()
    {
        JPanel panel = new JPanel(new MigLayout("insets 0 6 2 6, fillx, hidemode 3", "[][grow,fill][]", "[][]"));
        panel.add(new JLabel("Sharable URL:"), "cell 0 0");
        mNetworkAccessUrlField = new JTextField("Checking network access…");
        mNetworkAccessUrlField.setEditable(false);
        mNetworkAccessUrlField.setToolTipText("Active listener and network address will appear here.");
        panel.add(mNetworkAccessUrlField, "cell 1 0, growx");
        mCopyNetworkAccessUrlButton = new JButton("Copy");
        mCopyNetworkAccessUrlButton.setEnabled(false);
        mCopyNetworkAccessUrlButton.addActionListener(event -> {
            if(mDisplayedNetworkAccessUrl != null)
            {
                try
                {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new StringSelection(mDisplayedNetworkAccessUrl), null);
                }
                catch(IllegalStateException exception)
                {
                    JOptionPane.showMessageDialog(mMainGui, "The clipboard is currently unavailable.",
                        "Copy Web Address", JOptionPane.WARNING_MESSAGE);
                }
            }
        });
        panel.add(mCopyNetworkAccessUrlButton, "cell 2 0");
        mNetworkAccessWarningLabel = new JLabel();
        mNetworkAccessWarningLabel.setVisible(false);
        panel.add(mNetworkAccessWarningLabel, "cell 0 1, span 3, growx");
        return panel;
    }

    private void refreshNetworkAccessStatus()
    {
        if(!mNetworkAccessRefreshInProgress.compareAndSet(false, true))
        {
            return;
        }

        ThreadPool.CACHED.execute(() -> {
            NetworkAccessPresentation presentation;
            try
            {
                presentation = inspectNetworkAccessStatus();
            }
            catch(Exception exception)
            {
                presentation = new NetworkAccessPresentation("Network address unavailable", null,
                    "Unable to inspect the active web listener or local network addresses.", null);
            }
            finally
            {
                mNetworkAccessRefreshInProgress.set(false);
            }

            NetworkAccessPresentation status = presentation;
            EventQueue.invokeLater(() -> {
                if(mNetworkAccessUrlField != null && !mShutdownProcessed)
                {
                    mDisplayedNetworkAccessUrl = status.url();
                    mNetworkAccessUrlField.setText(status.display());
                    mNetworkAccessUrlField.setCaretPosition(0);
                    mNetworkAccessUrlField.setToolTipText(status.explanation());
                    mCopyNetworkAccessUrlButton.setEnabled(status.url() != null);
                    mNetworkAccessWarningLabel.setText(status.warning());
                    mNetworkAccessWarningLabel.setVisible(status.warning() != null);
                    mMainGui.revalidate();
                    ensureShellFitsContent();
                }
            });
        });
    }

    private NetworkAccessPresentation inspectNetworkAccessStatus() throws Exception
    {
        WebServerRuntimeState state = mStatsWebServerService.getRuntimeState();
        if(state == null || !state.running())
        {
            return new NetworkAccessPresentation("Web server is not running", null,
                "Other devices cannot connect until the web listener is running.", null);
        }
        if(!state.anyIpEnabled())
        {
            return new NetworkAccessPresentation("Not available (this computer only)", null,
                "Other devices cannot connect while access is set to This computer only.", null);
        }
        if(!state.https())
        {
            return new NetworkAccessPresentation("Remote HTTPS is not active", null,
                "The active listener is not using HTTPS; verify Web Server settings.", null);
        }

        String warning = null;
        try
        {
            if(!mStatsWebServerService.isPrimaryAdminConfigured())
            {
                warning = "Admin password required to sign in.";
            }
        }
        catch(Exception exception)
        {
            warning = "Admin status unavailable; check settings.";
        }

        String adminWarning = warning;
        return WebNetworkAddressDiscovery.discover().stream()
            .filter(address -> address.address() instanceof Inet4Address)
            .findFirst()
            .map(address -> {
                String url = address.url("https", state.port());
                return new NetworkAccessPresentation(url, url,
                    "Active HTTPS listener on " + address.interfaceDisplayName() +
                        ". Firewall, routing, and certificate trust may still affect access. " +
                        "Other LAN/VPN addresses are listed in Web Server settings.", adminWarning);
            })
            .orElseGet(() -> new NetworkAccessPresentation("No LAN/VPN IPv4 address found", null,
                "The web listener is active, but no usable address for another device was discovered.",
                adminWarning));
    }

    private record NetworkAccessPresentation(String display, String url, String explanation, String warning) {}

    private JButton getUserPreferencesShortcutButton()
    {
        if(mUserPreferencesShortcutButton == null)
        {
            mUserPreferencesShortcutButton = new JButton("Settings",
                IconFontSwing.buildIcon(FontAwesome.COG, 14));
            mUserPreferencesShortcutButton.setFocusable(false);
            mUserPreferencesShortcutButton.setToolTipText("User Preferences");
            mUserPreferencesShortcutButton.addActionListener(e ->
                MyEventBus.getGlobalEventBus().post(new ViewUserPreferenceEditorRequest()));
        }

        return mUserPreferencesShortcutButton;
    }

    private JButton getWebInterfaceButton()
    {
        if(mWebInterfaceButton == null)
        {
            mWebInterfaceButton = new JButton("Web", IconFontSwing.buildIcon(FontAwesome.GLOBE, 14));
            mWebInterfaceButton.setFocusable(false);
            mWebInterfaceButton.addActionListener(event -> openWebInterface());
            updateWebInterfaceButton();
        }

        return mWebInterfaceButton;
    }

    private void openWebInterface()
    {
        openWebInterface(true);
    }

    private void openWebInterface(boolean showFeedback)
    {
        if(!mUserPreferences.getApplicationPreference().isStatsWebServerEnabled())
        {
            return;
        }

        io.github.dsheirer.stats.StatsWebNavigationState navigation = mStatsWebServerService.getNavigationState();

        if(!navigation.running())
        {
            if(showFeedback)
            {
                JOptionPane.showMessageDialog(mMainGui, "The web server is enabled but is not currently running.",
                    "Web Interface", JOptionPane.INFORMATION_MESSAGE);
            }
            return;
        }

        try
        {
            if(!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            {
                throw new IOException("Desktop browser integration is unavailable");
            }

            URI handoffUri = mStatsWebServerService.createDesktopAdministratorHandoffUri();

            if(handoffUri == null)
            {
                if(showFeedback)
                {
                    JOptionPane.showMessageDialog(mMainGui,
                        "Set the primary administrator password in Web Server settings before opening the web interface.",
                        "Web Interface", JOptionPane.INFORMATION_MESSAGE);
                }
                return;
            }

            Desktop.getDesktop().browse(handoffUri);
        }
        catch(IOException | RuntimeException exception)
        {
            mStatsWebServerService.cancelDesktopAdministratorHandoff();
            mLog.warn("Unable to open the web interface", exception);
            if(showFeedback)
            {
                JOptionPane.showMessageDialog(mMainGui,
                    "Unable to open the web browser. Open " + navigation.baseUri() + " manually.",
                    "Web Interface", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.APPLICATION)
        {
            EventQueue.invokeLater(this::updateWebInterfaceButton);
            refreshNetworkAccessStatus();
        }
    }

    private void updateWebInterfaceButton()
    {
        if(mWebInterfaceButton != null)
        {
            boolean enabled = mUserPreferences.getApplicationPreference().isStatsWebServerEnabled();
            mWebInterfaceButton.setEnabled(enabled);
            mWebInterfaceButton.setToolTipText(enabled ? "Open Web Interface" :
                "Enable the Web Interface in User Preferences");
        }
    }

    /**
     * Lazy constructor for resource status panel
     */
    private JFXPanel getResourceStatusPanel()
    {

        if(mResourceStatusPanel == null)
        {
            mResourceStatusPanel = mJavaFxWindowManager.getStatusPanel(mResourceMonitor,
                mUserPreferences.getEncryptionKeyPreference().getVaultService(),
                mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager(),
                mStatsWebServerService::getNavigationState, () -> mUpdateCheckResult,
                this::openUpdateReleasePage);
            //The JavaFX scene is assigned asynchronously; reserve its two-row height in Swing immediately.
            mResourceStatusPanel.setPreferredSize(new Dimension(1, 50));
        }

        return mResourceStatusPanel;
    }

    public class ShutdownMonitor extends WindowAdapter
    {
        @Override
        public void windowClosing(WindowEvent e)
        {
            requestGuiShutdown();
        }
    }

    /**
     * Resource status panel visible toggle menu item
     */
    public class ResourceStatusVisibleMenuItem extends JCheckBoxMenuItem
    {
        public ResourceStatusVisibleMenuItem()
        {
            super("Show Status Footer");
            setSelected(mResourceStatusVisible);
            addActionListener(e -> {
                mResourceStatusVisible = !mResourceStatusVisible;
                EventQueue.invokeLater(() -> {
                    if(mResourceStatusVisible)
                    {
                        mMainGui.add(getResourceStatusPanel(), "cell 0 2,growx");
                    }
                    else
                    {
                        mMainGui.remove(getResourceStatusPanel());
                    }
                    mMainGui.revalidate();
                });
                mPreferences.putBoolean(PREFERENCE_RESOURCE_STATUS_VISIBLE, mResourceStatusVisible);
                setSelected(mResourceStatusVisible);
            });
        }
    }

    /**
     * Launch the application.
     */
    public static void main(String[] args)
    {
        EmbeddedHttpServerPolicy.configureBeforeServerInitialization();
        System.setProperty("apple.awt.application.name", "VCE");
        PortableDataRootLock dataRootLock = null;

        try
        {
            Path dataRoot = PortableApplicationPaths.getDataRoot();
            Path databasePath = SdrTrunkDatabasePath.getDatabasePath(dataRoot);

            if(Files.isRegularFile(databasePath))
            {
                dataRootLock = PortableDataRootLock.acquire(dataRoot);
            }

            UserPreferences userPreferences;
            boolean startConfiguredChannels = true;
            boolean openWebAfterSetup = false;
            if(!GraphicsEnvironment.isHeadless())
            {
                var setup = io.github.dsheirer.gui.setup.SetupWizard.run(args, dataRoot, dataRootLock);
                if(setup == null)
                {
                    if(dataRootLock != null) dataRootLock.close();
                    SqlitePreferencesFactory.shutdown();
                    System.exit(0);
                    return;
                }
                dataRootLock = setup.lock();
                if(setup.replacement() != null)
                {
                    System.exit(replaceSetupDatabase(setup.replacement(), dataRoot, dataRootLock));
                    return;
                }
                userPreferences = setup.preferences();
                startConfiguredChannels = setup.startChannels();
                openWebAfterSetup = setup.openWebAfterSetup();
            }
            else
            {
                if(java.util.Arrays.asList(args).contains("--setup-wizard"))
                    throw new IllegalArgumentException("--setup-wizard requires a graphical desktop.");
                SdrTrunkDatabaseBootstrap.BootstrapResult bootstrap = SdrTrunkDatabaseBootstrap.run(args);

                if(!bootstrap.startApplication())
                {
                    if(dataRootLock != null)
                    {
                        dataRootLock.close();
                    }

                    return;
                }

                if(dataRootLock == null)
                {
                    dataRootLock = PortableDataRootLock.acquire(dataRoot);
                }

                SqlitePreferencesFactory.install(databasePath);
                userPreferences = new UserPreferences();

                if(bootstrap.initializeNewPreferences())
                {
                    userPreferences.getApplicationPreference().setStatsLoggingEnabled(true);
                }
            }

            new SDRTrunk(userPreferences, dataRootLock, startConfiguredChannels, openWebAfterSetup);
            dataRootLock = null;
        }
        catch(Exception e)
        {
            SqlitePreferencesFactory.shutdown();

            if(dataRootLock != null)
            {
                try
                {
                    dataRootLock.close();
                }
                catch(IOException closeFailure)
                {
                    e.addSuppressed(closeFailure);
                }
            }

            if(e instanceof io.github.dsheirer.gui.setup.SetupWizard.Cancelled)
            {
                System.exit(0);
                return;
            }
            String message = "VCE could not start.\n\n" + e.getMessage();
            System.err.println(message);
            e.printStackTrace(System.err);

            if(!GraphicsEnvironment.isHeadless())
            {
                CopyableErrorDialog.show(null, "VCE Startup Error",
                    "VCE could not start. Receiver services were not started.", exceptionMessage(e));
            }

            System.exit(1);
        }
    }
}
