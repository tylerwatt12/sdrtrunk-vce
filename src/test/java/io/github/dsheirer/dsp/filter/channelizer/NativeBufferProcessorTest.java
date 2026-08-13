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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    public void unboundedProfileRetainsAllQueuedIqAndReportsMetrics() throws Exception
    {
        CountDownLatch firstBufferStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstBuffer = new CountDownLatch(1);
        CountDownLatch allBuffersProcessed = new CountDownLatch(13);
        NativeBufferProcessor processor = new NativeBufferProcessor("retain all", 1_000_000, 0, buffer -> {
            int id = ((TestNativeBuffer)buffer).id();

            if(id == -1)
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

            allBuffersProcessed.countDown();
        });

        try
        {
            processor.start();
            processor.receive(new TestNativeBuffer(-1, 10_000));
            assertTrue(firstBufferStarted.await(2, TimeUnit.SECONDS));

            for(int id = 0; id < 12; id++)
            {
                processor.receive(new TestNativeBuffer(id, 10_000));
            }

            ReceiverQueueMetricsSnapshot.NativeBufferMetrics metrics = processor.getQueueMetrics();
            assertTrue(metrics.unbounded());
            assertEquals(12, metrics.waitingBuffers());
            assertEquals(120_000, metrics.waitingSamples());
            assertEquals(120, metrics.waitingMilliseconds());
            assertEquals(1, metrics.inFlightBuffers());
            assertEquals(10_000, metrics.inFlightSamples());
            assertEquals(13, metrics.receivedBuffers());
            assertEquals(0, metrics.droppedBuffers());
            assertTrue(metrics.activeSinceNanos() > 0);
            assertTrue(metrics.running());

            releaseFirstBuffer.countDown();
            assertTrue(allBuffersProcessed.await(5, TimeUnit.SECONDS));
            metrics = awaitProcessedBuffers(processor, 13);
            assertEquals(0, metrics.waitingBuffers());
            assertEquals(0, metrics.inFlightBuffers());
            assertEquals(13, metrics.processedBuffers());
            assertTrue(metrics.lastCompletionNanos() > 0);
        }
        finally
        {
            releaseFirstBuffer.countDown();
            processor.dispose();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void metricsSnapshotDoesNotWaitForBlockedConsumer() throws Exception
    {
        CountDownLatch consumerStarted = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        NativeBufferProcessor processor = new NativeBufferProcessor("nonblocking metrics", 1_000_000,
            MAXIMUM_QUEUE_DURATION_MILLISECONDS, buffer -> {
                consumerStarted.countDown();

                try
                {
                    releaseConsumer.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                }
            });

        try
        {
            processor.start();
            processor.receive(new TestNativeBuffer(1, 10_000));
            assertTrue(consumerStarted.await(2, TimeUnit.SECONDS));
            processor.receive(new TestNativeBuffer(2, 10_000));

            CountDownLatch snapshotsComplete = new CountDownLatch(1);
            Thread snapshotReader = new Thread(() -> {
                for(int x = 0; x < 10_000; x++)
                {
                    processor.getQueueMetrics();
                }

                snapshotsComplete.countDown();
            });
            snapshotReader.start();

            long ingressStarted = System.nanoTime();

            for(int x = 0; x < 100; x++)
            {
                processor.receive(new TestNativeBuffer(10 + x, 1_000));
            }

            assertTrue(System.nanoTime() - ingressStarted < TimeUnit.MILLISECONDS.toNanos(500),
                "Producer handoff waited for a blocked consumer or metrics reader");
            assertTrue(snapshotsComplete.await(500, TimeUnit.MILLISECONDS),
                "Metrics snapshots did not remain constant-time");
            ReceiverQueueMetricsSnapshot.NativeBufferMetrics metrics = processor.getQueueMetrics();
            assertTrue(metrics.waitingBuffers() > 0);
            assertEquals(1, metrics.inFlightBuffers());
            assertFalse(metrics.unbounded());
        }
        finally
        {
            releaseConsumer.countDown();
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

    @Test
    public void stopRestartCleansOldGenerationWithoutMixingItWithTheNewRun() throws Exception
    {
        List<Integer> received = new CopyOnWriteArrayList<>();
        CountDownLatch oldCallbackStarted = new CountDownLatch(1);
        CountDownLatch releaseOldCallback = new CountDownLatch(1);
        CountDownLatch newBufferProcessed = new CountDownLatch(1);
        NativeBufferProcessor processor = new NativeBufferProcessor("generation lifecycle", 1_000_000,
            MAXIMUM_QUEUE_DURATION_MILLISECONDS, buffer -> {
                int id = ((TestNativeBuffer)buffer).id();
                received.add(id);

                if(id == 0)
                {
                    oldCallbackStarted.countDown();

                    try
                    {
                        releaseOldCallback.await(5, TimeUnit.SECONDS);
                    }
                    catch(InterruptedException exception)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
                else if(id == 3)
                {
                    newBufferProcessed.countDown();
                }
            });

        try
        {
            processor.start();
            processor.receive(new TestNativeBuffer(0, 10_000));
            assertTrue(oldCallbackStarted.await(2, TimeUnit.SECONDS));
            processor.receive(new TestNativeBuffer(1, 10_000));
            processor.receive(new TestNativeBuffer(2, 10_000));
            processor.stop();
            processor.start();
            processor.receive(new TestNativeBuffer(3, 10_000));
            releaseOldCallback.countDown();
            assertTrue(newBufferProcessed.await(2, TimeUnit.SECONDS));

            ReceiverQueueMetricsSnapshot.NativeBufferMetrics metrics = processor.getQueueMetrics();
            assertEquals(List.of(0, 3), received);
            assertEquals(2, metrics.cleanupBuffers());
            assertEquals(20_000, metrics.cleanupSamples());
            assertEquals(20, metrics.cleanupMilliseconds());
        }
        finally
        {
            releaseOldCallback.countDown();
            processor.dispose();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private record ReceiverProfile(String name, int sampleRate, int bufferSamples)
    {
    }

    private static ReceiverQueueMetricsSnapshot.NativeBufferMetrics awaitProcessedBuffers(
        NativeBufferProcessor processor, long expected) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        ReceiverQueueMetricsSnapshot.NativeBufferMetrics metrics = processor.getQueueMetrics();

        while(metrics.processedBuffers() < expected && System.nanoTime() < deadline)
        {
            Thread.sleep(1);
            metrics = processor.getQueueMetrics();
        }

        return metrics;
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
