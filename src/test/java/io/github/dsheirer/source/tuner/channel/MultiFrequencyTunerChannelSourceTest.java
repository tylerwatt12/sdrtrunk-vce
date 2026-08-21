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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationFrequencySelectionRequest;
import io.github.dsheirer.source.tuner.channel.rotation.FrequencyLockChangeRequest;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.lang.reflect.Method;
import java.util.List;
import java.util.SortedSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void targetedSelectionUsesExactConfiguredFrequency() throws Exception
    {
        TestTunerManager tunerManager = new TestTunerManager(1, 0);
        MultiFrequencyTunerChannelSource source = source(FIRST_FREQUENCY, tunerManager);
        source.start();
        source.process(SourceEvent.frequencySelectionRequest(THIRD_FREQUENCY));

        assertTrue(tunerManager.awaitRequests(2, TimeUnit.SECONDS));
        assertEquals(List.of(THIRD_FREQUENCY), tunerManager.getRequestedFrequencies());
        source.stop();
    }

    @Test
    void targetedControlSelectionOverridesFrequencyLock() throws Exception
    {
        TestTunerManager tunerManager = new TestTunerManager(1, 0);
        MultiFrequencyTunerChannelSource source = source(FIRST_FREQUENCY, tunerManager);
        source.process(FrequencyLockChangeRequest.lock(SECOND_FREQUENCY));
        source.start();
        source.process(SourceEvent.frequencySelectionRequest(SECOND_FREQUENCY));

        assertTrue(tunerManager.awaitRequests(2, TimeUnit.SECONDS));
        assertEquals(List.of(SECOND_FREQUENCY), tunerManager.getRequestedFrequencies());
        source.stop();
    }

    @Test
    void failedTargetedSelectionRetriesSameControlFrequency() throws Exception
    {
        TestTunerManager tunerManager = new TestTunerManager(2, 1);
        MultiFrequencyTunerChannelSource source = source(FIRST_FREQUENCY, tunerManager);
        source.start();
        source.process(SourceEvent.frequencySelectionRequest(THIRD_FREQUENCY));

        assertTrue(tunerManager.awaitRequests(3, TimeUnit.SECONDS));
        assertEquals(List.of(THIRD_FREQUENCY, THIRD_FREQUENCY), tunerManager.getRequestedFrequencies());
        assertEquals(FIRST_FREQUENCY, getNextFrequency(source), "target retry left the rotation pointer stale");
        source.stop();
    }

    @Test
    void newerTargetReplacesFailedTargetBeforeRetry() throws Exception
    {
        TestTunerManager tunerManager = new TestTunerManager(2, 1);
        MultiFrequencyTunerChannelSource source = source(FIRST_FREQUENCY, tunerManager);
        source.start();
        source.process(SourceEvent.frequencySelectionRequest(SECOND_FREQUENCY));
        assertTrue(tunerManager.awaitFirstRequest(2, TimeUnit.SECONDS));

        source.process(SourceEvent.frequencySelectionRequest(THIRD_FREQUENCY));

        assertTrue(tunerManager.awaitRequests(3, TimeUnit.SECONDS));
        assertEquals(List.of(SECOND_FREQUENCY, THIRD_FREQUENCY), tunerManager.getRequestedFrequencies());
        source.stop();
    }

    @Test
    void stopBeforeRetryPreventsAnotherSourceAcquisition() throws Exception
    {
        TestTunerManager tunerManager = new TestTunerManager(2, 1);
        MultiFrequencyTunerChannelSource source = source(FIRST_FREQUENCY, tunerManager);
        source.start();
        source.process(SourceEvent.frequencySelectionRequest(SECOND_FREQUENCY));
        assertTrue(tunerManager.awaitFirstRequest(2, TimeUnit.SECONDS));

        source.stop();
        source.retryNextSource();

        assertEquals(List.of(SECOND_FREQUENCY), tunerManager.getRequestedFrequencies());
    }

    @Test
    void candidateStartFailureIsCleanedAndRetriesSameTarget() throws Exception
    {
        TestTunerManager tunerManager = new TestTunerManager(2, 0, 1);
        MultiFrequencyTunerChannelSource source = source(FIRST_FREQUENCY, tunerManager);
        List<SourceEvent.Event> events = new CopyOnWriteArrayList<>();
        source.setSourceEventListener(event -> events.add(event.getEvent()));
        source.start();

        source.process(SourceEvent.frequencySelectionRequest(SECOND_FREQUENCY));

        assertTrue(tunerManager.awaitRequests(3, TimeUnit.SECONDS));
        assertEquals(List.of(SECOND_FREQUENCY, SECOND_FREQUENCY), tunerManager.getRequestedFrequencies());
        ThrowingStartTunerChannelSource failed = tunerManager.getFailedStartSource();
        assertEquals(1, failed.getStopCount());
        assertTrue(failed.isSampleListenerCleared());
        assertTrue(failed.isSourceEventListenerRemoved());
        assertEquals(1, events.stream()
            .filter(event -> event == SourceEvent.Event.NOTIFICATION_STOP_SAMPLE_STREAM).count());
        source.stop();
    }

    @Test
    void sameFrequencyAndUnconfiguredTargetsDoNotReplaceSource() throws Exception
    {
        TestTunerManager tunerManager = new TestTunerManager(1, 0);
        MultiFrequencyTunerChannelSource source = source(FIRST_FREQUENCY, tunerManager);
        source.start();
        source.process(SourceEvent.frequencySelectionRequest(FIRST_FREQUENCY));
        source.process(SourceEvent.frequencySelectionRequest(999_000_000L));

        assertEquals(0, tunerManager.getRequestCount());
        source.stop();
    }

    @Test
    void targetedSelectionRejectsNonPositiveFrequency()
    {
        assertThrows(IllegalArgumentException.class, () -> new ChannelRotationFrequencySelectionRequest(0));
    }

    private static MultiFrequencyTunerChannelSource source(long initialFrequency)
    {
        return source(initialFrequency, null);
    }

    private static MultiFrequencyTunerChannelSource source(long initialFrequency, TunerManager tunerManager)
    {
        return new MultiFrequencyTunerChannelSource(tunerManager, new TestTunerChannelSource(initialFrequency), FREQUENCIES,
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

    private static class TestTunerManager extends TunerManager
    {
        private final CountDownLatch mRequestLatch;
        private final CountDownLatch mFirstRequestLatch = new CountDownLatch(1);
        private final AtomicInteger mFailuresRemaining;
        private final AtomicInteger mStartFailuresRemaining;
        private final List<Long> mRequestedFrequencies = new CopyOnWriteArrayList<>();
        private volatile ThrowingStartTunerChannelSource mFailedStartSource;

        private TestTunerManager(int expectedRequests, int failures)
        {
            this(expectedRequests, failures, 0);
        }

        private TestTunerManager(int expectedRequests, int failures, int startFailures)
        {
            super(null);
            mRequestLatch = new CountDownLatch(expectedRequests);
            mFailuresRemaining = new AtomicInteger(failures);
            mStartFailuresRemaining = new AtomicInteger(startFailures);
        }

        @Override
        public Source getSource(TunerChannel tunerChannel, ChannelSpecification channelSpecification,
                                String preferredTuner, String threadName, SortedSet<TunerChannel> tunerChannels)
        {
            mRequestedFrequencies.add(tunerChannel.getFrequency());
            mFirstRequestLatch.countDown();
            mRequestLatch.countDown();

            if(mFailuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0)
            {
                return null;
            }

            if(mStartFailuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0)
            {
                mFailedStartSource = new ThrowingStartTunerChannelSource(tunerChannel.getFrequency());
                return mFailedStartSource;
            }

            return new TestTunerChannelSource(tunerChannel.getFrequency());
        }

        private boolean awaitRequests(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mRequestLatch.await(timeout, unit);
        }

        private boolean awaitFirstRequest(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mFirstRequestLatch.await(timeout, unit);
        }

        private int getRequestCount()
        {
            return mRequestedFrequencies.size();
        }

        private List<Long> getRequestedFrequencies()
        {
            return List.copyOf(mRequestedFrequencies);
        }

        private ThrowingStartTunerChannelSource getFailedStartSource()
        {
            return mFailedStartSource;
        }
    }

    private static class ThrowingStartTunerChannelSource extends TestTunerChannelSource
    {
        private final AtomicInteger mStopCount = new AtomicInteger();
        private volatile boolean mSampleListenerCleared;
        private volatile boolean mSourceEventListenerRemoved;

        private ThrowingStartTunerChannelSource(long frequency)
        {
            super(frequency);
        }

        @Override
        public void start()
        {
            throw new IllegalStateException("test source start failure");
        }

        @Override
        public void stop()
        {
            mStopCount.incrementAndGet();
        }

        @Override
        public void setListener(Listener<ComplexSamples> listener)
        {
            mSampleListenerCleared = listener == null;
        }

        @Override
        public void removeSourceEventListener()
        {
            mSourceEventListenerRemoved = true;
            super.removeSourceEventListener();
        }

        private int getStopCount()
        {
            return mStopCount.get();
        }

        private boolean isSampleListenerCleared()
        {
            return mSampleListenerCleared;
        }

        private boolean isSourceEventListenerRemoved()
        {
            return mSourceEventListenerRemoved;
        }
    }
}
