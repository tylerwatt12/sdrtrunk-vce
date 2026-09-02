/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.dsp.filter.halfband;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.window.WindowType;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.Function;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;

/** Verifies arithmetic, vector tails and retained stream history for every real half-band SIMD implementation. */
class RealHalfBandDecimationFilterParityTest
{
    private static final float TOLERANCE = 0.000_02f;
    private static final float[][] STREAM_BUFFERS = {
        samples(2048, 0),
        samples(18, 2048),
        samples(2050, 2066),
        samples(2, 4116),
        samples(514, 4118)
    };

    @Test
    void sharedKernelCachesCoefficientsWithoutRetainingRuntimeVectorSpecies()
    {
        List<java.lang.reflect.Field> fields = List.of(VectorRealHalfBandDecimationFilter.class.getDeclaredFields());
        assertFalse(fields.stream().anyMatch(field ->
            !Modifier.isStatic(field.getModifiers()) && VectorSpecies.class.isAssignableFrom(field.getType())),
            "A runtime VectorSpecies field prevents reliable SIMD intrinsic lowering in the half-band hot loop");
        assertTrue(fields.stream().anyMatch(field -> !Modifier.isStatic(field.getModifiers()) &&
            field.getType().equals(FloatVector[].class)),
            "Half-band coefficients should be converted to vectors once instead of reloaded for every output sample");
    }

    @Test
    void everySpecializedWidthMatchesScalarAcrossConsecutiveBuffersAndTails()
    {
        assertStreamingParity(11, List.of(
            new Candidate("11-tap 64", VectorRealHalfBandDecimationFilter11Tap64Bit::new),
            new Candidate("11-tap 128", VectorRealHalfBandDecimationFilter11Tap128Bit::new),
            new Candidate("11-tap 256", VectorRealHalfBandDecimationFilter11Tap256Bit::new),
            new Candidate("11-tap 512", VectorRealHalfBandDecimationFilter11Tap512Bit::new)));
        assertStreamingParity(15, List.of(
            new Candidate("15-tap 64", VectorRealHalfBandDecimationFilter15Tap64Bit::new),
            new Candidate("15-tap 128", VectorRealHalfBandDecimationFilter15Tap128Bit::new),
            new Candidate("15-tap 256", VectorRealHalfBandDecimationFilter15Tap256Bit::new),
            new Candidate("15-tap 512", VectorRealHalfBandDecimationFilter15Tap512Bit::new)));
        assertStreamingParity(23, List.of(
            new Candidate("23-tap 64", VectorRealHalfBandDecimationFilter23Tap64Bit::new),
            new Candidate("23-tap 128", VectorRealHalfBandDecimationFilter23Tap128Bit::new),
            new Candidate("23-tap 256", VectorRealHalfBandDecimationFilter23Tap256Bit::new),
            new Candidate("23-tap 512", VectorRealHalfBandDecimationFilter23Tap512Bit::new)));
        assertStreamingParity(63, List.of(
            new Candidate("63-tap 64", VectorRealHalfBandDecimationFilter63Tap64Bit::new),
            new Candidate("63-tap 128", VectorRealHalfBandDecimationFilter63Tap128Bit::new),
            new Candidate("63-tap 256", VectorRealHalfBandDecimationFilter63Tap256Bit::new),
            new Candidate("63-tap 512", VectorRealHalfBandDecimationFilter63Tap512Bit::new)));
    }

    @Test
    void genericWidthsMatchScalarForShortPaddedAndMultiVectorCoefficientTails()
    {
        List<Candidate> candidates = List.of(
            new Candidate("generic preferred", VectorRealHalfBandDecimationFilterDefaultBit::new),
            new Candidate("generic 64", VectorRealHalfBandDecimationFilter64Bit::new),
            new Candidate("generic 128", VectorRealHalfBandDecimationFilter128Bit::new),
            new Candidate("generic 256", VectorRealHalfBandDecimationFilter256Bit::new),
            new Candidate("generic 512", VectorRealHalfBandDecimationFilter512Bit::new));

        //Each valid half-band size exercises a different amount of coefficient padding for the SIMD widths.
        assertStreamingParity(7, candidates);
        assertStreamingParity(47, candidates);
        assertStreamingParity(67, candidates);
    }

    private static void assertStreamingParity(int tapCount, List<Candidate> candidates)
    {
        float[] coefficients = FilterFactory.getHalfBand(tapCount, WindowType.BLACKMAN);
        float[][] expected = filterSequence(new RealHalfBandDecimationFilter(coefficients), STREAM_BUFFERS);

        for(Candidate candidate: candidates)
        {
            float[][] actual = filterSequence(candidate.factory().apply(coefficients.clone()), STREAM_BUFFERS);

            for(int x = 0; x < STREAM_BUFFERS.length; x++)
            {
                assertArrayEquals(expected[x], actual[x], TOLERANCE,
                    candidate.label() + ", " + tapCount + " taps, stream buffer " + x);
            }
        }
    }

    private static float[][] filterSequence(IRealDecimationFilter filter, float[][] buffers)
    {
        float[][] outputs = new float[buffers.length][];

        for(int x = 0; x < buffers.length; x++)
        {
            outputs[x] = filter.decimateReal(buffers[x]);
        }

        return outputs;
    }

    private static float[] samples(int length, int offset)
    {
        float[] samples = new float[length];

        for(int x = 0; x < samples.length; x++)
        {
            int index = x + offset;
            samples[x] = (float)(Math.sin(index * 0.071) * 0.73 + Math.cos(index * 0.193) * 0.21 +
                ((index % 17) - 8) * 0.003);
        }

        if(samples.length > 0)
        {
            samples[0] += 0.5f;
        }

        return samples;
    }

    private record Candidate(String label, Function<float[], IRealDecimationFilter> factory)
    {
    }
}
