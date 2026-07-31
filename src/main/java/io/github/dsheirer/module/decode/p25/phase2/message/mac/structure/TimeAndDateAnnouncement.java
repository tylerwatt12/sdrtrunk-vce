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

package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

/**
 * Time and date announcement
 */
public class TimeAndDateAnnouncement extends MacStructure
{
    private static final int VD_FLAG = 8;
    private static final int VT_FLAG = 9;
    private static final int VL_FLAG = 10;
    private static final int LOCAL_TIME_OFFSET_SIGN = 12;
    private static final IntField LOCAL_TIME_OFFSET = IntField.range(13, 23);
    private static final IntField MONTH = IntField.range(24, 27);
    private static final IntField DAY = IntField.range(28, 32);
    private static final IntField YEAR = IntField.range(33, 45);
    private static final IntField HOURS = IntField.range(48, 52);
    private static final IntField MINUTES = IntField.range(53, 58);
    private static final IntField SECONDS = IntField.range(59, 64);

    /**
     * Constructs the message
     *
     * @param message containing the message bits
     * @param offset into the message for this structure
     */
    public TimeAndDateAnnouncement(CorrectedBinaryMessage message, int offset)
    {
        super(message, offset);
    }

    /**
     * Textual representation of this message
     */
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getOpcode());
        OffsetDateTime dateAndTime = getDateAndTime();
        sb.append(" ").append(dateAndTime != null ? dateAndTime : "INVALID DATE/TIME");
        return sb.toString();
    }

    /**
     * Decoded date and time.
     *
     * @return date and time, or null when the validity flags or encoded fields are invalid
     */
    public OffsetDateTime getDateAndTime()
    {
        if(!hasValidDate() || !hasValidTime())
        {
            return null;
        }

        int offsetMinutes = hasLocalTimeOffset() ? getInt(LOCAL_TIME_OFFSET) : 0;
        offsetMinutes *= getMessage().get(LOCAL_TIME_OFFSET_SIGN + getOffset()) ? -1 : 1;

        try
        {
            return OffsetDateTime.of(getInt(YEAR), getInt(MONTH), getInt(DAY), getInt(HOURS), getInt(MINUTES),
                getInt(SECONDS), 0,
                ZoneOffset.ofTotalSeconds(offsetMinutes * 60));
        }
        catch(DateTimeException e)
        {
            return null;
        }
    }

    public boolean hasValidDate()
    {
        return getMessage().get(VD_FLAG + getOffset());
    }

    public boolean hasValidTime()
    {
        return getMessage().get(VT_FLAG + getOffset());
    }

    public boolean hasLocalTimeOffset()
    {
        return getMessage().get(VL_FLAG + getOffset());
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.emptyList();
    }
}
