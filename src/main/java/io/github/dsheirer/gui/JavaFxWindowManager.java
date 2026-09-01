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
import io.github.dsheirer.application.update.UpdateCheckResult;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.icon.IconManager;
import io.github.dsheirer.gui.icon.ViewIconManagerRequest;
import io.github.dsheirer.gui.configuration.ConfigurationEditor;
import io.github.dsheirer.gui.configuration.ConfigurationEditorRequest;
import io.github.dsheirer.gui.preference.UserPreferencesEditor;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.gui.preference.encryption.EncryptionKeyPreferenceEditor;
import io.github.dsheirer.gui.preference.encryption.ViewEncryptionKeyPreferenceEditorRequest;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.gui.viewer.MessageRecordingViewer;
import io.github.dsheirer.gui.viewer.ViewRecordingViewerRequest;
import io.github.dsheirer.jmbe.JmbeEditor;
import io.github.dsheirer.jmbe.JmbeEditorRequest;
import io.github.dsheirer.monitor.ResourceMonitor;
import io.github.dsheirer.monitor.StatusBox;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultService;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceDecryptionModuleManager;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.stats.StatsWebNavigationState;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import jiconfont.javafx.IconFontFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java FX window manager.  Handles all secondary Java FX windows that are used within this primarily
 * Swing application.
 */
public class JavaFxWindowManager
{
    private static final Logger mLog = LoggerFactory.getLogger(JavaFxWindowManager.class);
    private static final String LOADING_TEXT_STYLE = "-fx-text-fill: -fx-text-base-color;";
    private static final String LOADING_FAILURE_STYLE = "-fx-text-fill: #ef5350;";

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
    private MessageRecordingViewer mMessageRecordingViewer;

    private Stage mIconManagerStage;
    private Stage mJmbeEditorStage;
    private Stage mConfigurationStage;
    private Stage mEncryptionKeyStage;
    private Stage mUserPreferencesStage;
    private Stage mRecordingViewerStage;
    private JFXPanel mStatusPanel;
    private LoadingShell mConfigurationLoadingShell;
    private LoadingShell mUserPreferencesLoadingShell;
    private final LoadingRequestGate<ConfigurationEditorRequest> mConfigurationEditorLoad =
        new LoadingRequestGate<>();
    private final LoadingRequestGate<ViewUserPreferenceEditorRequest> mUserPreferencesEditorLoad =
        new LoadingRequestGate<>();

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
            mConfigurationEditor = new ConfigurationEditor(mConfigurationManager, mTunerManager, mUserPreferences);
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
            mConfigurationLoadingShell = createLoadingShell("Playlist", "Loading configuration editor…");
            Scene scene = new Scene(mConfigurationLoadingShell.root(), 1000, 750);
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
                Stage stage = getConfigurationStage();

                if(mConfigurationEditor != null)
                {
                    installLoadedContent(mConfigurationLoadingShell, mConfigurationEditor);
                    restoreStage(stage);
                    mConfigurationEditor.process(request);
                }
                else if(mConfigurationEditorLoad.offer(request))
                {
                    showLoadingStage(stage, mConfigurationLoadingShell, mConfigurationEditorLoad,
                        () -> loadConfigurationEditor(stage),
                        "Unable to show Playlist. Click Playlist to retry.");
                }
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
            mUserPreferencesEditor = new UserPreferencesEditor(mUserPreferences);
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
            mUserPreferencesLoadingShell = createLoadingShell("Settings", "Loading user preferences…");
            Scene scene = new Scene(mUserPreferencesLoadingShell.root(), 900, 500);
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
            try
            {
                Stage stage = getUserPreferencesStage();

                if(mUserPreferencesEditor != null)
                {
                    installLoadedContent(mUserPreferencesLoadingShell, mUserPreferencesEditor);
                    restoreStage(stage);
                    mUserPreferencesEditor.process(request);
                }
                else if(mUserPreferencesEditorLoad.offer(request))
                {
                    showLoadingStage(stage, mUserPreferencesLoadingShell, mUserPreferencesEditorLoad,
                        () -> loadUserPreferencesEditor(stage),
                        "Unable to show Settings. Click Settings to retry.");
                }
            }
            catch(Throwable throwable)
            {
                mLog.error("Error processing show user preferences editor request", throwable);
            }
        });
    }

    private void loadConfigurationEditor(Stage stage)
    {
        ConfigurationEditor editor;

        try
        {
            editor = getConfigurationEditor();
            installLoadedContent(mConfigurationLoadingShell, editor);
        }
        catch(Throwable throwable)
        {
            mLog.error("Unable to load the configuration editor", throwable);
            showLoadingFailure(mConfigurationLoadingShell,
                "Unable to load the configuration editor. Click Playlist to retry.");
            mConfigurationEditorLoad.fail();
            finishLoading(stage);
            return;
        }

        try
        {
            ConfigurationEditorRequest request = mConfigurationEditorLoad.complete();

            if(request != null)
            {
                editor.process(request);
            }
        }
        catch(Throwable throwable)
        {
            mLog.error("Unable to process the configuration editor request", throwable);
        }

        finishLoading(stage);
    }

    private void loadUserPreferencesEditor(Stage stage)
    {
        UserPreferencesEditor editor;

        try
        {
            editor = getUserPreferencesEditor();
            installLoadedContent(mUserPreferencesLoadingShell, editor);
        }
        catch(Throwable throwable)
        {
            mLog.error("Unable to load the user preferences editor", throwable);
            showLoadingFailure(mUserPreferencesLoadingShell, "Unable to load Settings. Click Settings to retry.");
            mUserPreferencesEditorLoad.fail();
            finishLoading(stage);
            return;
        }

        try
        {
            ViewUserPreferenceEditorRequest request = mUserPreferencesEditorLoad.complete();

            if(request != null)
            {
                editor.process(request);
            }
        }
        catch(Throwable throwable)
        {
            mLog.error("Unable to process the user preferences editor request", throwable);
        }

        finishLoading(stage);
    }

    private static void finishLoading(Stage stage)
    {
        stage.requestFocus();
        stage.toFront();
    }

    private static LoadingShell createLoadingShell(String title, String message)
    {
        Label titleLabel = new Label(title);
        titleLabel.setFont(Font.font("System", FontWeight.SEMI_BOLD, 20));
        titleLabel.setStyle(LOADING_TEXT_STYLE);
        Label statusLabel = new Label(message);
        statusLabel.setFont(Font.font("System", 13));
        statusLabel.setStyle(LOADING_TEXT_STYLE);
        statusLabel.setOpacity(0.72d);
        VBox content = new VBox(8, titleLabel, statusLabel);
        content.setAlignment(Pos.CENTER);
        StackPane root = new StackPane(content);
        root.setId("javafx-loading-shell");
        root.setPadding(new Insets(24));
        //This wrapper remains the Scene root after the editor replaces the loading content.  Resolve its background
        //through the theme lookup so transparent editor regions follow later light/dark theme changes.
        root.setStyle("-fx-background-color: -fx-background;");
        return new LoadingShell(root, content, titleLabel, statusLabel, message);
    }

    private static void showLoadingStage(Stage stage, LoadingShell loadingShell, LoadingRequestGate<?> loadingGate,
                                         Runnable loader, String failureMessage)
    {
        Consumer<Throwable> failureHandler = throwable ->
            restoreLoadingStageAfterFailure(stage, loadingShell, failureMessage, throwable);

        guardLoadingSetup(loadingGate, () -> {
            showLoadingMessage(loadingShell);
            stage.setOpacity(0.0d);
            stage.setIconified(false);
            stage.show();
            stage.toFront();
            Scene scene = stage.getScene();
            scene.getRoot().applyCss();
            scene.getRoot().layout();

            //Snapshot completion proves the loading shell has completed a CSS/layout/render pass.  Reveal it, then
            //wait one additional rendered frame before starting expensive editor construction on the JavaFX thread.
            scene.snapshot(snapshotResult -> {
                guardLoadingSetup(loadingGate, () -> {
                    stage.setOpacity(1.0d);
                    stage.requestFocus();
                    AnimationTimer firstVisibleFrame = new AnimationTimer()
                    {
                        private int mFrames;

                        @Override
                        public void handle(long now)
                        {
                            if(++mFrames >= 2)
                            {
                                stop();
                                guardLoadingSetup(loadingGate, loader, failureHandler);
                            }
                        }
                    };
                    firstVisibleFrame.start();
                }, failureHandler);
                return null;
            }, null);
        }, failureHandler);
    }

    /**
     * Runs one loading-stage lifecycle action and releases the request gate before reporting a failure.  Keeping this
     * boundary independent from JavaFX lets CI inject failures at the same point as show/CSS/layout/snapshot setup.
     */
    static void guardLoadingSetup(LoadingRequestGate<?> loadingGate, Runnable setup,
                                  Consumer<Throwable> failureHandler)
    {
        try
        {
            setup.run();
        }
        catch(Throwable throwable)
        {
            loadingGate.fail();
            failureHandler.accept(throwable);
        }
    }

    private static void restoreLoadingStageAfterFailure(Stage stage, LoadingShell loadingShell, String failureMessage,
                                                        Throwable throwable)
    {
        mLog.error(failureMessage, throwable);

        //Each recovery action is isolated so a Stage implementation failure cannot leave the window permanently
        //transparent or prevent a later action from making the failure shell visible.
        recoverLoadingStage("restore opacity", () -> stage.setOpacity(1.0d));
        recoverLoadingStage("restore loading content", () -> showLoadingFailure(loadingShell, failureMessage));
        recoverLoadingStage("restore from iconified state", () -> stage.setIconified(false));
        recoverLoadingStage("show failure shell", stage::show);
        recoverLoadingStage("raise failure shell", stage::toFront);
        recoverLoadingStage("focus failure shell", stage::requestFocus);
    }

    private static void recoverLoadingStage(String action, Runnable recovery)
    {
        try
        {
            recovery.run();
        }
        catch(Throwable throwable)
        {
            mLog.warn("Unable to {} after JavaFX editor loading failure", action, throwable);
        }
    }

    private static void showLoadingMessage(LoadingShell loadingShell)
    {
        loadingShell.root().setPadding(new Insets(24));
        loadingShell.root().getChildren().setAll(loadingShell.loadingContent());
        loadingShell.titleLabel().setStyle(LOADING_TEXT_STYLE);
        loadingShell.statusLabel().setStyle(LOADING_TEXT_STYLE);
        loadingShell.statusLabel().setOpacity(0.72d);
        loadingShell.statusLabel().setText(loadingShell.loadingMessage());
    }

    private static void installLoadedContent(LoadingShell loadingShell, Parent content)
    {
        if(loadingShell.root().getChildren().size() == 1 &&
            loadingShell.root().getChildren().getFirst() == content)
        {
            return;
        }

        //Padding belongs to the centered loading message, not to the full-size editor that replaces it.
        loadingShell.root().setPadding(Insets.EMPTY);
        loadingShell.root().getChildren().setAll(content);
        content.applyCss();
        content.layout();
    }

    private static void showLoadingFailure(LoadingShell loadingShell, String message)
    {
        loadingShell.root().setPadding(new Insets(24));
        loadingShell.root().getChildren().setAll(loadingShell.loadingContent());
        loadingShell.titleLabel().setStyle(LOADING_TEXT_STYLE);
        loadingShell.statusLabel().setStyle(LOADING_FAILURE_STYLE);
        loadingShell.statusLabel().setOpacity(1.0d);
        loadingShell.statusLabel().setText(message);
    }

    private record LoadingShell(StackPane root, VBox loadingContent, Label titleLabel, Label statusLabel,
                                String loadingMessage)
    {
    }

    static final class LoadingRequestGate<T>
    {
        private boolean mLoading;
        private T mPendingRequest;

        /**
         * Stores the newest request and reports whether its caller owns the one active load attempt.
         */
        boolean offer(T request)
        {
            mPendingRequest = request;

            if(!mLoading)
            {
                mLoading = true;
                return true;
            }

            return false;
        }

        /**
         * Finishes a successful load and returns only the newest request received while loading.
         */
        T complete()
        {
            mLoading = false;
            T request = mPendingRequest;
            mPendingRequest = null;
            return request;
        }

        /**
         * Releases a failed load so the next request can retry.  Its request is superseded by that retry.
         */
        void fail()
        {
            mLoading = false;
            mPendingRequest = null;
        }
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

}
