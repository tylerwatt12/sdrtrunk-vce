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

package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.isp;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.ISPMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Group affiliation request
 */
public class GroupAffiliationRequest extends ISPMessage
{
    private static final IntField SYSTEM_ID = IntField.length12(28);
    private static final IntField GROUP_ID = IntField.length16(40);
    private static final IntField SOURCE_ADDRESS = IntField.length24(56);

    private Identifier mSystemId;
    private Identifier mGroupAddress;
    private Identifier mSourceAddress;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public GroupAffiliationRequest(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" FM:").append(getSourceAddress());
        sb.append(" TO:").append(getGroupAddress());
        sb.append(" SYSTEM:").append(getSystemId());
        return sb.toString();
    }

    public Identifier getSystemId()
    {
        if(mSystemId == null)
        {
            mSystemId = APCO25System.create(getMessage().getInt(SYSTEM_ID));
        }

        return mSystemId;
    }

    public Identifier getGroupAddress()
    {
        if(mGroupAddress == null)
        {
            mGroupAddress = APCO25Talkgroup.create(getMessage().getInt(GROUP_ID));
        }

        return mGroupAddress;
    }

    public Identifier getSourceAddress()
    {
        if(mSourceAddress == null)
        {
            mSourceAddress = APCO25RadioIdentifier.createFrom(getMessage().getInt(SOURCE_ADDRESS));
        }

        return mSourceAddress;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getSystemId());
            mIdentifiers.add(getGroupAddress());
            mIdentifiers.add(getSourceAddress());
        }

        return mIdentifiers;
    }
}
