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

package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.l3harris;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructureVendor;
import io.github.dsheirer.module.decode.p25.reference.Vendor;
import io.github.dsheirer.module.decode.p25.reference.UserAliasText;
import java.util.ArrayList;
import java.util.List;

/**
 * L3Harris Talker Alias.
 */
public class L3HarrisTalkerAlias extends MacStructureVendor
{
    private static final int ALIAS_START = OCTET_4_BIT_24;
    private List<Identifier> mIdentifiers;
    private P25TalkerAliasIdentifier mAliasIdentifier;

    /**
     * Constructs the message
     *
     * @param message containing the message bits
     * @param offset into the message for this structure
     */
    public L3HarrisTalkerAlias(CorrectedBinaryMessage message, int offset)
    {
        super(message, offset);
    }

    /**
     * Textual representation of this message
     */
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        if(getVendor() == Vendor.HARRIS)
        {
            sb.append("L3HARRIS");
        }
        else
        {
            sb.append("WARNING: UNKNOWN VENDOR:").append(getVendor());
        }

        sb.append(" TALKER ALIAS: ");
        sb.append(getAlias());
        return sb.toString();
    }

    /**
     * Alias identifier
     */
    public P25TalkerAliasIdentifier getAlias()
    {
        if(mAliasIdentifier == null)
        {
            //TIA-102.BBAD-A-1 (March 2025), 3.99: 17 octets including the three-octet envelope.
            //Retain bounded shorter legacy forms, but never accept a truncated declared structure.
            if(getOffset() < 0 || getOffset() + ALIAS_START > getMessage().size()) return null;
            int length = getLength();
            int end = getOffset() + length * 8;
            if(getVendor() == Vendor.HARRIS && length >= 4 && length <= 17 && end <= getMessage().size())
            {
                String alias = UserAliasText.decode(getMessage().getSubMessage(ALIAS_START + getOffset(), end).getBytes());
                if(alias != null) mAliasIdentifier = P25TalkerAliasIdentifier.create(alias);
            }
        }

        return mAliasIdentifier;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            if(getAlias() != null) mIdentifiers.add(getAlias());
        }

        return mIdentifiers;
    }
}
