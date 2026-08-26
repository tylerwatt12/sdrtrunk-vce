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
import com.jidesoft.swing.JideSplitPane;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.application.update.UpdateCheckResult;
import io.github.dsheirer.application.update.UpdateCheckService;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticOutputEvent;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticOutputType;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticService;
import io.github.dsheirer.audio.broadcast.AudioStreamingManager;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastStatusPanel;
import io.github.dsheirer.channel.quality.ControlChannelQualityRegistry;
import io.github.dsheirer.controller.ControllerPanel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelSelectionManager;
import io.github.dsheirer.database.SdrTrunkDatabaseBootstrap;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.configuration.LegacyPlaylistImportDialog;
import io.github.dsheirer.gui.configuration.ViewConfigurationRequest;
import io.github.dsheirer.gui.bugreport.BugReportDialog;
import io.github.dsheirer.gui.icon.ViewIconManagerRequest;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.gui.preference.encryption.ViewEncryptionKeyPreferenceEditorRequest;
import io.github.dsheirer.gui.startup.CoordinatedStartupDialog;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.gui.viewer.ViewLogicalCallMonitorRequest;
import io.github.dsheirer.gui.viewer.ViewRecordingViewerRequest;
import io.github.dsheirer.gui.whatsnew.WhatsNewDialog;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.log.ApplicationLog;
import io.github.dsheirer.map.MapService;
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
import io.github.dsheirer.preference.swing.JTableColumnWidthMonitor;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.stats.activity.P25ActivityLogService;
import io.github.dsheirer.record.AudioRecordingManager;
import io.github.dsheirer.settings.SettingsManager;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.sdrplay.api.SDRPlayLibraryHelper;
import io.github.dsheirer.stats.StatsWebServerService;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.TimeStamp;
import io.github.dsheirer.vector.calibrate.CalibrationManager;
import io.github.dsheirer.web.http.EmbeddedHttpServerPolicy;
import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Robot;
import java.awt.desktop.QuitResponse;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import javafx.embed.swing.JFXPanel;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;

public class SDRTrunk
{
    private static final Logger mLog = LoggerFactory.getLogger(SDRTrunk.class);
    private Preferences mPreferences;

    private static final String PREFERENCE_BROADCAST_STATUS_VISIBLE = "sdrtrunk.broadcast.status.visible";
    private static final String PREFERENCE_RESOURCE_STATUS_VISIBLE = "sdrtrunk.resource.status.visible";
    private static final String PREFERENCE_UPDATE_FOOTER_MIGRATION =
        "sdrtrunk.resource.status.update.icon.migration.1";
    private static final String BASE_WINDOW_NAME = "sdrtrunk.main.window";
    private static final String CONTROLLER_PANEL_IDENTIFIER = BASE_WINDOW_NAME + ".control.panel";
    private static final String WINDOW_FRAME_IDENTIFIER = BASE_WINDOW_NAME + ".frame";
    private static final int MAIN_CONTROLLER_MINIMUM_HEIGHT = 180;

    private boolean mBroadcastStatusVisible;
    private boolean mResourceStatusVisible;
    private AudioCallCoordinator mAudioCallCoordinator;
    private LogicalCallDiagnosticService mLogicalCallDiagnosticService;
    private P25ActivityLogService mP25ActivityLogService;
    private DecodeEventViewService mDecodeEventViewService;
    private StatsWebServerService mStatsWebServerService;
    private AudioRecordingManager mAudioRecordingManager;
    private AudioStreamingManager mAudioStreamingManager;
    private ControlChannelQualityRegistry mControlChannelQualityRegistry;
    private BroadcastStatusPanel mBroadcastStatusPanel;
    private ControllerPanel mControllerPanel;
    private IconModel mIconModel;
    private ConfigurationManager mConfigurationManager;
    private SettingsManager mSettingsManager;
    private JFrame mMainGui;
    private JideSplitPane mSplitPane;
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
    private JButton mConfigurationEditorShortcutButton;
    private JButton mUserPreferencesShortcutButton;
    private JButton mCallMatchingMonitorShortcutButton;
    private JButton mWebInterfaceButton;
    private JMenuItem mEncryptionKeysItem;
    private boolean mShutdownProcessed;
    private PortableDataRootLock mDataRootLock;

    private SDRTrunk(UserPreferences userPreferences, PortableDataRootLock dataRootLock)
    {
        mUserPreferences = userPreferences;
        mDataRootLock = dataRootLock;
        mPreferences = Preferences.userNodeForPackage(SDRTrunk.class);
        mUpdateCheckService = new UpdateCheckService();
        mIconModel = new IconModel();

        if(!GraphicsEnvironment.isHeadless())
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

        mResourceMonitor = new ResourceMonitor(mUserPreferences);

        ThreadPool.logSettings();

        //Register FontAwesome so we can use the fonts in Swing windows
        IconFontSwing.register(FontAwesome.getIconFont());

        mTunerManager = new TunerManager(mUserPreferences);
        mTunerManager.start();

        mSettingsManager = new SettingsManager();

        AliasModel aliasModel = new AliasModel();
        EventLogManager eventLogManager = new EventLogManager(aliasModel, mUserPreferences);
        mConfigurationManager = new ConfigurationManager(mUserPreferences, mTunerManager, aliasModel, eventLogManager, mIconModel);

        if(!GraphicsEnvironment.isHeadless())
        {
            mJavaFxWindowManager = new JavaFxWindowManager(mUserPreferences, mTunerManager, mConfigurationManager);
        }

        CalibrationManager calibrationManager = CalibrationManager.getInstance(mUserPreferences);
        final boolean calibrating = !calibrationManager.isCalibrated() &&
            !mUserPreferences.getVectorCalibrationPreference().isHideCalibrationDialog();

        new ChannelSelectionManager(mConfigurationManager.getChannelModel());

        mP25ActivityLogService = new P25ActivityLogService(mUserPreferences);

        mLogicalCallDiagnosticService = new LogicalCallDiagnosticService(
            mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog());

        mAudioRecordingManager = new AudioRecordingManager(mUserPreferences,
            this::receiveRecordedCall);
        mAudioRecordingManager.start();

        mAudioStreamingManager = new AudioStreamingManager(mConfigurationManager.getBroadcastModel(), BroadcastFormat.MP3,
            mUserPreferences, this::receiveStreamedCall);
        mAudioStreamingManager.start();

        mDecodeEventViewService = new DecodeEventViewService(
            mConfigurationManager.getChannelProcessingManager(), mConfigurationManager.getAliasModel());
        mConfigurationManager.getChannelProcessingManager().addChannelDecodeEventListener(
            mDecodeEventViewService.getDecodeEventListener());

        mStatsWebServerService = new StatsWebServerService(mUserPreferences,
            mConfigurationManager.getChannelProcessingManager(), mP25ActivityLogService,
            mConfigurationManager.getAliasAdministrationService(), mDecodeEventViewService, mTunerManager,
            mConfigurationManager.getScanListModel());

        if(mJavaFxWindowManager != null)
        {
            mJavaFxWindowManager.setStatsWebServerService(mStatsWebServerService);
        }
        mControlChannelQualityRegistry = new ControlChannelQualityRegistry();
        mAudioCallCoordinator = new AudioCallCoordinator(mAudioRecordingManager, mAudioStreamingManager,
            mStatsWebServerService::receive, mP25ActivityLogService::receiveResolvedCall,
            mLogicalCallDiagnosticService);

        if(mJavaFxWindowManager != null)
        {
            mJavaFxWindowManager.setLogicalCallDiagnostics(mLogicalCallDiagnosticService, mAudioCallCoordinator,
                path -> EventQueue.invokeLater(() -> openFileExplorer(path.toFile())));
        }

        mStatsWebServerService.setReceiverHealthOutputSources(mAudioCallCoordinator, mAudioRecordingManager,
            mAudioStreamingManager);

        mConfigurationManager.getChannelProcessingManager().addAudioCallListener(mAudioCallCoordinator);
        mConfigurationManager.getChannelProcessingManager().addChannelDecodeEventListener(
            mP25ActivityLogService.getDecodeEventListener());
        mConfigurationManager.getChannelProcessingManager().addControlChannelQualityListener(
            mP25ActivityLogService.getControlChannelQualityListener());
        mConfigurationManager.getChannelProcessingManager().addControlChannelQualityListener(
            mControlChannelQualityRegistry);
        mConfigurationManager.getChannelProcessingManager().addSiteMetadataListener(mP25ActivityLogService);
        mConfigurationManager.getChannelProcessingManager().addProtocolSiteMetadataListener(mP25ActivityLogService);
        mConfigurationManager.getChannelProcessingManager().addSiteMetadataListener(mConfigurationManager.getBroadcastModel());
        mConfigurationManager.getChannelProcessingManager().addSiteMetadataListener(new SiteControlChannelLearner(mConfigurationManager));

        MapService mapService = new MapService(aliasModel);
        mConfigurationManager.getChannelProcessingManager().addDecodeEventListener(mapService);

        if(!GraphicsEnvironment.isHeadless())
        {
            mControllerPanel = new ControllerPanel(mConfigurationManager, mIconModel, mapService,
                    mSettingsManager, mTunerManager, mUserPreferences);
        }

        mConfigurationManager.init();

        if(GraphicsEnvironment.isHeadless())
        {
            mLog.info("starting main application headless");
        }
        else
        {
            mLog.info("starting main application gui");

            //Initialize the GUI
            initGUI();
        }

        //Start the gui
        EventQueue.invokeLater(() -> {
            try
            {
                if(!GraphicsEnvironment.isHeadless())
                {
                    ThemeManager.getInstance().registerSwing(mMainGui);
                    mMainGui.setVisible(true);
                    checkForUpdates(false);
                }
            }
            catch(Exception e)
            {
                mLog.error("Unable to finish initial GUI setup; continuing with post-launch startup", e);
            }

            try
            {
                startPostLaunchExperience(calibrating);
            }
            catch(Exception e)
            {
                mLog.error("Post-launch startup failed; starting configured channels without the startup dialog", e);
                EncryptionKeyVaultService vaultService = getLockedLaunchVault();

                if(vaultService != null)
                {
                    vaultService.disableForRun();
                }

                startChannelsWithoutDialog(mConfigurationManager.getChannelModel().getAutoStartChannels());
            }
        });
    }

    private void startPostLaunchExperience(boolean calibrationRequired)
    {
        List<Channel> channels = mConfigurationManager.getChannelModel().getAutoStartChannels();
        EncryptionKeyVaultService vaultService = getLockedLaunchVault();

        if(GraphicsEnvironment.isHeadless())
        {
            if(vaultService != null)
            {
                vaultService.disableForRun();
            }

            startChannelsWithoutDialog(channels);
            return;
        }

        CoordinatedStartupDialog dialog = new CoordinatedStartupDialog(mMainGui, mUserPreferences,
            WhatsNewDialog.getPendingReleaseNotes(), calibrationRequired, vaultService, channels,
            mConfigurationManager.getChannelProcessingManager());

        if(dialog.hasSteps())
        {
            dialog.showExperience();
        }
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
        mMainGui.setLayout(new MigLayout("insets 0 0 0 0 ", "[grow,fill]", "[]0[grow,fill]0[shrink 0]"));
        ApplicationIcon.apply(mMainGui);

        /**
         * Setup main JFrame window
         */
        mMainGui.setTitle(ApplicationInfo.getDisplayName());

        Point location = mUserPreferences.getSwingPreference().getLocation(WINDOW_FRAME_IDENTIFIER);
        Dimension dimension = mUserPreferences.getSwingPreference().getDimension(WINDOW_FRAME_IDENTIFIER);
        mMainGui.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        mMainGui.addWindowListener(new ShutdownMonitor());
        registerQuitHandler();

        mControllerPanel.setPreferredSize(new Dimension(1280, 500));
        mControllerPanel.setMinimumSize(new Dimension(0, MAIN_CONTROLLER_MINIMUM_HEIGHT));

        if(dimension != null)
        {
            Dimension controller = mUserPreferences.getSwingPreference().getDimension(CONTROLLER_PANEL_IDENTIFIER);
            if(controller != null)
            {
                Dimension pref = mControllerPanel.getPreferredSize();
                mControllerPanel.setPreferredSize(new Dimension(pref.width, controller.height));
            }

            mMainGui.setSize(dimension);
        }
        else
        {
            mMainGui.setSize(new Dimension(1280, 800));
        }

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
        mSplitPane = new JideSplitPane(JideSplitPane.VERTICAL_SPLIT);
        mSplitPane.setDividerSize(5);
        mSplitPane.add(mControllerPanel);
        mBroadcastStatusVisible = mPreferences.getBoolean(PREFERENCE_BROADCAST_STATUS_VISIBLE, false);

        //Show broadcast status panel when user requests - disabled by default
        if(mBroadcastStatusVisible)
        {
            mSplitPane.add(getBroadcastStatusPanel());
        }

        mMainGui.add(getMainControlPanel(), "cell 0 0,growx");
        mMainGui.add(mSplitPane, "cell 0 1,grow");

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

        JMenuItem importLegacyPlaylistMenu = new JMenuItem("Import Legacy Playlist XML...");
        importLegacyPlaylistMenu.addActionListener(event -> LegacyPlaylistImportDialog.show(mMainGui,
            mConfigurationManager, mUserPreferences.getDirectoryPreference().getDirectoryApplicationRoot()));
        fileMenu.add(importLegacyPlaylistMenu);
        fileMenu.addSeparator();

        JMenuItem exitMenu = new JMenuItem("Exit");
        exitMenu.addActionListener(event -> {
                processShutdown();
                System.exit(0);
            }
        );

        fileMenu.add(exitMenu);

        JMenu viewMenu = new JMenu("View");

        JMenuItem viewConfigurationItem = new JMenuItem("Configuration Editor");
        viewConfigurationItem.setIcon(IconFontSwing.buildIcon(FontAwesome.PLAY_CIRCLE_O, 12));
        viewConfigurationItem.addActionListener(e -> MyEventBus.getGlobalEventBus().post(new ViewConfigurationRequest()));
        viewMenu.add(viewConfigurationItem);

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

        JMenuItem iconManagerMenu = new JMenuItem("Icon Manager");
        iconManagerMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.PICTURE_O, 12));
        iconManagerMenu.addActionListener(arg0 -> MyEventBus.getGlobalEventBus().post(new ViewIconManagerRequest()));
        viewMenu.add(iconManagerMenu);

        JMenuItem recordingViewerMenu = new JMenuItem("Message Recording Viewer (.bits)");
        recordingViewerMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.BRAILLE, 12));
        recordingViewerMenu.addActionListener(e -> MyEventBus.getGlobalEventBus().post(new ViewRecordingViewerRequest()));
        viewMenu.add(recordingViewerMenu);

        JMenuItem logicalCallMonitorMenu = new JMenuItem("Call Matching Monitor");
        logicalCallMonitorMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.SITEMAP, 12));
        logicalCallMonitorMenu.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, ActionEvent.ALT_MASK));
        logicalCallMonitorMenu.addActionListener(e ->
            MyEventBus.getGlobalEventBus().post(new ViewLogicalCallMonitorRequest()));
        viewMenu.add(logicalCallMonitorMenu);

        JMenuItem viewScreenCapturesMenu = new JMenuItem("Screen Captures");
        viewScreenCapturesMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER_OPEN_O, 12));
        viewScreenCapturesMenu.addActionListener(arg0 ->
                openFileExplorer(mUserPreferences.getDirectoryPreference().getDirectoryScreenCapture().toFile()));
        viewMenu.add(viewScreenCapturesMenu);

        JMenuItem preferencesItem = new JMenuItem("User Preferences");
        preferencesItem.setIcon(IconFontSwing.buildIcon(FontAwesome.COG, 12));
        preferencesItem.addActionListener(e -> MyEventBus.getGlobalEventBus().post(new ViewUserPreferenceEditorRequest()));
        viewMenu.add(preferencesItem);

        viewMenu.add(new JSeparator());
        JMenuItem resetColumnWidthsMenuItem = new JMenuItem("Reset Table Column Widths");
        resetColumnWidthsMenuItem.addActionListener(e -> {
            int removed = JTableColumnWidthMonitor.resetSavedColumnWidths(mUserPreferences);
            JOptionPane.showMessageDialog(mMainGui, "Reset " + removed + " saved table column width setting" +
                    (removed == 1 ? "." : "s."), "Table Column Widths Reset", JOptionPane.INFORMATION_MESSAGE);
        });
        viewMenu.add(resetColumnWidthsMenuItem);
        viewMenu.add(new JSeparator());
        viewMenu.add(new BroadcastStatusVisibleMenuItem());
        viewMenu.add(new ResourceStatusVisibleMenuItem());

        menuBar.add(viewMenu);

        JMenuItem screenCaptureItem = new JMenuItem("Screen Capture");
        screenCaptureItem.setIcon(IconFontSwing.buildIcon(FontAwesome.CAMERA, 12));
        screenCaptureItem.setMnemonic(KeyEvent.VK_C);
        screenCaptureItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, ActionEvent.ALT_MASK));
        screenCaptureItem.setMaximumSize(screenCaptureItem.getPreferredSize());
        screenCaptureItem.addActionListener(arg0 -> {
            try
            {
                Robot robot = new Robot();

                final BufferedImage image = robot.createScreenCapture(mMainGui.getBounds());

                String filename = TimeStamp.getTimeStamp("_") + "_screen_capture.png";

                final Path captureFile = mUserPreferences.getDirectoryPreference().getDirectoryScreenCapture().resolve(filename);

                ThreadPool.CACHED.submit(() -> {
                    try
                    {
                        ImageIO.write(image, "png", captureFile.toFile());
                    }
                    catch(IOException e)
                    {
                        mLog.error("Couldn't write screen capture to file [" + captureFile + "]", e);
                    }
                });
            }
            catch(AWTException e)
            {
                mLog.error("Exception while taking screen capture", e);
            }
        });

        menuBar.add(screenCaptureItem);

        JMenu helpMenu = new JMenu("Help");
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
        helpMenu.add(new JSeparator());

        JMenuItem bugReportItem = new JMenuItem("Submit Bug Report...");
        bugReportItem.setIcon(IconFontSwing.buildIcon(FontAwesome.BUG, 12));
        bugReportItem.addActionListener(event ->
            new BugReportDialog(mMainGui, mUserPreferences, mTunerManager).setVisible(true));
        helpMenu.add(bugReportItem);
        helpMenu.add(new JSeparator());
        JMenuItem creditsItem = new JMenuItem("Credits & Licensing");
        creditsItem.addActionListener(event -> new CreditsDialog(mMainGui).setVisible(true));
        helpMenu.add(creditsItem);
        menuBar.add(helpMenu);
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

    /** Records the completed local file action and preserves the existing activity-statistics callback. */
    private void receiveRecordedCall(CompletedAudioCall completedAudioCall)
    {
        try
        {
            mP25ActivityLogService.receiveRecordedCall(completedAudioCall);
        }
        finally
        {
            offerDiagnosticOutput(completedAudioCall, LogicalCallDiagnosticOutputType.RECORDED);
        }
    }

    /** Records local streamer submission, not acknowledgement or publication by a remote provider. */
    private void receiveStreamedCall(CompletedAudioCall completedAudioCall)
    {
        try
        {
            mP25ActivityLogService.receiveStreamedCall(completedAudioCall);
        }
        finally
        {
            offerDiagnosticOutput(completedAudioCall, LogicalCallDiagnosticOutputType.STREAM_SUBMITTED);
        }
    }

    private void offerDiagnosticOutput(CompletedAudioCall completedAudioCall,
                                       LogicalCallDiagnosticOutputType outputType)
    {
        LogicalCallDiagnosticService diagnosticService = mLogicalCallDiagnosticService;

        if(diagnosticService != null && completedAudioCall != null && completedAudioCall.logicalCallId() != null)
        {
            diagnosticService.offerOutput(new LogicalCallDiagnosticOutputEvent(
                completedAudioCall.logicalCallId().sequence(), System.currentTimeMillis(), outputType));
        }
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

    private void processShutdown()
    {
        if(mShutdownProcessed)
        {
            return;
        }

        mShutdownProcessed = true;
        MyEventBus.getGlobalEventBus().unregister(this);
        mLog.info("Application shutdown started ...");
        mUserPreferences.getSwingPreference().setLocation(WINDOW_FRAME_IDENTIFIER, mMainGui.getLocation());
        mUserPreferences.getSwingPreference().setDimension(WINDOW_FRAME_IDENTIFIER, mMainGui.getSize());
        mUserPreferences.getSwingPreference().setMaximized(WINDOW_FRAME_IDENTIFIER,
            (mMainGui.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH);
        mUserPreferences.getSwingPreference().setDimension(CONTROLLER_PANEL_IDENTIFIER, mControllerPanel.getSize());
        mUserPreferences.getSwingPreference().flush();
        mControllerPanel.dispose();
        mJavaFxWindowManager.shutdown();
        mLog.info("Stopping channels ...");
        if(mStatsWebServerService != null)
        {
            mStatsWebServerService.close();
        }
        if(mControlChannelQualityRegistry != null)
        {
            mConfigurationManager.getChannelProcessingManager().removeControlChannelQualityListener(
                mControlChannelQualityRegistry);
        }
        mConfigurationManager.getChannelProcessingManager().close();
        if(mAudioCallCoordinator != null)
        {
            //Channel close emits terminal call events. Resolve and hand those calls to statistics/output consumers
            //before disposing the activity writer or the downstream recording and streaming queues.
            mAudioCallCoordinator.dispose();
        }
        if(mP25ActivityLogService != null)
        {
            mConfigurationManager.getChannelProcessingManager().removeChannelDecodeEventListener(
                mP25ActivityLogService.getDecodeEventListener());
            mConfigurationManager.getChannelProcessingManager().removeControlChannelQualityListener(
                mP25ActivityLogService.getControlChannelQualityListener());
            mConfigurationManager.getChannelProcessingManager().removeSiteMetadataListener(mP25ActivityLogService);
            mConfigurationManager.getChannelProcessingManager()
                .removeProtocolSiteMetadataListener(mP25ActivityLogService);
        }
        if(mDecodeEventViewService != null)
        {
            mConfigurationManager.getChannelProcessingManager().removeChannelDecodeEventListener(
                mDecodeEventViewService.getDecodeEventListener());
            mDecodeEventViewService.close();
        }
        EventLogger.flushPendingWrites();
        if(mAudioStreamingManager != null)
        {
            mAudioStreamingManager.stop();
        }
        mAudioRecordingManager.stop();

        if(mP25ActivityLogService != null)
        {
            //Resolved calls can still be completing their recording and streaming work during the final drains.
            //Keep the statistics writer alive until those local output confirmations have been accepted.
            mP25ActivityLogService.dispose();
        }

        if(mLogicalCallDiagnosticService != null)
        {
            //Output managers report their final recorded/stream-submitted confirmations while draining.
            mLogicalCallDiagnosticService.close();
        }

        if(mControlChannelQualityRegistry != null)
        {
            mControlChannelQualityRegistry.clear();
        }
        mResourceMonitor.stop();

        mLog.info("Stopping tuners ...");
        mTunerManager.stop();
        mLog.info("Shutdown complete.");
        mApplicationLog.stop();
        SqlitePreferencesFactory.shutdown();

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

    /**
     * Lazy constructor for broadcast status panel
     */
    private BroadcastStatusPanel getBroadcastStatusPanel()
    {
        if(mBroadcastStatusPanel == null)
        {
            mBroadcastStatusPanel = new BroadcastStatusPanel(mConfigurationManager.getBroadcastModel(), mUserPreferences,
                "application.broadcast.status.panel");
            mBroadcastStatusPanel.setPreferredSize(new Dimension(880, 70));
            mBroadcastStatusPanel.getTable().setEnabled(false);
        }

        return mBroadcastStatusPanel;
    }

    private JPanel getMainControlPanel()
    {
        JPanel panel = new JPanel(new MigLayout("insets 2 6 2 6", "[][][][][grow,fill]", "[]"));
        panel.add(getConfigurationEditorShortcutButton());
        panel.add(getUserPreferencesShortcutButton());
        panel.add(getCallMatchingMonitorShortcutButton());
        panel.add(getWebInterfaceButton());
        panel.add(new JPanel(), "grow");
        return panel;
    }

    private JButton getConfigurationEditorShortcutButton()
    {
        if(mConfigurationEditorShortcutButton == null)
        {
            mConfigurationEditorShortcutButton = new JButton("Playlist",
                IconFontSwing.buildIcon(FontAwesome.PLAY_CIRCLE_O, 14));
            mConfigurationEditorShortcutButton.setFocusable(false);
            mConfigurationEditorShortcutButton.setToolTipText("Configuration Editor");
            mConfigurationEditorShortcutButton.addActionListener(e ->
                MyEventBus.getGlobalEventBus().post(new ViewConfigurationRequest()));
        }

        return mConfigurationEditorShortcutButton;
    }

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

    private JButton getCallMatchingMonitorShortcutButton()
    {
        if(mCallMatchingMonitorShortcutButton == null)
        {
            mCallMatchingMonitorShortcutButton = new JButton("Call Monitor",
                IconFontSwing.buildIcon(FontAwesome.SITEMAP, 14));
            mCallMatchingMonitorShortcutButton.setFocusable(false);
            mCallMatchingMonitorShortcutButton.setToolTipText("Call Matching Monitor");
            mCallMatchingMonitorShortcutButton.addActionListener(event ->
                MyEventBus.getGlobalEventBus().post(new ViewLogicalCallMonitorRequest()));
        }

        return mCallMatchingMonitorShortcutButton;
    }

    private void openWebInterface()
    {
        if(!mUserPreferences.getApplicationPreference().isStatsWebServerEnabled())
        {
            return;
        }

        io.github.dsheirer.stats.StatsWebNavigationState navigation = mStatsWebServerService.getNavigationState();

        if(!navigation.running())
        {
            JOptionPane.showMessageDialog(mMainGui, "The web server is enabled but is not currently running.",
                "Web Interface", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        try
        {
            if(!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            {
                throw new IOException("Desktop browser integration is unavailable");
            }

            Desktop.getDesktop().browse(navigation.baseUri());
        }
        catch(IOException exception)
        {
            mLog.warn("Unable to open the web interface", exception);
            JOptionPane.showMessageDialog(mMainGui,
                "Unable to open the web browser. Open " + navigation.baseUri() + " manually.",
                "Web Interface", JOptionPane.WARNING_MESSAGE);
        }
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.APPLICATION)
        {
            EventQueue.invokeLater(this::updateWebInterfaceButton);
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
        }

        return mResourceStatusPanel;
    }

    public class ShutdownMonitor extends WindowAdapter
    {
        @Override
        public void windowClosing(WindowEvent e)
        {
            processShutdown();
        }
    }

    /**
     * Broadcast status panel visible toggle menu item
     */
    public class BroadcastStatusVisibleMenuItem extends JCheckBoxMenuItem
    {
        public BroadcastStatusVisibleMenuItem()
        {
            super("Show Streaming Status");
            setSelected(mBroadcastStatusVisible);
            addActionListener(e -> {
                mBroadcastStatusVisible = !mBroadcastStatusVisible;
                EventQueue.invokeLater(() -> {
                    if(mBroadcastStatusVisible)
                    {
                        mSplitPane.add(getBroadcastStatusPanel());
                    }
                    else
                    {
                        mSplitPane.remove(getBroadcastStatusPanel());
                    }
                    mMainGui.revalidate();
                });
                mPreferences.putBoolean(PREFERENCE_BROADCAST_STATUS_VISIBLE, mBroadcastStatusVisible);
                setSelected(mBroadcastStatusVisible);
            });
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
        System.setProperty("apple.awt.application.name", "sdrtrunk-vce");
        PortableDataRootLock dataRootLock = null;

        try
        {
            Path dataRoot = PortableApplicationPaths.getDataRoot();
            Path databasePath = SdrTrunkDatabasePath.getDatabasePath(dataRoot);

            if(Files.isRegularFile(databasePath))
            {
                dataRootLock = PortableDataRootLock.acquire(dataRoot);
            }

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
            UserPreferences userPreferences = new UserPreferences();

            if(bootstrap.initializeNewPreferences())
            {
                userPreferences.getApplicationPreference().setStatsLoggingEnabled(true);
            }

            new SDRTrunk(userPreferences, dataRootLock);
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

            String message = "sdrtrunk-vce could not start.\n\n" + e.getMessage();
            System.err.println(message);
            e.printStackTrace(System.err);

            if(!GraphicsEnvironment.isHeadless())
            {
                JOptionPane.showMessageDialog(null, message, "sdrtrunk-vce Startup Error",
                    JOptionPane.ERROR_MESSAGE);
            }

            System.exit(1);
        }
    }
}
