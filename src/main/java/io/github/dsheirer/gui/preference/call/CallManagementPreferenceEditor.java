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

package io.github.dsheirer.gui.preference.call;

import io.github.dsheirer.audio.broadcast.PatchGroupStreamingOption;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.call.CallManagementPreference;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * Preference settings for audio call routing.
 */
public class CallManagementPreferenceEditor extends HBox
{
    private CallManagementPreference mPreference;
    private GridPane mEditorPane;
    private ComboBox<PatchGroupStreamingOption> mPatchGroupStreamingOptionComboBox;

    /**
     * Constructs an instance
     */
    public CallManagementPreferenceEditor(UserPreferences userPreferences)
    {
        mPreference = userPreferences.getCallManagementPreference();

        HBox.setHgrow(getEditorPane(), Priority.ALWAYS);
        getChildren().add(getEditorPane());
    }

    private GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            int row = 0;
            mEditorPane = new GridPane();
            mEditorPane.setPadding(new Insets(10, 10, 10, 10));
            mEditorPane.setHgap(10);
            mEditorPane.setVgap(10);

            Label patchGroupLabel = new Label("Stream a patch group call as:");
            patchGroupLabel.setWrapText(true);
            GridPane.setConstraints(patchGroupLabel, 0, row, 2, 1);
            mEditorPane.getChildren().add(patchGroupLabel);

            GridPane.setConstraints(getPatchGroupStreamingOptionComboBox(), 0, ++row, 2, 1);
            mEditorPane.getChildren().add(getPatchGroupStreamingOptionComboBox());
        }

        return mEditorPane;
    }

    /**
     * Combo box for presenting the patch group streaming options.
     * @return combo box.
     */
    private ComboBox<PatchGroupStreamingOption> getPatchGroupStreamingOptionComboBox()
    {
        if(mPatchGroupStreamingOptionComboBox == null)
        {
            ObservableList<PatchGroupStreamingOption> options = FXCollections.observableArrayList();
            options.addAll(PatchGroupStreamingOption.values());
            mPatchGroupStreamingOptionComboBox = new ComboBox<>(options);
            mPatchGroupStreamingOptionComboBox.getSelectionModel().select(mPreference.getPatchGroupStreamingOption());
            mPatchGroupStreamingOptionComboBox.getSelectionModel().selectedItemProperty()
                    .addListener((observable, oldValue, newValue) -> mPreference.setPatchGroupStreamingOption(newValue));
        }

        return mPatchGroupStreamingOptionComboBox;
    }
}
