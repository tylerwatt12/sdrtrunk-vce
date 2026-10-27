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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.DecodeEventSource;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationMonitorPauseRequest;
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationMonitorResumeRequest;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationContext;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    void receiverAssignmentDoesNotWaitForReplacementAndPairsItsIdentityWithTheActualSourceFrequency() throws Exception
    {
        CountDownLatch startupEntered = new CountDownLatch(1);
        CountDownLatch releaseStartup = new CountDownLatch(1);
        TestTunerChannelSource first = new TestTunerChannelSource(FIRST_FREQUENCY);
        first.setTunerIdentity("receiver-a");
        TestTunerChannelSource replacement = new TestTunerChannelSource(SECOND_FREQUENCY)
        {
            @Override public void start()
            {
                startupEntered.countDown();
                try { assertTrue(releaseStartup.await(2, TimeUnit.SECONDS)); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                super.start();
            }
        };
        replacement.setTunerIdentity("receiver-b");
        MultiFrequencyTunerChannelSource source = new MultiFrequencyTunerChannelSource(
            new TestTunerManager(null, null, replacement), first, FREQUENCIES, CHANNEL_SPECIFICATION,
            null, "receiver assignment test", null, null);
        source.setSourceEventListener(event -> {});
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread rotation = new Thread(() -> {
            try { source.process(SourceEvent.frequencyRotationRequest()); }
            catch(Throwable exception) { failure.set(exception); }
        }, "receiver-assignment-rotation");
        try
        {
            assertNull(source.getReceiverAssignment(), "a source not started yet has no receiving assignment");
            source.start();
            assertEquals(new TunerChannelSource.ReceiverAssignment("receiver-a", FIRST_FREQUENCY),
                source.getReceiverAssignment());
            rotation.start();
            assertTrue(startupEntered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            assertNull(source.getReceiverAssignment(), "published replacement must remain unassigned until startup completes");
            assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(250),
                "receiver observation must not wait for a blocked rotation/startup");
            releaseStartup.countDown();
            rotation.join(2_000);
            assertFalse(rotation.isAlive());
            assertNull(failure.get());
            assertEquals(new TunerChannelSource.ReceiverAssignment("receiver-b", SECOND_FREQUENCY),
                source.getReceiverAssignment());
        }
        finally
        {
            releaseStartup.countDown();
            rotation.join(2_000);
            source.stop();
        }
        assertNull(source.getReceiverAssignment(), "a stopped wrapper must not retain its last receiver");
    }

    @Test
    void runningRotationSeesCommittedLearnedListReplacementsWithoutAChannelRestart() throws Exception
    {
        SourceConfigTunerMultipleFrequency configuration = new SourceConfigTunerMultipleFrequency();
        configuration.setFrequencies(List.of(FIRST_FREQUENCY));
        MultiFrequencyTunerChannelSource source = new MultiFrequencyTunerChannelSource(null,
            new TestTunerChannelSource(FIRST_FREQUENCY), configuration::getFrequencies,
            CHANNEL_SPECIFICATION, null, "live learned snapshot", null, null);
        source.setSourceEventListener(event -> {});
        try
        {
            source.start();
            configuration.addFrequency(SECOND_FREQUENCY);
            assertEquals(SECOND_FREQUENCY, getNextFrequency(source));
            configuration.setFrequencies(List.of(FIRST_FREQUENCY, SECOND_FREQUENCY, THIRD_FREQUENCY));
            assertEquals(THIRD_FREQUENCY, getNextFrequency(source));
            configuration.setFrequencies(List.of(FIRST_FREQUENCY));
            assertEquals(0, getNextFrequency(source));
        }
        finally { source.stop(); }
    }

    @Test
    void eachRotationAndCoverageDecisionReadsOneImmutableFrequencySnapshot() throws Exception
    {
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<List<Long>> current = new AtomicReference<>(FREQUENCIES);
        MultiFrequencyTunerChannelSource source = new MultiFrequencyTunerChannelSource(null,
            new TestTunerChannelSource(FIRST_FREQUENCY), () -> {
                reads.incrementAndGet();
                return current.getAndSet(List.of());
            }, CHANNEL_SPECIFICATION, null, "snapshot consistency", null, null);
        assertEquals(1, reads.get());
        current.set(FREQUENCIES);
        assertEquals(SECOND_FREQUENCY, getNextFrequency(source));
        assertEquals(2, reads.get(), "selection cannot mix sizes or indexes from different publications");
        current.set(FREQUENCIES);
        Method coverage = MultiFrequencyTunerChannelSource.class.getDeclaredMethod("getAllFrequencyChannels");
        coverage.setAccessible(true);
        @SuppressWarnings("unchecked")
        SortedSet<TunerChannel> channels = (SortedSet<TunerChannel>)coverage.invoke(source);
        assertEquals(FREQUENCIES, channels.stream().map(TunerChannel::getFrequency).toList());
        assertEquals(3, reads.get());
    }

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
    void conventionalRotationPublishesTheFrequencyAndNewOriginBeforeSamplesAndFencesReturnToA() throws Exception
    {
        TestTunerManager tunerManager = new TestTunerManager(null, null, null)
        {
            @Override
            public Source getSource(TunerChannel tunerChannel, ChannelSpecification specification,
                                    String preferredTuner, String threadName, SortedSet<TunerChannel> tunerChannels,
                                    TunerAllocationContext allocationContext)
            {
                return new TestTunerChannelSource(tunerChannel.getFrequency());
            }
        };
        MultiFrequencyTunerChannelSource source = new MultiFrequencyTunerChannelSource(tunerManager,
            new TestTunerChannelSource(FIRST_FREQUENCY), List.of(FIRST_FREQUENCY, SECOND_FREQUENCY),
            CHANNEL_SPECIFICATION, null, "conventional rotation origin test", null, null);
        Channel channel = new Channel("Conventional DMR", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigDMR());
        ProcessingChain chain = new ProcessingChain(channel, new AliasModel());
        List<DecodeEventSource> startupOrigins = new ArrayList<>();
        List<Long> startupFrequencies = new ArrayList<>();

        try
        {
            chain.setSource(source);
            chain.addSourceEventListener(event ->
            {
                if(event.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE)
                {
                    assertEquals(event.getValue().longValue(), source.getFrequency(),
                        "wrapper frequency must be coherent during the startup notification");
                    startupOrigins.add(chain.getDecodeEventSource());
                    startupFrequencies.add(source.getFrequency());
                }
            });
            source.start();
            DecodeEventSource firstA = chain.getDecodeEventSource();
            source.process(SourceEvent.frequencyRotationRequest());
            DecodeEventSource b = chain.getDecodeEventSource();
            source.process(SourceEvent.frequencyRotationRequest());
            DecodeEventSource returnedA = chain.getDecodeEventSource();

            assertEquals(List.of(FIRST_FREQUENCY, SECOND_FREQUENCY, FIRST_FREQUENCY), startupFrequencies);
            assertEquals(List.of(firstA, b, returnedA), startupOrigins);
            assertNotSame(firstA, b);
            assertNotSame(firstA, returnedA, "an old A observation must retain a stale origin after returning to A");
            assertSame(channel, returnedA.channel());
            assertSame(source, returnedA.source());
        }
        finally
        {
            chain.dispose();
        }
    }

    @Test
    void failedReplacementStartupRestoresThePreviousWrapperDescriptor() throws Exception
    {
        TestTunerChannelSource failing = new TestTunerChannelSource(SECOND_FREQUENCY)
        {
            @Override
            public void start()
            {
                super.start();
                throw new IllegalStateException("Injected replacement startup failure");
            }
        };
        failing.setTunerIdentity("failed-receiver");
        MultiFrequencyTunerChannelSource source = source(FIRST_FREQUENCY,
            new TestTunerManager(null, null, failing));
        source.setSourceEventListener(event -> {});

        try
        {
            source.start();
            assertThrows(IllegalStateException.class, () -> source.process(SourceEvent.frequencyRotationRequest()));
            assertEquals(FIRST_FREQUENCY, source.getFrequency());
            assertNull(source.getReceiverAssignment(), "failed replacement startup cannot claim a receiver");
        }
        finally
        {
            source.stop();
        }
    }

    @Test
    void pauseCancelsPersistentSourceRetryAndPreventsItFromSurvivingTheFence() throws Exception
    {
        TestTunerManager tunerManager = new TestTunerManager(null, null, null);
        MultiFrequencyTunerChannelSource source = source(FIRST_FREQUENCY, tunerManager);
        source.setSourceEventListener(event -> {});
        source.start();

        assertEquals(FIRST_FREQUENCY, source.getActiveSourceFrequency());

        source.process(SourceEvent.frequencyRotationRequest());
        assertEquals(1, tunerManager.getRequestCount());
        assertTrue(source.hasPendingRotationRetry());
        assertEquals(FIRST_FREQUENCY, source.getFrequency(), "wrapper retains its previous channel metadata");
        assertEquals(0, source.getActiveSourceFrequency(), "no source is allocated during the retry gap");

        ChannelRotationMonitorPauseRequest pauseRequest = new ChannelRotationMonitorPauseRequest();
        source.pauseRotation(pauseRequest);
        assertFalse(source.hasPendingRotationRetry());
        assertFalse(pauseRequest.isSourceStableAt(SECOND_FREQUENCY),
            "a failed rotation cannot report the disposed underlying source as stable");

        source.runRotationRetryForTest();
        assertEquals(1, tunerManager.getRequestCount(), "the fenced retry requested another tuner source");

        source.resumeRotation(new ChannelRotationMonitorResumeRequest());
        assertTrue(source.hasPendingRotationRetry());
        source.pauseRotation(new ChannelRotationMonitorPauseRequest());
        source.stop();
    }

    @Test
    void pauseWaitsForInFlightSourceReplacementAndReportsTheLiveFrequency() throws Exception
    {
        CountDownLatch sourceRequestEntered = new CountDownLatch(1);
        CountDownLatch releaseSourceRequest = new CountDownLatch(1);
        TestTunerChannelSource replacement = new TestTunerChannelSource(SECOND_FREQUENCY);
        TestTunerManager tunerManager = new TestTunerManager(sourceRequestEntered, releaseSourceRequest, replacement);
        MultiFrequencyTunerChannelSource source = source(FIRST_FREQUENCY, tunerManager);
        source.setSourceEventListener(event -> {});
        source.start();
        Thread rotateThread = new Thread(() -> {
            try
            {
                source.process(SourceEvent.frequencyRotationRequest());
            }
            catch(Exception exception)
            {
                throw new AssertionError(exception);
            }
        }, "multi-frequency-rotate-test");
        rotateThread.start();
        assertTrue(sourceRequestEntered.await(2, TimeUnit.SECONDS));
        assertEquals(0, source.getActiveSourceFrequency(),
            "an administrator snapshot never waits on an in-flight rotation");
        ChannelRotationMonitorPauseRequest pauseRequest = new ChannelRotationMonitorPauseRequest();
        CountDownLatch pauseReturned = new CountDownLatch(1);
        Thread pauseThread = new Thread(() -> {
            source.pauseRotation(pauseRequest);
            pauseReturned.countDown();
        }, "multi-frequency-pause-test");
        pauseThread.start();

        assertFalse(pauseReturned.await(100, TimeUnit.MILLISECONDS),
            "source pause returned while tuner replacement was still in flight");
        releaseSourceRequest.countDown();
        assertTrue(pauseReturned.await(2, TimeUnit.SECONDS));
        rotateThread.join(2_000);
        pauseThread.join(2_000);
        assertTrue(pauseRequest.isSourceStableAt(SECOND_FREQUENCY));
        assertEquals(SECOND_FREQUENCY, source.getActiveSourceFrequency());
        source.stop();
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
        private final CountDownLatch mRequestEntered;
        private final CountDownLatch mReleaseRequest;
        private final TunerChannelSource mSource;
        private final AtomicInteger mRequestCount = new AtomicInteger();

        private TestTunerManager(CountDownLatch requestEntered, CountDownLatch releaseRequest,
                                 TunerChannelSource source)
        {
            super(null);
            mRequestEntered = requestEntered;
            mReleaseRequest = releaseRequest;
            mSource = source;
        }

        @Override
        public Source getSource(TunerChannel tunerChannel, ChannelSpecification channelSpecification,
                                String preferredTuner, String threadName, SortedSet<TunerChannel> tunerChannels,
                                TunerAllocationContext allocationContext)
        {
            mRequestCount.incrementAndGet();

            if(mRequestEntered != null)
            {
                mRequestEntered.countDown();
            }

            if(mReleaseRequest != null)
            {
                try
                {
                    assertTrue(mReleaseRequest.await(2, TimeUnit.SECONDS));
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            return mSource;
        }

        private int getRequestCount()
        {
            return mRequestCount.get();
        }
    }
}
