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
import io.github.dsheirer.module.decode.p25.IServiceOptionsProvider;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBandReceiver;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.OSPMessage;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Telephone interconnect voice call channel grant.
 */
public class TelephoneInterconnectVoiceChannelGrantUpdate extends OSPMessage implements IFrequencyBandReceiver, IServiceOptionsProvider
{
    private static final IntField SERVICE_OPTIONS = IntField.length8(16);
    private static final IntField FREQUENCY_BAND = IntField.length4(24);
    private static final IntField CHANNEL_NUMBER = IntField.length12(28);
    private static final IntField CALL_TIMER = IntField.length16(40);
    private static final IntField ANY_ADDRESS = IntField.length24(56);

    private VoiceServiceOptions mVoiceServiceOptions;
    private APCO25Channel mChannel;
    private Identifier mAnyAddress;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public TelephoneInterconnectVoiceChannelGrantUpdate(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" RADIO:").append(getAnyAddress());
        sb.append(" TIMER:").append(getCallTimer());
        sb.append("ms CHAN:").append(getChannel());
        sb.append(" ").append(getServiceOptions().toString());
        return sb.toString();
    }

    /**
     * Service options for the request
     */
    public VoiceServiceOptions getServiceOptions()
    {
        if(mVoiceServiceOptions == null)
        {
            mVoiceServiceOptions = new VoiceServiceOptions(getMessage().getInt(SERVICE_OPTIONS));
        }

        return mVoiceServiceOptions;
    }

    /**
     * Call duration timer in milliseconds
     */
    public long getCallTimer()
    {
        return getMessage().getInt(CALL_TIMER) * 100l; //Convert from 100ms units to milliseconds
    }

    public APCO25Channel getChannel()
    {
        if(mChannel == null)
        {
            mChannel = APCO25Channel.create(getMessage().getInt(FREQUENCY_BAND), getMessage().getInt(CHANNEL_NUMBER));
        }

        return mChannel;
    }

    public Identifier getAnyAddress()
    {
        if(mAnyAddress == null)
        {
            mAnyAddress = APCO25Talkgroup.create(getMessage().getInt(ANY_ADDRESS));
        }

        return mAnyAddress;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getAnyAddress());
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
