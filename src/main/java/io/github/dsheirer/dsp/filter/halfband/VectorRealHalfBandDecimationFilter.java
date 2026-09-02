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
    private final float[] mCoefficients;
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
        int laneCount = species.length();
        int paddedCoefficientCount = Math.ceilDiv(coefficients.length, laneCount) * laneCount;
        mCoefficients = Arrays.copyOf(coefficients, paddedCoefficientCount);

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
            case 64 -> filter64(mCoefficients, mBuffer, filtered);
            case 128 -> filter128(mCoefficients, mBuffer, filtered);
            case 256 -> filter256(mCoefficients, mBuffer, filtered);
            case 512 -> filter512(mCoefficients, mBuffer, filtered);
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
    private static void filter64(float[] coefficients, float[] samples, float[] filtered)
    {
        for(int outputPointer = 0; outputPointer < filtered.length; outputPointer++)
        {
            int bufferPointer = outputPointer * 2;
            FloatVector accumulator = FloatVector.zero(FloatVector.SPECIES_64);

            for(int coefficientPointer = 0; coefficientPointer < coefficients.length;
                coefficientPointer += FloatVector.SPECIES_64.length())
            {
                accumulator = FloatVector.fromArray(FloatVector.SPECIES_64, coefficients, coefficientPointer).fma(
                    FloatVector.fromArray(FloatVector.SPECIES_64, samples, bufferPointer + coefficientPointer),
                    accumulator);
            }

            filtered[outputPointer] = accumulator.reduceLanes(VectorOperators.ADD);
        }
    }

    private static void filter128(float[] coefficients, float[] samples, float[] filtered)
    {
        for(int outputPointer = 0; outputPointer < filtered.length; outputPointer++)
        {
            int bufferPointer = outputPointer * 2;
            FloatVector accumulator = FloatVector.zero(FloatVector.SPECIES_128);

            for(int coefficientPointer = 0; coefficientPointer < coefficients.length;
                coefficientPointer += FloatVector.SPECIES_128.length())
            {
                accumulator = FloatVector.fromArray(FloatVector.SPECIES_128, coefficients, coefficientPointer).fma(
                    FloatVector.fromArray(FloatVector.SPECIES_128, samples, bufferPointer + coefficientPointer),
                    accumulator);
            }

            filtered[outputPointer] = accumulator.reduceLanes(VectorOperators.ADD);
        }
    }

    private static void filter256(float[] coefficients, float[] samples, float[] filtered)
    {
        for(int outputPointer = 0; outputPointer < filtered.length; outputPointer++)
        {
            int bufferPointer = outputPointer * 2;
            FloatVector accumulator = FloatVector.zero(FloatVector.SPECIES_256);

            for(int coefficientPointer = 0; coefficientPointer < coefficients.length;
                coefficientPointer += FloatVector.SPECIES_256.length())
            {
                accumulator = FloatVector.fromArray(FloatVector.SPECIES_256, coefficients, coefficientPointer).fma(
                    FloatVector.fromArray(FloatVector.SPECIES_256, samples, bufferPointer + coefficientPointer),
                    accumulator);
            }

            filtered[outputPointer] = accumulator.reduceLanes(VectorOperators.ADD);
        }
    }

    private static void filter512(float[] coefficients, float[] samples, float[] filtered)
    {
        for(int outputPointer = 0; outputPointer < filtered.length; outputPointer++)
        {
            int bufferPointer = outputPointer * 2;
            FloatVector accumulator = FloatVector.zero(FloatVector.SPECIES_512);

            for(int coefficientPointer = 0; coefficientPointer < coefficients.length;
                coefficientPointer += FloatVector.SPECIES_512.length())
            {
                accumulator = FloatVector.fromArray(FloatVector.SPECIES_512, coefficients, coefficientPointer).fma(
                    FloatVector.fromArray(FloatVector.SPECIES_512, samples, bufferPointer + coefficientPointer),
                    accumulator);
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
