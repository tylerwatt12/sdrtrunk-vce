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
    private final VectorSpecies<Float> mSpecies;
    private final FloatVector[] mCoefficientVectors;
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
        mSpecies = species;
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

        mCoefficientVectors = new FloatVector[paddedCoefficientLength / species.length()];

        for(int x = 0; x < mCoefficientVectors.length; x++)
        {
            mCoefficientVectors[x] = FloatVector.fromArray(species, reversedPaddedCoefficients,
                x * species.length());
        }

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

        for(int samplePointer = 0; samplePointer < samples.length; samplePointer++)
        {
            FloatVector accumulator = FloatVector.zero(mSpecies);

            for(int coefficientVector = 0; coefficientVector < mCoefficientVectors.length; coefficientVector++)
            {
                FloatVector sampleVector = FloatVector.fromArray(mSpecies, mWorkspace,
                    samplePointer + coefficientVector * mSpecies.length());
                accumulator = mCoefficientVectors[coefficientVector].fma(sampleVector, accumulator);
            }

            filtered[samplePointer] = accumulator.reduceLanes(VectorOperators.ADD);
        }

        if(mHistoryLength > 0)
        {
            //The concatenated history and input starts at zero.  Its final history-length values start at input size.
            System.arraycopy(mWorkspace, samples.length, mHistory, 0, mHistoryLength);
        }

        return filtered;
    }
}
