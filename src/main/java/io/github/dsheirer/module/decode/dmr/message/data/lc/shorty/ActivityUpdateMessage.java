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

package io.github.dsheirer.module.decode.dmr.message.data.lc.shorty;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.dmr.message.type.Activity;
import java.util.Collections;
import java.util.List;

/**
 * Short Link Control Message - Activity Update
 */
public class ActivityUpdateMessage extends ShortLCMessage
{
    private static final IntField TIMESLOT_1_ACTIVITY = IntField.length4(4);
    private static final IntField TIMESLOT_2_ACTIVITY = IntField.length4(8);
    private static final IntField TIMESLOT_1_HASH_ADDRESS = IntField.length8(12);
    private static final IntField TIMESLOT_2_HASH_ADDRESS = IntField.length8(20);

    /**
     * Constructs an instance
     *
     * @param message containing the short link control message bits
     */
    public ActivityUpdateMessage(CorrectedBinaryMessage message, long timestamp, int timeslot)
    {
        super(message, timestamp, timeslot);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        if(!isValid())
        {
            sb.append("[CRC ERROR] ");
        }
        sb.append("SLC TS1:");
        sb.append(getActivityTS1());
        if(getActivityTS1() != Activity.IDLE)
        {
            sb.append(" [").append(getHashAddressTS1()).append("]");
        }
        sb.append(" TS2:");
        sb.append(getActivityTS2());
        if(getActivityTS2() != Activity.IDLE)
        {
            sb.append(" [").append(getHashAddressTS2()).append("]");
        }
        sb.append(" MSG:").append(getMessage().toHexString());
        return sb.toString();
    }

    /**
     * Description of current activity for timeslot 1
     */
    public Activity getActivityTS1()
    {
        return Activity.fromValue(getMessage().getInt(TIMESLOT_1_ACTIVITY));
    }

    /**
     * Description of current activity for timeslot 2
     */
    public Activity getActivityTS2()
    {
        return Activity.fromValue(getMessage().getInt(TIMESLOT_2_ACTIVITY));
    }

    /**
     * Hashed address for the radio or talkgroup active on timeslot 1
     */
    public String getHashAddressTS1()
    {
        return String.format("%02X", getMessage().getInt(TIMESLOT_1_HASH_ADDRESS));
    }

    /**
     * Hashed address for the radio or talkgroup active on timeslot 2
     */
    public String getHashAddressTS2()
    {
        return String.format("%02X", getMessage().getInt(TIMESLOT_2_HASH_ADDRESS));
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.emptyList();
    }
}
