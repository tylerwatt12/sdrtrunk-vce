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
 * Unquantized Viterbi decoder for P25 Phase 1 1/2-rate trellis coded modulation (TCM).
 * Used for TSBK, PDU headers, and unconfirmed data blocks.
 */
public class QPSKViterbiDecoder_1_2_P25 extends QPSKViterbiDecoder
{
    private static final int[][] TRANSITION_MATRIX = new int[][]
    {
        {2, 12, 1, 15},
        {14, 0, 13, 3},
        {9, 7, 10, 4},
        {5, 11, 6, 8}
    };

    public QPSKViterbiDecoder_1_2_P25()
    {
        super(2, TRANSITION_MATRIX);
    }
}
