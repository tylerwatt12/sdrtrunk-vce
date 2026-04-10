/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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

package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.isp;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.ISPMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.reference.CancelReason;
import io.github.dsheirer.module.decode.p25.reference.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Cancel Service Request
 */
public class CancelServiceRequest extends ISPMessage
{
    private static final int ADDITIONAL_INFORMATION_VALID_FLAG = 16;
    private static final IntField SERVICE_TYPE = IntField.length6(18);
    private static final IntField REASON_CODE = IntField.length8(24);
    private static final IntField ADDITIONAL_INFORMATION = IntField.length24(32);
    private static final IntField SOURCE_ADDRESS = IntField.length24(56);

    private CancelReason mCancelReason;
    private Identifier mSourceAddress;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs a TSBK from the binary message sequence.
     */
    public CancelServiceRequest(P25P1DataUnitID dataUnitId, CorrectedBinaryMessage message, int nac, long timestamp)
    {
        super(dataUnitId, message, nac, timestamp);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" FM:").append(getSourceAddress());
        sb.append(" REASON:").append(getCancelReason());
        return sb.toString();
    }

    public CancelReason getCancelReason()
    {
        if(mCancelReason == null)
        {
            mCancelReason = CancelReason.fromCode(getMessage().getInt(REASON_CODE));
        }

        return mCancelReason;
    }

    /**
     * Service type for the cancel request
     */
    public Opcode getServiceType()
    {
        return Opcode.fromValue(getMessage().getInt(SERVICE_TYPE), Direction.INBOUND, getVendor());
    }

    /**
     * Additional details about the cancel request
     */
    public String getAdditionalInformation()
    {
        return getMessage().getHex(ADDITIONAL_INFORMATION);
    }

    /**
     * Indicates if the additional information field contains information
     */
    public boolean hasAdditionalInformation()
    {
        return getMessage().get(ADDITIONAL_INFORMATION_VALID_FLAG);
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
            mIdentifiers.add(getSourceAddress());
        }

        return mIdentifiers;
    }
}
