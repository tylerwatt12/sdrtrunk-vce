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
package io.github.dsheirer.dsp.filter.channelizer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for receiver-independent native IQ queueing.
 */
public class NativeBufferProcessorTest
{
    private static final long MAXIMUM_QUEUE_DURATION_MILLISECONDS = 100;

    @Test
    public void receiverBufferSizesRetainTheSameTimeWindow() throws Exception
    {
        List<ReceiverProfile> profiles = List.of(
            new ReceiverProfile("SDRplay", 8_000_000, 2_048),
            new ReceiverProfile("RTL-SDR", 2_400_000, 32_768),
            new ReceiverProfile("Airspy", 10_000_000, 65_536),
            new ReceiverProfile("HackRF", 5_000_000, 131_072));

        for(ReceiverProfile profile: profiles)
        {
            verifyTimeBoundedQueue(profile);
        }
    }

    private void verifyTimeBoundedQueue(ReceiverProfile profile) throws Exception
    {
        List<Integer> received = new CopyOnWriteArrayList<>();
        CountDownLatch firstBufferStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstBuffer = new CountDownLatch(1);
        int maximumQueuedBuffers = Math.max(1,
            (int)((profile.sampleRate() * MAXIMUM_QUEUE_DURATION_MILLISECONDS / 1000) / profile.bufferSamples()));
        CountDownLatch retainedBuffersProcessed = new CountDownLatch(maximumQueuedBuffers);
        NativeBufferProcessor processor = new NativeBufferProcessor(profile.name(), profile.sampleRate(),
            MAXIMUM_QUEUE_DURATION_MILLISECONDS, buffer -> {
                TestNativeBuffer testBuffer = (TestNativeBuffer)buffer;
                received.add(testBuffer.id());

                if(testBuffer.id() == -1)
                {
                    firstBufferStarted.countDown();

                    try
                    {
                        releaseFirstBuffer.await(5, TimeUnit.SECONDS);
                    }
                    catch(InterruptedException ie)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
                else
                {
                    retainedBuffersProcessed.countDown();
                }
            });

        try
        {
            processor.start();
            processor.receive(new TestNativeBuffer(-1, profile.bufferSamples()));
            assertTrue(firstBufferStarted.await(2, TimeUnit.SECONDS), profile.name() + " did not process on arrival");

            int incomingBufferCount = maximumQueuedBuffers + 3;

            for(int id = 0; id < incomingBufferCount; id++)
            {
                processor.receive(new TestNativeBuffer(id, profile.bufferSamples()));
            }

            assertEquals(maximumQueuedBuffers, processor.getQueueSize(), profile.name());
            assertTrue(processor.getQueuedSampleCount() <= processor.getMaximumQueuedSampleCount(), profile.name());
            assertEquals(3, processor.getDroppedBufferCount(), profile.name());
            assertEquals(3L * profile.bufferSamples(), processor.getDroppedSampleCount(), profile.name());
            assertEquals(Math.round(3L * profile.bufferSamples() * 1000.0 / profile.sampleRate()),
                processor.status().droppedMilliseconds(), profile.name());
            assertTrue(processor.status().highWaterSamples() >= processor.status().queuedSamples(), profile.name());
            assertTrue(processor.status().highWaterMilliseconds() > 0, profile.name());

            releaseFirstBuffer.countDown();
            assertTrue(retainedBuffersProcessed.await(5, TimeUnit.SECONDS), profile.name());

            List<Integer> expected = new ArrayList<>();
            expected.add(-1);

            for(int id = 3; id < incomingBufferCount; id++)
            {
                expected.add(id);
            }

            assertEquals(expected, received, profile.name() + " must discard the oldest queued IQ");
        }
        finally
        {
            releaseFirstBuffer.countDown();
            processor.dispose();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS), profile.name());
        }
    }

    @Test
    public void overflowUpdatesHealthCountersWithoutLoggingOnTheProducer() throws Exception
    {
        String processorName = "producer-thread overflow regression";
        CountDownLatch firstBufferStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstBuffer = new CountDownLatch(1);
        Logger logger = (Logger)LoggerFactory.getLogger(NativeBufferProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        NativeBufferProcessor processor = new NativeBufferProcessor(processorName, 1_000_000,
            MAXIMUM_QUEUE_DURATION_MILLISECONDS, buffer -> {
                if(((TestNativeBuffer)buffer).id() == -1)
                {
                    firstBufferStarted.countDown();

                    try
                    {
                        releaseFirstBuffer.await(5, TimeUnit.SECONDS);
                    }
                    catch(InterruptedException interruptedException)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            });

        try
        {
            processor.start();
            processor.receive(new TestNativeBuffer(-1, 1));
            assertTrue(firstBufferStarted.await(2, TimeUnit.SECONDS));
            processor.receive(new TestNativeBuffer(0, 60_000));
            processor.receive(new TestNativeBuffer(1, 60_000));

            NativeBufferProcessor.QueueStatus status = processor.status();
            assertEquals(1, status.droppedBuffers());
            assertEquals(60_000, status.droppedSamples());
            assertEquals(60, status.droppedMilliseconds());
            assertTrue(appender.list.stream().noneMatch(event ->
                event.getFormattedMessage().contains(processorName) &&
                    event.getFormattedMessage().contains("discarded")));
        }
        finally
        {
            releaseFirstBuffer.countDown();
            processor.dispose();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    public void normalTrafficIsProcessedImmediatelyWithoutDrops() throws Exception
    {
        CountDownLatch processed = new CountDownLatch(100);
        NativeBufferProcessor processor = new NativeBufferProcessor("normal traffic", 8_000_000,
            MAXIMUM_QUEUE_DURATION_MILLISECONDS, buffer -> processed.countDown());

        try
        {
            processor.start();

            for(int id = 0; id < 100; id++)
            {
                processor.receive(new TestNativeBuffer(id, 2_048));
            }

            assertTrue(processed.await(2, TimeUnit.SECONDS));
            assertEquals(0, processor.getDroppedBufferCount());
            assertEquals(0, processor.getDroppedSampleCount());
        }
        finally
        {
            processor.dispose();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void processingWorkerRunsAboveNormalPriority() throws Exception
    {
        CountDownLatch processed = new CountDownLatch(1);
        AtomicInteger callbackPriority = new AtomicInteger();
        NativeBufferProcessor processor = new NativeBufferProcessor("native buffer priority", 10_000_000,
            buffer -> {
                callbackPriority.set(Thread.currentThread().getPriority());
                processed.countDown();
            });

        try
        {
            processor.start();
            processor.receive(new TestNativeBuffer(1, 65_536));
            assertTrue(processed.await(2, TimeUnit.SECONDS));
            assertEquals(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2), callbackPriority.get());
        }
        finally
        {
            processor.dispose();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void sampleRateChangeRecalculatesAndTrimsTheQueue() throws Exception
    {
        CountDownLatch firstBufferStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstBuffer = new CountDownLatch(1);
        NativeBufferProcessor processor = new NativeBufferProcessor("sample rate change", 2_400_000,
            MAXIMUM_QUEUE_DURATION_MILLISECONDS, buffer -> {
                if(((TestNativeBuffer)buffer).id() == -1)
                {
                    firstBufferStarted.countDown();

                    try
                    {
                        releaseFirstBuffer.await(5, TimeUnit.SECONDS);
                    }
                    catch(InterruptedException ie)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            });

        try
        {
            processor.start();
            processor.receive(new TestNativeBuffer(-1, 32_768));
            assertTrue(firstBufferStarted.await(2, TimeUnit.SECONDS));

            for(int id = 0; id < 7; id++)
            {
                processor.receive(new TestNativeBuffer(id, 32_768));
            }

            assertEquals(7, processor.getQueueSize());
            processor.setSampleRate(1_000_000);
            assertEquals(100_000, processor.getMaximumQueuedSampleCount());
            assertEquals(3, processor.getQueueSize());
            assertEquals(4, processor.getDroppedBufferCount());
            assertTrue(processor.getQueuedSampleCount() <= processor.getMaximumQueuedSampleCount());
        }
        finally
        {
            releaseFirstBuffer.countDown();
            processor.dispose();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void tunerCanConfigureSampleRateAfterConstruction() throws Exception
    {
        CountDownLatch processed = new CountDownLatch(1);
        NativeBufferProcessor processor = new NativeBufferProcessor("deferred sample rate", 0,
            MAXIMUM_QUEUE_DURATION_MILLISECONDS, buffer -> processed.countDown());

        try
        {
            processor.start();
            processor.receive(new TestNativeBuffer(1, 2_048));
            assertEquals(0, processor.getQueueSize());
            processor.setSampleRate(2_400_000);
            processor.receive(new TestNativeBuffer(2, 2_048));
            assertTrue(processed.await(2, TimeUnit.SECONDS));
        }
        finally
        {
            processor.dispose();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void invalidInitialSampleRatesAreRejected()
    {
        assertThrows(IllegalArgumentException.class,
            () -> new NativeBufferProcessor("negative sample rate", -1, buffer -> {}));
        assertThrows(IllegalArgumentException.class,
            () -> new NativeBufferProcessor("non-finite sample rate", Double.NaN, buffer -> {}));
    }

    @Test
    public void defaultQueueDurationIsFourHundredMilliseconds()
    {
        NativeBufferProcessor processor = new NativeBufferProcessor("default queue duration", 1_000_000,
            buffer -> {});

        try
        {
            assertEquals(400, processor.status().appliedDurationMilliseconds());
            assertEquals(400, processor.status().requestedDurationMilliseconds());
            assertEquals(400_000, processor.getMaximumQueuedSampleCount());
        }
        finally
        {
            processor.dispose();
        }
    }

    @Test
    public void queueDurationRequestIsNonBlockingAndAppliedByTheReceiver() throws Exception
    {
        CountDownLatch processed = new CountDownLatch(1);
        NativeBufferProcessor processor = new NativeBufferProcessor("queue duration", 1_000_000,
            MAXIMUM_QUEUE_DURATION_MILLISECONDS, buffer -> processed.countDown());

        try
        {
            processor.start();
            assertEquals(100, processor.status().appliedDurationMilliseconds());
            processor.requestMaximumQueueDurationMilliseconds(400);
            assertEquals(100, processor.status().appliedDurationMilliseconds());
            assertEquals(400, processor.status().requestedDurationMilliseconds());
            processor.receive(new TestNativeBuffer(1, 1_024));
            assertTrue(processed.await(2, TimeUnit.SECONDS));
            assertEquals(400, processor.status().appliedDurationMilliseconds());
            assertEquals(400_000, processor.getMaximumQueuedSampleCount());
            assertThrows(IllegalArgumentException.class,
                () -> processor.requestMaximumQueueDurationMilliseconds(401));
        }
        finally
        {
            processor.dispose();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void retainsOneCompleteBufferWhenItExceedsTheTimeLimit() throws Exception
    {
        CountDownLatch firstBufferStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstBuffer = new CountDownLatch(1);
        List<Integer> received = new CopyOnWriteArrayList<>();
        CountDownLatch oversizedBufferProcessed = new CountDownLatch(1);
        NativeBufferProcessor processor = new NativeBufferProcessor("oversized buffer", 1_000_000,
            MAXIMUM_QUEUE_DURATION_MILLISECONDS, buffer -> {
                TestNativeBuffer testBuffer = (TestNativeBuffer)buffer;
                received.add(testBuffer.id());

                if(testBuffer.id() == -1)
                {
                    firstBufferStarted.countDown();

                    try
                    {
                        releaseFirstBuffer.await(5, TimeUnit.SECONDS);
                    }
                    catch(InterruptedException ie)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
                else
                {
                    oversizedBufferProcessed.countDown();
                }
            });

        try
        {
            processor.start();
            processor.receive(new TestNativeBuffer(-1, 1));
            assertTrue(firstBufferStarted.await(2, TimeUnit.SECONDS));
            processor.receive(new TestNativeBuffer(0, 60_000));
            processor.receive(new TestNativeBuffer(1, 60_000));
            processor.receive(new TestNativeBuffer(2, 150_000));

            assertEquals(1, processor.getQueueSize());
            assertEquals(150_000, processor.getQueuedSampleCount());
            assertEquals(2, processor.getDroppedBufferCount());

            releaseFirstBuffer.countDown();
            assertTrue(oversizedBufferProcessed.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(-1, 2), received);
        }
        finally
        {
            releaseFirstBuffer.countDown();
            processor.dispose();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void startsANewLatencyHighWaterEpochWhenSampleRateChanges() throws Exception
    {
        CountDownLatch firstBufferStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstBuffer = new CountDownLatch(1);
        CountDownLatch queuedBufferProcessed = new CountDownLatch(1);
        NativeBufferProcessor processor = new NativeBufferProcessor("sample-rate high water", 10_000_000,
            MAXIMUM_QUEUE_DURATION_MILLISECONDS, buffer ->
            {
                if(((TestNativeBuffer)buffer).id() == -1)
                {
                    firstBufferStarted.countDown();

                    try
                    {
                        releaseFirstBuffer.await(5, TimeUnit.SECONDS);
                    }
                    catch(InterruptedException interruptedException)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
                else
                {
                    queuedBufferProcessed.countDown();
                }
            });

        try
        {
            processor.start();
            processor.receive(new TestNativeBuffer(-1, 1));
            assertTrue(firstBufferStarted.await(2, TimeUnit.SECONDS));
            processor.receive(new TestNativeBuffer(1, 500_000));
            assertEquals(50, processor.status().highWaterMilliseconds());
            releaseFirstBuffer.countDown();
            assertTrue(queuedBufferProcessed.await(2, TimeUnit.SECONDS));
            processor.setSampleRate(2_400_000);
            assertEquals(0, processor.status().highWaterSamples());
            assertEquals(0, processor.status().highWaterMilliseconds());
        }
        finally
        {
            releaseFirstBuffer.countDown();
            processor.dispose();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void stopRestartAndDisposeHaveCleanLifecycle() throws Exception
    {
        List<Integer> received = new CopyOnWriteArrayList<>();
        CountDownLatch firstProcessed = new CountDownLatch(1);
        CountDownLatch secondProcessed = new CountDownLatch(1);
        NativeBufferProcessor processor = new NativeBufferProcessor("lifecycle", 2_400_000,
            MAXIMUM_QUEUE_DURATION_MILLISECONDS, buffer -> {
                int id = ((TestNativeBuffer)buffer).id();
                received.add(id);

                if(id == 1)
                {
                    firstProcessed.countDown();
                }
                else if(id == 2)
                {
                    secondProcessed.countDown();
                }
            });

        try
        {
            processor.start();
            processor.receive(new TestNativeBuffer(1, 32_768));
            assertTrue(firstProcessed.await(2, TimeUnit.SECONDS));
            processor.stop();
            processor.receive(new TestNativeBuffer(99, 32_768));
            processor.start();
            processor.receive(new TestNativeBuffer(2, 32_768));
            assertTrue(secondProcessed.await(2, TimeUnit.SECONDS));
            processor.dispose();
            processor.receive(new TestNativeBuffer(100, 32_768));

            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), received);
            assertThrows(IllegalStateException.class, processor::start);
        }
        finally
        {
            processor.dispose();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private record ReceiverProfile(String name, int sampleRate, int bufferSamples)
    {
    }

    private record TestNativeBuffer(int id, int sampleCount) implements INativeBuffer
    {
        @Override
        public Iterator<ComplexSamples> iterator()
        {
            return Collections.emptyIterator();
        }

        @Override
        public Iterator<InterleavedComplexSamples> iteratorInterleaved()
        {
            return Collections.emptyIterator();
        }

        @Override
        public long getTimestamp()
        {
            return id;
        }
    }
}
