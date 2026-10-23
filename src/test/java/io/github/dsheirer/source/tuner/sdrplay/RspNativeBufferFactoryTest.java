/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.sdrplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RspNativeBufferFactoryTest
{
    private static final long INITIAL_TIMESTAMP = 1_725_000_000_000L;

    @Test
    void matchesLegacyFragmentsAcrossIrregularCallbacksAndSampleRateChanges() throws Exception
    {
        RspNativeBufferFactory actual = factory(RspSampleRate.RATE_8_000, INITIAL_TIMESTAMP);
        LegacyFactory expected = new LegacyFactory(RspSampleRate.RATE_8_000, INITIAL_TIMESTAMP);
        int[] lengths = {0, 1, 31, 127, 128, 129, 511, 2016, 2016, 2048, 2049, 65, 3, 256, 31,
            4097, 128, 2016, 8192, 1, 0, 2016, 1537, 512, 127, 0, 2051};
        RspSampleRate[] rates = {RspSampleRate.RATE_0_250, RspSampleRate.DUO_RATE_0_500,
            RspSampleRate.RATE_2_048, RspSampleRate.RATE_8_000};

        for(int round = 0; round < 3; round++)
        {
            for(int index = 0; index < lengths.length; index++)
            {
                RspSampleRate rate = rates[(index + round) % rates.length];
                actual.setSampleRate(rate);
                expected.setSampleRate(rate);
                short[] i = samples(lengths[index], 17L * index + round);
                short[] q = samples(lengths[index], 31L * index + round + 1000);
                short[] beforeI = i.clone();
                short[] beforeQ = q.clone();
                long timestamp = INITIAL_TIMESTAMP + round * 100_000L + index * 23L;

                assertBuffers(expected.get(i, q, timestamp), actual.get(i, q, timestamp));
                assertArrayEquals(beforeI, i, "Repackaging must not mutate callback I samples");
                assertArrayEquals(beforeQ, q, "Repackaging must not mutate callback Q samples");
            }
        }
    }

    @Test
    void preservesExistingTimestampAnchorAndPerFragmentIntegerOffsets() throws Exception
    {
        RspNativeBufferFactory factory = factory(RspSampleRate.RATE_0_250, INITIAL_TIMESTAMP);
        assertTrue(factory.get(new short[32], new short[32], 10_000).isEmpty());
        assertTrue(factory.get(new short[32], new short[32], 20_000).isEmpty());
        List<RspNativeBuffer> first = factory.get(new short[64], new short[64], 30_000);
        assertEquals(1, first.size());
        assertEquals(INITIAL_TIMESTAMP, first.getFirst().getTimestamp(),
            "An underfilled callback does not move the existing residual timestamp");
        assertEquals(30_000, factory.get(new short[128], new short[128], 40_000).getFirst().getTimestamp(),
            "After emission the next anchor is the callback timestamp, not its calculated tail time");

        factory = factory(RspSampleRate.RATE_0_250, INITIAL_TIMESTAMP);
        assertTrue(factory.get(new short[1490], new short[1490], 50_000).isEmpty());
        List<RspNativeBuffer> fragments = factory.get(new short[512], new short[512], 60_000);
        assertEquals(3, fragments.size());
        for(int index = 0; index < fragments.size(); index++)
        {
            assertEquals(512, fragments.get(index).sampleCount());
            assertEquals(INITIAL_TIMESTAMP + 2L * index, fragments.get(index).getTimestamp());
        }
        List<RspNativeBuffer> next = factory.get(new short[512], new short[512], 70_000);
        assertEquals(1, next.size());
        assertEquals(60_000, next.getFirst().getTimestamp());
    }

    @Test
    void ownsResidualsAndRetainedOutputsAfterCallbackArraysAreReused() throws Exception
    {
        RspNativeBufferFactory actual = factory(RspSampleRate.RATE_8_000, INITIAL_TIMESTAMP);
        LegacyFactory expected = new LegacyFactory(RspSampleRate.RATE_8_000, INITIAL_TIMESTAMP);
        List<RspNativeBuffer> retained = new ArrayList<>();
        List<ExpectedBuffer> retainedExpected = new ArrayList<>();
        List<ComplexSamples> converted = new ArrayList<>();

        for(int index = 0; index < 12; index++)
        {
            int length = index % 3 == 2 ? 128 : 2016;
            short[] i = samples(length, index);
            short[] q = samples(length, index + 100);
            retainedExpected.addAll(expected.get(i, q, INITIAL_TIMESTAMP + index * 100L));
            List<RspNativeBuffer> buffers = actual.get(i, q, INITIAL_TIMESTAMP + index * 100L);
            retained.addAll(buffers);
            buffers.forEach(buffer -> converted.add(buffer.iterator().next()));
            Arrays.fill(i, Short.MIN_VALUE);
            Arrays.fill(q, Short.MAX_VALUE);
            assertBuffers(retainedExpected, retained);
        }

        for(int index = 0; index < retainedExpected.size(); index++)
        {
            assertSamples(retainedExpected.get(index), converted.get(index));
        }
    }

    @Test
    void separateTunerFactoriesKeepTheirResidualsAndClocksIndependent() throws Exception
    {
        RspNativeBufferFactory first = factory(RspSampleRate.RATE_1_000, INITIAL_TIMESTAMP);
        RspNativeBufferFactory second = factory(RspSampleRate.DUO_RATE_2_000, INITIAL_TIMESTAMP + 1234);
        LegacyFactory firstExpected = new LegacyFactory(RspSampleRate.RATE_1_000, INITIAL_TIMESTAMP);
        LegacyFactory secondExpected = new LegacyFactory(RspSampleRate.DUO_RATE_2_000, INITIAL_TIMESTAMP + 1234);

        for(int index = 0; index < 20; index++)
        {
            short[] i = samples(index % 2 == 0 ? 2016 : 129, index);
            short[] q = samples(i.length, index + 100);
            assertBuffers(firstExpected.get(i, q, 1000 + index), first.get(i, q, 1000 + index));
            assertBuffers(secondExpected.get(q, i, 5000 + index), second.get(q, i, 5000 + index));
        }
    }

    private static RspNativeBufferFactory factory(RspSampleRate rate, long timestamp) throws Exception
    {
        RspNativeBufferFactory factory = new RspNativeBufferFactory(rate);
        Field field = RspNativeBufferFactory.class.getDeclaredField("mResidualTimestamp");
        field.setAccessible(true);
        field.setLong(factory, timestamp);
        return factory;
    }

    private static short[] samples(int length, long seed)
    {
        Random random = new Random(seed);
        short[] samples = new short[length];
        for(int index = 0; index < length; index++)
        {
            samples[index] = (short)random.nextInt();
        }
        return samples;
    }

    private static void assertBuffers(List<ExpectedBuffer> expected, List<RspNativeBuffer> actual)
    {
        assertEquals(expected.size(), actual.size(), "Fragment count");
        for(int index = 0; index < expected.size(); index++)
        {
            ExpectedBuffer reference = expected.get(index);
            RspNativeBuffer buffer = actual.get(index);
            assertEquals(reference.i().length, buffer.sampleCount());
            assertEquals(reference.timestamp(), buffer.getTimestamp());
            var separate = buffer.iterator();
            assertTrue(separate.hasNext());
            assertSamples(reference, separate.next());
            assertFalse(separate.hasNext());
            var interleaved = buffer.iteratorInterleaved();
            assertTrue(interleaved.hasNext());
            InterleavedComplexSamples samples = interleaved.next();
            float[] expectedInterleaved = new float[reference.i().length * 2];
            for(int sample = 0; sample < reference.i().length; sample++)
            {
                expectedInterleaved[2 * sample] = reference.i()[sample] * IRspSampleConverter.SAMPLE_TO_FLOAT;
                expectedInterleaved[2 * sample + 1] = reference.q()[sample] * IRspSampleConverter.SAMPLE_TO_FLOAT;
            }
            assertArrayEquals(expectedInterleaved, samples.samples(), 0.0f);
            assertEquals(reference.timestamp(), samples.timestamp());
            assertFalse(interleaved.hasNext());
        }
    }

    private static void assertSamples(ExpectedBuffer expected, ComplexSamples actual)
    {
        float[] i = new float[expected.i().length];
        float[] q = new float[expected.q().length];
        for(int index = 0; index < i.length; index++)
        {
            i[index] = expected.i()[index] * IRspSampleConverter.SAMPLE_TO_FLOAT;
            q[index] = expected.q()[index] * IRspSampleConverter.SAMPLE_TO_FLOAT;
        }
        assertArrayEquals(i, actual.i(), 0.0f);
        assertArrayEquals(q, actual.q(), 0.0f);
        assertEquals(expected.timestamp(), actual.timestamp());
    }

    private record ExpectedBuffer(short[] i, short[] q, long timestamp) {}

    /** Previous concatenate/shrink algorithm, retained as an independent sample and timestamp oracle. */
    private static final class LegacyFactory
    {
        private short[] mIResidual = new short[0];
        private short[] mQResidual = new short[0];
        private long mTimestamp;
        private float mSamplesPerMillisecond;

        private LegacyFactory(RspSampleRate rate, long timestamp)
        {
            mTimestamp = timestamp;
            setSampleRate(rate);
        }

        private void setSampleRate(RspSampleRate rate)
        {
            mSamplesPerMillisecond = rate.getSamplesPerMillisecond();
        }

        private List<ExpectedBuffer> get(short[] i, short[] q, long timestamp)
        {
            int optimal = 128;
            while(optimal < i.length)
            {
                optimal *= 2;
            }
            short[] combinedI = Arrays.copyOf(mIResidual, mIResidual.length + i.length);
            System.arraycopy(i, 0, combinedI, mIResidual.length, i.length);
            short[] combinedQ = Arrays.copyOf(mQResidual, mQResidual.length + q.length);
            System.arraycopy(q, 0, combinedQ, mQResidual.length, q.length);
            if(combinedI.length < optimal)
            {
                mIResidual = combinedI;
                mQResidual = combinedQ;
                return Collections.emptyList();
            }
            List<ExpectedBuffer> result = new ArrayList<>();
            while(combinedI.length >= optimal)
            {
                short[] outputI = Arrays.copyOf(combinedI, optimal);
                combinedI = Arrays.copyOfRange(combinedI, optimal, combinedI.length);
                short[] outputQ = Arrays.copyOf(combinedQ, optimal);
                combinedQ = Arrays.copyOfRange(combinedQ, optimal, combinedQ.length);
                result.add(new ExpectedBuffer(outputI, outputQ, mTimestamp));
                mTimestamp += (long)(optimal / mSamplesPerMillisecond);
            }
            mIResidual = combinedI;
            mQResidual = combinedQ;
            mTimestamp = timestamp;
            return result;
        }
    }
}
