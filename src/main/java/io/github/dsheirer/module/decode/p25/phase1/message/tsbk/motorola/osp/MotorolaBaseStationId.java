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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MotorolaBaseStationId extends OSPMessage implements IFrequencyBandReceiver
{
    private static final IntField CHARACTER_1 = IntField.length6(16);
    private static final IntField CHARACTER_2 = IntField.length6(22);
    private static final IntField CHARACTER_3 = IntField.length6(28);
    private static final IntField CHARACTER_4 = IntField.length6(34);
    private static final IntField CHARACTER_5 = IntField.length6(40);
    private static final IntField CHARACTER_6 = IntField.length6(46);
    private static final IntField CHARACTER_7 = IntField.length6(52);
    private static final IntField CHARACTER_8 = IntField.length6(58);
    private static final IntField FREQUENCY_BAND = IntField.length4(64);
    private static final IntField CHANNEL_NUMBER = IntField.length12(68);

    private String mCWID;
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
            sb.append(" CWID:").append(getCWID());
        }
        else
        {
            sb.append(" CWID NOT SPECIFIED");
        }

        return sb.toString();
    }

    public boolean hasChannel()
    {
        return getMessage().getInt(CHANNEL_NUMBER) != 0;
    }


    public String getCWID()
    {
        if(mCWID == null)
        {
            StringBuilder sb = new StringBuilder();

            sb.append(getCharacter(CHARACTER_1));
            sb.append(getCharacter(CHARACTER_2));
            sb.append(getCharacter(CHARACTER_3));
            sb.append(getCharacter(CHARACTER_4));
            sb.append(getCharacter(CHARACTER_5));
            sb.append(getCharacter(CHARACTER_6));
            sb.append(getCharacter(CHARACTER_7));
            sb.append(getCharacter(CHARACTER_8));

            mCWID = sb.toString();
        }

        return mCWID;
    }

    private String getCharacter(IntField field)
    {
        int value = getMessage().getInt(field);

        if(value != 0)
        {
            return String.valueOf((char)(value + 43));
        }

        return "";
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
