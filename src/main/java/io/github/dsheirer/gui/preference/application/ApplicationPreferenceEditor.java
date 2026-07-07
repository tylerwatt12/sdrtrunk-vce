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

package io.github.dsheirer.gui.preference.application;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.radioresolve.activitylog.P25ActivityLogMaintenance;
import io.github.dsheirer.radioresolve.activitylog.P25ActivityLogPath;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.controlsfx.control.ToggleSwitch;


/**
 * Preference settings for application
 */
public class ApplicationPreferenceEditor extends HBox
{
    private ApplicationPreference mApplicationPreference;
    private UserPreferences mUserPreferences;
    private GridPane mEditorPane;
    private Label mAutoStartTimeoutLabel;
    private Label mStatsLoggingPathLabel;
    private Label mStatsMaintenanceStatusLabel;
    private Spinner<Integer> mTimeoutSpinner;
    private Spinner<Integer> mStatsLoggingRetentionSpinner;
    private ToggleSwitch mAutomaticDiagnosticMonitoringToggle;
    private CheckBox mStatsLoggingCheckBox;
    private Button mStatsMaintenanceButton;
    private Button mStatsShrinkButton;
    private Button mStatsCheckButton;

    /**
     * Constructs an instance
     * @param userPreferences for obtaining reference to preference.
     */
    public ApplicationPreferenceEditor(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
        mApplicationPreference = userPreferences.getApplicationPreference();
        setMaxWidth(Double.MAX_VALUE);

        VBox vbox = new VBox();
        vbox.setMaxHeight(Double.MAX_VALUE);
        vbox.setMaxWidth(Double.MAX_VALUE);
        vbox.getChildren().add(getEditorPane());
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
            mEditorPane.setHgap(3);
            mEditorPane.setPadding(new Insets(10, 10, 10, 10));

            Label monitoringLabel = new Label("Application Health and Diagnostic Monitoring.");
            mEditorPane.add(monitoringLabel, 0, row, 2, 1);
            GridPane.setHalignment(getAutomaticDiagnosticMonitoringToggle(), HPos.RIGHT);
            mEditorPane.add(getAutomaticDiagnosticMonitoringToggle(), 0, ++row);
            mEditorPane.add(new Label("Enable Diagnostic Monitoring"), 1, row, 2, 1);

            Separator separator = new Separator(Orientation.HORIZONTAL);
            GridPane.setHgrow(separator, Priority.ALWAYS);
            mEditorPane.add(separator, 0, ++row, 3, 1);

            mEditorPane.add(getAutoStartTimeoutLabel(), 0, ++row, 2, 1);
            GridPane.setHalignment(getTimeoutSpinner(), HPos.RIGHT);
            mEditorPane.add(getTimeoutSpinner(), 0, ++row);
            mEditorPane.add(new Label("seconds"), 1, row);

            Separator statsSeparator = new Separator(Orientation.HORIZONTAL);
            GridPane.setHgrow(statsSeparator, Priority.ALWAYS);
            mEditorPane.add(statsSeparator, 0, ++row, 3, 1);

            Label statsLabel = new Label("Stats Server");
            mEditorPane.add(statsLabel, 0, ++row, 2, 1);
            mEditorPane.add(getStatsLoggingCheckBox(), 0, ++row, 3, 1);

            mEditorPane.add(new Label("Keep history for"), 0, ++row);
            GridPane.setHalignment(getStatsLoggingRetentionSpinner(), HPos.RIGHT);
            mEditorPane.add(getStatsLoggingRetentionSpinner(), 1, row);
            mEditorPane.add(new Label("days"), 2, row);

            mEditorPane.add(new Label("Database file"), 0, ++row);
            mEditorPane.add(getStatsLoggingPathLabel(), 1, row, 2, 1);

            HBox maintenanceButtons = new HBox(8, getStatsMaintenanceButton(), getStatsShrinkButton(),
                getStatsCheckButton());
            mEditorPane.add(new Label("Database maintenance"), 0, ++row);
            mEditorPane.add(maintenanceButtons, 1, row, 2, 1);

            mEditorPane.add(getStatsMaintenanceStatusLabel(), 1, ++row, 2, 1);

            ColumnConstraints c1 = new ColumnConstraints();
            c1.setPercentWidth(30);
            ColumnConstraints c2 = new ColumnConstraints();
            c2.setHgrow(Priority.ALWAYS);
            mEditorPane.getColumnConstraints().addAll(c1, c2);
            updateStatsLoggingControlState();
        }

        return mEditorPane;
    }

    private Label getAutoStartTimeoutLabel()
    {
        if(mAutoStartTimeoutLabel == null)
        {
            mAutoStartTimeoutLabel = new Label("Channel Auto-Start Timeout");
        }

        return mAutoStartTimeoutLabel;
    }

    /**
     * Spinner to select channel auto-start timeout value in range 0-30 seconds.
     * @return spinner
     */
    private Spinner<Integer> getTimeoutSpinner()
    {
        if(mTimeoutSpinner == null)
        {
            mTimeoutSpinner = new Spinner<>(0, 30, mApplicationPreference.getChannelAutoStartTimeout(), 1);
            mTimeoutSpinner.valueProperty().addListener((observable, oldValue, newValue) -> mApplicationPreference.setChannelAutoStartTimeout(newValue));
        }

        return mTimeoutSpinner;
    }

    private CheckBox getStatsLoggingCheckBox()
    {
        if(mStatsLoggingCheckBox == null)
        {
            mStatsLoggingCheckBox = new CheckBox("Enable Stats Logging");
            mStatsLoggingCheckBox.setTooltip(new Tooltip(
                "Stores P25 activity, radio, talkgroup, frequency, site, network, and writer status history in SQLite."));
            mStatsLoggingCheckBox.setSelected(mApplicationPreference.isStatsLoggingEnabled());
            mStatsLoggingCheckBox.setOnAction(event -> {
                mApplicationPreference.setStatsLoggingEnabled(mStatsLoggingCheckBox.isSelected());
                updateStatsLoggingControlState();
            });
        }

        return mStatsLoggingCheckBox;
    }

    /**
     * Spinner to select P25 activity retention in days.
     */
    private Spinner<Integer> getStatsLoggingRetentionSpinner()
    {
        if(mStatsLoggingRetentionSpinner == null)
        {
            mStatsLoggingRetentionSpinner = new Spinner<>(
                ApplicationPreference.MIN_STATS_LOGGING_RETENTION_DAYS,
                ApplicationPreference.MAX_STATS_LOGGING_RETENTION_DAYS,
                mApplicationPreference.getStatsLoggingRetentionDays(), 1);
            mStatsLoggingRetentionSpinner.valueProperty().addListener((observable, oldValue, newValue) ->
                mApplicationPreference.setStatsLoggingRetentionDays(newValue));
            updateStatsLoggingControlState();
        }

        return mStatsLoggingRetentionSpinner;
    }

    private Label getStatsLoggingPathLabel()
    {
        if(mStatsLoggingPathLabel == null)
        {
            mStatsLoggingPathLabel =
                new Label(P25ActivityLogPath.getDatabasePath(mUserPreferences).toString());
            mStatsLoggingPathLabel.setWrapText(true);
        }

        return mStatsLoggingPathLabel;
    }

    private Label getStatsMaintenanceStatusLabel()
    {
        if(mStatsMaintenanceStatusLabel == null)
        {
            mStatsMaintenanceStatusLabel = new Label("Idle");
            mStatsMaintenanceStatusLabel.setWrapText(true);
            mStatsMaintenanceStatusLabel.setMaxWidth(Double.MAX_VALUE);
        }

        return mStatsMaintenanceStatusLabel;
    }

    private Button getStatsMaintenanceButton()
    {
        if(mStatsMaintenanceButton == null)
        {
            mStatsMaintenanceButton = new Button("Run Maintenance");
            mStatsMaintenanceButton.setTooltip(new Tooltip(
                "Runs retention cleanup, WAL checkpoint, and SQLite query optimization."));
            mStatsMaintenanceButton.setOnAction(event -> runStatsMaintenance(P25ActivityLogMaintenance.Operation.MAINTAIN));
        }

        return mStatsMaintenanceButton;
    }

    private Button getStatsShrinkButton()
    {
        if(mStatsShrinkButton == null)
        {
            mStatsShrinkButton = new Button("Shrink Database");
            mStatsShrinkButton.setTooltip(new Tooltip(
                "Runs retention cleanup, checkpoint, VACUUM, and optimization. This can take time."));
            mStatsShrinkButton.setOnAction(event -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "Shrink rebuilds the SQLite database to reclaim disk space. It can take time and temporarily " +
                        "needs extra disk space. Continue?",
                    ButtonType.YES, ButtonType.NO);
                alert.setHeaderText("Shrink Stats Database");
                Optional<ButtonType> result = alert.showAndWait();

                if(result.isPresent() && result.get() == ButtonType.YES)
                {
                    runStatsMaintenance(P25ActivityLogMaintenance.Operation.SHRINK);
                }
            });
        }

        return mStatsShrinkButton;
    }

    private Button getStatsCheckButton()
    {
        if(mStatsCheckButton == null)
        {
            mStatsCheckButton = new Button("Check Database");
            mStatsCheckButton.setTooltip(new Tooltip("Runs SQLite quick_check and reports the result."));
            mStatsCheckButton.setOnAction(event -> runStatsMaintenance(P25ActivityLogMaintenance.Operation.CHECK));
        }

        return mStatsCheckButton;
    }

    /**
     * Toggle switch to enable/disable automatic diagnostic monitoring.
     */
    private ToggleSwitch getAutomaticDiagnosticMonitoringToggle()
    {
        if(mAutomaticDiagnosticMonitoringToggle == null)
        {
            mAutomaticDiagnosticMonitoringToggle = new ToggleSwitch();
            mAutomaticDiagnosticMonitoringToggle.setSelected(mApplicationPreference.isAutomaticDiagnosticMonitoring());
            mAutomaticDiagnosticMonitoringToggle.selectedProperty().addListener((observable, oldValue, enabled) ->
                    mApplicationPreference.setAutomaticDiagnosticMonitoring(enabled));
        }

        return mAutomaticDiagnosticMonitoringToggle;
    }

    private void updateStatsLoggingControlState()
    {
        if(mStatsLoggingRetentionSpinner != null)
        {
            mStatsLoggingRetentionSpinner.setDisable(!getStatsLoggingCheckBox().isSelected());
        }
    }

    private void runStatsMaintenance(P25ActivityLogMaintenance.Operation operation)
    {
        Path databasePath = P25ActivityLogPath.getDatabasePath(mUserPreferences);
        int retentionDays = mApplicationPreference.getStatsLoggingRetentionDays();
        setStatsMaintenanceRunning(true, operation);

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
            setStatsMaintenanceRunning(false, operation);

            if(throwable != null)
            {
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                getStatsMaintenanceStatusLabel().setText(operation + " failed: " + cause.getMessage());
            }
            else
            {
                getStatsMaintenanceStatusLabel().setText(result.summary());
            }
        }));
    }

    private void setStatsMaintenanceRunning(boolean running, P25ActivityLogMaintenance.Operation operation)
    {
        getStatsMaintenanceButton().setDisable(running);
        getStatsShrinkButton().setDisable(running);
        getStatsCheckButton().setDisable(running);

        if(running)
        {
            getStatsMaintenanceStatusLabel().setText(operation + " running...");
        }
    }

}
