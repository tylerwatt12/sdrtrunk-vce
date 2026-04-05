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

package io.github.dsheirer.edac;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.IntField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P25 Phase 2 Binary (40, 9, 16) Code for IISCH in the Super Frame Fragment.
 */
public class Binary40_9_16
{
    private static final long[] CHECKSUMS = {0x8816CE36D7l, 0x201DFD4F64l, 0x100F4B1758l, 0x0C00DED18El, 0x020807F7FFl,
            0x09048D9B72l, 0x009DA3A171l, 0x0058CBAA4El, 0x00343D8597l};
    private static final long CODE_WORD_OFFSET = 0x184229d461l;
    private static final Map<Long,BinaryMessage> CODEWORD_MAP_TS1 = new TreeMap<>();
    private static final Map<Long,BinaryMessage> CODEWORD_MAP_TS2 = new TreeMap<>();
    private static final IntField CHANNEL_NUMBER = IntField.range(2, 3);
    private static final IntField ISCH_LOCATION = IntField.range(4, 5);

    static
    {
        for(int x = 0; x < 512; x++)
        {
            BinaryMessage message = new BinaryMessage(9);
            message.load(0, 9, x);

            int channelNumber = message.getInt(CHANNEL_NUMBER) + 1;
            int ischLocation = message.getInt(ISCH_LOCATION);

            long codeword = getCodeWord(message);
            codeword ^= CODE_WORD_OFFSET;

            if(ischLocation != 3)
            {
                if(channelNumber == 1)
                {
                    CODEWORD_MAP_TS1.put(codeword, message);
                }
                else if(channelNumber == 2)
                {
                    CODEWORD_MAP_TS2.put(codeword, message);
                }
            }
        }

        List<Long> keys = new ArrayList<>(CODEWORD_MAP_TS1.keySet());
        Collections.sort(keys);
        for(Long key: keys)
        {
            getDistance(key, keys);
        }
        List<Long> keys2 = new ArrayList<>(CODEWORD_MAP_TS2.keySet());
        Collections.sort(keys2);
        for(Long key: keys2)
        {
            getDistance(key, keys);
        }
    }

    private Binary40_9_16()
    {
    }

    /**
     * Calculates the closest distance between the value and the values in the value set.
     * @param value to test
     * @param valueSet to test against
     * @return closest distance of the value to the values in the value set, ignoring any equivalent values.
     */
    public static int getDistance(long value, List<Long> valueSet)
    {
        int distance = 40;

        for(Long candidate: valueSet)
        {
            if(candidate != value)
            {
                long syndrome = candidate ^ value;
                int candidateDistance = Long.bitCount(syndrome);
                if(candidateDistance < distance)
                {
                    distance = candidateDistance;
                }
            }
        }

        return distance;
    }

    /**
     * Creates a codeword from the binary message where each set bit in the binary message corresponds to one of the
     * words in the generator and we XOR the words together.
     * @param message for generating a codeword
     * @return codeword
     */
    public static long getCodeWord(BinaryMessage message)
    {
        long codeword = 0;

        for(int x = 0; x < message.length(); x++)
        {
            if(message.get(x))
            {
                codeword ^= CHECKSUMS[x];
            }
        }

        return codeword;
    }
}
