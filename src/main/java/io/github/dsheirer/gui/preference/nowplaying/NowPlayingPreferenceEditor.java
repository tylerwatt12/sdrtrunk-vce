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
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference.JavaInterfaceView;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
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
 * Preference settings for the Now Playing activity view.
 */
public class NowPlayingPreferenceEditor extends HBox
{
    private final NowPlayingPreference mNowPlayingPreference;
    private GridPane mEditorPane;
    private ToggleSwitch mRetainIdleCallDetailsToggle;
    private ToggleSwitch mAdvancedEncryptionToggle;
    private Spinner<Integer> mTrafficGrantAgeOutSpinner;
    private CheckBox mShowControlDecodeQualityCheckBox;
    private CheckBox mShowVoiceDecodeQualityCheckBox;
    private CheckBox mClearVoiceDecodeQualityOnCallEndCheckBox;
    private ComboBox<NowPlayingPreference.DecodeQualityDisplayMode> mDecodeQualityDisplayModeComboBox;

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

            Label tabsLabel = new Label("Java Interface Views");
            tabsLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 1.08em;");
            mEditorPane.add(tabsLabel, 0, row++, 3, 1);
            mEditorPane.add(new Label("Show"), 0, row);
            mEditorPane.add(getViewVisibilityControls(), 1, row++, 2, 1);

            Separator tabSeparator = new Separator(Orientation.HORIZONTAL);
            GridPane.setHgrow(tabSeparator, Priority.ALWAYS);
            mEditorPane.add(tabSeparator, 0, row++, 3, 1);

            Label nowPlayingLabel = new Label("Systems Activity Settings");
            nowPlayingLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 1.08em;");
            mEditorPane.add(nowPlayingLabel, 0, row, 2, 1);

            GridPane.setHalignment(getRetainIdleCallDetailsToggle(), HPos.RIGHT);
            mEditorPane.add(getRetainIdleCallDetailsToggle(), 0, ++row);
            mEditorPane.add(new Label("Retain Last Call Source/Target On Idle Rows"), 1, row, 2, 1);

            GridPane.setHalignment(getAdvancedEncryptionToggle(), HPos.RIGHT);
            mEditorPane.add(getAdvancedEncryptionToggle(), 0, ++row);
            mEditorPane.add(new Label("Show Encryption Algorithm And Key"), 1, row, 2, 1);

            GridPane.setHalignment(getShowControlDecodeQualityCheckBox(), HPos.RIGHT);
            mEditorPane.add(getShowControlDecodeQualityCheckBox(), 0, ++row);
            mEditorPane.add(new Label("Show CC Decode Quality"), 1, row, 2, 1);

            GridPane.setHalignment(getShowVoiceDecodeQualityCheckBox(), HPos.RIGHT);
            mEditorPane.add(getShowVoiceDecodeQualityCheckBox(), 0, ++row);
            mEditorPane.add(new Label("Show VC Decode Quality"), 1, row, 2, 1);

            GridPane.setHalignment(getClearVoiceDecodeQualityOnCallEndCheckBox(), HPos.RIGHT);
            mEditorPane.add(getClearVoiceDecodeQualityOnCallEndCheckBox(), 0, ++row);
            mEditorPane.add(new Label("Clear VC Decode Quality When Call Ends"), 1, row, 2, 1);

            mEditorPane.add(new Label("Decode Quality Display"), 0, ++row);
            mEditorPane.add(getDecodeQualityDisplayModeComboBox(), 1, row, 2, 1);

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

    private HBox getViewVisibilityControls()
    {
        HBox controls = new HBox(12);

        for(JavaInterfaceView view: JavaInterfaceView.values())
        {
            CheckBox checkBox = new CheckBox(view.getLabel());
            checkBox.setSelected(mNowPlayingPreference.isJavaInterfaceViewEnabled(view));
            checkBox.setOnAction(event ->
                mNowPlayingPreference.setJavaInterfaceViewEnabled(view, checkBox.isSelected()));
            controls.getChildren().add(checkBox);
        }

        return controls;
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

    private ToggleSwitch getAdvancedEncryptionToggle()
    {
        if(mAdvancedEncryptionToggle == null)
        {
            mAdvancedEncryptionToggle = new ToggleSwitch();
            mAdvancedEncryptionToggle.setSelected(mNowPlayingPreference.isAdvancedEncryptionStatus());
            mAdvancedEncryptionToggle.selectedProperty().addListener((observable, oldValue, advanced) ->
                mNowPlayingPreference.setAdvancedEncryptionStatus(advanced));
        }

        return mAdvancedEncryptionToggle;
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

    private CheckBox getShowControlDecodeQualityCheckBox()
    {
        if(mShowControlDecodeQualityCheckBox == null)
        {
            mShowControlDecodeQualityCheckBox = new CheckBox();
            mShowControlDecodeQualityCheckBox.setSelected(mNowPlayingPreference.isShowControlDecodeQuality());
            mShowControlDecodeQualityCheckBox.selectedProperty().addListener((observable, oldValue, show) ->
                mNowPlayingPreference.setShowControlDecodeQuality(show));
        }

        return mShowControlDecodeQualityCheckBox;
    }

    private CheckBox getShowVoiceDecodeQualityCheckBox()
    {
        if(mShowVoiceDecodeQualityCheckBox == null)
        {
            mShowVoiceDecodeQualityCheckBox = new CheckBox();
            mShowVoiceDecodeQualityCheckBox.setSelected(mNowPlayingPreference.isShowVoiceDecodeQuality());
            mShowVoiceDecodeQualityCheckBox.selectedProperty().addListener((observable, oldValue, show) ->
                mNowPlayingPreference.setShowVoiceDecodeQuality(show));
        }

        return mShowVoiceDecodeQualityCheckBox;
    }

    private CheckBox getClearVoiceDecodeQualityOnCallEndCheckBox()
    {
        if(mClearVoiceDecodeQualityOnCallEndCheckBox == null)
        {
            mClearVoiceDecodeQualityOnCallEndCheckBox = new CheckBox();
            mClearVoiceDecodeQualityOnCallEndCheckBox.setTooltip(new Tooltip(
                "Clears the completed voice-channel value. Control-channel decode quality remains visible."));
            mClearVoiceDecodeQualityOnCallEndCheckBox.setSelected(
                mNowPlayingPreference.isClearVoiceDecodeQualityOnCallEnd());
            mClearVoiceDecodeQualityOnCallEndCheckBox.selectedProperty().addListener(
                (observable, oldValue, clear) ->
                    mNowPlayingPreference.setClearVoiceDecodeQualityOnCallEnd(clear));
        }

        return mClearVoiceDecodeQualityOnCallEndCheckBox;
    }

    private ComboBox<NowPlayingPreference.DecodeQualityDisplayMode> getDecodeQualityDisplayModeComboBox()
    {
        if(mDecodeQualityDisplayModeComboBox == null)
        {
            mDecodeQualityDisplayModeComboBox = new ComboBox<>();
            mDecodeQualityDisplayModeComboBox.getItems().addAll(
                NowPlayingPreference.DecodeQualityDisplayMode.values());
            mDecodeQualityDisplayModeComboBox.setValue(mNowPlayingPreference.getDecodeQualityDisplayMode());
            mDecodeQualityDisplayModeComboBox.valueProperty().addListener((observable, oldValue, mode) ->
                mNowPlayingPreference.setDecodeQualityDisplayMode(mode));
        }

        return mDecodeQualityDisplayModeComboBox;
    }
}
