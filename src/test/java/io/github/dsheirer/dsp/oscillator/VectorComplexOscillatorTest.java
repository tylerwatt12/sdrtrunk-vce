/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.dsp.oscillator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.sample.complex.ComplexSamples;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jdk.incubator.vector.FloatVector;
import org.junit.jupiter.api.Test;

class VectorComplexOscillatorTest
{
    private static final double FREQUENCY = 5_000.0d;
    private static final double SAMPLE_RATE = 50_000.0d;
    private static final int VECTOR_LENGTH = FloatVector.SPECIES_PREFERRED.length();
    private static final int[] BUFFER_SIZES = {
        VECTOR_LENGTH * 2,
        VECTOR_LENGTH + 1,
        1,
        (VECTOR_LENGTH * 2) - 1,
        0,
        (VECTOR_LENGTH * 3) + 3
    };
    private static final float TOLERANCE = 0.001f;
    private static final double PHASE_TOLERANCE = 0.002d;

    @Test
    void interleavedGenerationMatchesScalarPhaseAndContinuity()
    {
        IComplexOscillator scalar = new ScalarComplexOscillator(FREQUENCY, SAMPLE_RATE);
        IComplexOscillator vector = new VectorComplexOscillator(FREQUENCY, SAMPLE_RATE);

        for(int bufferSize: BUFFER_SIZES)
        {
            assertArrayEquals(scalar.generate(bufferSize), vector.generate(bufferSize), TOLERANCE);
        }
    }

    @Test
    void deinterleavedGenerationMatchesScalarPhaseAndContinuity()
    {
        IComplexOscillator scalar = new ScalarComplexOscillator(FREQUENCY, SAMPLE_RATE);
        IComplexOscillator vector = new VectorComplexOscillator(FREQUENCY, SAMPLE_RATE);

        for(int x = 0; x < BUFFER_SIZES.length; x++)
        {
            assertEquivalent(scalar.generateComplexSamples(BUFFER_SIZES[x], x * 1_000L),
                vector.generateComplexSamples(BUFFER_SIZES[x], x * 1_000L));
        }
    }

    @Test
    void frequencyChangePreservesTheLastGeneratedPhase()
    {
        IComplexOscillator scalar = new ScalarComplexOscillator(FREQUENCY, SAMPLE_RATE);
        IComplexOscillator vector = new VectorComplexOscillator(FREQUENCY, SAMPLE_RATE);
        scalar.generateComplexSamples(VECTOR_LENGTH + 3, 0L);
        vector.generateComplexSamples(VECTOR_LENGTH + 3, 0L);

        scalar.setFrequency(7_500.0d);
        vector.setFrequency(7_500.0d);

        for(int bufferSize: BUFFER_SIZES)
        {
            assertEquivalent(scalar.generateComplexSamples(bufferSize, 1_000L),
                vector.generateComplexSamples(bufferSize, 1_000L));
        }
    }

    @Test
    void sampleRateChangePreservesTheLastGeneratedPhase()
    {
        IComplexOscillator scalar = new ScalarComplexOscillator(FREQUENCY, SAMPLE_RATE);
        IComplexOscillator vector = new VectorComplexOscillator(FREQUENCY, SAMPLE_RATE);
        scalar.generateComplexSamples((VECTOR_LENGTH * 2) + 1, 0L);
        vector.generateComplexSamples((VECTOR_LENGTH * 2) + 1, 0L);

        scalar.setSampleRate(48_000.0d);
        vector.setSampleRate(48_000.0d);

        for(int bufferSize: BUFFER_SIZES)
        {
            assertEquivalent(scalar.generateComplexSamples(bufferSize, 1_000L),
                vector.generateComplexSamples(bufferSize, 1_000L));
        }
    }

    @Test
    void concurrentFrequencyUpdatesAreAppliedOnlyAtBufferBoundaries() throws Exception
    {
        VectorComplexOscillator oscillator = new VectorComplexOscillator(3_500.0d, SAMPLE_RATE);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch start = new CountDownLatch(1);

        try
        {
            Future<?> updates = executor.submit(() ->
            {
                await(start);

                for(int x = 0; x < 20_000; x++)
                {
                    oscillator.setFrequency((x & 1) == 0 ? 3_500.0d : 7_500.0d);
                }

                oscillator.setFrequency(6_250.0d);
            });

            start.countDown();

            for(int x = 0; x < 100; x++)
            {
                ComplexSamples samples = oscillator.generateComplexSamples((VECTOR_LENGTH * 64) + 3, x);
                assertSinglePhaseIncrement(samples);
            }

            updates.get(10, TimeUnit.SECONDS);

            ComplexSamples finalSamples = oscillator.generateComplexSamples((VECTOR_LENGTH * 4) + 3, 1_000L);
            assertPhaseIncrement(finalSamples, (Math.PI * 2.0d * 6_250.0d) / SAMPLE_RATE);
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    private static void assertEquivalent(ComplexSamples expected, ComplexSamples actual)
    {
        assertArrayEquals(expected.i(), actual.i(), TOLERANCE);
        assertArrayEquals(expected.q(), actual.q(), TOLERANCE);
    }

    private static void assertSinglePhaseIncrement(ComplexSamples samples)
    {
        if(samples.i().length < 2)
        {
            return;
        }

        double expected = phaseIncrement(samples.i()[0], samples.q()[0], samples.i()[1], samples.q()[1]);
        assertPhaseIncrement(samples, expected);
    }

    private static void assertPhaseIncrement(ComplexSamples samples, double expected)
    {
        for(int x = 1; x < samples.i().length; x++)
        {
            float previousInphase = samples.i()[x - 1];
            float previousQuadrature = samples.q()[x - 1];
            float inphase = samples.i()[x];
            float quadrature = samples.q()[x];
            assertTrue(Float.isFinite(inphase));
            assertTrue(Float.isFinite(quadrature));
            assertEquals(expected, phaseIncrement(previousInphase, previousQuadrature, inphase, quadrature),
                PHASE_TOLERANCE);
        }
    }

    private static double phaseIncrement(float previousInphase, float previousQuadrature, float inphase,
                                         float quadrature)
    {
        double dotProduct = (previousInphase * inphase) + (previousQuadrature * quadrature);
        double crossProduct = (previousInphase * quadrature) - (previousQuadrature * inphase);
        return Math.atan2(crossProduct, dotProduct);
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        }
        catch(InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to start concurrent frequency updates", ie);
        }
    }
}
