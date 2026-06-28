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
    private Spinner<Integer> mSymbolGraphHangSpinner;
    private Spinner<Integer> mP25ClassificationDelaySpinner;
    private Spinner<Integer> mControlDecodeHangSpinner;
    private Spinner<Integer> mTrafficGrantAgeOutSpinner;
    private Spinner<Integer> mActivitySweeperIntervalSpinner;

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

            Label nowPlayingLabel = new Label("Now Playing Activity View");
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

            mEditorPane.add(new Label("Symbol Graph Hang Time"), 0, ++row, 2, 1);
            GridPane.setHalignment(getSymbolGraphHangSpinner(), HPos.RIGHT);
            mEditorPane.add(getSymbolGraphHangSpinner(), 0, ++row);
            mEditorPane.add(new Label("milliseconds"), 1, row);

            Separator timingSeparator = new Separator(Orientation.HORIZONTAL);
            GridPane.setHgrow(timingSeparator, Priority.ALWAYS);
            mEditorPane.add(timingSeparator, 0, ++row, 3, 1);

            mEditorPane.add(new Label("Activity Timing"), 0, ++row, 2, 1);

            GridPane.setHalignment(getP25ClassificationDelaySpinner(), HPos.RIGHT);
            mEditorPane.add(getP25ClassificationDelaySpinner(), 0, ++row);
            mEditorPane.add(new Label("P25 Classification Delay ms"), 1, row, 2, 1);

            GridPane.setHalignment(getControlDecodeHangSpinner(), HPos.RIGHT);
            mEditorPane.add(getControlDecodeHangSpinner(), 0, ++row);
            mEditorPane.add(new Label("Control Decode Hang ms"), 1, row, 2, 1);

            GridPane.setHalignment(getTrafficGrantAgeOutSpinner(), HPos.RIGHT);
            mEditorPane.add(getTrafficGrantAgeOutSpinner(), 0, ++row);
            mEditorPane.add(new Label("Traffic Grant Age-Out ms"), 1, row, 2, 1);

            GridPane.setHalignment(getActivitySweeperIntervalSpinner(), HPos.RIGHT);
            mEditorPane.add(getActivitySweeperIntervalSpinner(), 0, ++row);
            mEditorPane.add(new Label("Activity Sweeper Interval ms"), 1, row, 2, 1);

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

    private Spinner<Integer> getSymbolGraphHangSpinner()
    {
        if(mSymbolGraphHangSpinner == null)
        {
            mSymbolGraphHangSpinner = new Spinner<>(
                NowPlayingPreference.MIN_SYMBOL_GRAPH_HANG_MILLISECONDS,
                NowPlayingPreference.MAX_SYMBOL_GRAPH_HANG_MILLISECONDS,
                mNowPlayingPreference.getSymbolGraphHangMilliseconds(),
                100);
            mSymbolGraphHangSpinner.setEditable(true);
            mSymbolGraphHangSpinner.valueProperty().addListener((observable, oldValue, milliseconds) -> {
                if(milliseconds != null)
                {
                    mNowPlayingPreference.setSymbolGraphHangMilliseconds(milliseconds);
                }
            });
        }

        return mSymbolGraphHangSpinner;
    }

    private Spinner<Integer> getP25ClassificationDelaySpinner()
    {
        if(mP25ClassificationDelaySpinner == null)
        {
            mP25ClassificationDelaySpinner = new Spinner<>(
                NowPlayingPreference.MIN_P25_CLASSIFICATION_DELAY_MILLISECONDS,
                NowPlayingPreference.MAX_P25_CLASSIFICATION_DELAY_MILLISECONDS,
                mNowPlayingPreference.getP25ClassificationDelayMilliseconds(),
                100);
            mP25ClassificationDelaySpinner.setEditable(true);
            mP25ClassificationDelaySpinner.valueProperty().addListener((observable, oldValue, milliseconds) -> {
                if(milliseconds != null)
                {
                    mNowPlayingPreference.setP25ClassificationDelayMilliseconds(milliseconds);
                }
            });
        }

        return mP25ClassificationDelaySpinner;
    }

    private Spinner<Integer> getControlDecodeHangSpinner()
    {
        if(mControlDecodeHangSpinner == null)
        {
            mControlDecodeHangSpinner = new Spinner<>(
                NowPlayingPreference.MIN_CONTROL_DECODE_HANG_MILLISECONDS,
                NowPlayingPreference.MAX_CONTROL_DECODE_HANG_MILLISECONDS,
                mNowPlayingPreference.getControlDecodeHangMilliseconds(),
                1000);
            mControlDecodeHangSpinner.setEditable(true);
            mControlDecodeHangSpinner.valueProperty().addListener((observable, oldValue, milliseconds) -> {
                if(milliseconds != null)
                {
                    mNowPlayingPreference.setControlDecodeHangMilliseconds(milliseconds);
                }
            });
        }

        return mControlDecodeHangSpinner;
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

    private Spinner<Integer> getActivitySweeperIntervalSpinner()
    {
        if(mActivitySweeperIntervalSpinner == null)
        {
            mActivitySweeperIntervalSpinner = new Spinner<>(
                NowPlayingPreference.MIN_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS,
                NowPlayingPreference.MAX_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS,
                mNowPlayingPreference.getActivitySweeperIntervalMilliseconds(),
                25);
            mActivitySweeperIntervalSpinner.setEditable(true);
            mActivitySweeperIntervalSpinner.valueProperty().addListener((observable, oldValue, milliseconds) -> {
                if(milliseconds != null)
                {
                    mNowPlayingPreference.setActivitySweeperIntervalMilliseconds(milliseconds);
                }
            });
        }

        return mActivitySweeperIntervalSpinner;
    }
}
