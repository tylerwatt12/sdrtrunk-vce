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
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Group affiliation query
 */
public class AMBTCGroupAffiliationQuery extends AMBTCMessage
{
    private static final IntField HEADER_WACN = IntField.length16(64);
    private static final IntField BLOCK_0_WACN = IntField.length4(0);
    private static final IntField BLOCK_0_SYSTEM = IntField.length12(4);
    private static final IntField BLOCK_0_SOURCE_ID = IntField.length24(16);

    private APCO25FullyQualifiedRadioIdentifier mSourceAddress;
    private Identifier mTargetAddress;
    private List<Identifier> mIdentifiers;

    public AMBTCGroupAffiliationQuery(PDUSequence PDUSequence, int nac, long timestamp)
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

        return sb.toString();
    }

    public Identifier getTargetAddress()
    {
        if(mTargetAddress == null && hasDataBlock(0))
        {
            mTargetAddress = APCO25RadioIdentifier.createTo(getHeader().getMessage().getInt(HEADER_ADDRESS));
        }

        return mTargetAddress;
    }

    public Identifier getSourceAddress()
    {
        if(mSourceAddress == null && hasDataBlock(0))
        {
            int wacn = getHeader().getMessage().getInt(HEADER_WACN);
            wacn <<= 4;
            wacn += getDataBlock(0).getMessage().getInt(BLOCK_0_WACN);
            int system = getDataBlock(0).getMessage().getInt(BLOCK_0_SYSTEM);
            int id = getDataBlock(0).getMessage().getInt(BLOCK_0_SOURCE_ID);
            //TODO: investigate if the first argument should be a separate local/header address instead of reusing source ID.
            mSourceAddress = APCO25FullyQualifiedRadioIdentifier.createFrom(id, wacn, system, id);
        }

        return mSourceAddress;
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
        }

        return mIdentifiers;
    }
}
