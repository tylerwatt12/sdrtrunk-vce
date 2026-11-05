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

package io.github.dsheirer.dsp.psk.demod;

import io.github.dsheirer.dsp.filter.interpolator.Interpolator;
import io.github.dsheirer.dsp.filter.interpolator.InterpolatorFactory;
import io.github.dsheirer.dsp.fm.IDemodulator;
import java.util.Arrays;

/**
 * Differential demodulator base class
 */
public abstract class DifferentialDemodulatorFloat implements IDemodulator
{
    private float mSamplesPerSymbol;
    protected float[] mIBuffer = new float[20]; //Initial size 20 for array copy, but gets resized on first buffer
    protected float[] mQBuffer = new float[20];
    protected float mMu;
    protected int mBufferOverlap;
    protected int mInterpolationOffset;
    protected final Interpolator mInterpolator = InterpolatorFactory.getInterpolator();

   /**
     * Constructor
     * @param sampleRate in Hertz
     * @param symbolRate symbols per second
     */
    protected DifferentialDemodulatorFloat(double sampleRate, int symbolRate)
    {
        mSamplesPerSymbol = (float)sampleRate / symbolRate;
        mMu = mSamplesPerSymbol % 1; //Fractional part of the samples per symbol rate
        //At mu=0 the interpolator selects offset+3. Compare samples one full symbol apart.
        mInterpolationOffset = (int) Math.floor(mSamplesPerSymbol) - 3;
        mBufferOverlap = (int) Math.floor(mSamplesPerSymbol) + 4;

        while(mInterpolationOffset < 0)
        {
            mInterpolationOffset++;
            mBufferOverlap++;
        }
    }

    /**
     * Preserves stream history and appends the next I/Q block for both scalar and vector calculations.
     */
    protected final void prepareBuffers(float[] i, float[] q)
    {
        int sampleLength = i.length;
        int bufferOverlap = mBufferOverlap;
        //Copy previous buffer residual samples to beginning of buffer.
        System.arraycopy(mIBuffer, mIBuffer.length - bufferOverlap, mIBuffer, 0, bufferOverlap);
        System.arraycopy(mQBuffer, mQBuffer.length - bufferOverlap, mQBuffer, 0, bufferOverlap);

        //Resize I/Q buffers if necessary
        int requiredBufferLength = sampleLength + bufferOverlap;
        if(mIBuffer.length != requiredBufferLength)
        {
            mIBuffer = Arrays.copyOf(mIBuffer, requiredBufferLength);
            mQBuffer = Arrays.copyOf(mQBuffer, requiredBufferLength);
        }

        //Append new samples to the residual samples from the previous buffer.
        System.arraycopy(i, 0, mIBuffer, bufferOverlap, sampleLength);
        System.arraycopy(q, 0, mQBuffer, bufferOverlap, sampleLength);
    }

    /**
     * Shared scalar calculation, including fallback for input lengths that cannot fill a vector.
     * Uses this demodulator's history directly so switching implementations cannot resume stale buffers.
     */
    protected final float[] demodulateScalar(float[] i, float[] q)
    {
        int sampleLength = i.length;
        float mu = mMu;
        prepareBuffers(i, q);

        float[] decodedPhases = new float[i.length];
        float iPrevious;
        float qPreviousConjugate;
        float iCurrent;
        float qCurrent;
        float differentialI;
        float differentialQ;

        //Differential demodulation.
        for(int x = 0; x < sampleLength; x++)
        {
            iPrevious = mIBuffer[x];
            qPreviousConjugate = -mQBuffer[x]; //Complex Conjugate

            int offset = mInterpolationOffset + x;
            iCurrent = mInterpolator.filter(mIBuffer, offset, mu);
            qCurrent = mInterpolator.filter(mQBuffer, offset, mu);

            //Multiply current complex sample by the complex conjugate of previous complex sample.
            differentialI = (iPrevious * iCurrent) - (qPreviousConjugate * qCurrent);
            differentialQ = (iPrevious * qCurrent) + (iCurrent * qPreviousConjugate);

            decodedPhases[x] = (float)Math.atan2(differentialQ, differentialI);
        }

        return decodedPhases;
    }

    /**
     * Calculated samples per symbol.
     * @return samples per symbol.
     */
    public float getSamplesPerSymbol()
    {
        return mSamplesPerSymbol;
    }
}
