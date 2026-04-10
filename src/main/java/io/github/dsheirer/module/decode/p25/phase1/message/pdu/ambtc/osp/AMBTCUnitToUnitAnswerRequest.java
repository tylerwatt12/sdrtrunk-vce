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
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.IServiceOptionsProvider;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCMessage;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import java.util.ArrayList;
import java.util.List;

public class AMBTCUnitToUnitAnswerRequest extends AMBTCMessage implements IServiceOptionsProvider
{
    private static final IntField HEADER_SERVICE_OPTIONS = IntField.length8(64);
    private static final IntField BLOCK_0_SOURCE_WACN = IntField.length20(8);
    private static final IntField BLOCK_0_SOURCE_SYSTEM = IntField.length12(28);
    private static final IntField BLOCK_0_SOURCE_ID = IntField.length24(40);

    private VoiceServiceOptions mVoiceServiceOptions;
    private APCO25FullyQualifiedRadioIdentifier mSourceAddress;
    private Identifier mTargetAddress;
    private List<Identifier> mIdentifiers;

    public AMBTCUnitToUnitAnswerRequest(PDUSequence PDUSequence, int nac, long timestamp)
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
        sb.append(" TO:").append(getTargetAddress());
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
            int wacn = getDataBlock(0).getMessage().getInt(BLOCK_0_SOURCE_WACN);
            int system = getDataBlock(0).getMessage().getInt(BLOCK_0_SOURCE_SYSTEM);
            int id = getDataBlock(0).getMessage().getInt(BLOCK_0_SOURCE_ID);
            //TODO: investigate if the first argument should be a separate local/header address instead of reusing source ID.
            mSourceAddress = APCO25FullyQualifiedRadioIdentifier.createFrom(id, wacn, system, id);
        }

        return mSourceAddress;
    }

    public Identifier getTargetAddress()
    {
        if(mTargetAddress == null)
        {
            mTargetAddress = APCO25RadioIdentifier.createTo(getHeader().getMessage().getInt(HEADER_ADDRESS));
        }

        return mTargetAddress;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            if(getTargetAddress() != null)
            {
                mIdentifiers.add(getTargetAddress());
            }
            if(getSourceAddress() != null)
            {
                mIdentifiers.add(getSourceAddress());
            }
        }

        return mIdentifiers;
    }
}
