/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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
import io.github.dsheirer.application.update.UpdateCheckResult;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.icon.IconManager;
import io.github.dsheirer.gui.icon.ViewIconManagerRequest;
import io.github.dsheirer.gui.configuration.ConfigurationEditor;
import io.github.dsheirer.gui.configuration.ConfigurationEditorRequest;
import io.github.dsheirer.gui.configuration.ViewConfigurationRequest;
import io.github.dsheirer.gui.preference.PreferenceEditorType;
import io.github.dsheirer.gui.preference.UserPreferencesEditor;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.gui.preference.encryption.EncryptionKeyPreferenceEditor;
import io.github.dsheirer.gui.preference.encryption.ViewEncryptionKeyPreferenceEditorRequest;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.gui.viewer.MessageRecordingViewer;
import io.github.dsheirer.gui.viewer.ViewRecordingViewerRequest;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.jmbe.JmbeEditor;
import io.github.dsheirer.jmbe.JmbeEditorRequest;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.monitor.ResourceMonitor;
import io.github.dsheirer.monitor.StatusBox;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultService;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceDecryptionModuleManager;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.stats.StatsWebNavigationState;
import io.github.dsheirer.stats.StatsWebServerService;
import java.net.URI;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.stage.Stage;
import jiconfont.javafx.IconFontFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java FX window manager.  Handles all secondary Java FX windows that are used within this primarily
 * Swing application.
 */
public class JavaFxWindowManager extends Application
{
    private static final Logger mLog = LoggerFactory.getLogger(JavaFxWindowManager.class);

    public static final String ICON_MANAGER = "iconmanager";
    public static final String CONFIGURATION_EDITOR = "configuration";
    public static final String ENCRYPTION_KEY_EDITOR = "encryptionkeys";
    public static final String USER_PREFERENCES_EDITOR = "preferences";
    public static final String STAGE_MONITOR_KEY_CALIBRATION_DIALOG = "calibration.dialog";
    public static final String STAGE_MONITOR_KEY_RECORDING_VIEWER = "recording.viewer";
    public static final String STAGE_MONITOR_KEY_ICON_MANAGER_EDITOR = "icon.manager";
    public static final String STAGE_MONITOR_KEY_JMBE_EDITOR = "jmbe.editor";
    public static final String STAGE_MONITOR_KEY_CONFIGURATION_EDITOR = "configuration";
    public static final String STAGE_MONITOR_KEY_ENCRYPTION_KEY_EDITOR = "encryption.keys";
    public static final String STAGE_MONITOR_KEY_USER_PREFERENCES_EDITOR = "user.preferences";

    private static final AtomicBoolean FX_TOOLKIT_STARTED = new AtomicBoolean();
    private IconManager mIconManager;
    private JmbeEditor mJmbeEditor;
    private ConfigurationEditor mConfigurationEditor;
    private ConfigurationManager mConfigurationManager;
    private TunerManager mTunerManager;
    private UserPreferences mUserPreferences;
    private EncryptionKeyPreferenceEditor mEncryptionKeyPreferenceEditor;
    private UserPreferencesEditor mUserPreferencesEditor;
    private volatile StatsWebServerService mStatsWebServerService;
    private MessageRecordingViewer mMessageRecordingViewer;

    private Stage mIconManagerStage;
    private Stage mJmbeEditorStage;
    private Stage mConfigurationStage;
    private Stage mEncryptionKeyStage;
    private Stage mUserPreferencesStage;
    private Stage mRecordingViewerStage;
    private JFXPanel mStatusPanel;

    /**
     * Constructs an instance.  Note: this constructor is used for Swing applications.
     */
    public JavaFxWindowManager(UserPreferences userPreferences, TunerManager tunerManager, ConfigurationManager configurationManager)
    {
        mUserPreferences = userPreferences;
        mTunerManager = tunerManager;
        mConfigurationManager = configurationManager;

        setup();
    }

    /**
     * Constructs an instance.  Note: this constructor is used for standalone JavaFX application testing
     */
    public JavaFxWindowManager()
    {
        mUserPreferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        EventLogManager eventLogManager = new EventLogManager(aliasModel, mUserPreferences);
        mTunerManager = new TunerManager(mUserPreferences);
        mTunerManager.start();
        mConfigurationManager = new ConfigurationManager(mUserPreferences, mTunerManager, aliasModel, eventLogManager, new IconModel());
        mConfigurationManager.init();
        setup();
    }

    /**
     * Connects the preferences window to the live embedded web-server service after application startup has created
     * it. The preferences editor is normally lazy, but an already-created editor is updated as well.
     */
    public void setStatsWebServerService(StatsWebServerService statsWebServerService)
    {
        mStatsWebServerService = statsWebServerService;

        if(mConfigurationEditor != null)
        {
            execute(() -> {
                if(mConfigurationEditor != null)
                {
                    mConfigurationEditor.setStatsWebServerService(statsWebServerService);
                }
            });
        }

        if(mUserPreferencesEditor != null)
        {
            execute(() -> {
                if(mUserPreferencesEditor != null)
                {
                    mUserPreferencesEditor.setStatsWebServerService(statsWebServerService);
                }
            });
        }
    }

    /**
     * Creates or accesses the JavaFX status panel, used by the main application GUI.
     * @param resourceMonitor for statistics
     * @return JFXPanel accessible on Swing thread that delegates JavaFX scene creation to the FX event thread.
     */
    public JFXPanel getStatusPanel(ResourceMonitor resourceMonitor, EncryptionKeyVaultService vaultService,
                                   VoiceDecryptionModuleManager moduleManager,
                                   Supplier<StatsWebNavigationState> navigationStateSupplier,
                                   Supplier<UpdateCheckResult> updateResultSupplier,
                                   Consumer<URI> updateReleasePageConsumer)
    {
        if(mStatusPanel == null)
        {
            mStatusPanel = new JFXPanel();

            //JFXPanel has to be populated on the FX event thread
            Platform.runLater(() -> {
                Scene scene = new Scene(new StatusBox(resourceMonitor, vaultService, moduleManager,
                    navigationStateSupplier, updateResultSupplier, updateReleasePageConsumer));
                ThemeManager.getInstance().register(scene);
                mStatusPanel.setScene(scene);
            });
        }

        return mStatusPanel;
    }

    private void setup()
    {
        //Register this class to receive events via each method annotated with @Subscribe
        MyEventBus.getGlobalEventBus().register(this);

        //Register JavaFX icon fonts
        IconFontFX.register(jiconfont.icons.font_awesome.FontAwesome.getIconFont());
        ApplicationIcon.applyTaskbarIcon();

        initFxToolkit();
    }

    /**
     * Executes the runnable on the JavaFX application thread
     */
    private void execute(Runnable runnable)
    {
        if(Platform.isFxApplicationThread())
        {
            runnable.run();
        }
        else
        {
            Platform.runLater(runnable);
        }
    }

    /**
     * Initializes the JavaFX toolkit without creating an unparented JFXPanel.  An unparented JFXPanel on macOS
     * causes the CVDisplayLink pulse timer to repeatedly spawn and destroy OS threads (~1/second) because the
     * panel has no display connection.  Using Platform.startup() avoids this entirely.
     */
    private void initFxToolkit()
    {
        if(FX_TOOLKIT_STARTED.compareAndSet(false, true))
        {
            Platform.startup(() -> Platform.setImplicitExit(false));
        }
    }

    /**
     * Removes monitoring for all JavaFX stages and shuts down the FX thread, killing all FX windows.
     */
    public void shutdown()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
        mUserPreferences.getJavaFxPreferences().clearStageMonitors();
        Platform.exit();
    }

    /**
     * Stage for the recording viewer
     */
    public Stage getRecordingViewerStage()
    {
        if(mRecordingViewerStage == null)
        {
            Scene scene = new Scene(getRecordingViewer(), 1100, 800);
            ThemeManager.getInstance().register(scene);
            mRecordingViewerStage = new Stage();
            mRecordingViewerStage.setTitle("sdrtrunk-vce - Message Recording Viewer (.bits)");
            mRecordingViewerStage.setScene(scene);
            ApplicationIcon.apply(mRecordingViewerStage);
            mUserPreferences.getJavaFxPreferences().monitor(mRecordingViewerStage, STAGE_MONITOR_KEY_RECORDING_VIEWER);
        }

        return mRecordingViewerStage;
    }

    public MessageRecordingViewer getRecordingViewer()
    {
        if(mMessageRecordingViewer == null)
        {
            mMessageRecordingViewer = new MessageRecordingViewer();
        }

        return mMessageRecordingViewer;
    }

    public Stage getIconManagerStage()
    {
        if(mIconManagerStage == null)
        {
            Scene scene = new Scene(getIconManager(), 500, 500);
            ThemeManager.getInstance().register(scene);
            mIconManagerStage = new Stage();
            mIconManagerStage.setTitle("sdrtrunk-vce - Icon Manager");
            mIconManagerStage.setScene(scene);
            ApplicationIcon.apply(mIconManagerStage);
            mUserPreferences.getJavaFxPreferences().monitor(mIconManagerStage, STAGE_MONITOR_KEY_ICON_MANAGER_EDITOR);
        }

        return mIconManagerStage;
    }

    public IconManager getIconManager()
    {
        if(mIconManager == null)
        {
            mIconManager = new IconManager(mConfigurationManager.getIconModel());
        }

        return mIconManager;
    }

    /**
     * Processes a JMBE s editor request
     */
    @Subscribe
    public void process(final JmbeEditorRequest request)
    {
        if(request.isCloseEditorRequest())
        {
            execute(() -> {
                getJmbeEditorStage().hide();
                mJmbeEditorStage = null;
                mJmbeEditor = null;
            });
        }
        else
        {
            execute(() -> {
                restoreStage(getJmbeEditorStage());
                getJmbeEditor().process(request);
            });
        }
    }

    public Stage getJmbeEditorStage()
    {
        if(mJmbeEditorStage == null)
        {
            Scene scene = new Scene(getJmbeEditor(), 650, 650);
            ThemeManager.getInstance().register(scene);
            mJmbeEditorStage = new Stage();
            mJmbeEditorStage.setTitle("sdrtrunk-vce - JMBE Library Updater");
            mJmbeEditorStage.setScene(scene);
            ApplicationIcon.apply(mJmbeEditorStage);
            mUserPreferences.getJavaFxPreferences().monitor(mJmbeEditorStage, STAGE_MONITOR_KEY_JMBE_EDITOR);
        }

        return mJmbeEditorStage;
    }

    public JmbeEditor getJmbeEditor()
    {
        if(mJmbeEditor == null)
        {
            mJmbeEditor = new JmbeEditor(mUserPreferences);
        }

        return mJmbeEditor;
    }

    /**
     * Lazy construct and access the configuration editor
     */
    public ConfigurationEditor getConfigurationEditor()
    {
        if(mConfigurationEditor == null)
        {
            mConfigurationEditor = new ConfigurationEditor(mConfigurationManager, mTunerManager, mUserPreferences,
                mStatsWebServerService);
        }

        return mConfigurationEditor;
    }

    /**
     * Access the configuration editor stage.
     */
    private Stage getConfigurationStage()
    {
        if(mConfigurationStage == null)
        {
            Scene scene = new Scene(getConfigurationEditor(), 1000, 750);
            ThemeManager.getInstance().register(scene);
            mConfigurationStage = new Stage();
            mConfigurationStage.setTitle("sdrtrunk-vce - Configuration Editor");
            mConfigurationStage.setScene(scene);
            ApplicationIcon.apply(mConfigurationStage);
            mUserPreferences.getJavaFxPreferences().monitor(mConfigurationStage, STAGE_MONITOR_KEY_CONFIGURATION_EDITOR);
        }

        return mConfigurationStage;
    }

    /**
     * Processes a configuration editor request and brings the configuration editor into focus
     */
    @Subscribe
    public void process(ConfigurationEditorRequest request)
    {
        execute(() -> {
            try
            {
                restoreStage(getConfigurationStage());
                getConfigurationEditor().process(request);
            }
            catch(Throwable t)
            {
                mLog.error("Error processing show configuration editor request", t);
            }
        });
    }

    /**
     * User Preferences Editor
     */
    private UserPreferencesEditor getUserPreferencesEditor()
    {
        if(mUserPreferencesEditor == null)
        {
            mUserPreferencesEditor = new UserPreferencesEditor(mUserPreferences, mStatsWebServerService);
        }

        return mUserPreferencesEditor;
    }

    /**
     * User Preferences Stage
     */
    private Stage getUserPreferencesStage()
    {
        if(mUserPreferencesStage == null)
        {
            Scene scene = new Scene(getUserPreferencesEditor(), 900, 500);
            ThemeManager.getInstance().register(scene);
            mUserPreferencesStage = new Stage();
            mUserPreferencesStage.setTitle("sdrtrunk-vce - User Preferences");
            mUserPreferencesStage.setScene(scene);
            ApplicationIcon.apply(mUserPreferencesStage);
            mUserPreferences.getJavaFxPreferences().monitor(mUserPreferencesStage, STAGE_MONITOR_KEY_USER_PREFERENCES_EDITOR);
        }

        return mUserPreferencesStage;
    }

    /**
     * Processes a user preferences editor request
     */
    @Subscribe
    public void process(final ViewUserPreferenceEditorRequest request)
    {
        execute(() -> {
            restoreStage(getUserPreferencesStage());
            getUserPreferencesEditor().process(request);
        });
    }

    /**
     * Voice encryption key editor.
     */
    private EncryptionKeyPreferenceEditor getEncryptionKeyPreferenceEditor()
    {
        if(mEncryptionKeyPreferenceEditor == null)
        {
            mEncryptionKeyPreferenceEditor = new EncryptionKeyPreferenceEditor(mUserPreferences);
        }

        return mEncryptionKeyPreferenceEditor;
    }

    /**
     * Voice encryption key editor stage.
     */
    private Stage getEncryptionKeyStage()
    {
        if(mEncryptionKeyStage == null)
        {
            Scene scene = new Scene(getEncryptionKeyPreferenceEditor(), 900, 500);
            ThemeManager.getInstance().register(scene);
            mEncryptionKeyStage = new Stage();
            mEncryptionKeyStage.setTitle("sdrtrunk-vce - Encryption Keys");
            mEncryptionKeyStage.setScene(scene);
            ApplicationIcon.apply(mEncryptionKeyStage);
            mUserPreferences.getJavaFxPreferences().monitor(mEncryptionKeyStage, STAGE_MONITOR_KEY_ENCRYPTION_KEY_EDITOR);
        }

        return mEncryptionKeyStage;
    }

    /**
     * Processes a voice encryption key editor request.
     */
    @Subscribe
    public void process(final ViewEncryptionKeyPreferenceEditorRequest request)
    {
        if(!mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded())
        {
            return;
        }

        execute(() -> {
            restoreStage(getEncryptionKeyStage());
            getEncryptionKeyPreferenceEditor().activate();
        });
    }

    @Subscribe
    public void process(final ViewIconManagerRequest request)
    {
        execute(() -> restoreStage(getIconManagerStage()));
    }

    /**
     * Process a recording viewer request
     */
    @Subscribe
    public void process(final ViewRecordingViewerRequest request)
    {
        execute(() -> restoreStage(getRecordingViewerStage()));
    }

    /**
     * Restores the stage to previous size and location.
     * @param stage to restore.
     */
    private void restoreStage(Stage stage)
    {
        stage.setIconified(false);
        stage.show();
        stage.requestFocus();
        stage.toFront();
    }

    @Override
    public void start(Stage primaryStage) throws Exception
    {
        mLog.debug("Starting ...");
        Parameters parameters = getParameters();
        mLog.debug("Parameters: " + (parameters != null));

        boolean valid = false;

        if(parameters != null && parameters.getRaw().size() == 1)
        {
            String window = parameters.getRaw().get(0);

            if(window != null)
            {
                switch(window)
                {
                    case ICON_MANAGER:
                        valid = true;
                        process(new ViewIconManagerRequest());
                        break;
                    case CONFIGURATION_EDITOR:
                        valid = true;
                        process(new ViewConfigurationRequest());
                        break;
                    case ENCRYPTION_KEY_EDITOR:
                        valid = true;
                        process(new ViewEncryptionKeyPreferenceEditorRequest());
                        break;
                    case USER_PREFERENCES_EDITOR:
                        valid = true;
                        process(new ViewUserPreferenceEditorRequest(PreferenceEditorType.DEFAULT));
                        break;
                    default:
                        break;
                }
            }
        }

        if(!valid)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("An argument is required to launch JavaFX windows from this window manager.  " +
                "Valid options are:\n\ticonmanager\tIcon Manager\n\tconfiguration\tConfiguration Editor\n" +
                "\tencryptionkeys\tEncryption Keys\n\tpreferences\tUser Preferences Editor\n");
            sb.append("Supplied Argument(s): ").append(parameters.getRaw());

            mLog.error(sb.toString());
        }
    }

    public static void main(String[] args)
    {
        mLog.info("Application Start - Parameters: " + args);
        launch(args);
    }
}
