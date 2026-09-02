/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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

package io.github.dsheirer.dsp.filter.halfband;

import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.vector.VectorUtilities;
import java.util.Arrays;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Shared vector implementation for real half-band filters.
 *
 * <p>The coefficient vectors are created once in the constructor.  The stream buffer keeps the true filter overlap
 * ({@code coefficientCount - 1}) separate from the zero padding needed for a final full-width vector load.  Keeping
 * those values separate is important: treating padding as signal history shifts the filter by one or more samples at
 * buffer boundaries.</p>
 */
abstract class VectorRealHalfBandDecimationFilter implements IRealDecimationFilter
{
    private final VectorSpecies<Float> mSpecies;
    private final FloatVector[] mCoefficientVectors;
    private final int mBufferOverlap;
    private final int mTailPadding;
    private float[] mBuffer;

    /** Constructs a generic half-band filter for any valid coefficient count. */
    protected VectorRealHalfBandDecimationFilter(float[] coefficients, VectorSpecies<Float> species)
    {
        this(coefficients, species, 0);
    }

    /** Constructs a specialized half-band filter and enforces its exact coefficient count. */
    protected VectorRealHalfBandDecimationFilter(float[] coefficients, VectorSpecies<Float> species,
                                                 int expectedCoefficientCount)
    {
        if(coefficients == null)
        {
            throw new IllegalArgumentException("Half-band filter coefficients cannot be null");
        }

        if(expectedCoefficientCount > 0 && coefficients.length != expectedCoefficientCount)
        {
            throw new IllegalArgumentException("Half-band filter coefficients must be " + expectedCoefficientCount +
                " taps.  You supplied a filter with " + coefficients.length + " taps.");
        }

        if((coefficients.length + 1) % 4 != 0)
        {
            throw new IllegalArgumentException("Half-band filter coefficients must be odd-length and symmetrical " +
                "(length = [x * 4 + 3])");
        }

        mSpecies = species;
        VectorUtilities.checkSpecies(mSpecies);
        int laneCount = mSpecies.length();
        int paddedCoefficientCount = Math.ceilDiv(coefficients.length, laneCount) * laneCount;
        float[] paddedCoefficients = Arrays.copyOf(coefficients, paddedCoefficientCount);
        mCoefficientVectors = new FloatVector[paddedCoefficientCount / laneCount];

        for(int x = 0; x < mCoefficientVectors.length; x++)
        {
            mCoefficientVectors[x] = FloatVector.fromArray(mSpecies, paddedCoefficients, x * laneCount);
        }

        mBufferOverlap = coefficients.length - 1;
        mTailPadding = paddedCoefficientCount - coefficients.length;
    }

    @Override
    public float[] decimateReal(float[] samples)
    {
        if(samples.length % 2 != 0)
        {
            throw new IllegalArgumentException("Samples array length must be an integer multiple of 2");
        }

        prepareBuffer(samples);
        float[] filtered = new float[samples.length / 2];
        int laneCount = mSpecies.length();

        for(int bufferPointer = 0; bufferPointer < samples.length; bufferPointer += 2)
        {
            FloatVector accumulator = FloatVector.zero(mSpecies);

            for(int vectorIndex = 0; vectorIndex < mCoefficientVectors.length; vectorIndex++)
            {
                FloatVector buffer = FloatVector.fromArray(mSpecies, mBuffer,
                    bufferPointer + vectorIndex * laneCount);
                accumulator = mCoefficientVectors[vectorIndex].fma(buffer, accumulator);
            }

            filtered[bufferPointer / 2] = accumulator.reduceLanes(VectorOperators.ADD);
        }

        return filtered;
    }

    /** Preserves exactly the signal history and leaves the vector-only tail padding at zero. */
    private void prepareBuffer(float[] samples)
    {
        int bufferLength = samples.length + mBufferOverlap + mTailPadding;

        if(mBuffer == null)
        {
            mBuffer = new float[bufferLength];
        }
        else if(mBuffer.length != bufferLength)
        {
            float[] replacement = new float[bufferLength];
            int oldSampleCount = mBuffer.length - mBufferOverlap - mTailPadding;
            System.arraycopy(mBuffer, oldSampleCount, replacement, 0, mBufferOverlap);
            mBuffer = replacement;
        }
        else
        {
            System.arraycopy(mBuffer, samples.length, mBuffer, 0, mBufferOverlap);
        }

        System.arraycopy(samples, 0, mBuffer, mBufferOverlap, samples.length);
    }
}
