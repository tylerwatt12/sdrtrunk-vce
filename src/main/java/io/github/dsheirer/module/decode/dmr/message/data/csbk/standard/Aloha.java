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

package io.github.dsheirer.module.decode.dmr.message.data.csbk.standard;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DmrTier3Radio;
import io.github.dsheirer.module.decode.dmr.message.CACH;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.dmr.message.type.ServiceFunction;
import io.github.dsheirer.module.decode.dmr.message.type.SystemIdentityCode;
import io.github.dsheirer.module.decode.dmr.message.type.Version;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import java.util.ArrayList;
import java.util.List;

/**
 * DMR Tier III - Aloha Message
 */
public class Aloha extends CSBKMessage
{
    private static final IntField VERSION = IntField.range(19, 21);
    private static final int ACTIVE_NETWORK_CONNECTION_FLAG = 23;
    private static final IntField MASK = IntField.range(24, 28);
    private static final IntField SERVICE_FUNCTION = IntField.length2(29);
    private static final int SYSTEM_IDENTITY_CODE_OFFSET = 40;
    private static final IntField RADIO = IntField.length24(56);

    private SystemIdentityCode mSystemIdentityCode;
    private RadioIdentifier mRadioIdentifier;
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
    public Aloha(DMRSyncPattern syncPattern, CorrectedBinaryMessage message, CACH cach, SlotType slotType, long timestamp, int timeslot)
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
        sb.append(" ALOHA");

        if(hasRadioIdentifier())
        {
            sb.append(" TO:").append(getRadioIdentifier());
        }

        sb.append(" ").append(getSystemIdentityCode().getModel());
        sb.append(" NETWORK:").append(getSystemIdentityCode().getNetwork());
        sb.append(" SITE:").append(getSystemIdentityCode().getSite());
        if(hasActiveNetworkConnection())
        {
            sb.append(" NET-CONNECTED");
        }
        else
        {
            sb.append(" NET-DISCONNECTED");
        }

        sb.append(" SERVICES:").append(getServiceFunction());

        sb.append(" ETSI VER:").append(getVersion());
        sb.append(" MASK:").append(getMask());

        if(getSystemIdentityCode().getPAR().isMultipleControlChannels())
        {
            sb.append(" ").append(getSystemIdentityCode().getPAR());
        }

        sb.append(" ").append(getMessage().toHexString());

        return sb.toString();
    }

    /**
     * Services provided by the control channel.
     */
    public ServiceFunction getServiceFunction()
    {
        return ServiceFunction.fromValue(getMessage().getInt(SERVICE_FUNCTION));
    }

    /**
     * Mobile subscriber ID masking value.  See: 102 361-4 p6.1.3
     */
    public int getMask()
    {
        return getMessage().getInt(MASK);
    }

    public boolean hasActiveNetworkConnection()
    {
        return getMessage().get(ACTIVE_NETWORK_CONNECTION_FLAG);
    }

    /**
     * DMR Tier III ETSI 102 361-4 ICD Version Number supported by this system
     */
    public Version getVersion()
    {
        return Version.fromValue(getMessage().getInt(VERSION));
    }

    /**
     * Acknowledged radio identifier
     */
    public RadioIdentifier getRadioIdentifier()
    {
        if(mRadioIdentifier == null)
        {
            mRadioIdentifier = DmrTier3Radio.createTo(getMessage().getInt(RADIO));
        }

        return mRadioIdentifier;
    }

    public boolean hasRadioIdentifier()
    {
        return getMessage().getInt(RADIO) != 0;
    }

    /**
     * System Identity Code structure
     */
    public SystemIdentityCode getSystemIdentityCode()
    {
        if(mSystemIdentityCode == null)
        {
            mSystemIdentityCode = new SystemIdentityCode(getMessage(), SYSTEM_IDENTITY_CODE_OFFSET, true);
        }

        return mSystemIdentityCode;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            if(hasRadioIdentifier())
            {
                mIdentifiers.add(getRadioIdentifier());
            }
            mIdentifiers.add(getSystemIdentityCode().getNetwork());
            mIdentifiers.add(getSystemIdentityCode().getSite());
        }

        return mIdentifiers;
    }
}
