/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.filter.fir.real;

import io.github.dsheirer.vector.VectorUtilities;
import java.util.Arrays;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Shared SIMD implementation for a streaming real FIR filter.
 *
 * <p>The coefficient array is reversed to match the oldest-to-newest sample window and padded at the end to a
 * complete vector.  The stream history remains the actual tap count minus one; vector padding is workspace only and
 * must not shift the coefficient window away from the current sample.</p>
 */
public abstract class VectorRealFIRFilter implements IRealFilter
{
    private final int mVectorBitSize;
    private final float[] mCoefficients;
    private final int mHistoryLength;
    private final int mWorkspacePadding;
    private final float[] mHistory;
    private float[] mWorkspace;

    /**
     * Constructs a filter for the supplied vector species.
     *
     * @param coefficients filter coefficients in normal order
     * @param species vector species
     */
    protected VectorRealFIRFilter(float[] coefficients, VectorSpecies<Float> species)
    {
        if(coefficients == null || coefficients.length == 0)
        {
            throw new IllegalArgumentException("FIR filter requires at least one coefficient");
        }

        VectorUtilities.checkSpecies(species);
        mVectorBitSize = requireSupportedVectorBitSize(species);
        mHistoryLength = coefficients.length - 1;
        int paddedCoefficientLength = species.loopBound(coefficients.length);

        if(paddedCoefficientLength < coefficients.length)
        {
            paddedCoefficientLength += species.length();
        }

        mWorkspacePadding = paddedCoefficientLength - coefficients.length;
        float[] reversedPaddedCoefficients = new float[paddedCoefficientLength];

        for(int x = 0; x < coefficients.length; x++)
        {
            reversedPaddedCoefficients[x] = coefficients[coefficients.length - 1 - x];
        }

        mCoefficients = reversedPaddedCoefficients;

        mHistory = new float[mHistoryLength];
        mWorkspace = new float[mHistoryLength + mWorkspacePadding];
    }

    /**
     * Filters a consecutive sample buffer while retaining the exact tap history for the next invocation.
     */
    @Override
    public final float[] filter(float[] samples)
    {
        int requiredLength = mHistoryLength + samples.length + mWorkspacePadding;

        if(mWorkspace.length < requiredLength)
        {
            mWorkspace = new float[requiredLength];
        }

        System.arraycopy(mHistory, 0, mWorkspace, 0, mHistoryLength);
        System.arraycopy(samples, 0, mWorkspace, mHistoryLength, samples.length);

        //Clear the readable vector tail when a previously larger workspace is reused.
        Arrays.fill(mWorkspace, mHistoryLength + samples.length, requiredLength, 0.0f);

        float[] filtered = new float[samples.length];

        switch(mVectorBitSize)
        {
            case 64 -> filter64(mCoefficients, mWorkspace, filtered);
            case 128 -> filter128(mCoefficients, mWorkspace, filtered);
            case 256 -> filter256(mCoefficients, mWorkspace, filtered);
            case 512 -> filter512(mCoefficients, mWorkspace, filtered);
            default -> throw new IllegalStateException("Unsupported FIR vector width: " + mVectorBitSize);
        }

        if(mHistoryLength > 0)
        {
            //The concatenated history and input starts at zero.  Its final history-length values start at input size.
            System.arraycopy(mWorkspace, samples.length, mHistory, 0, mHistoryLength);
        }

        return filtered;
    }

    /**
     * Vector API species must be compile-time constants at each hot call site.  Passing a species through an instance
     * field prevents reliable intrinsic lowering on some JDK/CPU combinations and causes every temporary vector in
     * the inner loop to be allocated on the heap.  These width-specific kernels keep the shared streaming state while
     * retaining constant species at the vector operations.
     */
    private static void filter64(float[] coefficients, float[] samples, float[] filtered)
    {
        for(int samplePointer = 0; samplePointer < filtered.length; samplePointer++)
        {
            FloatVector accumulator = FloatVector.zero(FloatVector.SPECIES_64);

            for(int coefficientPointer = 0; coefficientPointer < coefficients.length;
                coefficientPointer += FloatVector.SPECIES_64.length())
            {
                accumulator = FloatVector.fromArray(FloatVector.SPECIES_64, coefficients, coefficientPointer).fma(
                    FloatVector.fromArray(FloatVector.SPECIES_64, samples, samplePointer + coefficientPointer),
                    accumulator);
            }

            filtered[samplePointer] = accumulator.reduceLanes(VectorOperators.ADD);
        }
    }

    private static void filter128(float[] coefficients, float[] samples, float[] filtered)
    {
        for(int samplePointer = 0; samplePointer < filtered.length; samplePointer++)
        {
            FloatVector accumulator = FloatVector.zero(FloatVector.SPECIES_128);

            for(int coefficientPointer = 0; coefficientPointer < coefficients.length;
                coefficientPointer += FloatVector.SPECIES_128.length())
            {
                accumulator = FloatVector.fromArray(FloatVector.SPECIES_128, coefficients, coefficientPointer).fma(
                    FloatVector.fromArray(FloatVector.SPECIES_128, samples, samplePointer + coefficientPointer),
                    accumulator);
            }

            filtered[samplePointer] = accumulator.reduceLanes(VectorOperators.ADD);
        }
    }

    private static void filter256(float[] coefficients, float[] samples, float[] filtered)
    {
        for(int samplePointer = 0; samplePointer < filtered.length; samplePointer++)
        {
            FloatVector accumulator = FloatVector.zero(FloatVector.SPECIES_256);

            for(int coefficientPointer = 0; coefficientPointer < coefficients.length;
                coefficientPointer += FloatVector.SPECIES_256.length())
            {
                accumulator = FloatVector.fromArray(FloatVector.SPECIES_256, coefficients, coefficientPointer).fma(
                    FloatVector.fromArray(FloatVector.SPECIES_256, samples, samplePointer + coefficientPointer),
                    accumulator);
            }

            filtered[samplePointer] = accumulator.reduceLanes(VectorOperators.ADD);
        }
    }

    private static void filter512(float[] coefficients, float[] samples, float[] filtered)
    {
        for(int samplePointer = 0; samplePointer < filtered.length; samplePointer++)
        {
            FloatVector accumulator = FloatVector.zero(FloatVector.SPECIES_512);

            for(int coefficientPointer = 0; coefficientPointer < coefficients.length;
                coefficientPointer += FloatVector.SPECIES_512.length())
            {
                accumulator = FloatVector.fromArray(FloatVector.SPECIES_512, coefficients, coefficientPointer).fma(
                    FloatVector.fromArray(FloatVector.SPECIES_512, samples, samplePointer + coefficientPointer),
                    accumulator);
            }

            filtered[samplePointer] = accumulator.reduceLanes(VectorOperators.ADD);
        }
    }

    private static int requireSupportedVectorBitSize(VectorSpecies<Float> species)
    {
        int vectorBitSize = species.vectorBitSize();

        return switch(vectorBitSize)
        {
            case 64, 128, 256, 512 -> vectorBitSize;
            default -> throw new IllegalArgumentException("Unsupported FIR vector width: " + vectorBitSize);
        };
    }
}
