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

package io.github.dsheirer.module.decode.dmr.message.data.header;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.integer.IntegerIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.CACH;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.dmr.message.type.ServiceAccessPoint;
import io.github.dsheirer.module.decode.dmr.message.type.UnifiedDataTransportFormat;
import io.github.dsheirer.module.decode.dmr.message.type.Vendor;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import java.util.ArrayList;
import java.util.List;

/**
 * Unified Data Transport Header
 */
public class UDTHeader extends DataHeader
{
    private static final int RADIO_TALKGROUP_FLAG = 0;
    private static final IntField SERVICE_ACCESS_POINT = IntField.length4(8);
    //TODO: UDT_FORMAT is intentionally non-contiguous (bit 3 plus bits 12-15). Verify against the spec before
    // converting it to an IntField or otherwise "simplifying" it.
    private static final int[] UDT_FORMAT = new int[]{3, 12, 13, 14, 15};

    private static final IntField DESTINATION_IDENTIFIER = IntField.length24(16);
    private static final IntField SOURCE_RADIO = IntField.length24(40);
    private static final IntField PAD_NIBBLE = IntField.range(64, 68);
    private static final int SUPPLEMENTARY_DATA_FLAG = 72;
    private static final IntField OPCODE = IntField.length6(74);

    private IntegerIdentifier mDestinationLLID;
    private RadioIdentifier mSourceLLID;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs an instance.
     *
     * @param syncPattern either BASE_STATION_DATA or MOBILE_STATION_DATA
     * @param message containing extracted 196-bit payload.
     * @param cach for the DMR burst
     * @param slotType for this data message
     * @param timestamp message was received
     * @param timeslot for the DMR burst
     */
    public UDTHeader(DMRSyncPattern syncPattern, CorrectedBinaryMessage message, CACH cach, SlotType slotType, long timestamp, int timeslot)
    {
        super(syncPattern, message, cach, slotType, timestamp, timeslot);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("CC:").append(getSlotType().getColorCode());
        if(!isValid())
        {
            sb.append(" [CRC ERROR]");
        }
        sb.append(" UDT HEADER");
        if(isShortData())
        {
            sb.append(" SHORT DATA");
        }
        else
        {
            sb.append(" SUPPLEMENTARY DATA");
        }
        sb.append(" FM:").append(getSourceLLID());
        sb.append(" TO:").append(getDestinationLLID());
        sb.append(" FORMAT:").append(getFormat());
        Opcode opcode = getOpcode();

        if(opcode == Opcode.UNKNOWN)
        {
            sb.append(" OPCODE:").append(getOpcodeValue());
        }
        else
        {
            sb.append(" OPCODE:").append(getOpcode());
        }
        sb.append(" PAD NIBBLES:").append(getPadNibbleCount());
        sb.append(" SAP:").append(getServiceAccessPoint());
        return sb.toString();
    }

    /**
     * Indicates if the header contains supplementary data (true) or short data (false)
     * @param message containing the flag
     * @return value
     */
    public static boolean isSupplementaryData(CorrectedBinaryMessage message)
    {
        return message.get(SUPPLEMENTARY_DATA_FLAG);
    }

    /**
     * Indicates if the header is for a short data sequence
     */
    public boolean isShortData()
    {
        return !isSupplementaryData();
    }

    /**
     * Indicates if the header is for supplementary data
     */
    public boolean isSupplementaryData()
    {
        return isSupplementaryData(getMessage());
    }

    /**
     * Count of nibbles that were padded onto the end of the message to make a full data block.
     */
    public int getPadNibbleCount()
    {
        return getMessage().getInt(PAD_NIBBLE);
    }

    /**
     * Utility method to lookup the Opcode for this UDT header message
     * @param message containing a UDT header
     * @return opcode for the sequence
     */
    public static Opcode getOpcode(CorrectedBinaryMessage message)
    {
        return Opcode.fromValue(message.getInt(OPCODE), Vendor.STANDARD);
    }

    /**
     * Value of the opcode for this message
     */
    public int getOpcodeValue()
    {
        return getMessage().getInt(OPCODE);
    }

    /**
     * Opcode for this UDT header
     */
    public Opcode getOpcode()
    {
        return getOpcode(getMessage());
    }

    /**
     * Data format
     */
    public UnifiedDataTransportFormat getFormat()
    {
        return UnifiedDataTransportFormat.fromValue(getMessage().getInt(UDT_FORMAT));
    }

    /**
     * Service access point for the specified message
     */
    public static ServiceAccessPoint getServiceAccessPoint(CorrectedBinaryMessage message)
    {
        return ServiceAccessPoint.fromValue(message.getInt(SERVICE_ACCESS_POINT));
    }

    /**
     * Service access point for this message
     */
    public ServiceAccessPoint getServiceAccessPoint()
    {
        return getServiceAccessPoint(getMessage());
    }

    /**
     * Destination Logical Link ID
     */
    public IntegerIdentifier getDestinationLLID()
    {
        if(mDestinationLLID == null)
        {
            if(getMessage().get(RADIO_TALKGROUP_FLAG))
            {
                mDestinationLLID = DMRTalkgroup.create(getMessage().getInt(DESTINATION_IDENTIFIER));
            }
            else
            {
                mDestinationLLID = DMRRadio.createTo(getMessage().getInt(DESTINATION_IDENTIFIER));
            }
        }

        return mDestinationLLID;
    }


    /**
     * Source Logical Link ID
     */
    public RadioIdentifier getSourceLLID()
    {
        if(mSourceLLID == null)
        {
            mSourceLLID = DMRRadio.createFrom(getMessage().getInt(SOURCE_RADIO));
        }

        return mSourceLLID;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getDestinationLLID());
            mIdentifiers.add(getSourceLLID());
        }

        return mIdentifiers;
    }
}
