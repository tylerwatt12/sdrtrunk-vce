/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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

package io.github.dsheirer.gui.preference.tuner;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.source.TunerPreference;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;


/**
 * Preference settings for channel event view
 */
public class TunerPreferenceEditor extends HBox
{
    private TunerPreference mTunerPreference;
    private GridPane mEditorPane;
    private ChoiceBox<RspDuoSelectionMode> mRspDuoTunerModeChoiceBox;
    private Label mRspDuoModeLabel;

    public TunerPreferenceEditor(UserPreferences userPreferences)
    {
        mTunerPreference = userPreferences.getTunerPreference();
        getChildren().add(getEditorPane());
    }

    private GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            int row = 0;
            mEditorPane = new GridPane();
            mEditorPane.setVgap(10);
            mEditorPane.setHgap(10);
            mEditorPane.setPadding(new Insets(10, 10, 10, 10));
            GridPane.setHalignment(getRspDuoModeLabel(), HPos.RIGHT);
            mEditorPane.add(getRspDuoModeLabel(), 0, row);
            mEditorPane.add(getRspDuoTunerModeChoiceBox(), 1, row);
        }

        return mEditorPane;
    }

    private ChoiceBox<RspDuoSelectionMode> getRspDuoTunerModeChoiceBox()
    {
        if(mRspDuoTunerModeChoiceBox == null)
        {
            mRspDuoTunerModeChoiceBox = new ChoiceBox<>();
            mRspDuoTunerModeChoiceBox.getItems().addAll(RspDuoSelectionMode.values());

            RspDuoSelectionMode current = mTunerPreference.getRspDuoTunerMode();
            mRspDuoTunerModeChoiceBox.getSelectionModel().select(current);

            mRspDuoTunerModeChoiceBox.setOnAction(event -> {
                RspDuoSelectionMode selected = mRspDuoTunerModeChoiceBox.getSelectionModel().getSelectedItem();
                mTunerPreference.setRspDuoTunerMode(selected);

                Label label = new Label("Please restart the application for this change to take effect");
                label.setWrapText(true);
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.getDialogPane().setContent(label);
                alert.initOwner(((Node)getRspDuoTunerModeChoiceBox()).getScene().getWindow());
                alert.show();
            });
        }

        return mRspDuoTunerModeChoiceBox;
    }

    private Label getRspDuoModeLabel()
    {
        if(mRspDuoModeLabel == null)
        {
            mRspDuoModeLabel = new Label("SDRPlay RSPduo Selection Mode");
        }

        return mRspDuoModeLabel;
    }
}
