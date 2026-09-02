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

package io.github.dsheirer.source.tuner.sdrplay;

import io.github.dsheirer.vector.calibrate.Implementation;
import java.util.Objects;

/**
 * Provides calibrated SDRplay sample converters.  Converter instances are immutable singletons, so native buffer
 * conversion does not allocate implementation objects or select a SIMD width inside its sample loops.
 */
public final class RspSampleConverterFactory
{
    private static final IRspSampleConverter SCALAR = new ScalarRspSampleConverter();
    private static final IRspSampleConverter VECTOR_PREFERRED =
        new VectorRspSampleConverter(Implementation.VECTOR_SIMD_PREFERRED);
    private static final IRspSampleConverter VECTOR_64 =
        new VectorRspSampleConverter(Implementation.VECTOR_SIMD_64);
    private static final IRspSampleConverter VECTOR_128 =
        new VectorRspSampleConverter(Implementation.VECTOR_SIMD_128);
    private static final IRspSampleConverter VECTOR_256 =
        new VectorRspSampleConverter(Implementation.VECTOR_SIMD_256);
    private static final IRspSampleConverter VECTOR_512 =
        new VectorRspSampleConverter(Implementation.VECTOR_SIMD_512);
    private static volatile IRspSampleConverter sCalibratedConverter = SCALAR;

    private RspSampleConverterFactory()
    {
    }

    /**
     * Gets the calibrated converter.  Before calibration preferences are loaded this safely returns the scalar
     * implementation; updating the reference after calibration is one non-blocking volatile write.
     */
    public static IRspSampleConverter getConverter()
    {
        return sCalibratedConverter;
    }

    /**
     * Updates the production converter after saved calibration preferences are loaded or a new calibration completes.
     */
    public static void setImplementation(Implementation implementation)
    {
        sCalibratedConverter = getConverter(implementation);
    }

    /**
     * Gets a converter for an explicit implementation.  Used by calibration and correctness tests.
     */
    public static IRspSampleConverter getConverter(Implementation implementation)
    {
        Objects.requireNonNull(implementation, "Implementation cannot be null");

        return switch(implementation)
        {
            case VECTOR_SIMD_PREFERRED -> VECTOR_PREFERRED;
            case VECTOR_SIMD_64 -> VECTOR_64;
            case VECTOR_SIMD_128 -> VECTOR_128;
            case VECTOR_SIMD_256 -> VECTOR_256;
            case VECTOR_SIMD_512 -> VECTOR_512;
            case SCALAR, UNCALIBRATED -> SCALAR;
        };
    }

    static void validate(short[] iSamples, short[] qSamples, float[] iOutput, float[] qOutput)
    {
        validateInputs(iSamples, qSamples);
        Objects.requireNonNull(iOutput, "I output cannot be null");
        Objects.requireNonNull(qOutput, "Q output cannot be null");

        if(iOutput.length != iSamples.length || qOutput.length != iSamples.length)
        {
            throw new IllegalArgumentException("Separate output arrays must match the input sample length");
        }
    }

    static void validateInterleaved(short[] iSamples, short[] qSamples, float[] output)
    {
        validateInputs(iSamples, qSamples);
        Objects.requireNonNull(output, "Interleaved output cannot be null");

        if(output.length != 2 * iSamples.length)
        {
            throw new IllegalArgumentException("Interleaved output length must be twice the input sample length");
        }
    }

    static void validateInputs(short[] iSamples, short[] qSamples)
    {
        Objects.requireNonNull(iSamples, "I samples cannot be null");
        Objects.requireNonNull(qSamples, "Q samples cannot be null");

        if(iSamples.length != qSamples.length)
        {
            throw new IllegalArgumentException("I and Q sample arrays must have the same length");
        }
    }
}
