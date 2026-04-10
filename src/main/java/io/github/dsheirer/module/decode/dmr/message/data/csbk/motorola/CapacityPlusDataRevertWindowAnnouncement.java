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
 * Capacity+ Window Announcement for Enhanced GPS/Data Revert Channel
 *
 * The window size dictates how many Windows happen within each data super frame.  You can infer the window size
 * by monitoring the maximum observed window value before rollover to zero as follows:
 *
 * Window Size: Number of Windows (hex) for 30-Second Data Super Frames
 * 5: 100 (0x52)
 * 6:  83 (0x53)
 * 7:  71 (0x47)
 * 8:  62 (0x3E)
 * 9:  55 (0x37)
 * 10:  50 (0x32)
 *
 * Window Size: Number of Windows (hex) for 2-Minute Data Super Frames
 * 1: 125 (0x7D)
 * 2:  62 (0x3E)
 *
 * Field UNKNOWN_2 may be reserved for a radio identifier that the target radio should transmit to.
 */
public class CapacityPlusDataRevertWindowAnnouncement extends CSBKMessage
{
    private static final IntField TARGET_RADIO = IntField.length16(24);
    private static final IntField WINDOW = IntField.length8(40);
    private static final IntField SUPER_FRAME = IntField.length8(48);
 
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
    public CapacityPlusDataRevertWindowAnnouncement(DMRSyncPattern syncPattern, CorrectedBinaryMessage message, CACH cach, SlotType slotType, long timestamp, int timeslot)
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
        sb.append(" CSBK CAP+ ENHANCED DATA REVERT ANNOUNCEMENT");
        sb.append(" WINDOW:").append(getSuperFrame()).append(".").append(getWindow());

        if(hasTargetRadio())
        {
            sb.append(" RESERVED FOR RADIO:").append(getTargetRadio());
        }

        sb.append(" MSG:").append(getMessage().toHexString());

        return sb.toString();
    }

    /**
     * Indicates if there is a target radio specified in this message
     */
    public boolean hasTargetRadio()
    {
        return getMessage().getInt(TARGET_RADIO) > 0;
    }

    /**
     * Optional target radio identifier that the announced window is reserved for
     * @return radio identifier or NULL
     */
    public RadioIdentifier getTargetRadio()
    {
        if(mTargetRadio == null && hasTargetRadio())
        {
            mTargetRadio = DMRRadio.createTo(getMessage().getInt(TARGET_RADIO));
        }

        return mTargetRadio;
    }

    /**
     * Current Super Frame Number
     *
     * @return 0-15
     */
    public int getSuperFrame()
    {
        return getMessage().getInt(SUPER_FRAME);
    }

    /**
     * Current Window Number
     *
     * @return current window number, values range from 0 to 125 depending on repeater configuration
     */
    public int getWindow()
    {
        return getMessage().getInt(WINDOW);
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();

            if(hasTargetRadio())
            {
                mIdentifiers.add(getTargetRadio());
            }
        }

        return mIdentifiers;
    }
}
