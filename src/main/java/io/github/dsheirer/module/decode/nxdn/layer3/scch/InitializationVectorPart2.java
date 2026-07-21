/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.module.decode.nxdn.layer3.scch;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.FragmentedIntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import java.util.List;

/**
 * Initialization vector part 2 of 2.
 */
public class InitializationVectorPart2 extends Information3
{
    //NXDN TS 1-E Figures 6.5-3 and 6.5-12: b11-b0 spans the last three bits of octet 1, octet 2,
    //and bit 7 of octet 3.
    private static final FragmentedIntField IV = FragmentedIntField.of(13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
        24);

    /**
     * Constructs an instance
     *
     * @param message with binary data
     * @param timestamp for the message
     * @param type of message
     * @param ran from the frame
     * @param lich from the frame
     */
    public InitializationVectorPart2(CorrectedBinaryMessage message, long timestamp, NXDNMessageType type, int ran, LICH lich)
    {
        super(message, timestamp, type, ran, lich);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = getMessageBuilder();
        sb.append("ENCRYPTION IV PART 2:").append(getIV());
        return sb.toString();
    }

    /**
     * Initialization vector part 2 fragment
     */
    public int getIV()
    {
        return getMessage().getInt(IV);
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return List.of();
    }
}
