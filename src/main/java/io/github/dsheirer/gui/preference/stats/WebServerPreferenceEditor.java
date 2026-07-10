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
import io.github.dsheirer.stats.StatsWebPath;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
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
 * Embedded browser server preferences. Stats logging is configured separately.
 */
public class WebServerPreferenceEditor extends HBox
{
    private final ApplicationPreference mApplicationPreference;
    private final UserPreferences mUserPreferences;
    private GridPane mEditorPane;
    private CheckBox mServerCheckBox;
    private CheckBox mLanCheckBox;
    private Spinner<Integer> mPortSpinner;
    private Label mUrlLabel;
    private Button mStartButton;
    private Button mStopButton;

    public WebServerPreferenceEditor(UserPreferences userPreferences)
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
            mEditorPane.add(new Label("Web Server"), 0, row++, 3, 1);
            mEditorPane.add(getServerCheckBox(), 0, row++, 3, 1);
            mEditorPane.add(getLanCheckBox(), 0, row++, 3, 1);
            mEditorPane.add(new Label("Port"), 0, row);
            GridPane.setHalignment(getPortSpinner(), HPos.RIGHT);
            mEditorPane.add(getPortSpinner(), 1, row++);
            mEditorPane.add(new Label("Web URL"), 0, row);
            mEditorPane.add(getUrlLabel(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Editable assets folder"), 0, row);
            Label assets = new Label(StatsWebPath.getAssetsPath().toString());
            assets.setWrapText(true);
            mEditorPane.add(assets, 1, row++, 2, 1);
            mEditorPane.add(new Label("Server control"), 0, row);
            mEditorPane.add(new HBox(8, getStartButton(), getStopButton()), 1, row, 2, 1);

            ColumnConstraints labelColumn = new ColumnConstraints();
            labelColumn.setPercentWidth(30);
            ColumnConstraints valueColumn = new ColumnConstraints();
            valueColumn.setHgrow(Priority.ALWAYS);
            mEditorPane.getColumnConstraints().addAll(labelColumn, valueColumn);
            updateControlState();
        }

        return mEditorPane;
    }

    private CheckBox getServerCheckBox()
    {
        if(mServerCheckBox == null)
        {
            mServerCheckBox = new CheckBox("Run Embedded Web Server");
            mServerCheckBox.setTooltip(new Tooltip(
                "Serves editable folder assets and read-only stats APIs from this SDRTrunk instance."));
            mServerCheckBox.setSelected(mApplicationPreference.isStatsWebServerEnabled());
            mServerCheckBox.setOnAction(event -> setServerEnabled(mServerCheckBox.isSelected()));
        }

        return mServerCheckBox;
    }

    private CheckBox getLanCheckBox()
    {
        if(mLanCheckBox == null)
        {
            mLanCheckBox = new CheckBox("Allow LAN/Tailscale Access");
            mLanCheckBox.setTooltip(new Tooltip(
                "When disabled, the server accepts requests only from this computer."));
            mLanCheckBox.setSelected(mApplicationPreference.isStatsWebServerLanEnabled());
            mLanCheckBox.setOnAction(event -> {
                mApplicationPreference.setStatsWebServerLanEnabled(mLanCheckBox.isSelected());
                updateControlState();
            });
        }

        return mLanCheckBox;
    }

    private Spinner<Integer> getPortSpinner()
    {
        if(mPortSpinner == null)
        {
            mPortSpinner = new Spinner<>(ApplicationPreference.MIN_STATS_WEB_SERVER_PORT,
                ApplicationPreference.MAX_STATS_WEB_SERVER_PORT, mApplicationPreference.getStatsWebServerPort(), 1);
            mPortSpinner.valueProperty().addListener((observable, oldValue, newValue) -> {
                mApplicationPreference.setStatsWebServerPort(newValue);
                updateControlState();
            });
        }

        return mPortSpinner;
    }

    private Label getUrlLabel()
    {
        if(mUrlLabel == null)
        {
            mUrlLabel = new Label();
            mUrlLabel.setWrapText(true);
        }

        return mUrlLabel;
    }

    private Button getStartButton()
    {
        if(mStartButton == null)
        {
            mStartButton = new Button("Start");
            mStartButton.setOnAction(event -> setServerEnabled(true));
        }

        return mStartButton;
    }

    private Button getStopButton()
    {
        if(mStopButton == null)
        {
            mStopButton = new Button("Stop");
            mStopButton.setOnAction(event -> setServerEnabled(false));
        }

        return mStopButton;
    }

    private void setServerEnabled(boolean enabled)
    {
        mApplicationPreference.setStatsWebServerEnabled(enabled);
        getServerCheckBox().setSelected(enabled);
        updateControlState();
    }

    private void updateControlState()
    {
        boolean running = getServerCheckBox().isSelected();
        getPortSpinner().setDisable(running);
        getLanCheckBox().setDisable(running);
        getStartButton().setDisable(running);
        getStopButton().setDisable(!running);
        String host = mApplicationPreference.isStatsWebServerLanEnabled() ? "<receiver-ip>" : "127.0.0.1";
        String url = "http://" + host + ":" + mApplicationPreference.getStatsWebServerPort() + "/";
        getUrlLabel().setText(running ? url : "Stopped - " + url);
    }
}
