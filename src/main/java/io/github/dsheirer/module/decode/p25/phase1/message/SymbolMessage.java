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

package io.github.dsheirer.module.decode.p25.phase1.message;

import java.util.Arrays;

/**
 * Stores the raw demodulated symbol phase angles for an assembled P25 Phase 1 message, one
 * floating-point value per dibit position. These unquantized phase angles are used directly
 * as branch metric inputs to the unquantized Viterbi decoder, preserving full channel
 * reliability information without discretization.
 */
public class SymbolMessage
{
    private float[] mSymbols;
    private int mPointer;

    public SymbolMessage(int dibitCount)
    {
        mSymbols = new float[dibitCount];
    }

    public SymbolMessage(float[] symbols)
    {
        mSymbols = Arrays.copyOf(symbols, symbols.length);
        mPointer = symbols.length;
    }

    public void add(float symbolPhase)
    {
        mSymbols[mPointer++] = symbolPhase;
    }

    public boolean isFull()
    {
        return mPointer >= mSymbols.length;
    }

    /**
     * Capacity — total number of symbol positions allocated.
     */
    public int size()
    {
        return mSymbols.length;
    }

    /**
     * Write pointer — number of symbol phase values written so far.
     */
    public int currentSize()
    {
        return mPointer;
    }

    public float get(int index)
    {
        return mSymbols[index];
    }

    public void set(int index, float value)
    {
        mSymbols[index] = value;
    }

    public void setSize(int dibitCount)
    {
        if(dibitCount != mSymbols.length)
        {
            mSymbols = Arrays.copyOf(mSymbols, dibitCount);
            mPointer = Math.min(mPointer, dibitCount);
        }
    }

    /**
     * Returns a sub-message using bit offsets so callers can mirror CorrectedBinaryMessage slicing.
     * Uses currentSize() (write pointer) as the upper bound to avoid reading unwritten positions.
     */
    public SymbolMessage getSubMessage(int startBit, int endBit)
    {
        int startDibit = Math.max(0, startBit / 2);
        int endDibit = Math.min(currentSize(), (endBit + 1) / 2);
        startDibit = Math.min(startDibit, endDibit);
        return new SymbolMessage(Arrays.copyOfRange(mSymbols, startDibit, endDibit));
    }
}
