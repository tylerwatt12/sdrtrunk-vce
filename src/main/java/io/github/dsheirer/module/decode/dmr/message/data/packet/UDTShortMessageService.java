/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.module.decode.dmr.message.data.packet;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.dmr.message.DMRMessage;
import io.github.dsheirer.module.decode.dmr.message.data.header.UDTHeader;
import java.util.List;
/**
 * Short Message Service (SMS) using Unified Data Transport
 */
public class UDTShortMessageService extends DMRMessage
{
    private static final String INSUFFICIENT_DATA = "(insufficient data)";
    private UDTHeader mHeader;
    private String mSMS;

    /**
     * Constructs an instance
     *
     * @param header for the sequence
     * @param payload from the data blocks
     */
    public UDTShortMessageService(UDTHeader header, CorrectedBinaryMessage payload)
    {
        super(payload, header.getTimestamp(), header.getTimeslot());
        mHeader = header;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return mHeader.getIdentifiers();
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("CC:").append(mHeader.getSlotType().getColorCode());
        sb.append(" SMS MESSAGE:").append(getSMS());
        sb.append(" FROM:").append(mHeader.getSourceLLID());
        sb.append(" TO:").append(mHeader.getDestinationLLID());
        sb.append(" HEX:").append(getMessage().toHexString());
        return sb.toString();
    }

    public String getSMS()
    {
        if(mSMS == null)
        {
            switch(mHeader.getFormat())
            {
                case UNICODE_16:
                    mSMS = parseUnicodePayload();
                    break;
                case ASCII_7:
                    mSMS = parseISO7Payload();
                    break;
                case ASCII_8:
                    mSMS = parseISO8Payload();
                    break;
                case BCD_4_BITS:
                    mSMS = parseBCD4Payload();
                    break;
                 case BINARY, MOBILE_SUBSCRIBER_OR_TALKGROUP_ADDRESS, NMEA_GPS_LOCATION_CODED, IP_ADDRESS,
                     VENDOR_PROPRIETARY_8, VENDOR_PROPRIETARY_9, MIXED_FORMAT, UNKNOWN:
                    mSMS = "Error:" + new String(getMessage().getBytes());
                    break;
            }
        }

        if(mSMS == null)
        {
            mSMS = "error - unrecognized format: " + mHeader.getFormat();
        }

        return mSMS;
    }

    /**
     * Parses a unicode 16-bit payload from the message.
     * @return parsed message
     */
    private String parseUnicodePayload()
    {
        int length = getMessage().size();
        length -= (mHeader.getPadNibbleCount() * 4);
        length -= 16; //Exclude the dangling 32-bit CRC
        length -= 16; //Exclude the dangling 0x0006 ACKNOWLEDGE character

        if(length > 16)
        {
            return getMessage().parseUnicode(0, (length / 16));
        }
        else
        {
            return INSUFFICIENT_DATA;
        }
    }

    /**
     * Parses an ISO-7 payload from the message.
     * @return parsed message
     */
    private String parseISO7Payload()
    {
        int length = getMessage().size();
        length -= (mHeader.getPadNibbleCount() * 4);

        if(length > 7)
        {
            return getMessage().parseISO7(0, (length / 7));
        }
        else
        {
            return INSUFFICIENT_DATA;
        }
    }

    /**
     * Parses an ISO-8 payload from the message.
     * @return parsed message
     */
    private String parseISO8Payload()
    {
        int length = getMessage().size();
        length -= (mHeader.getPadNibbleCount() * 4);

        if(length > 8)
        {
            return getMessage().parseISO8(0, (length / 8));
        }
        else
        {
            return INSUFFICIENT_DATA;
        }
    }

    /**
     * Parses a BCD 4-bit payload from the message.
     * @return parsed message
     */
    private String parseBCD4Payload()
    {
        int length = getMessage().size();
        length -= (mHeader.getPadNibbleCount() * 4);

        if(length > 4)
        {
            return getMessage().parseBCD4(0, (length / 4));
        }
        else
        {
            return INSUFFICIENT_DATA;
        }
    }
}
