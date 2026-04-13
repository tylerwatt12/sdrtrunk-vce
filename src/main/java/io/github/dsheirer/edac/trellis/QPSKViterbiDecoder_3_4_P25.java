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

package io.github.dsheirer.edac.trellis;

/**
 * Unquantized Viterbi decoder for P25 Phase 1 3/4-rate trellis coded modulation (TCM).
 * Used for confirmed data blocks.
 */
public class QPSKViterbiDecoder_3_4_P25 extends QPSKViterbiDecoder
{
    private static final int[][] TRANSITION_MATRIX = new int[][]
    {
        { 2, 13, 14,  1,  7,  8, 11,  4},
        {14,  1,  7,  8, 11,  4,  2, 13},
        {10,  5,  6,  9, 15,  0,  3, 12},
        { 6,  9, 15,  0,  3, 12, 10,  5},
        {15,  0,  3, 12, 10,  5,  6,  9},
        { 3, 12, 10,  5,  6,  9, 15,  0},
        { 7,  8, 11,  4,  2, 13, 14,  1},
        {11,  4,  2, 13, 14,  1,  7,  8}
    };

    public QPSKViterbiDecoder_3_4_P25()
    {
        super(3, TRANSITION_MATRIX);
    }
}
