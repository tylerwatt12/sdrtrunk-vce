/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import java.util.Objects;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

/**
 * Serial, reusable complex FFT boundary for optional diagnostics.  Apache Commons Math performs this transform on
 * the calling thread, so it cannot borrow normal-priority workers from a process-wide executor.  The double-precision
 * working planes are retained by the plan and reused for every frame.
 */
final class SerialDiagnosticFft implements DiagnosticComplexFft
{
    static final Factory FACTORY = SerialDiagnosticFft::new;

    private final int mSize;
    private final double[] mReal;
    private final double[] mImaginary;
    private final double[][] mWorkingData;

    SerialDiagnosticFft(int size)
    {
        if(size < 2 || Integer.bitCount(size) != 1)
        {
            throw new IllegalArgumentException("Diagnostic FFT size must be a power of two");
        }

        mSize = size;
        mReal = new double[size];
        mImaginary = new double[size];
        mWorkingData = new double[][]{mReal, mImaginary};
    }

    @Override
    public void forward(float[] interleavedSamples)
    {
        Objects.requireNonNull(interleavedSamples, "Diagnostic FFT samples cannot be null");

        if(interleavedSamples.length != mSize * 2)
        {
            throw new IllegalArgumentException("Diagnostic FFT sample length does not match the plan");
        }

        for(int sample = 0, offset = 0; sample < mSize; sample++, offset += 2)
        {
            mReal[sample] = interleavedSamples[offset];
            mImaginary[sample] = interleavedSamples[offset + 1];
        }

        FastFourierTransformer.transformInPlace(mWorkingData, DftNormalization.STANDARD, TransformType.FORWARD);

        for(int sample = 0, offset = 0; sample < mSize; sample++, offset += 2)
        {
            interleavedSamples[offset] = (float)mReal[sample];
            interleavedSamples[offset + 1] = (float)mImaginary[sample];
        }
    }
}

/** Complex transform seam that keeps optional diagnostics independent from real-time FFT implementations. */
interface DiagnosticComplexFft
{
    void forward(float[] interleavedSamples);

    @FunctionalInterface
    interface Factory
    {
        DiagnosticComplexFft create(int size);
    }
}
