/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.window;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.vector.calibrate.Implementation;
import org.junit.jupiter.api.Test;

class WindowFactoryTest
{
    @Test
    void mapsCalibrationResultsToReusableWindowProcessors()
    {
        float[] coefficients = WindowFactory.getHann(19);

        assertInstanceOf(ScalarWindow.class,
            WindowFactory.getWindowProcessor(coefficients, Implementation.UNCALIBRATED));
        assertInstanceOf(ScalarWindow.class,
            WindowFactory.getWindowProcessor(coefficients, Implementation.SCALAR));

        for(Implementation implementation: new Implementation[]{Implementation.VECTOR_SIMD_PREFERRED,
            Implementation.VECTOR_SIMD_64, Implementation.VECTOR_SIMD_128, Implementation.VECTOR_SIMD_256,
            Implementation.VECTOR_SIMD_512})
        {
            assertInstanceOf(VectorWindow.class,
                WindowFactory.getWindowProcessor(coefficients, implementation));
        }
    }

    @Test
    void vectorWindowMatchesScalarIncludingANonVectorAlignedTail()
    {
        float[] coefficients = WindowFactory.getBlackmanHarris7(19);
        float[] scalarSamples = samples(19);
        float[] vectorSamples = scalarSamples.clone();

        WindowFactory.getWindowProcessor(coefficients, Implementation.SCALAR).apply(scalarSamples);
        WindowFactory.getWindowProcessor(coefficients, Implementation.VECTOR_SIMD_PREFERRED).apply(vectorSamples);

        assertArrayEquals(scalarSamples, vectorSamples, 0.0f);
    }

    @Test
    void reusableProcessorsRejectMismatchedSampleLengths()
    {
        float[] coefficients = WindowFactory.getHann(19);

        assertThrows(IllegalArgumentException.class,
            () -> WindowFactory.getWindowProcessor(coefficients, Implementation.SCALAR).apply(samples(18)));
        assertThrows(IllegalArgumentException.class,
            () -> WindowFactory.getWindowProcessor(coefficients, Implementation.VECTOR_SIMD_PREFERRED)
                .apply(samples(18)));
    }

    private static float[] samples(int length)
    {
        float[] samples = new float[length];

        for(int x = 0; x < length; x++)
        {
            samples[x] = (float)(Math.sin(x * 0.19) - Math.cos(x * 0.07) * 0.4);
        }

        return samples;
    }
}
