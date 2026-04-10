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
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.dmr.channel.DMRLsn;
import io.github.dsheirer.module.decode.dmr.channel.ITimeslotFrequencyReceiver;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.CACH;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import java.util.ArrayList;
import java.util.List;

/**
 * Motorola Connect Plus - Voice Channel User
 */
public class ConnectPlusVoiceChannelUser extends CSBKMessage implements ITimeslotFrequencyReceiver
{
    private static final IntField SOURCE_ADDRESS = IntField.length24(16);
    private static final IntField GROUP_ADDRESS = IntField.length24(40);
    private static final IntField LOGICAL_SLOT_NUMBER = IntField.range(64, 68);
    private static final IntField UNKNOWN_FIELD = IntField.length8(72);

    private RadioIdentifier mRadio;
    private TalkgroupIdentifier mTalkgroup;
    private DMRLsn mDmrLsn;
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
    public ConnectPlusVoiceChannelUser(DMRSyncPattern syncPattern, CorrectedBinaryMessage message, CACH cach, SlotType slotType, long timestamp, int timeslot)
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
        sb.append(" CSBK CON+ VOICE CHANNEL USER FM:").append(getRadio());
        sb.append(" TO:").append(getTalkgroup());
        sb.append(" ").append(getChannel());
        sb.append(" UNK:").append(getUnknownField());
        return sb.toString();
    }

    /**
     * Source radio address
     */
    public RadioIdentifier getRadio()
    {
        if(mRadio == null)
        {
            mRadio = DMRRadio.createFrom(getMessage().getInt(SOURCE_ADDRESS));
        }

        return mRadio;
    }

    /**
     * Talkgroup address
     */
    public TalkgroupIdentifier getTalkgroup()
    {
        if(mTalkgroup == null)
        {
            mTalkgroup = DMRTalkgroup.create(getMessage().getInt(GROUP_ADDRESS));
        }

        return mTalkgroup;
    }

    /**
     * Unknown field
     */
    public int getUnknownField()
    {
        return getMessage().getInt(UNKNOWN_FIELD);
    }

    /**
     * Traffic channel repeater
     */
    public int getTrafficLsn()
    {
        return getMessage().getInt(LOGICAL_SLOT_NUMBER) - 1; //Always leave as minus 1
    }

    /**
     * DMR Channel
     */
    public DMRLsn getChannel()
    {
        if(mDmrLsn == null)
        {
            mDmrLsn = new DMRLsn(getTrafficLsn());
        }

        return mDmrLsn;
    }

    @Override
    public int[] getLogicalChannelNumbers()
    {
        return getChannel().getLogicalChannelNumbers();
    }

    /**
     * Assigns a timeslot frequency map for the DMR channel
     * @param timeslotFrequencies that match the logical timeslots
     */
    @Override
    public void apply(List<TimeslotFrequency> timeslotFrequencies)
    {
        getChannel().apply(timeslotFrequencies);
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getTalkgroup());
            mIdentifiers.add(getRadio());
            mIdentifiers.add(getChannel());
        }

        return mIdentifiers;
    }
}
