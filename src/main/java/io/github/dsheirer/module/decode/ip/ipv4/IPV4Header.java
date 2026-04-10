/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2018 Dennis Sheirer
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
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.ip.ipv4;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.ipv4.IPV4Identifier;
import io.github.dsheirer.module.decode.ip.Header;
import io.github.dsheirer.module.decode.ip.IPProtocol;
import io.github.dsheirer.module.decode.p25.identifier.ipv4.APCO25IpAddress;

public class IPV4Header extends Header
{
    private static final IntField VERSION = IntField.length4(0);
    private static final IntField HEADER_LENGTH = IntField.length4(4);
    private static final IntField TOTAL_LENGTH = IntField.length16(16);
    private static final IntField PROTOCOL = IntField.length8(72);
    private static final IntField FROM_ADDRESS = IntField.length32(96);
    private static final IntField TO_ADDRESS = IntField.length32(128);

    private IPV4Identifier mFromAddress;
    private IPV4Identifier mToAddress;

    public IPV4Header(BinaryMessage message, int offset)
    {
        super(message, offset);
        checkValid();
    }

    /**
     * Performs simple header and packet length validations relative to the packet offset within the binary message.
     */
    private void checkValid()
    {
        int headerLength = getLength();

        if(headerLength < 40)
        {
            setValid(false);
            return;
        }

        if(getMessage().size() < headerLength + getOffset())
        {
            setValid(false);
            return;
        }

        int totalLength = getTotalLength();

        if(totalLength < headerLength)
        {
            setValid(false);
        }
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("IP FROM:").append(getFromAddress());
        sb.append(" TO:").append(getToAddress());
        return sb.toString();
    }

    /**
     * Determines the IP version of the packet that starts at the indicated offset within the binary message.
     *
     * @param message message containing an IP packet
     * @param offset to the start of the packet within the binary message
     * @return
     */
    public static int getIPVersion(BinaryMessage message, int offset)
    {
        return message.getInt(VERSION, offset);
    }

    /**
     * IP Version of this packet
     */
    public int getIPVersion()
    {
        return getIPVersion(getMessage(), getOffset());
    }

    /**
     * Length of this IPV4 header in bits (NOT BYTES)
     */
    public int getLength()
    {
        return getMessage().getInt(HEADER_LENGTH, getOffset()) * 32;
    }

    /**
     * Total Length of this IPV4 packet in bits (NOT BYTES)
     */
    public int getTotalLength()
    {
        return getMessage().getInt(TOTAL_LENGTH, getOffset()) * 8;
    }

    /**
     * Protocol for the payload being carried by this IP packet
     */
    public IPProtocol getProtocol()
    {
        return IPProtocol.fromValue(getMessage().getInt(PROTOCOL, getOffset()));
    }

    /**
     * From IP Address
     */
    public IPV4Identifier getFromAddress()
    {
        if(mFromAddress == null)
        {
            mFromAddress = APCO25IpAddress.createFrom(getMessage().getInt(FROM_ADDRESS, getOffset()));
        }

        return mFromAddress;
    }

    /**
     * To IP Address
     */
    public IPV4Identifier getToAddress()
    {
        if(mToAddress == null)
        {
            mToAddress = APCO25IpAddress.createTo(getMessage().getInt(TO_ADDRESS, getOffset()));
        }

        return mToAddress;
    }
}
