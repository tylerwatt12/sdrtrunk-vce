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

package io.github.dsheirer.module.decode.dmr.message.data.lc.full.motorola;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import java.util.ArrayList;
import java.util.List;

/**
 * Motorola Capacity Plus - Group Voice Channel User
 */
public class MotorolaGroupVoiceChannelUser extends CapacityPlusVoiceChannelUser
{
    private static final IntField GROUP_ADDRESS = IntField.length24(24);
    private static final IntField SOURCE_ADDRESS = IntField.length24(48);
    //Reed Solomon FEC: 72-95

    private RadioIdentifier mRadio;
    private TalkgroupIdentifier mTalkgroup;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs an instance.
     *
     * @param message for the link control payload
     */
    public MotorolaGroupVoiceChannelUser(CorrectedBinaryMessage message, long timestamp, int timeslot)
    {
        super(message, timestamp, timeslot);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        if(!isValid())
        {
            sb.append("[CRC-ERROR] ");
        }

        if(isEncrypted())
        {
            sb.append(" ENCRYPTED");
        }

        if(isReservedBitSet())
        {
            sb.append(" RESERVED-BIT");
        }

        sb.append("FLC MOTOROLA GROUP VOICE CHANNEL USER");
        sb.append(" FM:").append(getRadio());
        sb.append(" TO:").append(getTalkgroup());
        sb.append(" ").append(getServiceOptions());
        sb.append(" MSG:").append(getMessage().toHexString());
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

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getTalkgroup());
            mIdentifiers.add(getRadio());
        }

        return mIdentifiers;
    }
}
