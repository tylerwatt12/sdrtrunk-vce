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

package io.github.dsheirer.gui.control;

import java.util.Locale;
import java.util.function.UnaryOperator;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

/**
 * Integer editor that keeps decimal and hexadecimal fields synchronized.
 */
public class DualBaseIntegerField extends HBox
{
    private final int mMinimum;
    private final int mMaximum;
    private final TextField mDecimalField = new TextField();
    private final TextField mHexField = new TextField();
    private boolean mUpdating;

    public DualBaseIntegerField(int minimum, int maximum)
    {
        mMinimum = minimum;
        mMaximum = maximum;

        setSpacing(5);
        setAlignment(Pos.CENTER_LEFT);

        mDecimalField.setPromptText("Dec");
        mDecimalField.setPrefColumnCount(8);
        mDecimalField.setTooltip(new Tooltip("Decimal value"));
        mDecimalField.setTextFormatter(new TextFormatter<>(decimalFilter()));
        mDecimalField.textProperty().addListener((observable, oldValue, newValue) -> updateHex(newValue));

        mHexField.setPromptText("Hex");
        mHexField.setPrefColumnCount(6);
        mHexField.setTooltip(new Tooltip("Hexadecimal value"));
        mHexField.setTextFormatter(new TextFormatter<>(hexFilter()));
        mHexField.textProperty().addListener((observable, oldValue, newValue) -> updateDecimal(newValue));

        getChildren().addAll(mDecimalField, new Label("-"), mHexField);
    }

    public void setEditable(boolean editable)
    {
        mDecimalField.setEditable(editable);
        mHexField.setEditable(editable);
    }

    public void setValue(int value)
    {
        if(!isInRange(value))
        {
            throw new IllegalArgumentException("Value outside valid range: " + value);
        }

        mUpdating = true;
        mDecimalField.setText(Integer.toString(value));
        mHexField.setText(toHex(value));
        mUpdating = false;
    }

    public int getValue()
    {
        Integer value = null;

        if(mHexField.isFocused())
        {
            value = parseHex(mHexField.getText());
        }

        if(value == null)
        {
            value = parseDecimal(mDecimalField.getText());
        }

        if(value == null)
        {
            value = parseHex(mHexField.getText());
        }

        if(value == null || !isInRange(value))
        {
            throw new NumberFormatException("Value must be between " + mMinimum + " and " + mMaximum);
        }

        return value;
    }

    private UnaryOperator<TextFormatter.Change> decimalFilter()
    {
        return change -> isValidDecimalText(change.getControlNewText()) ? change : null;
    }

    private UnaryOperator<TextFormatter.Change> hexFilter()
    {
        return change -> isValidHexText(change.getControlNewText()) ? change : null;
    }

    private boolean isValidDecimalText(String text)
    {
        if(text == null || text.isBlank())
        {
            return true;
        }

        Integer value = parseDecimal(text);
        return value != null && isInRange(value);
    }

    private boolean isValidHexText(String text)
    {
        if(text == null || text.isBlank())
        {
            return true;
        }

        Integer value = parseHex(text);
        return value != null && isInRange(value);
    }

    private void updateHex(String decimalText)
    {
        if(mUpdating)
        {
            return;
        }

        Integer value = parseDecimal(decimalText);

        if(value != null && isInRange(value))
        {
            mUpdating = true;
            mHexField.setText(toHex(value));
            mUpdating = false;
        }
    }

    private void updateDecimal(String hexText)
    {
        if(mUpdating)
        {
            return;
        }

        Integer value = parseHex(hexText);

        if(value != null && isInRange(value))
        {
            mUpdating = true;
            mDecimalField.setText(Integer.toString(value));
            mUpdating = false;
        }
    }

    private Integer parseDecimal(String text)
    {
        if(text == null || text.isBlank())
        {
            return null;
        }

        try
        {
            return Integer.parseInt(text.trim());
        }
        catch(NumberFormatException nfe)
        {
            return null;
        }
    }

    private Integer parseHex(String text)
    {
        if(text == null || text.isBlank())
        {
            return null;
        }

        try
        {
            String value = text.trim().toUpperCase(Locale.ROOT);

            if(value.startsWith("0X"))
            {
                value = value.substring(2);
            }

            return Integer.parseInt(value, 16);
        }
        catch(NumberFormatException nfe)
        {
            return null;
        }
    }

    private boolean isInRange(int value)
    {
        return mMinimum <= value && value <= mMaximum;
    }

    private String toHex(int value)
    {
        return Integer.toHexString(value).toUpperCase(Locale.ROOT);
    }
}
