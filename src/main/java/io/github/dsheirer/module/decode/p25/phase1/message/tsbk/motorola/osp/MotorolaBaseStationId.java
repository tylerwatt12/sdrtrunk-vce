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
package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBandReceiver;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.OSPMessage;

import io.github.dsheirer.module.decode.p25.reference.MotorolaBsi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MotorolaBaseStationId extends OSPMessage implements IFrequencyBandReceiver
{
    private static final IntField FREQUENCY_BAND = IntField.length4(64);
    private static final IntField CHANNEL_NUMBER = IntField.length12(68);

    private String mCallsign;
    private IChannelDescriptor mChannel;

    public MotorolaBaseStationId(P25P1DataUnitID dataUnitID, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitID, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        sb.append(getMessageStub());

        if(hasChannel())
        {
            sb.append(" CHAN:").append(getChannel());
            sb.append(" BSI:").append(getCallsign());
        }
        else
        {
            sb.append(" BSI NOT SPECIFIED");
        }

        return sb.toString();
    }

    public boolean hasChannel()
    {
        return getMessage().getInt(CHANNEL_NUMBER) != 0;
    }


    public String getCallsign()
    {
        if(mCallsign == null)
        {
            mCallsign = MotorolaBsi.decodeCallsign(getMessage());
        }

        return mCallsign;
    }

    public IChannelDescriptor getChannel()
    {
        if(mChannel == null)
        {
            mChannel = APCO25Channel.create(getMessage().getInt(FREQUENCY_BAND),
                getMessage().getInt(CHANNEL_NUMBER));
        }

        return mChannel;
    }

    @Override
    public List<IChannelDescriptor> getChannels()
    {
        List<IChannelDescriptor> channels = new ArrayList<>();
        channels.add(getChannel());
        return channels;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.emptyList();
    }
}
