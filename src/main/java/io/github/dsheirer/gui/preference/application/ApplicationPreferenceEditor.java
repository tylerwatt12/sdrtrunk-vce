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
import io.github.dsheirer.radioresolve.activitylog.P25ActivityLogPath;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
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
    private Label mP25ActivityLoggingPathLabel;
    private Spinner<Integer> mTimeoutSpinner;
    private Spinner<Integer> mP25ActivityLoggingRetentionSpinner;
    private ToggleSwitch mAutomaticDiagnosticMonitoringToggle;
    private CheckBox mP25ActivityLoggingCheckBox;
    private CheckBox mP25SiteStatisticsLoggingCheckBox;
    private CheckBox mDatabaseLoggerStatusLoggingCheckBox;

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

            Separator activitySeparator = new Separator(Orientation.HORIZONTAL);
            GridPane.setHgrow(activitySeparator, Priority.ALWAYS);
            mEditorPane.add(activitySeparator, 0, ++row, 3, 1);

            Label activityLabel = new Label("P25 History Database");
            mEditorPane.add(activityLabel, 0, ++row, 2, 1);
            mEditorPane.add(getP25ActivityLoggingCheckBox(), 0, ++row, 3, 1);
            mEditorPane.add(getP25SiteStatisticsLoggingCheckBox(), 0, ++row, 3, 1);
            mEditorPane.add(getDatabaseLoggerStatusLoggingCheckBox(), 0, ++row, 3, 1);

            mEditorPane.add(new Label("Keep history for"), 0, ++row);
            GridPane.setHalignment(getP25ActivityLoggingRetentionSpinner(), HPos.RIGHT);
            mEditorPane.add(getP25ActivityLoggingRetentionSpinner(), 1, row);
            mEditorPane.add(new Label("days"), 2, row);

            mEditorPane.add(new Label("Database file"), 0, ++row);
            mEditorPane.add(getP25ActivityLoggingPathLabel(), 1, row, 2, 1);

            ColumnConstraints c1 = new ColumnConstraints();
            c1.setPercentWidth(30);
            ColumnConstraints c2 = new ColumnConstraints();
            c2.setHgrow(Priority.ALWAYS);
            mEditorPane.getColumnConstraints().addAll(c1, c2);
            updateDatabaseLoggingControlState();
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

    private CheckBox getP25ActivityLoggingCheckBox()
    {
        if(mP25ActivityLoggingCheckBox == null)
        {
            mP25ActivityLoggingCheckBox = new CheckBox("Save call and radio activity");
            mP25ActivityLoggingCheckBox.setTooltip(new Tooltip(
                "Stores P25 call, radio, talkgroup, and frequency history rows in SQLite."));
            mP25ActivityLoggingCheckBox.setSelected(mApplicationPreference.isP25ActivityLoggingEnabled());
            mP25ActivityLoggingCheckBox.setOnAction(event -> {
                mApplicationPreference.setP25ActivityLoggingEnabled(mP25ActivityLoggingCheckBox.isSelected());
                updateDatabaseLoggingControlState();
            });
        }

        return mP25ActivityLoggingCheckBox;
    }

    private CheckBox getP25SiteStatisticsLoggingCheckBox()
    {
        if(mP25SiteStatisticsLoggingCheckBox == null)
        {
            mP25SiteStatisticsLoggingCheckBox = new CheckBox("Save site and network snapshots");
            mP25SiteStatisticsLoggingCheckBox.setTooltip(new Tooltip(
                "Stores P25 site channels, neighbors, frequency bands, patches, and talker aliases in SQLite."));
            mP25SiteStatisticsLoggingCheckBox.setSelected(mApplicationPreference.isP25SiteStatisticsLoggingEnabled());
            mP25SiteStatisticsLoggingCheckBox.setOnAction(event -> {
                mApplicationPreference.setP25SiteStatisticsLoggingEnabled(
                    mP25SiteStatisticsLoggingCheckBox.isSelected());
                updateDatabaseLoggingControlState();
            });
        }

        return mP25SiteStatisticsLoggingCheckBox;
    }

    private CheckBox getDatabaseLoggerStatusLoggingCheckBox()
    {
        if(mDatabaseLoggerStatusLoggingCheckBox == null)
        {
            mDatabaseLoggerStatusLoggingCheckBox = new CheckBox("Save writer health counters");
            mDatabaseLoggerStatusLoggingCheckBox.setTooltip(new Tooltip(
                "Stores database writer counters in SQLite for troubleshooting; this does not control console logging."));
            mDatabaseLoggerStatusLoggingCheckBox.setSelected(
                mApplicationPreference.isDatabaseLoggerStatusLoggingEnabled());
            mDatabaseLoggerStatusLoggingCheckBox.setOnAction(event ->
                mApplicationPreference.setDatabaseLoggerStatusLoggingEnabled(
                    mDatabaseLoggerStatusLoggingCheckBox.isSelected()));
        }

        return mDatabaseLoggerStatusLoggingCheckBox;
    }

    /**
     * Spinner to select P25 activity retention in days.
     */
    private Spinner<Integer> getP25ActivityLoggingRetentionSpinner()
    {
        if(mP25ActivityLoggingRetentionSpinner == null)
        {
            mP25ActivityLoggingRetentionSpinner = new Spinner<>(
                ApplicationPreference.MIN_P25_ACTIVITY_LOGGING_RETENTION_DAYS,
                ApplicationPreference.MAX_P25_ACTIVITY_LOGGING_RETENTION_DAYS,
                mApplicationPreference.getP25ActivityLoggingRetentionDays(), 1);
            mP25ActivityLoggingRetentionSpinner.valueProperty().addListener((observable, oldValue, newValue) ->
                mApplicationPreference.setP25ActivityLoggingRetentionDays(newValue));
            updateDatabaseLoggingControlState();
        }

        return mP25ActivityLoggingRetentionSpinner;
    }

    private Label getP25ActivityLoggingPathLabel()
    {
        if(mP25ActivityLoggingPathLabel == null)
        {
            mP25ActivityLoggingPathLabel =
                new Label(P25ActivityLogPath.getDatabasePath(mUserPreferences).toString());
            mP25ActivityLoggingPathLabel.setWrapText(true);
        }

        return mP25ActivityLoggingPathLabel;
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

    private void updateDatabaseLoggingControlState()
    {
        if(mP25ActivityLoggingRetentionSpinner != null)
        {
            boolean p25RecordLoggingEnabled = getP25ActivityLoggingCheckBox().isSelected() ||
                getP25SiteStatisticsLoggingCheckBox().isSelected();
            mP25ActivityLoggingRetentionSpinner.setDisable(!p25RecordLoggingEnabled);
        }
    }

}
