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
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCMessage;

import java.util.ArrayList;
import java.util.List;

public class AMBTCAuthenticationResponse extends AMBTCMessage
{
    private static final IntField HEADER_WACN = IntField.length16(64);
    private static final IntField BLOCK_0_WACN = IntField.length4(0);
    private static final IntField BLOCK_0_SYSTEM = IntField.length12(4);
    private static final IntField BLOCK_0_SOURCE_ID = IntField.length24(16);
    private static final IntField BLOCK_0_AUTHENTICATION_VALUE = IntField.length24(40);
    private static final IntField BLOCK_1_AUTHENTICATION_VALUE = IntField.length4(0);

    private String mAuthenticationValue;
    private Identifier mWacn;
    private Identifier mSystem;
    private Identifier mTargetAddress;
    private Identifier mSourceId;
    private List<Identifier> mIdentifiers;

    public AMBTCAuthenticationResponse(PDUSequence PDUSequence, int nac, long timestamp)
    {
        super(PDUSequence, nac, timestamp);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" FM:").append(getSourceId());
        if(getSourceId() != null)
        {
            sb.append(" TO:").append(getTargetAddress());
        }
        if(getWacn() != null)
        {
            sb.append(" WACN:").append(getWacn());
        }
        if(getSystem() != null)
        {
            sb.append(" SYSTEM:").append(getSystem());
        }
        if(getAuthenticationValue() != null)
        {
            sb.append(" AUTHENTICATION:").append(getAuthenticationValue());
        }
        return sb.toString();
    }

    public Identifier getTargetAddress()
    {
        if(mTargetAddress == null)
        {
            mTargetAddress = APCO25RadioIdentifier.createTo(getHeader().getMessage().getInt(HEADER_ADDRESS));
        }

        return mTargetAddress;
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

    public Identifier getSourceId()
    {
        if(mSourceId == null && hasDataBlock(0))
        {
            mSourceId = APCO25RadioIdentifier.createFrom(getDataBlock(0).getMessage().getInt(BLOCK_0_SOURCE_ID));
        }

        return mSourceId;
    }

    public String getAuthenticationValue()
    {
        if(mAuthenticationValue == null && hasDataBlock(0) && hasDataBlock(1))
        {
            //TODO: verify the intended authentication value width per spec; preserve the legacy 14+2 hex-digit formatting for now.
            mAuthenticationValue = String.format("%014X", getDataBlock(0).getMessage().getInt(BLOCK_0_AUTHENTICATION_VALUE)) +
                String.format("%02X", getDataBlock(1).getMessage().getInt(BLOCK_1_AUTHENTICATION_VALUE));
        }

        return mAuthenticationValue;
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
        }

        return mIdentifiers;
    }
}
