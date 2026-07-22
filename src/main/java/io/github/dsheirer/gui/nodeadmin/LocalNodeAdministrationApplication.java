/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.nodeadmin;

import io.github.dsheirer.gui.ApplicationIcon;
import io.github.dsheirer.gui.preference.stats.WebServerPreferenceEditor;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.preference.application.ApplicationPreference;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * Focused local maintenance window for settings that must never be exposed as unauthenticated web mutations.
 *
 * <p>This first slice contains only the embedded web-server and single-administrator settings.  It deliberately does
 * not construct tuner, decoder, audio, recording, streaming, or web-server runtime services.</p>
 */
public final class LocalNodeAdministrationApplication extends Application
{
    private static final String TITLE = "sdrtrunk-vce - Local Web Server Settings";
    private WebServerPreferenceEditor mEditor;
    private Label mExplanation;

    public static void launchApplication(String[] arguments)
    {
        Application.launch(LocalNodeAdministrationApplication.class, arguments);
    }

    @Override
    public void start(Stage stage)
    {
        ApplicationPreference applicationPreference = new ApplicationPreference(ignored -> {});
        BorderPane root = new BorderPane();
        mExplanation = new Label(
            "Local maintenance mode is active. Radio receivers, decoders, audio, streaming, and the web server are " +
                "not running. Configure the web server and its one administrator account here, close this window, " +
                "then start sdrtrunk-vce normally.");
        mExplanation.setWrapText(true);
        mExplanation.setPadding(new Insets(12, 12, 4, 12));
        root.setTop(mExplanation);
        mEditor = new WebServerPreferenceEditor(applicationPreference, SdrTrunkDatabasePath.getDatabasePath(),
            WebServerPreferenceEditor.RuntimeMode.LOCAL_MAINTENANCE);
        ScrollPane scrollPane = new ScrollPane(mEditor);
        scrollPane.setFitToWidth(true);
        root.setCenter(scrollPane);

        stage.setTitle(TITLE);
        stage.setScene(new Scene(root, 880, 720));
        stage.setMinWidth(680);
        stage.setMinHeight(520);
        ApplicationIcon.apply(stage);
        stage.setOnCloseRequest(event -> {
            if(mEditor.isAdminOperationRunning())
            {
                event.consume();
                mExplanation.setText("Please wait for the administrator account operation to finish before closing " +
                    "Local Web Server Settings.");
            }
        });
        stage.show();
        stage.requestFocus();
        stage.toFront();
    }

    @Override
    public void stop()
    {
        if(mEditor != null)
        {
            mEditor.awaitAdminOperationCompletion();
        }
    }
}
