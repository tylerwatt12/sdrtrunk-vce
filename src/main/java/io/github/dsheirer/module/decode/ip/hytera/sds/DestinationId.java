/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

package io.github.dsheirer.module.decode.ip.hytera.sds;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.ipv4.IPV4Address;

/**
 * Destination Identifier
 */
public class DestinationId extends HyteraToken
{
    private static final IntField CONTENT_LENGTH = IntField.length16(16);
    private static final IntField ID = IntField.length32(32);

    /**
     * Constructs an instance
     *
     * @param message containing both the token, run-length, and content
     */
    public DestinationId(CorrectedBinaryMessage message)
    {
        super(message);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("DESTINATION:").append(getIdFormatted());
        return sb.toString();
    }

    /**
     * Indicates if this is an IP address (true) or a Radio ID (false).
     * @return true if IP
     */
    public boolean isIp()
    {
        return getId() > 0x00FFFFFF;
    }

    /**
     * Length of the content field in bytes.
     * @return content field length.
     */
    public int getContentLength()
    {
        return mMessage.getInt(CONTENT_LENGTH);
    }

    /**
     * Identify value parsed from the field content.
     *
     * Note: although this field has a content length specifier, I've hard-coded it to parse a 32-bit value for now
     * until we find need to make it more fancy.
     */
    public int getId()
    {
        return mMessage.getInt(ID);
    }

    public String getIdFormatted()
    {
        if(isIp())
        {
            return new IPV4Address(getId()).toString() + " [" + (getId() & 0xFFFFFF) + "]";
        }
        else
        {
            return String.valueOf(getId());
        }
    }

    @Override
    HyteraTokenType getTokenType()
    {
        return HyteraTokenType.ID_DESTINATION;
    }
}
