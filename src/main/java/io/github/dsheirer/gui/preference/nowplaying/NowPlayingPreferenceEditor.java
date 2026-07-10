/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.gui.preference.nowplaying;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.controlsfx.control.ToggleSwitch;

/**
 * Preference settings for the Now Playing activity view.
 */
public class NowPlayingPreferenceEditor extends HBox
{
    private final NowPlayingPreference mNowPlayingPreference;
    private GridPane mEditorPane;
    private ToggleSwitch mRetainIdleCallDetailsToggle;
    private ToggleSwitch mAdvancedP25EncryptionToggle;
    private Spinner<Integer> mTrafficGrantAgeOutSpinner;

    /**
     * Constructs an instance.
     */
    public NowPlayingPreferenceEditor(UserPreferences userPreferences)
    {
        mNowPlayingPreference = userPreferences.getNowPlayingPreference();
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

            Label nowPlayingLabel = new Label("Systems Activity View");
            mEditorPane.add(nowPlayingLabel, 0, row, 2, 1);

            GridPane.setHalignment(getRetainIdleCallDetailsToggle(), HPos.RIGHT);
            mEditorPane.add(getRetainIdleCallDetailsToggle(), 0, ++row);
            mEditorPane.add(new Label("Retain Last Call Source/Target On Idle Rows"), 1, row, 2, 1);

            GridPane.setHalignment(getAdvancedP25EncryptionToggle(), HPos.RIGHT);
            mEditorPane.add(getAdvancedP25EncryptionToggle(), 0, ++row);
            mEditorPane.add(new Label("Show Advanced P25 Encryption Status"), 1, row, 2, 1);

            Separator separator = new Separator(Orientation.HORIZONTAL);
            GridPane.setHgrow(separator, Priority.ALWAYS);
            mEditorPane.add(separator, 0, ++row, 3, 1);

            mEditorPane.add(new Label("P25 Grant Idle Age-Out"), 0, ++row, 2, 1);
            GridPane.setHalignment(getTrafficGrantAgeOutSpinner(), HPos.RIGHT);
            mEditorPane.add(getTrafficGrantAgeOutSpinner(), 0, ++row);
            mEditorPane.add(new Label("milliseconds"), 1, row);

            ColumnConstraints c1 = new ColumnConstraints();
            c1.setPercentWidth(30);
            ColumnConstraints c2 = new ColumnConstraints();
            c2.setHgrow(Priority.ALWAYS);
            mEditorPane.getColumnConstraints().addAll(c1, c2);
        }

        return mEditorPane;
    }

    private ToggleSwitch getRetainIdleCallDetailsToggle()
    {
        if(mRetainIdleCallDetailsToggle == null)
        {
            mRetainIdleCallDetailsToggle = new ToggleSwitch();
            mRetainIdleCallDetailsToggle.setSelected(mNowPlayingPreference.isRetainIdleCallDetails());
            mRetainIdleCallDetailsToggle.selectedProperty().addListener((observable, oldValue, retain) ->
                mNowPlayingPreference.setRetainIdleCallDetails(retain));
        }

        return mRetainIdleCallDetailsToggle;
    }

    private ToggleSwitch getAdvancedP25EncryptionToggle()
    {
        if(mAdvancedP25EncryptionToggle == null)
        {
            mAdvancedP25EncryptionToggle = new ToggleSwitch();
            mAdvancedP25EncryptionToggle.setSelected(mNowPlayingPreference.isAdvancedP25EncryptionStatus());
            mAdvancedP25EncryptionToggle.selectedProperty().addListener((observable, oldValue, advanced) ->
                mNowPlayingPreference.setAdvancedP25EncryptionStatus(advanced));
        }

        return mAdvancedP25EncryptionToggle;
    }

    private Spinner<Integer> getTrafficGrantAgeOutSpinner()
    {
        if(mTrafficGrantAgeOutSpinner == null)
        {
            mTrafficGrantAgeOutSpinner = new Spinner<>(
                NowPlayingPreference.MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS,
                NowPlayingPreference.MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS,
                mNowPlayingPreference.getTrafficGrantAgeOutMilliseconds(),
                100);
            mTrafficGrantAgeOutSpinner.setEditable(true);
            mTrafficGrantAgeOutSpinner.valueProperty().addListener((observable, oldValue, milliseconds) -> {
                if(milliseconds != null)
                {
                    mNowPlayingPreference.setTrafficGrantAgeOutMilliseconds(milliseconds);
                }
            });
        }

        return mTrafficGrantAgeOutSpinner;
    }
}
