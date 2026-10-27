/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.gui.preference.stats;

import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.activity.ReceiverActivityMaintenance;
import io.github.dsheirer.stats.activity.ReceiverActivityPath;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest;
import java.util.Optional;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * Local-only statistics database maintenance. Operational collection and retention settings live in the web UI.
 */
public class StatsServerPreferenceEditor extends HBox
{
    private final UserPreferences mUserPreferences;
    private GridPane mEditorPane;
    private Label mMaintenanceStatusLabel;
    private Button mMaintainButton;
    private Button mShrinkButton;
    private Button mCheckButton;
    private Button mResetButton;
    private Button mStopButton;
    private StatsDatabaseMaintenanceRequest mActiveRequest;

    public StatsServerPreferenceEditor(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
        setMaxWidth(Double.MAX_VALUE);
        ScrollPane content = new ScrollPane(getEditorPane());
        content.setFitToWidth(true);
        content.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        HBox.setHgrow(content, Priority.ALWAYS);
        getChildren().add(content);
    }

    private GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            int row = 0;
            mEditorPane = new GridPane();
            mEditorPane.setMaxWidth(Double.MAX_VALUE);
            mEditorPane.setVgap(8);
            mEditorPane.setPadding(new Insets(10));
            Label heading = new Label("Local statistics database");
            heading.setStyle("-fx-font-weight: bold; -fx-font-size: 1.08em;");
            mEditorPane.add(heading, 0, row++);
            Label settingsNote = new Label("Collection and retention settings are available in the web interface.");
            settingsNote.setWrapText(true);
            settingsNote.setMinWidth(0);
            mEditorPane.add(settingsNote, 0, row++);
            mEditorPane.add(new Label("Database file"), 0, row++);
            Label path = new Label(SdrTrunkDatabase.historyPath(
                ReceiverActivityPath.getDatabasePath(mUserPreferences)).toString());
            path.setWrapText(true);
            path.setMinWidth(0);
            path.setTooltip(new Tooltip(path.getText()));
            mEditorPane.add(path, 0, row++);
            mEditorPane.add(new Label("Maintenance actions"), 0, row++);
            FlowPane actions = new FlowPane(8, 8, getMaintainButton(), getCheckButton(), getShrinkButton(),
                getResetButton(), getStopButton());
            actions.setPrefWrapLength(320);
            mEditorPane.add(actions, 0, row++);
            mEditorPane.add(getMaintenanceStatusLabel(), 0, row);
        }

        return mEditorPane;
    }

    private Button getMaintainButton()
    {
        if(mMaintainButton == null)
        {
            mMaintainButton = new Button("Run Maintenance");
            mMaintainButton.setTooltip(new Tooltip("Removes one bounded batch of expired statistics."));
            mMaintainButton.setOnAction(event -> run(ReceiverActivityMaintenance.Operation.MAINTAIN));
        }

        return mMaintainButton;
    }

    private Button getShrinkButton()
    {
        if(mShrinkButton == null)
        {
            mShrinkButton = new Button("Shrink Database");
            mShrinkButton.setTooltip(new Tooltip("Rebuilds the database to reclaim unused disk space."));
            mShrinkButton.setOnAction(event -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "Shrink rebuilds the statistics database and can delay saving new activity. " +
                        "VCE checks for enough temporary disk space before starting. Continue?",
                    ButtonType.YES, ButtonType.NO);
                alert.setHeaderText("Shrink Stats Database");
                Optional<ButtonType> result = alert.showAndWait();

                if(result.isPresent() && result.get() == ButtonType.YES)
                {
                    run(ReceiverActivityMaintenance.Operation.SHRINK);
                }
            });
        }

        return mShrinkButton;
    }

    private Button getCheckButton()
    {
        if(mCheckButton == null)
        {
            mCheckButton = new Button("Check Database");
            mCheckButton.setTooltip(new Tooltip("Checks the full statistics database while activity continues to be saved. You can stop the check."));
            mCheckButton.setOnAction(event -> run(ReceiverActivityMaintenance.Operation.CHECK));
        }

        return mCheckButton;
    }

    private Button getResetButton()
    {
        if(mResetButton == null)
        {
            mResetButton = new Button("Reset Lifetime Stats");
            mResetButton.setTooltip(new Tooltip(
                "Deletes P25, DMR, and NXDN Stats Server summaries and history only."));
            mResetButton.setOnAction(event -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "Reset deletes saved statistics and history. Statistics pause until it finishes; activity during " +
                        "this pause is not saved. Receiving, audio, and uploads continue. " +
                        "VCE configuration is unchanged. Stopping waits for the current table or step; " +
                        "completed steps remain cleared. Continue?",
                    ButtonType.YES, ButtonType.NO);
                alert.setHeaderText("Reset Lifetime Stats");
                Optional<ButtonType> result = alert.showAndWait();

                if(result.isPresent() && result.get() == ButtonType.YES)
                {
                    run(ReceiverActivityMaintenance.Operation.RESET_STATS);
                }
            });
        }

        return mResetButton;
    }

    private Button getStopButton()
    {
        if(mStopButton == null)
        {
            mStopButton = new Button("Stop");
            mStopButton.setDisable(true);
            mStopButton.setOnAction(event -> {
                if(mActiveRequest != null) mActiveRequest.cancel();
                mStopButton.setDisable(true);
                getMaintenanceStatusLabel().setText("Stopping after the current database step finishes...");
            });
        }
        return mStopButton;
    }

    private Label getMaintenanceStatusLabel()
    {
        if(mMaintenanceStatusLabel == null)
        {
            mMaintenanceStatusLabel = new Label("Idle");
            mMaintenanceStatusLabel.setWrapText(true);
            mMaintenanceStatusLabel.setMaxWidth(Double.MAX_VALUE);
        }

        return mMaintenanceStatusLabel;
    }

    private void run(ReceiverActivityMaintenance.Operation operation)
    {
        StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.forOperation(operation);
        mActiveRequest = request;
        setMaintenanceRunning(true, operation);
        Timeline progress = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            if(operation == ReceiverActivityMaintenance.Operation.RESET_STATS)
                getMaintenanceStatusLabel().setText((request.cancelled() ?
                    "Stopping after the current table or step. " : request.resetWaitingForReaders() ?
                    "Waiting for saved activity readers to finish. " : "") + "Statistics paused for reset. Cleared " +
                    request.progress().rowsDeleted() + " rows in " + request.progress().batchesCompleted() +
                    " steps. Skipped " + request.observationsSkipped() + " observations. " +
                    "Receiving, audio, and uploads continue.");
        }));
        progress.setCycleCount(Timeline.INDEFINITE);
        progress.play();
        MyEventBus.getGlobalEventBus().post(request);

        request.result().whenComplete((result, throwable) -> Platform.runLater(() -> {
            progress.stop();
            mActiveRequest = null;
            setMaintenanceRunning(false, operation);
            String resetStatus = operation == ReceiverActivityMaintenance.Operation.RESET_STATS &&
                request.observationsSkipped() > 0 ? " Skipped " + request.observationsSkipped() +
                " observations during the statistics pause." : "";

            if(throwable != null)
            {
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                getMaintenanceStatusLabel().setText(operation + " failed: " + cause.getMessage() +
                    (request.progress().batchesCompleted() > 0 ? " Completed steps remain cleared (" +
                        request.progress().rowsDeleted() + " rows)." : "") + resetStatus);
            }
            else
            {
                getMaintenanceStatusLabel().setText(result.summary() + resetStatus);
            }
        }));
    }

    private void setMaintenanceRunning(boolean running, ReceiverActivityMaintenance.Operation operation)
    {
        getMaintainButton().setDisable(running);
        getShrinkButton().setDisable(running);
        getCheckButton().setDisable(running);
        getResetButton().setDisable(running);
        getStopButton().setDisable(!running || (operation != ReceiverActivityMaintenance.Operation.CHECK &&
            operation != ReceiverActivityMaintenance.Operation.RESET_STATS));

        if(running)
        {
            getMaintenanceStatusLabel().setText(operation == ReceiverActivityMaintenance.Operation.CHECK ?
                "Checking the full database. Activity continues to be saved. This may take several minutes." :
                operation == ReceiverActivityMaintenance.Operation.RESET_STATS ?
                    "Statistics paused for reset. Receiving, audio, and uploads continue." : operation + " running...");
        }
    }
}
