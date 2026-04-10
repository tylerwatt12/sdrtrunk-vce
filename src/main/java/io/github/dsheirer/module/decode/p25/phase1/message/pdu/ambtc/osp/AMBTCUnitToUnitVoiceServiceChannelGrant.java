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

package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp;

import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.IServiceOptionsProvider;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25ExplicitChannel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBandReceiver;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import java.util.ArrayList;
import java.util.List;

public class AMBTCUnitToUnitVoiceServiceChannelGrant extends AMBTCMessage implements IFrequencyBandReceiver, IServiceOptionsProvider
{
    private static final IntField HEADER_SERVICE_OPTIONS = IntField.length8(64);
    private static final IntField HEADER_TARGET_WACN = IntField.length8(72);
    private static final IntField BLOCK_0_SOURCE_WACN = IntField.length20(0);
    private static final IntField BLOCK_0_SOURCE_SYSTEM = IntField.length12(20);
    private static final IntField BLOCK_0_SOURCE_ID = IntField.length24(32);
    private static final IntField BLOCK_0_TARGET_ADDRESS = IntField.length24(56);
    private static final IntField BLOCK_0_DOWNLINK_FREQUENCY_BAND = IntField.length4(80);
    private static final IntField BLOCK_0_DOWNLINK_CHANNEL_NUMBER = IntField.length12(84);
    private static final IntField BLOCK_1_UPLINK_FREQUENCY_BAND = IntField.length4(0);
    private static final IntField BLOCK_1_UPLINK_CHANNEL_NUMBER = IntField.length12(4);
    private static final IntField BLOCK_1_TARGET_WACN = IntField.length12(16);
    private static final IntField BLOCK_1_TARGET_SYSTEM = IntField.length12(28);
    private static final IntField BLOCK_1_TARGET_ID = IntField.length24(40);

    private VoiceServiceOptions mVoiceServiceOptions;
    private APCO25FullyQualifiedRadioIdentifier mSourceAddress;
    private APCO25FullyQualifiedRadioIdentifier mTargetAddress;
    private List<Identifier> mIdentifiers;
    private APCO25Channel mChannel;
    private List<IChannelDescriptor> mChannels;

    public AMBTCUnitToUnitVoiceServiceChannelGrant(PDUSequence PDUSequence, int nac, long timestamp)
    {
        super(PDUSequence, nac, timestamp);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        if(getSourceAddress() != null)
        {
            sb.append(" FM:").append(getSourceAddress());
        }
        if(getTargetAddress() != null)
        {
            sb.append(" TO:").append(getTargetAddress());
        }
        sb.append(" CHANNEL:").append(getChannel());
        sb.append(" SERVICE OPTIONS:").append(getServiceOptions());
        return sb.toString();
    }

    public VoiceServiceOptions getServiceOptions()
    {
        if(mVoiceServiceOptions == null)
        {
            mVoiceServiceOptions = new VoiceServiceOptions(getHeader().getMessage().getInt(HEADER_SERVICE_OPTIONS));
        }

        return mVoiceServiceOptions;
    }

    public APCO25FullyQualifiedRadioIdentifier getSourceAddress()
    {
        if(mSourceAddress == null && hasDataBlock(0))
        {
            int localAddress = getHeader().getMessage().getInt(HEADER_ADDRESS);
            int wacn = getDataBlock(0).getMessage().getInt(BLOCK_0_SOURCE_WACN);
            int system = getDataBlock(0).getMessage().getInt(BLOCK_0_SOURCE_SYSTEM);
            int id = getDataBlock(0).getMessage().getInt(BLOCK_0_SOURCE_ID);
            mSourceAddress = APCO25FullyQualifiedRadioIdentifier.createFrom(localAddress, wacn, system, id);
        }

        return mSourceAddress;
    }

    public Identifier getTargetAddress()
    {
        if(mTargetAddress == null && hasDataBlock(0) && hasDataBlock(1))
        {
            int localAddress = getDataBlock(0).getMessage().getInt(BLOCK_0_TARGET_ADDRESS);
            int wacn = getHeader().getMessage().getInt(HEADER_TARGET_WACN) << 12;
            wacn += getDataBlock(1).getMessage().getInt(BLOCK_1_TARGET_WACN);
            int system = getDataBlock(1).getMessage().getInt(BLOCK_1_TARGET_SYSTEM);
            int id = getDataBlock(1).getMessage().getInt(BLOCK_1_TARGET_ID);
            mTargetAddress = APCO25FullyQualifiedRadioIdentifier.createTo(localAddress, wacn, system, id);
        }

        return mTargetAddress;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            if(getSourceAddress() != null)
            {
                mIdentifiers.add(getSourceAddress());
            }
            if(getTargetAddress() != null)
            {
                mIdentifiers.add(getTargetAddress());
            }
            if(getChannel() != null)
            {
                mIdentifiers.add(getChannel());
            }
        }

        return mIdentifiers;
    }

    public boolean isExtendedChannel()
    {
        return hasDataBlock(0) &&
            (getDataBlock(0).getMessage().getInt(BLOCK_0_DOWNLINK_CHANNEL_NUMBER) !=
                getDataBlock(0).getMessage().getInt(BLOCK_1_UPLINK_CHANNEL_NUMBER));
    }

    public APCO25Channel getChannel()
    {
        if(mChannel == null)
        {
            if(hasDataBlock(0))
            {
                UnconfirmedDataBlock block0 = getDataBlock(0);

                if(isExtendedChannel() && hasDataBlock(1))
                {
                    UnconfirmedDataBlock block1 = getDataBlock(1);
                    mChannel = APCO25ExplicitChannel.create(block0.getMessage().getInt(BLOCK_0_DOWNLINK_FREQUENCY_BAND),
                        block0.getMessage().getInt(BLOCK_0_DOWNLINK_CHANNEL_NUMBER),
                        block1.getMessage().getInt(BLOCK_1_UPLINK_FREQUENCY_BAND),
                        block1.getMessage().getInt(BLOCK_1_UPLINK_CHANNEL_NUMBER));
                }
                else
                {
                    mChannel = APCO25Channel.create(block0.getMessage().getInt(BLOCK_0_DOWNLINK_FREQUENCY_BAND),
                        block0.getMessage().getInt(BLOCK_0_DOWNLINK_CHANNEL_NUMBER));
                }
            }

            if(mChannel == null)
            {
                mChannel = APCO25Channel.create(-1, 0);
            }
        }

        return mChannel;
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
