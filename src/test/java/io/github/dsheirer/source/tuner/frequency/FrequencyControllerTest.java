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
package io.github.dsheirer.source.tuner.frequency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.source.SourceException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class FrequencyControllerTest
{
    @Test
    void failedHardwareTuneDoesNotPublishRequestedFrequencyOrBroadcast() throws Exception
    {
        TestTunable tunable = new TestTunable();
        FrequencyController controller = new FrequencyController(tunable);
        controller.setMinimumFrequency(1);
        controller.setMaximumFrequency(1_000_000_000L);
        controller.setFrequency(100_000_000L);
        AtomicInteger frequencyChangeEvents = new AtomicInteger();
        controller.addSourceEventProcessor(event -> frequencyChangeEvents.incrementAndGet());

        tunable.setFailTuning(true);

        assertThrows(SourceException.class, () -> controller.setFrequency(200_000_000L));
        assertEquals(100_000_000L, controller.getFrequency());
        assertEquals(100_000_000L, controller.getTunedFrequency());
        assertEquals(100_000_000L, tunable.getTunedFrequency());
        assertEquals(0, frequencyChangeEvents.get());
    }

    private static class TestTunable implements FrequencyController.Tunable
    {
        private final ReentrantLock mLock = new ReentrantLock();
        private long mTunedFrequency;
        private boolean mFailTuning;

        @Override
        public ReentrantLock getLock()
        {
            return mLock;
        }

        @Override
        public long getTunedFrequency()
        {
            return mTunedFrequency;
        }

        @Override
        public void setTunedFrequency(long frequency) throws SourceException
        {
            if(mFailTuning)
            {
                throw new SourceException("Simulated hardware tune failure");
            }

            mTunedFrequency = frequency;
        }

        @Override
        public double getCurrentSampleRate()
        {
            return 2_400_000.0;
        }

        @Override
        public boolean canTune(long frequency)
        {
            return true;
        }

        private void setFailTuning(boolean failTuning)
        {
            mFailTuning = failTuning;
        }
    }
}
