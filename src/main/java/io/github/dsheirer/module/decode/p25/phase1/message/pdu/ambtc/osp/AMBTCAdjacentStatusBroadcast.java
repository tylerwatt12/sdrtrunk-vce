/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp;

import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Lra;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Rfss;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Site;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25ExplicitChannel;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBandReceiver;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;
import java.util.ArrayList;
import java.util.List;

public class AMBTCAdjacentStatusBroadcast extends AMBTCMessage implements IFrequencyBandReceiver
{

    private static final IntField HEADER_LRA = IntField.length8(24);
    private static final IntField HEADER_SYSTEM = IntField.length12(36);
    private static final IntField HEADER_RFSS = IntField.length8(64);
    private static final IntField HEADER_SITE = IntField.length8(72);
    private static final IntField BLOCK_0_DOWNLINK_FREQUENCY_BAND = IntField.length4(0);
    private static final IntField BLOCK_0_DOWNLINK_CHANNEL_NUMBER = IntField.length12(4);
    private static final IntField BLOCK_0_UPLINK_FREQUENCY_BAND = IntField.length4(16);
    private static final IntField BLOCK_0_UPLINK_CHANNEL_NUMBER = IntField.length12(20);

    private Identifier mSystem;
    private Identifier mLocationRegistrationArea;
    private Identifier mRfss;
    private Identifier mSite;
    private IChannelDescriptor mChannel;
    private List<Identifier> mIdentifiers;
    private List<IChannelDescriptor> mChannels;

    public AMBTCAdjacentStatusBroadcast(PDUSequence PDUSequence, int nac, long timestamp)
    {
        super(PDUSequence, nac, timestamp);

        setValid(hasDataBlock(0));
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" SYSTEM:").append(getSystem());
        sb.append(" LRA:").append(getLocationRegistrationArea());
        sb.append(" RFSS:").append(getRfss());
        sb.append(" SITE:").append(getSite());
        sb.append(" CHANNEL:").append(getChannel());
        return sb.toString();
    }

    public Identifier getSystem()
    {
        if(mSystem == null)
        {
            mSystem = APCO25System.create(getHeader().getMessage().getInt(HEADER_SYSTEM));
        }

        return mSystem;
    }

    public Identifier getLocationRegistrationArea()
    {
        if(mLocationRegistrationArea == null)
        {
            mLocationRegistrationArea = APCO25Lra.create(getHeader().getMessage().getInt(HEADER_LRA));
        }

        return mLocationRegistrationArea;
    }

    public Identifier getRfss()
    {
        if(mRfss == null)
        {
            mRfss = APCO25Rfss.create(getHeader().getMessage().getInt(HEADER_RFSS));
        }

        return mRfss;
    }

    public Identifier getSite()
    {
        if(mSite == null)
        {
            mSite = APCO25Site.create(getHeader().getMessage().getInt(HEADER_SITE));
        }

        return mSite;
    }

    public boolean isExtendedChannel()
    {
        return hasDataBlock(0) &&
            (getDataBlock(0).getMessage().getInt(BLOCK_0_DOWNLINK_CHANNEL_NUMBER) !=
                getDataBlock(0).getMessage().getInt(BLOCK_0_UPLINK_CHANNEL_NUMBER));
    }

    public IChannelDescriptor getChannel()
    {
        if(mChannel == null && hasDataBlock(0))
        {
            if(hasDataBlock(0))
            {
                UnconfirmedDataBlock block = getDataBlock(0);

                if(isExtendedChannel())
                {
                    mChannel = APCO25ExplicitChannel.create(block.getMessage().getInt(BLOCK_0_DOWNLINK_FREQUENCY_BAND),
                        block.getMessage().getInt(BLOCK_0_DOWNLINK_CHANNEL_NUMBER),
                        block.getMessage().getInt(BLOCK_0_UPLINK_FREQUENCY_BAND),
                        block.getMessage().getInt(BLOCK_0_UPLINK_CHANNEL_NUMBER));
                }
                else
                {
                    mChannel = APCO25Channel.create(block.getMessage().getInt(BLOCK_0_DOWNLINK_FREQUENCY_BAND),
                        block.getMessage().getInt(BLOCK_0_DOWNLINK_CHANNEL_NUMBER));
                }
            }
            else
            {
                mChannel = APCO25Channel.create(-1, 0);
            }
        }

        return mChannel;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            if(getRfss() != null)
            {
                mIdentifiers.add(getRfss());
            }
            if(getSite() != null)
            {
                mIdentifiers.add(getSite());
            }
        }

        return mIdentifiers;
    }

    @Override
    public List<IChannelDescriptor> getChannels()
    {
        if(mChannels == null)
        {
            mChannels = new ArrayList<>();

            if(getChannel() != null)
            {
                mChannels.add(getChannel());
            }
        }

        return mChannels;
    }
}
