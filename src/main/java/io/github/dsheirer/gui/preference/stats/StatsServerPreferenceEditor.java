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

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.stats.activity.P25ActivityLogMaintenance;
import io.github.dsheirer.stats.activity.P25ActivityLogPath;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Stats collection, retention, and database maintenance preferences.
 */
public class StatsServerPreferenceEditor extends HBox
{
    private final ApplicationPreference mApplicationPreference;
    private final UserPreferences mUserPreferences;
    private GridPane mEditorPane;
    private Spinner<Integer> mRetentionSpinner;
    private CheckBox mLoggingCheckBox;
    private CheckBox mDetailedHistoryCheckBox;
    private Label mMaintenanceStatusLabel;
    private Button mMaintainButton;
    private Button mShrinkButton;
    private Button mCheckButton;
    private Button mResetButton;

    public StatsServerPreferenceEditor(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
        mApplicationPreference = userPreferences.getApplicationPreference();
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
            mEditorPane.add(new Label("Statistics Collection"), 0, row++, 3, 1);
            mEditorPane.add(getLoggingCheckBox(), 0, row++, 3, 1);
            Label featureExplanation = new Label(
                "Summary statistics power Dashboard and directory pages. Detailed history additionally powers " +
                    "Activity pages. The web server, Live Systems, and web audio operate independently.");
            featureExplanation.setWrapText(true);
            mEditorPane.add(featureExplanation, 0, row++, 3, 1);
            mEditorPane.add(getDetailedHistoryCheckBox(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Retain time-based data for"), 0, row);
            GridPane.setHalignment(getRetentionSpinner(), HPos.RIGHT);
            mEditorPane.add(getRetentionSpinner(), 1, row);
            mEditorPane.add(new Label("days"), 2, row++);
            mEditorPane.add(new Label("Database file"), 0, row);
            Label path = new Label(P25ActivityLogPath.getDatabasePath(mUserPreferences).toString());
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
            updateControlState();
        }

        return mEditorPane;
    }

    private CheckBox getLoggingCheckBox()
    {
        if(mLoggingCheckBox == null)
        {
            mLoggingCheckBox = new CheckBox("Collect Summary Statistics");
            mLoggingCheckBox.setTooltip(new Tooltip(
                "Stores compact lifetime and hourly summaries in SQLite for Dashboard and directory pages."));
            mLoggingCheckBox.setSelected(mApplicationPreference.isStatsLoggingEnabled());
            mLoggingCheckBox.setOnAction(event -> {
                mApplicationPreference.setStatsLoggingEnabled(mLoggingCheckBox.isSelected());
                updateControlState();
            });
        }

        return mLoggingCheckBox;
    }

    private CheckBox getDetailedHistoryCheckBox()
    {
        if(mDetailedHistoryCheckBox == null)
        {
            mDetailedHistoryCheckBox = new CheckBox("Store Detailed Event History");
            mDetailedHistoryCheckBox.setTooltip(new Tooltip(
                "Additionally stores individual compact P25 event rows for Activity pages."));
            mDetailedHistoryCheckBox.setSelected(mApplicationPreference.isStatsDetailedHistoryEnabled());
            mDetailedHistoryCheckBox.setOnAction(event ->
                mApplicationPreference.setStatsDetailedHistoryEnabled(mDetailedHistoryCheckBox.isSelected()));
        }

        return mDetailedHistoryCheckBox;
    }

    private Spinner<Integer> getRetentionSpinner()
    {
        if(mRetentionSpinner == null)
        {
            mRetentionSpinner = new Spinner<>(ApplicationPreference.MIN_STATS_LOGGING_RETENTION_DAYS,
                ApplicationPreference.MAX_STATS_LOGGING_RETENTION_DAYS,
                mApplicationPreference.getStatsLoggingRetentionDays(), 1);
            mRetentionSpinner.valueProperty().addListener((observable, oldValue, newValue) ->
                mApplicationPreference.setStatsLoggingRetentionDays(newValue));
        }

        return mRetentionSpinner;
    }

    private Button getMaintainButton()
    {
        if(mMaintainButton == null)
        {
            mMaintainButton = new Button("Run Maintenance");
            mMaintainButton.setTooltip(new Tooltip("Runs retention cleanup, WAL checkpoint, and query optimization."));
            mMaintainButton.setOnAction(event -> run(P25ActivityLogMaintenance.Operation.MAINTAIN));
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
                    run(P25ActivityLogMaintenance.Operation.SHRINK);
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
            mCheckButton.setOnAction(event -> run(P25ActivityLogMaintenance.Operation.CHECK));
        }

        return mCheckButton;
    }

    private Button getResetButton()
    {
        if(mResetButton == null)
        {
            mResetButton = new Button("Reset Lifetime Stats");
            mResetButton.setTooltip(new Tooltip("Deletes Stats Server summaries and history only."));
            mResetButton.setOnAction(event -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "Reset deletes Stats Server summaries and history. SDRTrunk configuration is unchanged. Continue?",
                    ButtonType.YES, ButtonType.NO);
                alert.setHeaderText("Reset Lifetime Stats");
                Optional<ButtonType> result = alert.showAndWait();

                if(result.isPresent() && result.get() == ButtonType.YES)
                {
                    run(P25ActivityLogMaintenance.Operation.RESET_STATS);
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

    private void updateControlState()
    {
        boolean enabled = getLoggingCheckBox().isSelected();
        getDetailedHistoryCheckBox().setDisable(!enabled);
        getRetentionSpinner().setDisable(!enabled);
    }

    private void run(P25ActivityLogMaintenance.Operation operation)
    {
        Path databasePath = P25ActivityLogPath.getDatabasePath(mUserPreferences);
        int retentionDays = mApplicationPreference.getStatsLoggingRetentionDays();
        setMaintenanceRunning(true, operation);

        CompletableFuture.supplyAsync(() -> {
            try
            {
                return P25ActivityLogMaintenance.run(databasePath, retentionDays, operation);
            }
            catch(Exception e)
            {
                throw new RuntimeException(e);
            }
        }).whenComplete((result, throwable) -> Platform.runLater(() -> {
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

    private void setMaintenanceRunning(boolean running, P25ActivityLogMaintenance.Operation operation)
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
