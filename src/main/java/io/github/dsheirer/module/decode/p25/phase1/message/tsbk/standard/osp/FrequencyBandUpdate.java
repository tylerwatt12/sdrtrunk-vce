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

package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.bits.LongField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.OSPMessage;

import java.util.Collections;
import java.util.List;

/**
 * Identifier Update - Frequency Band details
 */
public class FrequencyBandUpdate extends OSPMessage implements IFrequencyBand
{
    private static final IntField FREQUENCY_BAND_IDENTIFIER = IntField.length4(16);
    private static final IntField BANDWIDTH = IntField.range(20, 28);
    private static final int TRANSMIT_OFFSET_SIGN = 29;
    private static final LongField TRANSMIT_OFFSET = LongField.length8(30);
    private static final IntField CHANNEL_SPACING = IntField.range(38, 47);
    private static final LongField BASE_FREQUENCY = LongField.length32(48);

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public FrequencyBandUpdate(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" ID:").append(getIdentifier());
        sb.append(" OFFSET:").append(getTransmitOffset());
        sb.append(" SPACING:").append(getChannelSpacing());
        sb.append(" BASE:").append(getBaseFrequency());
        sb.append(" FDMA BW:").append(getBandwidth());
        return sb.toString();
    }

    @Override
    public int getIdentifier()
    {
        return getMessage().getInt(FREQUENCY_BAND_IDENTIFIER);
    }

    @Override
    public long getChannelSpacing()
    {
        return getMessage().getInt(CHANNEL_SPACING) * 125;
    }

    @Override
    public long getBaseFrequency()
    {
        return getMessage().getLong(BASE_FREQUENCY) * 5;
    }

    @Override
    public int getBandwidth()
    {
        return getMessage().getInt(BANDWIDTH) * 125;
    }

    @Override
    public long getTransmitOffset()
    {
        long offset = getMessage().getLong(TRANSMIT_OFFSET) * 250000;

        if(!getMessage().get(TRANSMIT_OFFSET_SIGN))
        {
            offset *= -1;
        }

        return offset;
    }

    /**
     * Indicates if the frequency band has a transmit option for the subscriber unit.
     */
    public boolean hasTransmitOffset()
    {
        return getMessage().getLong(TRANSMIT_OFFSET) != 0x80;
    }

    @Override
    public long getDownlinkFrequency(int channelNumber)
    {
        return getBaseFrequency() + (getChannelSpacing() * channelNumber);
    }

    @Override
    public long getUplinkFrequency(int channelNumber)
    {
        if(hasTransmitOffset())
        {
            return getDownlinkFrequency(channelNumber) + getTransmitOffset();
        }

        return 0;
    }

    @Override
    public boolean isTDMA()
    {
        return false;
    }

    @Override
    public int getTimeslotCount()
    {
        return 1;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.emptyList();
    }
}
