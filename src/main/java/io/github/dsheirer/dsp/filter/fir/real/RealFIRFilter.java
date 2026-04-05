/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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
package io.github.dsheirer.dsp.filter.fir.real;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.window.WindowType;
import org.apache.commons.lang3.ArrayUtils;

import java.text.DecimalFormat;
import java.util.Random;

/**
 * Finite Impulse Response (FIR) filter for filtering individual float samples or float sample arrays.
 *
 * Note: filtering operations in this class are structured to leverage SIMD processor intrinsics when
 * available to the Java runtime.
 */
public class RealFIRFilter implements IRealFilter
{
    private float[] mBuffer;
    private float[] mCoefficients;
    private int mBufferOverlap;

    /**
     * Float sample FIR filter base class.
     *
     * @param coefficients - filter coefficients in normal order.
     */
    public RealFIRFilter(float[] coefficients)
    {
        //Reverse the order of the coefficients.
        mCoefficients = coefficients;
        ArrayUtils.reverse(mCoefficients);
        mBufferOverlap = mCoefficients.length - 1;

        //We'll resize this later when we get the first sample buffer.  For now, make it non-null.
        mBuffer = new float[mCoefficients.length];
    }

    /**
     * Filters the sample array
     * @param samples to filter
     * @return filtered samples
     */
    public float[] filter(float[] samples)
    {
        int bufferLength = samples.length + mBufferOverlap;

        //Resize the data buffer if needed.  This shouldn't happen more than once since all buffers should be same size
        if(mBuffer.length != bufferLength)
        {
            float[] temp = new float[bufferLength];
            //Move residual samples from end of previous buffer to the beginning of the new temp buffer and reassign
            System.arraycopy(mBuffer, mBuffer.length - mBufferOverlap, temp, 0, mBufferOverlap);
            mBuffer = temp;
        }
        else
        {
            //Move residual samples from end of buffer to the beginning of the buffer
            System.arraycopy(mBuffer, samples.length, mBuffer, 0, mBufferOverlap);
        }

        //Copy new sample array into end of buffer
        System.arraycopy(samples, 0, mBuffer, mBufferOverlap, samples.length);

        float[] filtered = new float[samples.length];

        for(int bufferPointer = 0; bufferPointer < samples.length; bufferPointer++)
        {
            for(int coefficientPointer = 0; coefficientPointer < mCoefficients.length; coefficientPointer++)
            {
                filtered[bufferPointer] += mBuffer[bufferPointer + coefficientPointer] * mCoefficients[coefficientPointer];
            }
        }

        return filtered;
    }
}
