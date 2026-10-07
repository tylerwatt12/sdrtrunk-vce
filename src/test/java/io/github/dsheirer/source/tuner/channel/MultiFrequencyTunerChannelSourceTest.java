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
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationMonitorPauseRequest;
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationMonitorResumeRequest;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
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
    void conventionalRotationPublishesTheFrequencyAndNewOriginBeforeSamplesAndFencesReturnToA() throws Exception
    {
        TestTunerManager tunerManager = new TestTunerManager(null, null, null)
        {
            @Override
            public Source getSource(TunerChannel tunerChannel, ChannelSpecification specification,
                                    String preferredTuner, String threadName, SortedSet<TunerChannel> tunerChannels)
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
        MultiFrequencyTunerChannelSource source = source(FIRST_FREQUENCY,
            new TestTunerManager(null, null, failing));
        source.setSourceEventListener(event -> {});

        try
        {
            source.start();
            assertThrows(IllegalStateException.class, () -> source.process(SourceEvent.frequencyRotationRequest()));
            assertEquals(FIRST_FREQUENCY, source.getFrequency());
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
                                String preferredTuner, String threadName, SortedSet<TunerChannel> tunerChannels)
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
