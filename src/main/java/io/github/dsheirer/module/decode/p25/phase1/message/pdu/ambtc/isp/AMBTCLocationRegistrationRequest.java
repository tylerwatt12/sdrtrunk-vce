/*
 *
 *  * ******************************************************************************
 *  * Copyright (C) 2014-2020 Dennis Sheirer
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

package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.isp;

import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Lra;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCMessage;

import java.util.ArrayList;
import java.util.List;

public class AMBTCLocationRegistrationRequest extends AMBTCMessage
{
    private static final IntField HEADER_WACN = IntField.length16(64);
    private static final IntField BLOCK_0_WACN = IntField.length4(0);
    private static final IntField BLOCK_0_SYSTEM = IntField.length12(4);
    private static final IntField BLOCK_0_SOURCE_ID = IntField.length24(16);
    private static final IntField BLOCK_0_PREVIOUS_LRA = IntField.length8(40);
    private static final IntField BLOCK_0_GROUP_ADDRESS = IntField.length16(48);

    private Identifier mWacn;
    private Identifier mSystem;
    private Identifier mSourceId;
    private Identifier mSourceAddress;
    private Identifier mGroupAddress;
    private Identifier mPreviousLra;
    private List<Identifier> mIdentifiers;

    public AMBTCLocationRegistrationRequest(PDUSequence PDUSequence, int nac, long timestamp)
    {
        super(PDUSequence, nac, timestamp);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" FM ADDR:").append(getSourceAddress());
        if(getSourceId() != null)
        {
            sb.append(" FM ID:").append(getSourceId());
        }
        if(getGroupAddress() != null)
        {
            sb.append(" TO:").append(getGroupAddress());
        }
        if(getWacn() != null)
        {
            sb.append(" WACN:").append(getWacn());
        }
        if(getSystem() != null)
        {
            sb.append(" SYSTEM:").append(getSystem());
        }
        if(getPreviousLra() != null)
        {
            sb.append(" PREVIOUS LRA:").append(getPreviousLra());
        }
        return sb.toString();
    }

    public Identifier getGroupAddress()
    {
        if(mGroupAddress == null && hasDataBlock(0))
        {
            mGroupAddress = APCO25Talkgroup.create(getDataBlock(0).getMessage().getInt(BLOCK_0_GROUP_ADDRESS));
        }

        return mGroupAddress;
    }

    public Identifier getPreviousLra()
    {
        if(mPreviousLra == null && hasDataBlock(0))
        {
            mPreviousLra = APCO25Lra.create(getDataBlock(0).getMessage().getInt(BLOCK_0_PREVIOUS_LRA));
        }

        return mPreviousLra;
    }

    public Identifier getSourceId()
    {
        if(mSourceId == null)
        {
            // TODO: Investigate whether BLOCK_0_SOURCE_ID is correctly read from the AMBTC header here,
            // or whether this should come from data block 0 and the field name is the accurate one.
            mSourceId = APCO25RadioIdentifier.createFrom(getHeader().getMessage().getInt(BLOCK_0_SOURCE_ID));
        }

        return mSourceId;
    }

    public Identifier getWacn()
    {
        if(mWacn == null && hasDataBlock(0))
        {
            int value = getHeader().getMessage().getInt(HEADER_WACN);
            value <<= 4;
            value += getDataBlock(0).getMessage().getInt(BLOCK_0_WACN);
            mWacn = APCO25Wacn.create(value);
        }

        return mWacn;
    }

    public Identifier getSystem()
    {
        if(mSystem == null && hasDataBlock(0))
        {
            mSystem = APCO25System.create(getDataBlock(0).getMessage().getInt(BLOCK_0_SYSTEM));
        }

        return mSystem;
    }

    public Identifier getSourceAddress()
    {
        if(mSourceAddress == null && hasDataBlock(0))
        {
            mSourceAddress = APCO25RadioIdentifier.createFrom(getDataBlock(0).getMessage().getInt(HEADER_ADDRESS));
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
            if(getWacn() != null)
            {
                mIdentifiers.add(getWacn());
            }
            if(getSystem() != null)
            {
                mIdentifiers.add(getSystem());
            }
            if(getSourceId() != null)
            {
                mIdentifiers.add(getSourceId());
            }
            if(getGroupAddress() != null)
            {
                mIdentifiers.add(getGroupAddress());
            }
            if(getPreviousLra() != null)
            {
                mIdentifiers.add(getPreviousLra());
            }
        }

        return mIdentifiers;
    }
}
