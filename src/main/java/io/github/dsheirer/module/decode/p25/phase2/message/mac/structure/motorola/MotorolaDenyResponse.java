/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacOpcode;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructureVendor;
import io.github.dsheirer.module.decode.p25.reference.DenyReason;
import io.github.dsheirer.module.decode.p25.reference.Vendor;
import java.util.ArrayList;
import java.util.List;

/**
 * Motorola deny response
 */
public class MotorolaDenyResponse extends MacStructureVendor
{
    // TIA-102.BBAD-A (2019), 3.97/Figure 192; 4.17 incorporates TIA-102.AABH 4.4.7.
    private static final int RC = 24;
    private static final IntField SERVICE_TYPE = IntField.range(26, 31);
    private static final IntField REASON = IntField.length8(OCTET_5_BIT_32);
    private static final IntField ADDITIONAL_INFO = IntField.length24(OCTET_6_BIT_40);
    private static final IntField TARGET_ADDRESS = IntField.length24(OCTET_9_BIT_64);

    private DenyReason mDenyReason;
    private String mAdditionalInfo;
    private Identifier mTargetAddress;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs the message
     *
     * @param message containing the message bits
     * @param offset into the message for this structure
     */
    public MotorolaDenyResponse(CorrectedBinaryMessage message, int offset)
    {
        super(message, offset);
    }

    /**
     * Textual representation of this message
     */
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getOpcode());
        sb.append(" TO:").append(getTargetAddress());
        sb.append(" SERVICE:").append(getDeniedServiceType());
        sb.append(" REASON:").append(getDenyReason());

        if(hasAdditionalInformation())
        {
            sb.append(" INFO:").append(getAdditionalInfo());
        }

        return sb.toString();
    }

    public boolean hasAdditionalInformation()
    {
        // TIA-102.AABH (2014), 4.4.7: absent additional information is zero-filled, independent of RC.
        return getInt(ADDITIONAL_INFO) != 0;
    }

    public String getAdditionalInfo()
    {
        if(mAdditionalInfo == null)
        {
            mAdditionalInfo = Integer.toHexString(getInt(ADDITIONAL_INFO)).toUpperCase();
        }

        return mAdditionalInfo;
    }

    /**
     * Opcode representing the service type that is being denied to the radio unit.
     */
    public MacOpcode getDeniedServiceType()
    {
        return MacOpcode.fromValue(getInt(SERVICE_TYPE));
    }

    /** Exact reason byte, including values represented by a shared lookup category. */
    public int getDenyReasonCode()
    {
        return getInt(REASON);
    }

    public boolean isVendorSpecificReason()
    {
        return getMessage().get(RC + getOffset());
    }

    public DenyReason getDenyReason()
    {
        if(mDenyReason == null)
        {
            mDenyReason = isVendorSpecificReason() ?
                DenyReason.fromCustomCode(getDenyReasonCode(), Vendor.MOTOROLA) :
                DenyReason.fromCode(getDenyReasonCode());
        }

        return mDenyReason;
    }

    public Identifier getTargetAddress()
    {
        if(mTargetAddress == null)
        {
            mTargetAddress = APCO25RadioIdentifier.createTo(getInt(TARGET_ADDRESS));
        }

        return mTargetAddress;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getTargetAddress());
        }

        return mIdentifiers;
    }
}
