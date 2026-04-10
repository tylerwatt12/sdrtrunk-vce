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
import io.github.dsheirer.identifier.integer.IntegerIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.CACH;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Preamble;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import java.util.ArrayList;
import java.util.List;

/**
 * Motorola Capacity Plus - Preamble
 */
public class CapacityPlusPreamble extends Preamble
{
    private static final int RADIO_TALKGROUP_FLAG = 17;
    private static final IntField BLOCKS_TO_FOLLOW = IntField.range(18, 22);
    private static final IntField UNKNOWN_1 = IntField.length8(24);
    private static final IntField UNKNOWN_2 = IntField.length8(32);
    private static final IntField TARGET_ADDRESS = IntField.length16(40);
    private static final IntField UNKNOWN_3 = IntField.length8(56);
    private static final IntField SOURCE_ADDRESS = IntField.length16(64);

    private IntegerIdentifier mTargetAddress;
    private RadioIdentifier mSourceAddress;
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
    public CapacityPlusPreamble(DMRSyncPattern syncPattern, CorrectedBinaryMessage message, CACH cach, SlotType slotType, long timestamp, int timeslot)
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
        sb.append(" CSBK CAP+").append(isCSBKPreamble() ? " CSBK" : " DATA");
        sb.append(" PREAMBLE FM:").append(getSourceAddress());
        sb.append(" TO:").append(getTargetAddress());
        sb.append(" BLOCKS TO FOLLOW:").append(getBlocksToFollow());
        sb.append(" UNK1:").append(getUnknown1());
        sb.append(" UNK2:").append(getUnknown2());
        sb.append(" UNK3:").append(getUnknown3());
        sb.append(" MSG:").append(getMessage().toHexString());
        return sb.toString();
    }

    public String getUnknown1()
    {
        return getMessage().getHex(UNKNOWN_1);
    }

    public String getUnknown2()
    {
        return getMessage().getHex(UNKNOWN_2);
    }

    public String getUnknown3()
    {
        return getMessage().getHex(UNKNOWN_3);
    }

    /**
     * Number of data blocks that will follow this preamble
     */
    public int getBlocksToFollow()
    {
        return getMessage().getInt(BLOCKS_TO_FOLLOW);
    }

    /**
     * Target radio address that is either a radio or talkgroup identifier
     */
    public IntegerIdentifier getTargetAddress()
    {
        if(mTargetAddress == null)
        {
            if(getMessage().get(RADIO_TALKGROUP_FLAG))
            {
                mTargetAddress = DMRTalkgroup.create(getMessage().getInt(TARGET_ADDRESS));
            }
            else
            {
                mTargetAddress = DMRRadio.createTo(getMessage().getInt(TARGET_ADDRESS));
            }
        }

        return mTargetAddress;
    }


    /**
     * Source radio identifier
     */
    public RadioIdentifier getSourceAddress()
    {
        if(mSourceAddress == null)
        {
            mSourceAddress = DMRRadio.createFrom(getMessage().getInt(SOURCE_ADDRESS));
        }

        return mSourceAddress;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getTargetAddress());
            mIdentifiers.add(getSourceAddress());
        }

        return mIdentifiers;
    }
}
