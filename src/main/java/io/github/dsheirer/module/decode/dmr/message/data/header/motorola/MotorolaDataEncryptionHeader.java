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

package io.github.dsheirer.module.decode.dmr.message.data.header.motorola;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.dmr.message.CACH;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.header.ProprietaryDataHeader;
import io.github.dsheirer.module.decode.dmr.message.type.ServiceAccessPoint;
import io.github.dsheirer.module.decode.dmr.message.type.Vendor;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;

/**
 * Motorola Proprietary Data Header
 */
public class MotorolaDataEncryptionHeader extends ProprietaryDataHeader
{
    private static final IntField SERVICE_ACCESS_POINT = IntField.length4(0);
    private static final IntField VENDOR = IntField.length8(8);
    private static final IntField ALGORITHM = IntField.length8(16);
    private static final IntField KEY_ID = IntField.length8(24);
    private static final IntField UNKNOWN = IntField.length16(32);
    private static final IntField INITIALIZATION_VECTOR = IntField.length32(48);

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
    public MotorolaDataEncryptionHeader(DMRSyncPattern syncPattern, CorrectedBinaryMessage message, CACH cach, SlotType slotType, long timestamp, int timeslot)
    {
        super(syncPattern, message, cach, slotType, timestamp, timeslot);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("CC:").append(getSlotType().getColorCode());
        sb.append(" MOTOROLA DATA ENCRYPTION HEADER");
        sb.append(" SAP:").append(getServiceAccessPoint());
        sb.append(" ALGORITHM?:").append(getAlgorithm());
        sb.append(" KEY:").append(getKeyId());
        sb.append(" IV:").append(getInitializationVector());
        sb.append(" UNK:").append(getUnknown());
        sb.append(" MSG:").append(getMessage().toHexString());
        return sb.toString();
    }

    /**
     * Unknown message field(s).
     * @return hex value.
     */
    public String getUnknown()
    {
        return getMessage().getHex(UNKNOWN);
    }

    /**
     * Encryption key ID
     * @return key ID
     */
    public int getKeyId()
    {
        return getMessage().getInt(KEY_ID);
    }

    /**
     * Encryption Algorithm
     * @return algorithm ID
     */
    public int getAlgorithm()
    {
        return getMessage().getInt(ALGORITHM);
    }

    /**
     * Encryption initialization vector
     *
     * @return vector in hex
     */
    public String getInitializationVector()
    {
        return getMessage().getHex(INITIALIZATION_VECTOR);
    }

    /**
     * Utility method to lookup the vendor from a CSBK message
     *
     * @param message containing CSBK bits
     * @return vendor
     */
    public static Vendor getVendor(BinaryMessage message)
    {
        return Vendor.fromValue(message.getInt(VENDOR));
    }

    /**
     * Vendor for this message
     */
    public Vendor getVendor()
    {
        return getVendor(getMessage());
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
}
