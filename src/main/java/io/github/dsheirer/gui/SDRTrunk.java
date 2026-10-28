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
import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.application.update.UpdateCheckResult;
import io.github.dsheirer.application.update.UpdateCheckService;
import io.github.dsheirer.database.SdrTrunkDatabaseBootstrap;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.upgrade.ApplicationMigrationService;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.diagnostic.BasebandRecordingDialog;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.gui.preference.encryption.ViewEncryptionKeyPreferenceEditorRequest;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.gui.viewer.ViewRecordingViewerRequest;
import io.github.dsheirer.gui.whatsnew.WhatsNewDialog;
import io.github.dsheirer.monitor.ResourceMonitor;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.receiver.ReceiverRuntime;
import io.github.dsheirer.receiver.ReceiverLaunchMode;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.stats.StatsWebServerService;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.web.network.WebNetworkAddressDiscovery;
import io.github.dsheirer.stats.WebServerRuntimeState;
import io.github.dsheirer.web.auth.WebAuthenticationService.DesktopHandoffDestination;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
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

    private boolean mResourceStatusVisible;
    private final ReceiverRuntime mRuntime;
    private boolean mDesktopClosed;
    private StatsWebServerService mStatsWebServerService;
    private JFrame mMainGui;
    private BasebandRecordingDialog mBasebandRecordingDialog;
    private JavaFxWindowManager mJavaFxWindowManager;
    private UserPreferences mUserPreferences;
    private TunerManager mTunerManager;
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
    private final AtomicBoolean mNetworkAccessRefreshRequested = new AtomicBoolean();
    private String mDisplayedNetworkAccessUrl;
    private boolean mOpenWebAfterSetup;
    private JMenuItem mEncryptionKeysItem;
    private boolean mShutdownProcessed;
    private final boolean mStartConfiguredChannels;

    /** Optional local setup/diagnostic shell over the same receiver used in server mode. */
    private SDRTrunk(ReceiverRuntime runtime, UserPreferences userPreferences,
                     boolean startConfiguredChannels, boolean openWebAfterSetup)
    {
        mRuntime = runtime;
        mUserPreferences = userPreferences;
        mTunerManager = runtime.tuners();
        mStatsWebServerService = runtime.webServer();
        mStartConfiguredChannels = startConfiguredChannels;
        mOpenWebAfterSetup = openWebAfterSetup;
        mPreferences = Preferences.userNodeForPackage(SDRTrunk.class);
        mUpdateCheckService = new UpdateCheckService();
        try
        {
            //Keep setup's fixed appearance until listener recovery is complete.
            if(!mStatsWebServerService.getRuntimeState().running())
            {
                if(!io.github.dsheirer.gui.setup.SetupWizard.ensureListener(mUserPreferences, mStatsWebServerService))
                    throw new io.github.dsheirer.gui.setup.SetupWizard.Cancelled();
                mOpenWebAfterSetup = true;
            }
            ThemeManager.getInstance().initialize(mUserPreferences);
            mMainGui = new JFrame();
            MyEventBus.getGlobalEventBus().register(this);
            mResourceMonitor = new ResourceMonitor(mUserPreferences);
            IconFontSwing.register(FontAwesome.getIconFont());
            mJavaFxWindowManager = new JavaFxWindowManager(mUserPreferences);
            mJavaFxWindowManager.setStatsWebServerService(mStatsWebServerService);
            mRuntime.activate();
            initGUI();
            mRuntime.setBeforeShutdown(this::closeDesktop);
            EventQueue.invokeLater(() -> {
                try
                {
                    ThemeManager.getInstance().registerSwing(mMainGui);
                    mMainGui.setVisible(true);
                    checkForUpdates(false);
                }
                catch(Exception exception)
                {
                    mLog.error("Unable to finish initial GUI setup", exception);
                }
                if(mStartConfiguredChannels) mRuntime.startConfiguredChannels(false);
                if(mOpenWebAfterSetup) openWebInterface(false, DesktopHandoffDestination.CHANNEL_SETUP);
            });
        }
        catch(RuntimeException | Error failure)
        {
            closeDesktop();
            throw failure;
        }
        catch(Exception failure)
        {
            closeDesktop();
            throw new IllegalStateException("Desktop setup could not complete", failure);
        }
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

        JMenuItem exitMenu = new JMenuItem("Exit");
        exitMenu.addActionListener(event -> requestGuiShutdown());

        fileMenu.add(exitMenu);

        JMenu viewMenu = new JMenu("View");

        mEncryptionKeysItem = new JMenuItem("Encryption Keys");
        mEncryptionKeysItem.setIcon(IconFontSwing.buildIcon(FontAwesome.KEY, 12));
        mEncryptionKeysItem.addActionListener(e -> MyEventBus.getGlobalEventBus().post(new ViewEncryptionKeyPreferenceEditorRequest()));
        mEncryptionKeysItem.setVisible(mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded());
        mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager()
            .addStatusListener((previous, current) -> {
                if(previous.loaded() == current.loaded()) return;
                EventQueue.invokeLater(() -> {
                    mEncryptionKeysItem.setVisible(current.loaded());

                    if(!current.loaded())
                    {
                        mUserPreferences.getEncryptionKeyPreference().getVaultService().lock();
                    }
                });
            });
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

        viewMenu.add(new JSeparator());
        viewMenu.add(new ResourceStatusVisibleMenuItem());

        menuBar.add(viewMenu);

        JMenu diagnosticsMenu = new JMenu("Diagnostics");
        JMenuItem recordingViewerMenu = new JMenuItem("Message Recording Viewer (.bits)");
        recordingViewerMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.BRAILLE, 12));
        recordingViewerMenu.addActionListener(e -> MyEventBus.getGlobalEventBus().post(new ViewRecordingViewerRequest()));
        diagnosticsMenu.add(recordingViewerMenu);
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
        try
        {
            processShutdown();
        }
        catch(RuntimeException exception)
        {
            mLog.error("Receiver cleanup failed; exiting", exception);
        }
        System.exit(0);
    }

    private void processShutdown()
    {
        synchronized(this)
        {
            if(mShutdownProcessed) return;
            mShutdownProcessed = true;
        }
        mRuntime.close();
    }

    private synchronized void closeDesktop()
    {
        if(mDesktopClosed) return;
        mDesktopClosed = true;
        if(mNetworkAccessRefreshTimer != null) mNetworkAccessRefreshTimer.stop();
        if(mMainGui != null)
        {
            MyEventBus.getGlobalEventBus().unregister(this);
            mUserPreferences.getSwingPreference().setLocation(WINDOW_FRAME_IDENTIFIER, mMainGui.getLocation());
            mUserPreferences.getSwingPreference().setDimension(WINDOW_FRAME_IDENTIFIER, mMainGui.getSize());
            mUserPreferences.getSwingPreference().setMaximized(WINDOW_FRAME_IDENTIFIER,
                (mMainGui.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH);
            mUserPreferences.getSwingPreference().flush();
        }
        if(mBasebandRecordingDialog != null) mBasebandRecordingDialog.dispose();
        if(mJavaFxWindowManager != null) mJavaFxWindowManager.shutdown();
        if(mResourceMonitor != null) mResourceMonitor.stop();
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
            }
            catch(Exception e)
            {
                mLog.error("Error while processing application quit", e);
            }
            quitResponse.performQuit();
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
        if(mStatsWebServerService == null || mShutdownProcessed)
        {
            return;
        }

        mNetworkAccessRefreshRequested.set(true);
        if(!mNetworkAccessRefreshInProgress.compareAndSet(false, true))
        {
            return;
        }

        ThreadPool.CACHED.execute(() -> {
            mNetworkAccessRefreshRequested.set(false);
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
            NetworkAccessPresentation status = presentation;
            EventQueue.invokeLater(() -> {
                try
                {
                    // A listener change during discovery makes this result stale; inspect again before displaying it.
                    if(!mNetworkAccessRefreshRequested.get() && mNetworkAccessUrlField != null && !mShutdownProcessed)
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
                }
                finally
                {
                    mNetworkAccessRefreshInProgress.set(false);
                    if(mNetworkAccessRefreshRequested.get())
                    {
                        refreshNetworkAccessStatus();
                    }
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
        openWebInterface(true, DesktopHandoffDestination.DASHBOARD);
    }

    private void openWebInterface(boolean showFeedback, DesktopHandoffDestination destination)
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

            URI handoffUri = mStatsWebServerService.createDesktopAdministratorHandoffUri(destination);

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
    public void webServerRuntimeStateUpdated(WebServerRuntimeState state)
    {
        refreshNetworkAccessStatus();
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
        ReceiverRuntime runtime = null;
        boolean desktopMode = false;

        try
        {
            desktopMode = ReceiverLaunchMode.select(args, !GraphicsEnvironment.isHeadless()) == ReceiverLaunchMode.DESKTOP;
            Path dataRoot = PortableApplicationPaths.getDataRoot();
            Path databasePath = SdrTrunkDatabasePath.getDatabasePath(dataRoot);

            if(Files.isRegularFile(databasePath) ||
                Files.isRegularFile(databasePath.resolveSibling("managed-recordings.sqlite")))
            {
                dataRootLock = PortableDataRootLock.acquire(dataRoot);
            }

            UserPreferences userPreferences;
            boolean startConfiguredChannels = true;
            boolean openWebAfterSetup = false;
            if(desktopMode)
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

            runtime = new ReceiverRuntime(userPreferences, dataRootLock);
            dataRootLock = null;
            runtime.start();
            if(desktopMode)
                new SDRTrunk(runtime, userPreferences, startConfiguredChannels, openWebAfterSetup);
            else
            {
                runtime.activate();
                if(startConfiguredChannels) runtime.startConfiguredChannels(true);
            }
        }
        catch(Exception e)
        {
            if(runtime != null)
            {
                try { runtime.close(); }
                catch(RuntimeException cleanupFailure) { e.addSuppressed(cleanupFailure); }
            }
            else SqlitePreferencesFactory.shutdown();

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

            if(desktopMode)
            {
                CopyableErrorDialog.show(null, "VCE Startup Error",
                    "VCE could not start. Receiver services were not started.", exceptionMessage(e));
            }

            System.exit(1);
        }
    }
}
