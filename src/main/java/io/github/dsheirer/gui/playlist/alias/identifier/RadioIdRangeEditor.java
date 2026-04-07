/*
 * *****************************************************************************
 *  Copyright (C) 2014-2020 Dennis Sheirer
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

package io.github.dsheirer.gui.playlist.alias.identifier;

import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.gui.control.HexFormatter;
import io.github.dsheirer.gui.control.IntegerFormatter;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.identifier.IntegerFormat;
import io.github.dsheirer.protocol.Protocol;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.HPos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor for radio ID range alias identifiers
 */
public class RadioIdRangeEditor extends IdentifierEditor<RadioRange>
{
    private static final Logger mLog = LoggerFactory.getLogger(RadioIdRangeEditor.class);

    private UserPreferences mUserPreferences;
    private Label mProtocolLabel;
    private Label mFormatLabel;
    private TextField mMinRadioIdField;
    private TextField mMaxRadioIdField;
    private TextFormatter<Integer> mMinIntegerTextFormatter;
    private TextFormatter<Integer> mMaxIntegerTextFormatter;
    private List<RadioIdDetail> mRadioDetails;
    private MinRadioValueChangeListener mMinRadioValueChangeListener = new MinRadioValueChangeListener();
    private MaxRadioValueChangeListener mMaxRadioValueChangeListener = new MaxRadioValueChangeListener();

    /**
     * Constructs an instance
     * @param userPreferences for determining user preferred radio ID formats
     */
    public RadioIdRangeEditor(UserPreferences userPreferences)
    {
        super(RadioRange.class);

        mUserPreferences = userPreferences;
        mRadioDetails = createRadioDetails();

        GridPane gridPane = new GridPane();
        gridPane.setHgap(5);
        gridPane.setVgap(3);

        GridPane.setConstraints(getProtocolLabel(), 0, 0);
        gridPane.getChildren().add(getProtocolLabel());

        Label valueLabel = new Label("Radio ID Range");
        GridPane.setHalignment(valueLabel, HPos.RIGHT);
        GridPane.setConstraints(valueLabel, 1, 0);
        gridPane.getChildren().add(valueLabel);

        GridPane.setConstraints(getMinRadioIdField(), 2, 0);
        gridPane.getChildren().add(getMinRadioIdField());

        Label dashLabel = new Label("-");
        GridPane.setConstraints(dashLabel, 3, 0);
        gridPane.getChildren().add(dashLabel);

        GridPane.setConstraints(getMaxRadioIdField(), 4, 0);
        gridPane.getChildren().add(getMaxRadioIdField());

        GridPane.setConstraints(getFormatLabel(), 5, 0);
        gridPane.getChildren().add(getFormatLabel());

        getChildren().add(gridPane);
    }

    @Override
    public void setItem(RadioRange item)
    {
        super.setItem(item);

        RadioRange radioRange = getItem();

        getProtocolLabel().setDisable(radioRange == null);
        getMinRadioIdField().setDisable(radioRange == null);
        getMaxRadioIdField().setDisable(radioRange == null);

        if(radioRange != null)
        {
            getProtocolLabel().setText(radioRange.getProtocol().toString());
            updateTextFormatter();
        }
        else
        {
            getProtocolLabel().setText("Unrecognized Protocol");
            getMinRadioIdField().setText(null);
            getMaxRadioIdField().setText(null);
        }

        modifiedProperty().set(false);
    }

    private void updateTextFormatter()
    {
        if(mMinIntegerTextFormatter != null)
        {
            mMinIntegerTextFormatter.valueProperty().removeListener(mMinRadioValueChangeListener);
        }

        if(mMaxIntegerTextFormatter != null)
        {
            mMaxIntegerTextFormatter.valueProperty().removeListener(mMaxRadioValueChangeListener);
        }

        IntegerFormat format = mUserPreferences.getTalkgroupFormatPreference().getTalkgroupFormat(getItem().getProtocol());

        if(format != null)
        {
            RadioIdDetail radioIdDetail = getRadioDetail(getItem().getProtocol(), format);

            if(radioIdDetail != null)
            {
                mMinIntegerTextFormatter = radioIdDetail.getMinTextFormatter();
                Integer minValue = getItem() != null ? getItem().getMinRadio() : null;
                mMinRadioIdField.setTextFormatter(mMinIntegerTextFormatter);
                mMinRadioIdField.setTooltip(new Tooltip(radioIdDetail.getTooltip()));
                mMinIntegerTextFormatter.setValue(minValue);
                mMinIntegerTextFormatter.valueProperty().addListener(mMinRadioValueChangeListener);

                mMaxIntegerTextFormatter = radioIdDetail.getMaxTextFormatter();
                Integer maxValue = getItem() != null ? getItem().getMaxRadio() : null;
                mMaxRadioIdField.setTextFormatter(mMaxIntegerTextFormatter);
                mMaxRadioIdField.setTooltip(new Tooltip(radioIdDetail.getTooltip()));
                mMaxIntegerTextFormatter.setValue(maxValue);
                mMaxIntegerTextFormatter.valueProperty().addListener(mMaxRadioValueChangeListener);

                getFormatLabel().setText(radioIdDetail.getTooltip());
            }
            else
            {
                logMissingRadioDetail("Couldn't find radio ID detail", getItem().getProtocol(), format);
                getFormatLabel().setText(" ");
            }
        }
        else
        {
            getFormatLabel().setText(" ");
            mMinIntegerTextFormatter.setValue(null);
            mMaxIntegerTextFormatter.setValue(null);
        }
    }

    @Override
    public void save()
    {
        //no-op
    }

    @Override
    public void dispose()
    {
        //no-op
    }

    private Label getFormatLabel()
    {
        if(mFormatLabel == null)
        {
            mFormatLabel = new Label(" ");
        }

        return mFormatLabel;
    }

    private Label getProtocolLabel()
    {
        if(mProtocolLabel == null)
        {
            mProtocolLabel = new Label();
        }

        return mProtocolLabel;
    }

    private TextField getMinRadioIdField()
    {
        if(mMinRadioIdField == null)
        {
            mMinRadioIdField = new TextField();
            mMinRadioIdField.setTextFormatter(mMinIntegerTextFormatter);
        }

        return mMinRadioIdField;
    }

    private TextField getMaxRadioIdField()
    {
        if(mMaxRadioIdField == null)
        {
            mMaxRadioIdField = new TextField();
            mMaxRadioIdField.setTextFormatter(mMinIntegerTextFormatter);
        }

        return mMaxRadioIdField;
    }

    public class MinRadioValueChangeListener implements ChangeListener<Integer>
    {
        @Override
        public void changed(ObservableValue<? extends Integer> observable, Integer oldValue, Integer newValue)
        {
            if(getItem() != null)
            {
                getItem().setMinRadio(newValue != null ? newValue : 0);
                modifiedProperty().set(true);
            }
        }
    }

    public class MaxRadioValueChangeListener implements ChangeListener<Integer>
    {
        @Override
        public void changed(ObservableValue<? extends Integer> observable, Integer oldValue, Integer newValue)
        {
            if(getItem() != null)
            {
                getItem().setMaxRadio(newValue != null ? newValue : 0);
                modifiedProperty().set(true);
            }
        }
    }

    /**
     * Radio ID detail formatting details for the specified protocol and format
     * @param protocol for the talkgroup
     * @param integerFormat for formatting the value
     * @return detail or null
     */
    private RadioIdDetail getRadioDetail(Protocol protocol, IntegerFormat integerFormat)
    {
        for(RadioIdDetail detail: mRadioDetails)
        {
            if(detail.getProtocol() == protocol && detail.getIntegerFormat() == integerFormat)
            {
                return detail;
            }
        }

        logMissingRadioDetail("Unable to find radio id range editor", protocol, integerFormat);
        mLog.warn("Using default editor");

        //Use a default instance
        for(RadioIdDetail detail: mRadioDetails)
        {
            if(detail.getProtocol() == Protocol.UNKNOWN && detail.getIntegerFormat() == integerFormat)
            {
                return detail;
            }
        }

        logMissingRadioDetail("No Radio ID Detail is configured", protocol, integerFormat);
        return null;
    }

    private void logMissingRadioDetail(String prefix, Protocol protocol, IntegerFormat integerFormat)
    {
        mLog.warn("{} for protocol [{}] and format [{}]", prefix, protocol, integerFormat);
    }

    /**
     * Creates a set of radio ID details with integer text formatters
     */
    private List<RadioIdDetail> createRadioDetails()
    {
        List<RadioIdDetail> details = new ArrayList<>();
        addRadioIdDetails(details, Protocol.APCO25, 0xFFFFFF);
        addRadioIdDetails(details, Protocol.PASSPORT, 0x7FFFFF);
        addRadioIdDetail(details, Protocol.UNKNOWN, IntegerFormat.DECIMAL, 0xFFFFFF);
        addRadioIdDetail(details, Protocol.UNKNOWN, IntegerFormat.FORMATTED, 0xFFFFFF);
        addRadioIdDetail(details, Protocol.UNKNOWN, IntegerFormat.HEXADECIMAL, 0xFFFFFF);
        addRadioIdDetails(details, Protocol.DMR, 0xFFFFFF);
        return details;
    }

    private void addRadioIdDetails(List<RadioIdDetail> details, Protocol protocol, int maximum)
    {
        addRadioIdDetail(details, protocol, IntegerFormat.DECIMAL, maximum);
        addRadioIdDetail(details, protocol, IntegerFormat.HEXADECIMAL, maximum);
    }

    private void addRadioIdDetail(List<RadioIdDetail> details, Protocol protocol, IntegerFormat integerFormat, int maximum)
    {
        TextFormatter<Integer> minTextFormatter = integerFormat == IntegerFormat.HEXADECIMAL ?
                new HexFormatter(0, maximum) : new IntegerFormatter(0, maximum);
        TextFormatter<Integer> maxTextFormatter = integerFormat == IntegerFormat.HEXADECIMAL ?
                new HexFormatter(0, maximum) : new IntegerFormatter(0, maximum);
        details.add(new RadioIdDetail(protocol, integerFormat, minTextFormatter, maxTextFormatter,
                getTooltip(integerFormat, maximum)));
    }

    private String getTooltip(IntegerFormat integerFormat, int maximum)
    {
        return integerFormat == IntegerFormat.HEXADECIMAL ?
                "Format: 0 - " + Integer.toHexString(maximum).toUpperCase() :
                "Format: 0 - " + maximum;
    }

    public class RadioIdDetail
    {
        private Protocol mProtocol;
        private IntegerFormat mIntegerFormat;
        private TextFormatter<Integer> mMinTextFormatter;
        private TextFormatter<Integer> mMaxTextFormatter;
        private String mTooltip;

        public RadioIdDetail(Protocol protocol, IntegerFormat integerFormat, TextFormatter<Integer> minTextFormatter,
                             TextFormatter<Integer> maxTextFormatter, String tooltip)
        {
            mProtocol = protocol;
            mIntegerFormat = integerFormat;
            mMinTextFormatter = minTextFormatter;
            mMaxTextFormatter = maxTextFormatter;
            mTooltip = tooltip;
        }

        public Protocol getProtocol()
        {
            return mProtocol;
        }

        public IntegerFormat getIntegerFormat()
        {
            return mIntegerFormat;
        }

        public TextFormatter<Integer> getMinTextFormatter()
        {
            return mMinTextFormatter;
        }

        public TextFormatter<Integer> getMaxTextFormatter()
        {
            return mMaxTextFormatter;
        }

        public String getTooltip()
        {
            return mTooltip;
        }
    }
}
