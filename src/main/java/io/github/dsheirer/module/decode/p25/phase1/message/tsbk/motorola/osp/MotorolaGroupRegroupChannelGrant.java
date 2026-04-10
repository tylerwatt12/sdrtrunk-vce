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
package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.module.decode.p25.IServiceOptionsProvider;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBandReceiver;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.VendorOSPMessage;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import java.util.ArrayList;
import java.util.List;

public class MotorolaGroupRegroupChannelGrant extends VendorOSPMessage implements IFrequencyBandReceiver, IServiceOptionsProvider
{
    private static final IntField SERVICE_OPTIONS = IntField.length8(16);
    private static final IntField FREQUENCY_BAND = IntField.length4(24);
    private static final IntField CHANNEL_NUMBER = IntField.length12(28);
    private static final IntField PATCH_GROUP_ADDRESS = IntField.length16(40);
    private static final IntField SOURCE_ADDRESS = IntField.length24(56);

    private VoiceServiceOptions mVoiceServiceOptions;
    private APCO25Channel mChannel;
    private Identifier mSourceAddress;
    private PatchGroupIdentifier mPatchGroup;
    private List<Identifier> mIdentifiers;

    public MotorolaGroupRegroupChannelGrant(P25P1DataUnitID dataUnitID, CorrectedBinaryMessage message, int nac, long timestlot)
    {
        super(dataUnitID, message, nac, timestlot);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        sb.append(getMessageStub());
        sb.append(" PATCH GROUP:").append(getPatchGroup());
        sb.append(" FROM:").append(getSourceAddress());
        sb.append(" SERVICE OPTIONS:").append(getServiceOptions());

        return sb.toString();
    }

    public VoiceServiceOptions getServiceOptions()
    {
        if(mVoiceServiceOptions == null)
        {
            mVoiceServiceOptions = new VoiceServiceOptions(getMessage().getInt(SERVICE_OPTIONS));
        }

        return mVoiceServiceOptions;
    }

    public PatchGroupIdentifier getPatchGroup()
    {
        if(mPatchGroup == null)
        {
            mPatchGroup = APCO25PatchGroup.create(new PatchGroup(APCO25Talkgroup.create(getMessage().getInt(PATCH_GROUP_ADDRESS))));
        }

        return mPatchGroup;
    }

    public Identifier getSourceAddress()
    {
        if(mSourceAddress == null)
        {
            mSourceAddress = APCO25RadioIdentifier.createFrom(getMessage().getInt(SOURCE_ADDRESS));
        }

        return mSourceAddress;
    }

    public APCO25Channel getChannel()
    {
        if(mChannel == null)
        {
            mChannel = APCO25Channel.create(getMessage().getInt(FREQUENCY_BAND), getMessage().getInt(CHANNEL_NUMBER));
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
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getPatchGroup());
            mIdentifiers.add(getSourceAddress());
        }

        return mIdentifiers;
    }
}
