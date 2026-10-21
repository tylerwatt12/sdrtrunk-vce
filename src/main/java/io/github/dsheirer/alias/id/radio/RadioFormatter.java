/*
 *
 *  * ******************************************************************************
 *  * Copyright (C) 2014-2019 Dennis Sheirer
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *  * *****************************************************************************
 *
 *
 */

package io.github.dsheirer.alias.id.radio;

import io.github.dsheirer.preference.identifier.talkgroup.AbstractIntegerFormatter;
import io.github.dsheirer.preference.identifier.talkgroup.UnknownTalkgroupFormatter;
import io.github.dsheirer.protocol.Protocol;

import java.text.ParseException;

/**
 * Protocol-specific formatting of radio id values and parsing of formatted radio id values.
 */
public class RadioFormatter
{
    private static final AbstractIntegerFormatter DECIMAL_FORMATTER = new UnknownTalkgroupFormatter();

    private RadioFormatter()
    {
    }

    /**
     * Parses a decimal radio value. The protocol remains part of the shared alias formatter API.
     */
    public static int parse(Protocol protocol, String value) throws ParseException
    {
        return DECIMAL_FORMATTER.parse(value);
    }

    /**
     * Formats a radio value as decimal.
     */
    public static String format(Protocol protocol, int value)
    {
        return DECIMAL_FORMATTER.format(value);
    }
}
