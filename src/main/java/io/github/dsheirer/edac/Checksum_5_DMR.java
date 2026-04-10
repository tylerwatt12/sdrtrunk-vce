/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.edac;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;

/**
 * Implements the 5-bit Checksum error detection algorithm specified in TS 102 361-1 paragraph B.3.11
 */
public class Checksum_5_DMR
{
    private static final IntField LC_0 = IntField.length8(0);
    private static final IntField LC_1 = IntField.length8(8);
    private static final IntField LC_2 = IntField.length8(16);
    private static final IntField LC_3 = IntField.length8(24);
    private static final IntField LC_4 = IntField.length8(32);
    private static final IntField LC_5 = IntField.length8(40);
    private static final IntField LC_6 = IntField.length8(48);
    private static final IntField LC_7 = IntField.length8(56);
    private static final IntField LC_8 = IntField.length8(64);
    private static final IntField CHECKSUM = IntField.range(72, 76);

    private Checksum_5_DMR()
    {
    }

    /**
     * Indicates if the 77-bit Full Link Control message is valid.
     * @param message to check
     * @return residual value leftover from xor of the calculated and transmitted checksums.
     */
    public static int isValid(CorrectedBinaryMessage message)
    {
        int accumulator = message.getInt(LC_0);
        accumulator += message.getInt(LC_1);
        accumulator += message.getInt(LC_2);
        accumulator += message.getInt(LC_3);
        accumulator += message.getInt(LC_4);
        accumulator += message.getInt(LC_5);
        accumulator += message.getInt(LC_6);
        accumulator += message.getInt(LC_7);
        accumulator += message.getInt(LC_8);
        accumulator = accumulator % 31;
        int checksum = message.getInt(CHECKSUM);
        return accumulator ^ checksum;
    }
}
