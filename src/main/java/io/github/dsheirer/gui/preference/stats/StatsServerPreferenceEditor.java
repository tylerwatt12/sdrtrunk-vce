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
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.activity.ReceiverActivityMaintenance;
import io.github.dsheirer.stats.activity.ReceiverActivityPath;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

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

    public StatsServerPreferenceEditor(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
        setMaxWidth(Double.MAX_VALUE);
        VBox vbox = new VBox(getEditorPane());
        vbox.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        HBox.setHgrow(vbox, Priority.ALWAYS);
        getChildren().add(vbox);
    }

    private GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            int row = 0;
            mEditorPane = new GridPane();
            mEditorPane.setMaxWidth(Double.MAX_VALUE);
            mEditorPane.setVgap(10);
            mEditorPane.setHgap(8);
            mEditorPane.setPadding(new Insets(10));
            mEditorPane.add(new Label("Local Statistics Database Maintenance"), 0, row++, 3, 1);
            Label settingsNote = new Label("Collection and retention settings are available in the web interface.");
            settingsNote.setWrapText(true);
            mEditorPane.add(settingsNote, 0, row++, 3, 1);
            mEditorPane.add(new Label("Database file"), 0, row);
            Label path = new Label(ReceiverActivityPath.getDatabasePath(mUserPreferences).toString());
            path.setWrapText(true);
            mEditorPane.add(path, 1, row++, 2, 1);
            mEditorPane.add(new Label("Database maintenance"), 0, row);
            mEditorPane.add(new HBox(8, getMaintainButton(), getShrinkButton(), getCheckButton(), getResetButton()),
                1, row++, 2, 1);
            mEditorPane.add(getMaintenanceStatusLabel(), 1, row, 2, 1);

            ColumnConstraints labelColumn = new ColumnConstraints();
            labelColumn.setPercentWidth(30);
            ColumnConstraints valueColumn = new ColumnConstraints();
            valueColumn.setHgrow(Priority.ALWAYS);
            mEditorPane.getColumnConstraints().addAll(labelColumn, valueColumn);
        }

        return mEditorPane;
    }

    private Button getMaintainButton()
    {
        if(mMaintainButton == null)
        {
            mMaintainButton = new Button("Run Maintenance");
            mMaintainButton.setTooltip(new Tooltip("Runs retention cleanup, WAL checkpoint, and query optimization."));
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
                    "Shrink can take time and temporarily needs extra disk space. Continue?",
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
            mCheckButton.setTooltip(new Tooltip("Runs SQLite quick_check and reports the result."));
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
                    "Reset deletes Stats Server summaries and history. VCE configuration is unchanged. Continue?",
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
        setMaintenanceRunning(true, operation);
        MyEventBus.getGlobalEventBus().post(request);

        request.result().whenComplete((result, throwable) -> Platform.runLater(() -> {
            setMaintenanceRunning(false, operation);

            if(throwable != null)
            {
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                getMaintenanceStatusLabel().setText(operation + " failed: " + cause.getMessage());
            }
            else
            {
                getMaintenanceStatusLabel().setText(result.summary());
            }
        }));
    }

    private void setMaintenanceRunning(boolean running, ReceiverActivityMaintenance.Operation operation)
    {
        getMaintainButton().setDisable(running);
        getShrinkButton().setDisable(running);
        getCheckButton().setDisable(running);
        getResetButton().setDisable(running);

        if(running)
        {
            getMaintenanceStatusLabel().setText(operation + " running...");
        }
    }
}
