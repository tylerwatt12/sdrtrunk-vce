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
    private final int mVectorBitSize;
    private final int mCoefficientCount;
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

        VectorUtilities.checkSpecies(species);
        mVectorBitSize = requireSupportedVectorBitSize(species);
        mCoefficientCount = coefficients.length;
        int laneCount = species.length();
        int paddedCoefficientCount = Math.ceilDiv(coefficients.length, laneCount) * laneCount;
        float[] paddedCoefficients = Arrays.copyOf(coefficients, paddedCoefficientCount);
        mCoefficientVectors = new FloatVector[paddedCoefficientCount / laneCount];

        for(int x = 0; x < mCoefficientVectors.length; x++)
        {
            mCoefficientVectors[x] = FloatVector.fromArray(species, paddedCoefficients, x * laneCount);
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

        switch(mVectorBitSize)
        {
            case 64 -> filter64(mCoefficientVectors, mBuffer, filtered);
            case 128 -> filter128(mCoefficientVectors, mBuffer, filtered);
            case 256 ->
            {
                if(mCoefficientCount == 23)
                {
                    filter23Tap256(mCoefficientVectors, mBuffer, filtered);
                }
                else if(mCoefficientCount == 63)
                {
                    filter63Tap256(mCoefficientVectors, mBuffer, filtered);
                }
                else
                {
                    filter256(mCoefficientVectors, mBuffer, filtered);
                }
            }
            case 512 -> filter512(mCoefficientVectors, mBuffer, filtered);
            default -> throw new IllegalStateException("Unsupported half-band vector width: " + mVectorBitSize);
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

    /** See {@link io.github.dsheirer.dsp.filter.fir.real.VectorRealFIRFilter} for the constant-species requirement. */
    private static void filter64(FloatVector[] coefficients, float[] samples, float[] filtered)
    {
        for(int outputPointer = 0; outputPointer < filtered.length; outputPointer++)
        {
            int bufferPointer = outputPointer * 2;
            FloatVector accumulator = FloatVector.zero(FloatVector.SPECIES_64);

            for(int coefficientIndex = 0; coefficientIndex < coefficients.length; coefficientIndex++)
            {
                int coefficientPointer = coefficientIndex * FloatVector.SPECIES_64.length();
                accumulator = FloatVector.fromArray(FloatVector.SPECIES_64, samples,
                    bufferPointer + coefficientPointer).fma(coefficients[coefficientIndex], accumulator);
            }

            filtered[outputPointer] = accumulator.reduceLanes(VectorOperators.ADD);
        }
    }

    private static void filter128(FloatVector[] coefficients, float[] samples, float[] filtered)
    {
        for(int outputPointer = 0; outputPointer < filtered.length; outputPointer++)
        {
            int bufferPointer = outputPointer * 2;
            FloatVector accumulator = FloatVector.zero(FloatVector.SPECIES_128);

            for(int coefficientIndex = 0; coefficientIndex < coefficients.length; coefficientIndex++)
            {
                int coefficientPointer = coefficientIndex * FloatVector.SPECIES_128.length();
                accumulator = FloatVector.fromArray(FloatVector.SPECIES_128, samples,
                    bufferPointer + coefficientPointer).fma(coefficients[coefficientIndex], accumulator);
            }

            filtered[outputPointer] = accumulator.reduceLanes(VectorOperators.ADD);
        }
    }

    private static void filter256(FloatVector[] coefficients, float[] samples, float[] filtered)
    {
        for(int outputPointer = 0; outputPointer < filtered.length; outputPointer++)
        {
            int bufferPointer = outputPointer * 2;
            FloatVector accumulator = FloatVector.zero(FloatVector.SPECIES_256);

            for(int coefficientIndex = 0; coefficientIndex < coefficients.length; coefficientIndex++)
            {
                int coefficientPointer = coefficientIndex * FloatVector.SPECIES_256.length();
                accumulator = FloatVector.fromArray(FloatVector.SPECIES_256, samples,
                    bufferPointer + coefficientPointer).fma(coefficients[coefficientIndex], accumulator);
            }

            filtered[outputPointer] = accumulator.reduceLanes(VectorOperators.ADD);
        }
    }

    /**
     * The production 23-tap filter occupies exactly three 256-bit vectors.  Binding those vectors before the sample
     * loop restores the fixed-shape kernel that HotSpot can keep in registers while retaining the shared stream-state
     * implementation.
     */
    private static void filter23Tap256(FloatVector[] coefficients, float[] samples, float[] filtered)
    {
        FloatVector coefficient0 = coefficients[0];
        FloatVector coefficient1 = coefficients[1];
        FloatVector coefficient2 = coefficients[2];

        for(int outputPointer = 0; outputPointer < filtered.length; outputPointer++)
        {
            int bufferPointer = outputPointer * 2;
            FloatVector product0 = FloatVector.fromArray(FloatVector.SPECIES_256, samples, bufferPointer)
                .mul(coefficient0);
            FloatVector product1 = FloatVector.fromArray(FloatVector.SPECIES_256, samples,
                bufferPointer + FloatVector.SPECIES_256.length()).mul(coefficient1);
            FloatVector product2 = FloatVector.fromArray(FloatVector.SPECIES_256, samples,
                bufferPointer + 2 * FloatVector.SPECIES_256.length()).mul(coefficient2);
            filtered[outputPointer] = product0.add(product1).add(product2).reduceLanes(VectorOperators.ADD);
        }
    }

    /** The production 63-tap filter occupies exactly eight 256-bit vectors. */
    private static void filter63Tap256(FloatVector[] coefficients, float[] samples, float[] filtered)
    {
        FloatVector coefficient0 = coefficients[0];
        FloatVector coefficient1 = coefficients[1];
        FloatVector coefficient2 = coefficients[2];
        FloatVector coefficient3 = coefficients[3];
        FloatVector coefficient4 = coefficients[4];
        FloatVector coefficient5 = coefficients[5];
        FloatVector coefficient6 = coefficients[6];
        FloatVector coefficient7 = coefficients[7];

        for(int outputPointer = 0; outputPointer < filtered.length; outputPointer++)
        {
            int bufferPointer = outputPointer * 2;
            FloatVector accumulator0 = FloatVector.fromArray(FloatVector.SPECIES_256, samples, bufferPointer)
                .mul(coefficient0);
            FloatVector accumulator1 = FloatVector.fromArray(FloatVector.SPECIES_256, samples,
                bufferPointer + FloatVector.SPECIES_256.length()).mul(coefficient1);
            FloatVector accumulator2 = FloatVector.fromArray(FloatVector.SPECIES_256, samples,
                bufferPointer + 2 * FloatVector.SPECIES_256.length()).mul(coefficient2);
            FloatVector accumulator3 = FloatVector.fromArray(FloatVector.SPECIES_256, samples,
                bufferPointer + 3 * FloatVector.SPECIES_256.length()).mul(coefficient3);
            accumulator0 = FloatVector.fromArray(FloatVector.SPECIES_256, samples,
                bufferPointer + 4 * FloatVector.SPECIES_256.length()).fma(coefficient4, accumulator0);
            accumulator1 = FloatVector.fromArray(FloatVector.SPECIES_256, samples,
                bufferPointer + 5 * FloatVector.SPECIES_256.length()).fma(coefficient5, accumulator1);
            accumulator2 = FloatVector.fromArray(FloatVector.SPECIES_256, samples,
                bufferPointer + 6 * FloatVector.SPECIES_256.length()).fma(coefficient6, accumulator2);
            accumulator3 = FloatVector.fromArray(FloatVector.SPECIES_256, samples,
                bufferPointer + 7 * FloatVector.SPECIES_256.length()).fma(coefficient7, accumulator3);
            filtered[outputPointer] = accumulator0.add(accumulator1).add(accumulator2).add(accumulator3)
                .reduceLanes(VectorOperators.ADD);
        }
    }

    private static void filter512(FloatVector[] coefficients, float[] samples, float[] filtered)
    {
        for(int outputPointer = 0; outputPointer < filtered.length; outputPointer++)
        {
            int bufferPointer = outputPointer * 2;
            FloatVector accumulator = FloatVector.zero(FloatVector.SPECIES_512);

            for(int coefficientIndex = 0; coefficientIndex < coefficients.length; coefficientIndex++)
            {
                int coefficientPointer = coefficientIndex * FloatVector.SPECIES_512.length();
                accumulator = FloatVector.fromArray(FloatVector.SPECIES_512, samples,
                    bufferPointer + coefficientPointer).fma(coefficients[coefficientIndex], accumulator);
            }

            filtered[outputPointer] = accumulator.reduceLanes(VectorOperators.ADD);
        }
    }

    private static int requireSupportedVectorBitSize(VectorSpecies<Float> species)
    {
        int vectorBitSize = species.vectorBitSize();

        return switch(vectorBitSize)
        {
            case 64, 128, 256, 512 -> vectorBitSize;
            default -> throw new IllegalArgumentException("Unsupported half-band vector width: " + vectorBitSize);
        };
    }
}
