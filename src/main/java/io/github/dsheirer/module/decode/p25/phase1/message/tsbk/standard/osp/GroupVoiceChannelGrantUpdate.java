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

package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBandReceiver;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.OSPMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Group voice channel grant update.
 */
public class GroupVoiceChannelGrantUpdate extends OSPMessage implements IFrequencyBandReceiver
{
    private static final IntField FREQUENCY_BAND_A = IntField.length4(16);
    private static final IntField CHANNEL_NUMBER_A = IntField.length12(20);
    private static final IntField GROUP_ADDRESS_A = IntField.length16(32);
    private static final IntField FREQUENCY_BAND_B = IntField.length4(48);
    private static final IntField CHANNEL_NUMBER_B = IntField.length12(52);
    private static final IntField GROUP_ADDRESS_B = IntField.length16(64);

    private APCO25Channel mChannelA;
    private Identifier mGroupAddressA;
    private APCO25Channel mChannelB;
    private Identifier mGroupAddressB;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public GroupVoiceChannelGrantUpdate(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" GROUP A:").append(getGroupAddressA());
        sb.append(" CHAN A:").append(getChannelA());
        if(hasGroupB())
        {
            sb.append(" GROUP B:").append(getGroupAddressB());
            sb.append(" CHAN B:").append(getChannelB());
        }
        return sb.toString();
    }

    public APCO25Channel getChannelA()
    {
        if(mChannelA == null)
        {
            mChannelA = APCO25Channel.create(getMessage().getInt(FREQUENCY_BAND_A),
                    getMessage().getInt(CHANNEL_NUMBER_A));
        }

        return mChannelA;
    }

    public Identifier getGroupAddressA()
    {
        if(mGroupAddressA == null)
        {
            mGroupAddressA = APCO25Talkgroup.create(getMessage().getInt(GROUP_ADDRESS_A));
        }

        return mGroupAddressA;
    }

    public boolean hasGroupB()
    {
        return (getMessage().getInt(GROUP_ADDRESS_A) != getMessage().getInt(GROUP_ADDRESS_B)) &&
            getMessage().getInt(GROUP_ADDRESS_B) != 0;
    }

    public APCO25Channel getChannelB()
    {
        if(mChannelB == null)
        {
            mChannelB = APCO25Channel.create(getMessage().getInt(FREQUENCY_BAND_B),
                    getMessage().getInt(CHANNEL_NUMBER_B));
        }

        return mChannelB;
    }

    public Identifier getGroupAddressB()
    {
        if(mGroupAddressB == null)
        {
            mGroupAddressB = APCO25Talkgroup.create(getMessage().getInt(GROUP_ADDRESS_B));
        }

        return mGroupAddressB;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getGroupAddressA());
            if(hasGroupB())
            {
                mIdentifiers.add(getGroupAddressB());
            }
        }

        return mIdentifiers;
    }

    @Override
    public List<IChannelDescriptor> getChannels()
    {
        List<IChannelDescriptor> channels = new ArrayList<>();
        channels.add(getChannelA());

        if(hasGroupB())
        {
            channels.add(getChannelB());
        }

        return channels;
    }
}
