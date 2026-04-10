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

package io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.message.CACH;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import java.util.ArrayList;
import java.util.List;

/**
 * Motorola Connect Plus - Terminate Channel Grant
 */
public class ConnectPlusTerminateChannelGrant extends CSBKMessage
{
    private static final IntField TARGET_ADDRESS = IntField.length24(16);

    //Analysis: this field correlates to UNKNOWN_FIELD(bits: 48-55) in ConnectPlusDataChannelGrant.
    private static final IntField UNKNOWN_FIELD_1 = IntField.length8(40);

    private static final IntField UNKNOWN_FIELD_2 = IntField.length8(48);
    private static final IntField UNKNOWN_FIELD_3 = IntField.length8(56);
    private static final IntField UNKNOWN_FIELD_4 = IntField.length8(64);
    private static final IntField UNKNOWN_FIELD_5 = IntField.length8(72);

    private RadioIdentifier mTargetRadio;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs an instance
     *
     * @param syncPattern for the CSBK
     * @param message bits
     * @param cach for the DMR burst
     * @param slotType for this message
     * @param timestamp
     * @param timeslot
     */
    public ConnectPlusTerminateChannelGrant(DMRSyncPattern syncPattern, CorrectedBinaryMessage message, CACH cach, SlotType slotType, long timestamp, int timeslot)
    {
        super(syncPattern, message, cach, slotType, timestamp, timeslot);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        if(!isValid())
        {
            sb.append("[CRC-ERROR] ");
        }

        sb.append("CC:").append(getSlotType().getColorCode());
        if(hasRAS())
        {
            sb.append(" RAS:").append(getBPTCReservedBits());
        }
        sb.append(" CSBK ").append(getVendor());
        sb.append(" TERMINATE CHANNEL GRANT TO:").append(getTargetRadio());
        sb.append(" U1:").append(getUnknownField1());
        sb.append(" U2:").append(getUnknownField2());
        sb.append(" U3:").append(getUnknownField3());
        sb.append(" U4:").append(getUnknownField4());
        sb.append(" U5:").append(getUnknownField5());
        sb.append(" MSG:").append(getMessage().toHexString());

        return sb.toString();
    }

    /**
     * Target radio address
     */
    public RadioIdentifier getTargetRadio()
    {
        if(mTargetRadio == null)
        {
            mTargetRadio = DMRRadio.createTo(getMessage().getInt(TARGET_ADDRESS));
        }

        return mTargetRadio;
    }

    /**
     * Unknown field
     */
    public int getUnknownField1()
    {
        return getMessage().getInt(UNKNOWN_FIELD_1);
    }

    /**
     * Unknown field
     */
    public int getUnknownField2()
    {
        return getMessage().getInt(UNKNOWN_FIELD_2);
    }

    /**
     * Unknown field
     */
    public int getUnknownField3()
    {
        return getMessage().getInt(UNKNOWN_FIELD_3);
    }

    /**
     * Unknown field
     */
    public int getUnknownField4()
    {
        return getMessage().getInt(UNKNOWN_FIELD_4);
    }

    /**
     * Unknown field
     */
    public int getUnknownField5()
    {
        return getMessage().getInt(UNKNOWN_FIELD_5);
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getTargetRadio());
        }

        return mIdentifiers;
    }
}
