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
import io.github.dsheirer.module.decode.p25.identifier.message.APCO25ShortDataMessage;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Message update - extended format
 */
public class AMBTCMessageUpdate extends AMBTCMessage
{
    private static final IntField HEADER_SOURCE_WACN = IntField.length16(64);
    private static final IntField BLOCK_0_SOURCE_WACN = IntField.length4(0);
    private static final IntField BLOCK_0_SOURCE_SYSTEM = IntField.length12(4);
    private static final IntField BLOCK_0_SOURCE_ID = IntField.length24(16);
    private static final IntField BLOCK_0_SDM = IntField.length16(40);
    private static final IntField BLOCK_0_SOURCE_ADDRESS = IntField.length16(80);
    private static final IntField BLOCK_1_SOURCE_ADDRESS = IntField.length8(0);
    private static final IntField BLOCK_1_TARGET_WACN = IntField.length20(8);
    private static final IntField BLOCK_1_TARGET_SYSTEM = IntField.length12(28);
    private static final IntField BLOCK_1_TARGET_ID = IntField.length24(40);

    private APCO25FullyQualifiedRadioIdentifier mSourceAddress;
    private APCO25FullyQualifiedRadioIdentifier mTargetAddress;
    private Identifier mShortDataMessage;
    private List<Identifier> mIdentifiers;

    public AMBTCMessageUpdate(PDUSequence PDUSequence, int nac, long timestamp)
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
        if(getShortDataMessage() != null)
        {
            sb.append(" SHORT DATA MESSAGE:").append(getShortDataMessage());
        }

        return sb.toString();
    }

    public Identifier getTargetAddress()
    {
        if(mTargetAddress == null && hasDataBlock(1))
        {
            int localAddress = getHeader().getMessage().getInt(HEADER_ADDRESS);
            int wacn = getDataBlock(1).getMessage().getInt(BLOCK_1_TARGET_WACN);
            int system = getDataBlock(1).getMessage().getInt(BLOCK_1_TARGET_SYSTEM);
            int id = getDataBlock(1).getMessage().getInt(BLOCK_1_TARGET_ID);
            mTargetAddress = APCO25FullyQualifiedRadioIdentifier.createTo(localAddress, wacn, system, id);
        }

        return mTargetAddress;
    }

    public Identifier getSourceAddress()
    {
        if(mSourceAddress == null && hasDataBlock(0) && hasDataBlock(1))
        {
            int localAddress = getDataBlock(0).getMessage().getInt(BLOCK_0_SOURCE_ADDRESS);
            localAddress <<= 8;
            localAddress += getDataBlock(1).getMessage().getInt(BLOCK_1_SOURCE_ADDRESS);

            int wacn = getHeader().getMessage().getInt(HEADER_SOURCE_WACN);
            wacn <<= 4;
            wacn += getDataBlock(0).getMessage().getInt(BLOCK_0_SOURCE_WACN);

            int system = getDataBlock(0).getMessage().getInt(BLOCK_0_SOURCE_SYSTEM);
            int id = getDataBlock(0).getMessage().getInt(BLOCK_0_SOURCE_ID);

            mSourceAddress = APCO25FullyQualifiedRadioIdentifier.createFrom(localAddress, wacn, system, id);
        }

        return mSourceAddress;
    }

    public Identifier getShortDataMessage()
    {
        if(mShortDataMessage == null && hasDataBlock(0))
        {
            mShortDataMessage = APCO25ShortDataMessage.create(getDataBlock(0).getMessage().getInt(BLOCK_0_SDM));
        }

        return mShortDataMessage;
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
            if(getShortDataMessage() != null)
            {
                mIdentifiers.add(getShortDataMessage());
            }
        }

        return mIdentifiers;
    }
}
