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
package io.github.dsheirer.source.tuner.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultiFrequencyTunerChannelSourceTest
{
    private static final long FIRST_FREQUENCY = 851_012_500L;
    private static final long SECOND_FREQUENCY = 851_512_500L;
    private static final long THIRD_FREQUENCY = 852_012_500L;
    private static final List<Long> FREQUENCIES =
        List.of(FIRST_FREQUENCY, SECOND_FREQUENCY, THIRD_FREQUENCY);
    private static final ChannelSpecification CHANNEL_SPECIFICATION =
        new ChannelSpecification(50_000, 12_500, 6_250, 7_000);

    @Test
    void restoredMiddleFrequencyRotatesToFollowingFrequency() throws Exception
    {
        MultiFrequencyTunerChannelSource source = source(SECOND_FREQUENCY);

        assertEquals(THIRD_FREQUENCY, getNextFrequency(source));
    }

    @Test
    void restoredLastFrequencyWrapsToFirstFrequency() throws Exception
    {
        MultiFrequencyTunerChannelSource source = source(THIRD_FREQUENCY);

        assertEquals(FIRST_FREQUENCY, getNextFrequency(source));
    }

    @Test
    void firstFrequencyStillRotatesToSecondFrequency() throws Exception
    {
        MultiFrequencyTunerChannelSource source = source(FIRST_FREQUENCY);

        assertEquals(SECOND_FREQUENCY, getNextFrequency(source));
    }

    private static MultiFrequencyTunerChannelSource source(long initialFrequency)
    {
        return new MultiFrequencyTunerChannelSource(null, new TestTunerChannelSource(initialFrequency), FREQUENCIES,
            CHANNEL_SPECIFICATION, null, "test multi-frequency source", null, null);
    }

    private static long getNextFrequency(MultiFrequencyTunerChannelSource source) throws Exception
    {
        Method method = MultiFrequencyTunerChannelSource.class.getDeclaredMethod("getNextFrequency");
        method.setAccessible(true);
        return (long)method.invoke(source);
    }

    private static class TestTunerChannelSource extends TunerChannelSource
    {
        private TestTunerChannelSource(long frequency)
        {
            super(null, new TunerChannel(frequency, CHANNEL_SPECIFICATION.getBandwidth()),
                "test tuner channel source", null);
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void setFrequency(long frequency)
        {
        }

        @Override
        public void setFrequencyCorrection(long correction)
        {
        }

        @Override
        protected void setSampleRate(double sampleRate)
        {
        }

        @Override
        public void setListener(Listener<ComplexSamples> listener)
        {
        }

        @Override
        public double getSampleRate()
        {
            return CHANNEL_SPECIFICATION.getMinimumSampleRate();
        }
    }
}
