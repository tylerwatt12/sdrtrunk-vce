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
 * Soft dibit container for assembled Phase 1 messages. Stores one demodulated soft symbol value per dibit.
 */
public class SoftDibitMessage
{
    private float[] mSoftDibits;
    private int mPointer;

    public SoftDibitMessage(int dibitCount)
    {
        mSoftDibits = new float[dibitCount];
    }

    public SoftDibitMessage(float[] softDibits)
    {
        mSoftDibits = Arrays.copyOf(softDibits, softDibits.length);
        mPointer = softDibits.length;
    }

    public void add(float softDibit)
    {
        mSoftDibits[mPointer++] = softDibit;
    }

    public boolean isFull()
    {
        return mPointer >= mSoftDibits.length;
    }

    public int size()
    {
        return mSoftDibits.length;
    }

    public int currentSize()
    {
        return mPointer;
    }

    public float get(int index)
    {
        return mSoftDibits[index];
    }

    public void set(int index, float value)
    {
        mSoftDibits[index] = value;
    }

    public void setSize(int dibitCount)
    {
        if(dibitCount != mSoftDibits.length)
        {
            mSoftDibits = Arrays.copyOf(mSoftDibits, dibitCount);
            mPointer = Math.min(mPointer, dibitCount);
        }
    }

    /**
     * Returns a sub message using bit offsets so callers can mirror CorrectedBinaryMessage slicing.
     */
    public SoftDibitMessage getSubMessage(int startBit, int endBit)
    {
        int startDibit = Math.max(0, startBit / 2);
        int endDibit = Math.min(currentSize(), (endBit + 1) / 2);
        startDibit = Math.min(startDibit, endDibit);
        return new SoftDibitMessage(Arrays.copyOfRange(mSoftDibits, startDibit, endDibit));
    }
}
