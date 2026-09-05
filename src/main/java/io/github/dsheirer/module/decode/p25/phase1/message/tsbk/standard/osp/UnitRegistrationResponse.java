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

package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25IncompleteRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.OSPMessage;
import io.github.dsheirer.module.decode.p25.reference.Response;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit registration response
 */
public class UnitRegistrationResponse extends OSPMessage
{
    private static final IntField RESPONSE = IntField.length2(18);
    private static final IntField SYSTEM_ID = IntField.length12(20);
    private static final IntField SOURCE_ID = IntField.length24(32);
    private static final IntField SOURCE_ADDRESS = IntField.length24(56);

    private Response mResponse;
    private Identifier mRegisteredRadio;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public UnitRegistrationResponse(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" REGISTRATION ").append(getResponse().name());
        sb.append(" FOR RADIO:").append(getRegisteredRadio());
        return sb.toString();
    }

    public Identifier getRegisteredRadio()
    {
        if(mRegisteredRadio == null)
        {
            int localAddress = getSourceAddress();
            mRegisteredRadio = APCO25IncompleteRadioIdentifier.createTo(
                localAddress > 0 ? localAddress : getSourceId());
        }

        return mRegisteredRadio;
    }

    /**
     * Returns the complete subscriber identity when the decoder has already established the serving WACN.  The
     * abbreviated message does not carry that value and must never substitute zero for it (TIA-102.AABC-B,
     * Section 6.2.21.1).
     */
    public Identifier getRegisteredRadio(Integer servingWacn)
    {
        if(servingWacn == null || servingWacn < 0 || servingWacn > 0xFFFFF)
        {
            return getRegisteredRadio();
        }

        int workingAddress = getSourceAddress() > 0 ? getSourceAddress() : getSourceId();
        return APCO25FullyQualifiedRadioIdentifier.createTo(workingAddress, servingWacn,
            getSourceSystemId(), getSourceId());
    }

    public int getSourceSystemId()
    {
        return getMessage().getInt(SYSTEM_ID);
    }

    public int getSourceId()
    {
        return getMessage().getInt(SOURCE_ID);
    }

    public int getSourceAddress()
    {
        return getMessage().getInt(SOURCE_ADDRESS);
    }

    public Response getResponse()
    {
        if(mResponse == null)
        {
            mResponse = Response.fromValue(getMessage().getInt(RESPONSE));
        }

        return mResponse;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getRegisteredRadio());
        }

        return mIdentifiers;
    }
}
