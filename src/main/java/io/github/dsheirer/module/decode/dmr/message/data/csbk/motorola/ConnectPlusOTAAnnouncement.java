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
import io.github.dsheirer.module.decode.dmr.channel.DMRLsn;
import io.github.dsheirer.module.decode.dmr.channel.ITimeslotFrequencyReceiver;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.message.CACH;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.ip.mototrbo.xcmp.XCMPMessageType;
import java.util.ArrayList;
import java.util.List;

/**
 * Connect Plus - Over The Air (OTA) Reprogramming Announcement
 *
 * Note: there are at least 2 file types that can be transferred:
 * 1. Network Frequency File
 * 2. Option Board Firmware
 */
public class ConnectPlusOTAAnnouncement extends CSBKMessage implements ITimeslotFrequencyReceiver
{
    private static final IntField MESSAGE_TYPE = IntField.length8(16);
    private static final IntField VERSION = IntField.length16(24);
    private static final IntField LOGICAL_SLOT_NUMBER = IntField.range(63, 67);

    private DMRLsn mDataChannel;
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
    public ConnectPlusOTAAnnouncement(DMRSyncPattern syncPattern, CorrectedBinaryMessage message, CACH cach, SlotType slotType, long timestamp, int timeslot)
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
        sb.append(" CSBK CON+ ANNOUNCE OTA ").append(getMessageType());
        sb.append(" VER:").append(getMessageVersion());
        sb.append(" AVAILABLE ON ").append(getDataChannel());
        sb.append(" MSG:").append(getMessage().toHexString());

        return sb.toString();
    }

    public XCMPMessageType getMessageType()
    {
        return XCMPMessageType.fromValue(getMessage().getInt(MESSAGE_TYPE));
    }

    public int getMessageVersion()
    {
        return getMessage().getInt(VERSION);
    }

    /**
     * Data logical slot numberrepeater number
     */
    public int getDataLsn()
    {
        return getMessage().getInt(LOGICAL_SLOT_NUMBER) - 1; //Leave this as minus 1
    }

    /**
     * DMR Channel where the data is available
     */
    public DMRLsn getDataChannel()
    {
        if(mDataChannel == null)
        {
            mDataChannel = new DMRLsn(getDataLsn());
        }

        return mDataChannel;
    }

    @Override
    public int[] getLogicalChannelNumbers()
    {
        return getDataChannel().getLogicalChannelNumbers();
    }

    /**
     * Assigns a timeslot frequency map for the DMR channel
     *
     * @param timeslotFrequencies that match the logical timeslots
     */
    @Override
    public void apply(List<TimeslotFrequency> timeslotFrequencies)
    {
        getDataChannel().apply(timeslotFrequencies);
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getDataChannel());
        }

        return mIdentifiers;
    }
}
