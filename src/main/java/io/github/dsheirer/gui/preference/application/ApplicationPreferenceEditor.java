/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.gui.preference.application;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * General application preferences.
 */
public class ApplicationPreferenceEditor extends HBox
{
    private final ApplicationPreference mApplicationPreference;
    private GridPane mEditorPane;
    private Spinner<Integer> mTimeoutSpinner;

    public ApplicationPreferenceEditor(UserPreferences userPreferences)
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
            mEditorPane = new GridPane();
            mEditorPane.setMaxWidth(Double.MAX_VALUE);
            mEditorPane.setVgap(10);
            mEditorPane.setHgap(3);
            mEditorPane.setPadding(new Insets(10));
            mEditorPane.add(new Label("Channel Auto-Start Timeout"), 0, 0, 2, 1);
            GridPane.setHalignment(getTimeoutSpinner(), HPos.RIGHT);
            mEditorPane.add(getTimeoutSpinner(), 0, 1);
            mEditorPane.add(new Label("seconds"), 1, 1);

            ColumnConstraints labelColumn = new ColumnConstraints();
            labelColumn.setPercentWidth(30);
            ColumnConstraints valueColumn = new ColumnConstraints();
            valueColumn.setHgrow(Priority.ALWAYS);
            mEditorPane.getColumnConstraints().addAll(labelColumn, valueColumn);
        }

        return mEditorPane;
    }

    private Spinner<Integer> getTimeoutSpinner()
    {
        if(mTimeoutSpinner == null)
        {
            mTimeoutSpinner = new Spinner<>(0, 30, mApplicationPreference.getChannelAutoStartTimeout(), 1);
            mTimeoutSpinner.valueProperty().addListener((observable, oldValue, newValue) ->
                mApplicationPreference.setChannelAutoStartTimeout(newValue));
        }

        return mTimeoutSpinner;
    }
}
