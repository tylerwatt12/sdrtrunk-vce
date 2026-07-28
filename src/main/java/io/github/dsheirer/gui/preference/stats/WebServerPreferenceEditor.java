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
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleGroup;
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
    private GridPane mEditorPane;
    private CheckBox mServerCheckBox;
    private RadioButton mLocalOnlyRadioButton;
    private RadioButton mAnyIpRadioButton;
    private ToggleGroup mAccessModeToggleGroup;
    private Spinner<Integer> mPortSpinner;
    private Label mUrlLabel;

    public WebServerPreferenceEditor(UserPreferences userPreferences)
    {
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
            mEditorPane.add(new Label("Network access"), 0, row);
            mEditorPane.add(new HBox(12, getLocalOnlyRadioButton(), getAnyIpRadioButton()), 1, row++, 2, 1);
            mEditorPane.add(new Label("Port"), 0, row);
            GridPane.setHalignment(getPortSpinner(), HPos.RIGHT);
            mEditorPane.add(getPortSpinner(), 1, row++);
            mEditorPane.add(new Label("Web URL"), 0, row);
            mEditorPane.add(getUrlLabel(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Editable assets folder"), 0, row);
            Label assets = new Label(StatsWebPath.getAssetsPath().toString());
            assets.setWrapText(true);
            mEditorPane.add(assets, 1, row++, 2, 1);

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
                "Runs independently of statistics collection. Live Systems and web audio remain available when " +
                    "summary collection is off."));
            mServerCheckBox.setSelected(mApplicationPreference.isStatsWebServerEnabled());
            mServerCheckBox.setOnAction(event -> setServerEnabled(mServerCheckBox.isSelected()));
        }

        return mServerCheckBox;
    }

    private ToggleGroup getAccessModeToggleGroup()
    {
        if(mAccessModeToggleGroup == null)
        {
            mAccessModeToggleGroup = new ToggleGroup();
        }

        return mAccessModeToggleGroup;
    }

    private RadioButton getLocalOnlyRadioButton()
    {
        if(mLocalOnlyRadioButton == null)
        {
            mLocalOnlyRadioButton = new RadioButton("Local only");
            mLocalOnlyRadioButton.setToggleGroup(getAccessModeToggleGroup());
            mLocalOnlyRadioButton.setTooltip(new Tooltip(
                "Accepts connections only from this computer at 127.0.0.1."));
            mLocalOnlyRadioButton.setSelected(!mApplicationPreference.isStatsWebServerAnyIpEnabled());
            mLocalOnlyRadioButton.setOnAction(event -> {
                mApplicationPreference.setStatsWebServerAnyIpEnabled(false);
                updateControlState();
            });
        }

        return mLocalOnlyRadioButton;
    }

    private RadioButton getAnyIpRadioButton()
    {
        if(mAnyIpRadioButton == null)
        {
            mAnyIpRadioButton = new RadioButton("Any IP");
            mAnyIpRadioButton.setToggleGroup(getAccessModeToggleGroup());
            mAnyIpRadioButton.setTooltip(new Tooltip(
                "Accepts connections through any network interface, subject to the computer's firewall."));
            mAnyIpRadioButton.setSelected(mApplicationPreference.isStatsWebServerAnyIpEnabled());
            mAnyIpRadioButton.setOnAction(event -> confirmAnyIpAccess());
        }

        return mAnyIpRadioButton;
    }

    private void confirmAnyIpAccess()
    {
        ButtonType allow = new ButtonType("Allow Any IP", ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.WARNING,
            "Anyone who can reach this computer on this port can use the web interface. It uses plain HTTP with " +
                "no login or encryption and exposes receiver activity and live decoded audio.\n\nDo not " +
                "port-forward this port or expose it to the public internet. Use only on a trusted LAN, Tailnet, " +
                "VPN, or behind a firewall.",
            allow, ButtonType.CANCEL);
        alert.setTitle("Web Server Security Warning");
        alert.setHeaderText("Allow connections from any IP address?");
        alert.initOwner(getAnyIpRadioButton().getScene().getWindow());
        ((Button)alert.getDialogPane().lookupButton(allow)).setDefaultButton(false);
        ((Button)alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);

        if(alert.showAndWait().filter(allow::equals).isPresent())
        {
            mApplicationPreference.setStatsWebServerAnyIpEnabled(true);
        }
        else
        {
            getLocalOnlyRadioButton().setSelected(true);
            mApplicationPreference.setStatsWebServerAnyIpEnabled(false);
        }

        updateControlState();
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

    private void setServerEnabled(boolean enabled)
    {
        mApplicationPreference.setStatsWebServerEnabled(enabled);
        getServerCheckBox().setSelected(enabled);
        updateControlState();
    }

    private void updateControlState()
    {
        boolean running = getServerCheckBox().isSelected();
        String host = mApplicationPreference.isStatsWebServerAnyIpEnabled() ? "<this-computer-ip>" : "127.0.0.1";
        String url = "http://" + host + ":" + mApplicationPreference.getStatsWebServerPort() + "/";
        getUrlLabel().setText(running ? url : "Stopped - " + url);
    }
}
