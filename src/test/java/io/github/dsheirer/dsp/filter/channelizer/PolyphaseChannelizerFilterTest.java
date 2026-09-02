/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.dsp.filter.channelizer;

import io.github.dsheirer.vector.calibrate.Implementation;
import java.util.Arrays;
import java.util.Random;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PolyphaseChannelizerFilterTest
{
    private static final Implementation[] VECTOR_IMPLEMENTATIONS = new Implementation[]{
        Implementation.VECTOR_SIMD_64,
        Implementation.VECTOR_SIMD_128,
        Implementation.VECTOR_SIMD_256,
        Implementation.VECTOR_SIMD_512
    };

    @Test
    void vectorImplementationsMatchScalarTapOrderAcrossChannelCountsAndTails()
    {
        Random random = new Random(0x5D2C_2026L);
        int[] tapsPerChannel = new int[]{1, 9, 12};

        //All counts represent an even number of complex channels.  The values around each lane boundary exercise the
        //full-vector and scalar-tail transitions, while the larger counts represent common tuner sample rates.
        int[] subChannelCounts = new int[]{2, 4, 6, 8, 10, 12, 14, 16, 18, 30, 32, 34, 80, 100, 192, 256, 800};

        for(int taps: tapsPerChannel)
        {
            for(int subChannelCount: subChannelCounts)
            {
                int inputLength = taps * subChannelCount;
                float[] samples = samples(random, inputLength);
                float[] coefficients = samples(random, inputLength);
                float[] originalSamples = samples.clone();
                float[] originalCoefficients = coefficients.clone();
                float[] expected = reference(samples, coefficients, taps, subChannelCount);

                assertExact(expected, apply(Implementation.SCALAR, samples, coefficients, taps, subChannelCount),
                    Implementation.SCALAR, taps, subChannelCount);

                for(Implementation implementation: VECTOR_IMPLEMENTATIONS)
                {
                    float[] actual = apply(implementation, samples, coefficients, taps, subChannelCount);
                    assertExact(expected, actual, implementation, taps, subChannelCount);
                }

                assertArrayEquals(originalSamples, samples, 0.0f, "sample input must remain unchanged");
                assertArrayEquals(originalCoefficients, coefficients, 0.0f,
                    "coefficient input must remain unchanged");
            }
        }
    }

    /**
     * Java 25 only reliably intrinsifies these loops when every vector operation receives a static species constant.
     * A generic hot method accepting VectorSpecies produced correct answers but allocated each vector intermediate
     * and was tens of times slower on receiver hardware.
     */
    @Test
    void vectorHotKernelsDoNotAcceptDynamicSpecies()
    {
        Arrays.stream(VectorPolyphaseChannelizerFilter.class.getDeclaredMethods())
            .flatMap(method -> Arrays.stream(method.getParameterTypes()))
            .forEach(parameterType -> assertNotEquals(VectorSpecies.class, parameterType,
                "hot vector kernels must use FloatVector.SPECIES_* constants directly"));
    }

    @Test
    void factoryUsesScalarForUncalibratedAndMapsPreferredSpecies()
    {
        assertSame(PolyphaseChannelizerFilterFactory.getFilter(Implementation.SCALAR),
            PolyphaseChannelizerFilterFactory.getFilter(Implementation.UNCALIBRATED));

        Implementation preferred = switch(FloatVector.SPECIES_PREFERRED.vectorBitSize())
        {
            case 64 -> Implementation.VECTOR_SIMD_64;
            case 128 -> Implementation.VECTOR_SIMD_128;
            case 256 -> Implementation.VECTOR_SIMD_256;
            case 512 -> Implementation.VECTOR_SIMD_512;
            default -> Implementation.SCALAR;
        };

        assertSame(PolyphaseChannelizerFilterFactory.getFilter(preferred),
            PolyphaseChannelizerFilterFactory.getFilter(Implementation.VECTOR_SIMD_PREFERRED));
    }

    private static float[] apply(Implementation implementation, float[] samples, float[] coefficients,
                                 int tapsPerChannel, int subChannelCount)
    {
        float[] accumulator = new float[subChannelCount];
        Arrays.fill(accumulator, Float.NaN);
        PolyphaseChannelizerFilterFactory.getFilter(implementation)
            .filter(samples, coefficients, accumulator, tapsPerChannel, subChannelCount);
        return accumulator;
    }

    /** Original tap-major scalar arithmetic used by ComplexPolyphaseChannelizerM2 before strategy dispatch. */
    private static float[] reference(float[] samples, float[] coefficients, int tapsPerChannel, int subChannelCount)
    {
        float[] accumulator = new float[subChannelCount];

        for(int tap = 0; tap < tapsPerChannel; tap++)
        {
            int tapOffset = tap * subChannelCount;

            for(int channel = 0; channel < subChannelCount; channel++)
            {
                int index = tapOffset + channel;
                accumulator[channel] += samples[index] * coefficients[index];
            }
        }

        return accumulator;
    }

    private static float[] samples(Random random, int length)
    {
        float[] samples = new float[length];

        for(int x = 0; x < samples.length; x++)
        {
            samples[x] = random.nextFloat() * 2.0f - 1.0f;
        }

        return samples;
    }

    private static void assertExact(float[] expected, float[] actual, Implementation implementation,
                                    int tapsPerChannel, int subChannelCount)
    {
        assertEquals(expected.length, actual.length);

        for(int channel = 0; channel < expected.length; channel++)
        {
            assertEquals(Float.floatToRawIntBits(expected[channel]), Float.floatToRawIntBits(actual[channel]),
                implementation + " mismatch with " + tapsPerChannel + " taps, " + subChannelCount +
                    " sub-channels, at channel " + channel);
        }
    }
}
