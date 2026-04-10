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
package io.github.dsheirer.module.decode.p25.phase1.message.pdu.response;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUHeader;
import io.github.dsheirer.module.decode.p25.reference.PacketResponse;
import io.github.dsheirer.module.decode.p25.reference.Vendor;

public class ResponseHeader extends PDUHeader
{
    private static final IntField RESPONSE = IntField.length8(8);
    public static final int SOURCE_LLID_FLAG = 48;
    private static final IntField SOURCE_LOGICAL_LINK_ID = IntField.length24(56);
    private Identifier mSourceLLID;

    public ResponseHeader(CorrectedBinaryMessage message, boolean passesCRC)
    {
        super(message, passesCRC);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        if(!isValid())
        {
            sb.append("***CRC-FAIL*** ");
        }

        sb.append("PDU RESPONSE");

        if(hasSourceLLID())
        {
            sb.append(" FROM:").append(getSourceLLID());
        }

        sb.append(" TO:").append(getTargetLLID());

        sb.append(" ").append(getResponse());

        Vendor vendor = getVendor();

        if(vendor != Vendor.STANDARD)
        {
            sb.append(" VENDOR:").append(getVendor());
        }

        return sb.toString();
    }

    /**
     * Packet response message
     */
    public PacketResponse getResponse()
    {
        return PacketResponse.fromValue(getMessage().getInt(RESPONSE));
    }

    /**
     * Indicates if this header contains a source (from) LLID
     */
    public boolean hasSourceLLID()
    {
        return !getMessage().get(SOURCE_LLID_FLAG);
    }

    /**
     * Source Logical Link Identifier (ie FROM radio identifier)
     */
    public Identifier getSourceLLID()
    {
        if(mSourceLLID == null)
        {
            mSourceLLID = APCO25RadioIdentifier.createFrom(getMessage().getInt(SOURCE_LOGICAL_LINK_ID));
        }

        return mSourceLLID;
    }
}
