/* Copyright (C) 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.dsp.filter.channelizer;

import io.github.dsheirer.sample.complex.ComplexSamples;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.jtransforms.fft.FloatFFT_1D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class TwoChannelSynthesizerM2Test
{
    @Test
    void matchesLegacyFftAcrossConsecutiveOddEvenBuffersAndFilters()
    {
        float[][] filters = {{1.0f}, {0.5f, -0.25f}, {0.125f, -0.5f, 0.25f},
            {0.0f, -0.0f, Float.MIN_VALUE, -Float.MIN_VALUE}, coefficients(49)};
        int[] lengths = {0, 1, 2, 3, 8, 17, 0, 2048, 1};

        for(float[] filter : filters)
        {
            float[] originalFilter = filter.clone();
            TwoChannelSynthesizerM2 actual = new TwoChannelSynthesizerM2(filter);
            LegacySynthesizer expected = new LegacySynthesizer(filter);
            Random random = new Random(0x25_2026L);

            for(int step = 0; step < lengths.length; step++)
            {
                long timestamp = 1_700_000_000_000L + step * 41L;
                ComplexSamples first = samples(lengths[step], timestamp, random, step);
                ComplexSamples second = samples(lengths[step], timestamp + 7, random, step + 3);
                float[][] originals = {first.i().clone(), first.q().clone(), second.i().clone(), second.q().clone()};
                ComplexSamples reference = expected.process(first, second);
                ComplexSamples result = actual.process(first, second);
                assertEquals(timestamp, result.timestamp());
                assertExact(reference.i(), result.i());
                assertExact(reference.q(), result.q());
                assertExact(originals[0], first.i());
                assertExact(originals[1], first.q());
                assertExact(originals[2], second.i());
                assertExact(originals[3], second.q());
            }

            assertExact(originalFilter, filter);
        }
    }

    @Test
    void laterProcessingDoesNotReuseReturnedArraysOrMutateInputs()
    {
        TwoChannelSynthesizerM2 synthesizer = new TwoChannelSynthesizerM2(coefficients(13));
        List<ComplexSamples> retained = new ArrayList<>();
        List<float[][]> snapshots = new ArrayList<>();
        Random random = new Random(73);

        for(int step = 0; step < 8; step++)
        {
            ComplexSamples input = samples(17 + step, step, random, step);
            ComplexSamples result = synthesizer.process(input, input);
            assertNotSame(input.i(), result.i());
            assertNotSame(input.q(), result.q());
            for(ComplexSamples previous : retained)
            {
                assertNotSame(previous.i(), result.i());
                assertNotSame(previous.q(), result.q());
            }
            retained.add(result);
            snapshots.add(new float[][]{result.i().clone(), result.q().clone()});
        }

        for(int index = 0; index < retained.size(); index++)
        {
            assertExact(snapshots.get(index)[0], retained.get(index).i());
            assertExact(snapshots.get(index)[1], retained.get(index).q());
        }
    }

    private static float[] coefficients(int length)
    {
        float[] filter = new float[length];
        Random random = new Random(length);
        for(int index = 0; index < length; index++) filter[index] = (random.nextFloat() - 0.5f) / length;
        return filter;
    }

    private static ComplexSamples samples(int length, long timestamp, Random random, int phase)
    {
        float[] edge = {0.0f, -0.0f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_NORMAL,
            -Float.MIN_NORMAL, 1.0f, -1.0f, Float.MAX_VALUE / 64, -Float.MAX_VALUE / 64};
        float[] i = new float[length];
        float[] q = new float[length];
        for(int index = 0; index < length; index++)
        {
            i[index] = index % 3 == 0 ? edge[(index + phase) % edge.length] : random.nextFloat() * 2 - 1;
            q[index] = index % 2 == 0 ? edge[(index + phase + 1) % edge.length] : random.nextFloat() * 2 - 1;
        }
        return new ComplexSamples(i, q, timestamp);
    }

    private static void assertExact(float[] expected, float[] actual)
    {
        assertEquals(expected.length, actual.length);
        for(int index = 0; index < expected.length; index++)
            assertEquals(Float.floatToRawIntBits(expected[index]), Float.floatToRawIntBits(actual[index]),
                "sample " + index);
    }

    /** Original general-FFT path, including persistent serpentine phase and tap-major float accumulation. */
    private static final class LegacySynthesizer
    {
        private final FloatFFT_1D mFft = new FloatFFT_1D(2);
        private final float[] mFilter;
        private final float[] mHistory;
        private final float[] mProducts;
        private boolean mTop = true;

        private LegacySynthesizer(float[] coefficients)
        {
            mFilter = new float[((coefficients.length + 1) / 2) * 4];
            for(int index = 0; index < coefficients.length; index++)
                mFilter[index * 2] = mFilter[index * 2 + 1] = coefficients[index];
            mHistory = new float[mFilter.length];
            mProducts = new float[mFilter.length];
        }

        private ComplexSamples process(ComplexSamples first, ComplexSamples second)
        {
            float[] i = new float[first.i().length];
            float[] q = new float[first.q().length];
            float[] fft = new float[4];
            for(int sample = 0; sample < i.length; sample++)
            {
                fft[0] = first.i()[sample];
                fft[1] = first.q()[sample];
                fft[2] = second.i()[sample];
                fft[3] = second.q()[sample];
                mFft.complexInverse(fft, true);
                System.arraycopy(mHistory, 0, mHistory, 4, mHistory.length - 4);
                if(mTop) System.arraycopy(fft, 0, mHistory, 0, 4);
                else
                {
                    System.arraycopy(fft, 0, mHistory, 2, 2);
                    System.arraycopy(fft, 2, mHistory, 0, 2);
                }
                for(int index = 0; index < mProducts.length; index++)
                    mProducts[index] = mHistory[index] * mFilter[index];
                float inphase = 0.0f;
                float quadrature = 0.0f;
                for(int index = 0; index < mProducts.length; index += 2)
                {
                    inphase += mProducts[index];
                    quadrature += mProducts[index + 1];
                }
                i[sample] = inphase;
                q[sample] = quadrature;
                mTop = !mTop;
            }
            return new ComplexSamples(i, q, first.timestamp());
        }
    }
}
