/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

package io.github.dsheirer.alias.id.talkgroup;

import io.github.dsheirer.preference.identifier.talkgroup.AbstractIntegerFormatter;
import io.github.dsheirer.preference.identifier.talkgroup.FleetsyncTalkgroupFormatter;
import io.github.dsheirer.preference.identifier.talkgroup.UnknownTalkgroupFormatter;
import io.github.dsheirer.protocol.Protocol;
import java.text.ParseException;

/**
 * Protocol-specific formatting of talkgroup values and parsing of formatted talkgroup values.
 */
public class TalkgroupFormatter
{
    private static final AbstractIntegerFormatter DECIMAL_FORMATTER = new UnknownTalkgroupFormatter();
    private static final AbstractIntegerFormatter FLEETSYNC_FORMATTER = new FleetsyncTalkgroupFormatter();

    private TalkgroupFormatter()
    {
    }

    /**
     * Parses the formatted value into an integer using a protocol specific formatter
     */
    public static int parse(Protocol protocol, String value) throws ParseException
    {
        AbstractIntegerFormatter formatter = protocol == Protocol.FLEETSYNC ? FLEETSYNC_FORMATTER : DECIMAL_FORMATTER;
        return formatter.parse(value);
    }

    /**
     * Formats the integer value using a protocol-specific formatter
     */
    public static String format(Protocol protocol, int value)
    {
        AbstractIntegerFormatter formatter = protocol == Protocol.FLEETSYNC ? FLEETSYNC_FORMATTER : DECIMAL_FORMATTER;
        return formatter.format(value);
    }
}
