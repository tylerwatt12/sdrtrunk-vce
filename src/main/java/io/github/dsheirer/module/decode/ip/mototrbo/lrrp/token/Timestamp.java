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

package io.github.dsheirer.module.decode.ip.mototrbo.lrrp.token;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * LRRP Timestamp Token
 *
 * Start Token: 0x34
 * Total Length: 6 bytes
 */
public class Timestamp extends Token
{
    private static final IntField YEAR = IntField.range(8, 21);
    private static final IntField MONTH = IntField.length4(22);
    private static final IntField DAY = IntField.range(26, 30);
    private static final IntField HOUR = IntField.range(31, 35);
    private static final IntField MINUTE = IntField.length6(36);
    private static final IntField SECOND = IntField.length6(42);

    /**
     * Constructs an instance of a timestamp token.
     *
     * @param message containing the timestamp
     * @param offset to the start of the token
     */
    public Timestamp(CorrectedBinaryMessage message, int offset)
    {
        super(message, offset);
    }

    @Override
    public TokenType getTokenType()
    {
        return TokenType.TIMESTAMP;
    }

    /**
     * Timestamp value in milliseconds
     */
    public long getTimestamp()
    {
        int year = getMessage().getInt(YEAR, getOffset());
        int month = getMessage().getInt(MONTH, getOffset()) - 1;
        int day = getMessage().getInt(DAY, getOffset());
        int hour = getMessage().getInt(HOUR, getOffset());
        int minute = getMessage().getInt(MINUTE, getOffset());
        int second = getMessage().getInt(SECOND, getOffset());

        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(year, month, day, hour, minute, second);
        return calendar.getTimeInMillis();
    }

    @Override
    public String toString()
    {
        return "TIME:" + new Date(getTimestamp()).toString().toUpperCase();
    }
}
