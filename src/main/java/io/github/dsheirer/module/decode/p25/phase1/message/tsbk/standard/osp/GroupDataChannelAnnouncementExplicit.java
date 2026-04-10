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
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25ExplicitChannel;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBandReceiver;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.OSPMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Group voice channel grant update.
 */
public class GroupDataChannelAnnouncementExplicit extends OSPMessage implements IFrequencyBandReceiver
{
    private static final IntField DOWNLINK_FREQUENCY_BAND = IntField.length4(32);
    private static final IntField DOWNLINK_CHANNEL_NUMBER = IntField.length12(36);
    private static final IntField UPLINK_FREQUENCY_BAND = IntField.length4(48);
    private static final IntField UPLINK_CHANNEL_NUMBER = IntField.length12(52);
    private static final IntField GROUP_ADDRESS = IntField.length16(64);

    private IChannelDescriptor mChannel;
    private Identifier mGroupAddress;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public GroupDataChannelAnnouncementExplicit(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" GROUP:").append(getGroupAddress());
        sb.append(" CHAN:").append(getChannel());
        return sb.toString();
    }

    public IChannelDescriptor getChannel()
    {
        if(mChannel == null)
        {
            mChannel = APCO25ExplicitChannel.create(getMessage().getInt(DOWNLINK_FREQUENCY_BAND),
                    getMessage().getInt(DOWNLINK_CHANNEL_NUMBER), getMessage().getInt(UPLINK_FREQUENCY_BAND),
                    getMessage().getInt(UPLINK_CHANNEL_NUMBER));
        }

        return mChannel;
    }

    public Identifier getGroupAddress()
    {
        if(mGroupAddress == null)
        {
            mGroupAddress = APCO25Talkgroup.create(getMessage().getInt(GROUP_ADDRESS));
        }

        return mGroupAddress;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getGroupAddress());
        }

        return mIdentifiers;
    }

    @Override
    public List<IChannelDescriptor> getChannels()
    {
        List<IChannelDescriptor> channels = new ArrayList<>();
        channels.add(getChannel());
        return channels;
    }
}
