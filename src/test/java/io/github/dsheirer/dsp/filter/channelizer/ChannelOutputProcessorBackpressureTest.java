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

import io.github.dsheirer.dsp.filter.channelizer.output.ChannelOutputProcessor;
import io.github.dsheirer.source.heartbeat.HeartbeatManager;
import io.github.dsheirer.util.Dispatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelOutputProcessorBackpressureTest
{
    @Test
    void protectedOutputQueueReleasesOverflowAndStoppedBuffers() throws Exception
    {
        BlockingOutputProcessor processor = new BlockingOutputProcessor();
        int capacity = ReceiverQueueProfile.getActive().getChannelOutputQueueCapacity();
        assertEquals(ReceiverQueueProfile.PROTECTED, ReceiverQueueProfile.getActive());
        int overflow = 4;
        int queuedAfterFirst = capacity + overflow;
        int total = queuedAfterFirst + 1;
        AtomicInteger recycled = new AtomicInteger();
        CountDownLatch allRecycled = new CountDownLatch(total);

        try
        {
            processor.start();
            processor.receiveChannelResults(buffer(recycled, allRecycled));
            assertTrue(processor.mProcessingStarted.await(5, TimeUnit.SECONDS));

            for(int x = 0; x < queuedAfterFirst; x++)
            {
                processor.receiveChannelResults(buffer(recycled, allRecycled));
            }

            Dispatcher.Metrics blocked = processor.getQueueMetrics();
            assertEquals(capacity, blocked.maximumQueueSize());
            assertEquals(capacity, blocked.waitingCount());
            assertEquals(1, blocked.inFlightCount());
            assertEquals(overflow, blocked.droppedCount());
            assertEquals(overflow, recycled.get());

            processor.stop();
            assertEquals(queuedAfterFirst, recycled.get());
            processor.mReleaseProcessing.countDown();
            assertTrue(allRecycled.await(5, TimeUnit.SECONDS));
            assertEquals(total, recycled.get());
        }
        finally
        {
            processor.mReleaseProcessing.countDown();
            processor.stop();
        }
    }

    @Test
    void twoConsumersReleaseEachSharedBatchExactlyOnceDuringConcurrentOverflowAndStop() throws Exception
    {
        BlockingOutputProcessor first = new BlockingOutputProcessor();
        BlockingOutputProcessor second = new BlockingOutputProcessor();
        int capacity = ReceiverQueueProfile.getActive().getChannelOutputQueueCapacity();
        int overflow = 4;
        int queuedAfterFirst = capacity + overflow;
        int total = queuedAfterFirst + 1;
        List<AtomicInteger> recycleCounts = new ArrayList<>();
        CountDownLatch allRecycled = new CountDownLatch(total);

        try
        {
            first.start();
            second.start();

            ComplexPolyphaseChannelizerM2.ChannelResultsBuffer active =
                sharedBuffer(recycleCounts, allRecycled, 2);
            first.receiveChannelResults(active);
            second.receiveChannelResults(active);
            assertTrue(first.mProcessingStarted.await(5, TimeUnit.SECONDS));
            assertTrue(second.mProcessingStarted.await(5, TimeUnit.SECONDS));

            for(int x = 0; x < queuedAfterFirst; x++)
            {
                ComplexPolyphaseChannelizerM2.ChannelResultsBuffer shared =
                    sharedBuffer(recycleCounts, allRecycled, 2);
                first.receiveChannelResults(shared);
                second.receiveChannelResults(shared);
            }

            assertEquals(overflow, first.getQueueMetrics().droppedCount());
            assertEquals(overflow, second.getQueueMetrics().droppedCount());

            Thread firstStop = new Thread(first::stop, "first channel output stop test");
            Thread secondStop = new Thread(second::stop, "second channel output stop test");
            firstStop.start();
            secondStop.start();
            firstStop.join(TimeUnit.SECONDS.toMillis(5));
            secondStop.join(TimeUnit.SECONDS.toMillis(5));
            assertTrue(!firstStop.isAlive() && !secondStop.isAlive());

            //Every queued shared batch now has one release from each consumer.  The active batch remains owned once by
            //each blocked callback until both callbacks are allowed to finish.
            assertEquals(queuedAfterFirst, recycleCounts.stream().filter(count -> count.get() == 1).count());
            first.mReleaseProcessing.countDown();
            second.mReleaseProcessing.countDown();
            assertTrue(allRecycled.await(5, TimeUnit.SECONDS));

            assertEquals(total, recycleCounts.size());

            for(AtomicInteger recycleCount: recycleCounts)
            {
                assertEquals(1, recycleCount.get());
            }
        }
        finally
        {
            first.mReleaseProcessing.countDown();
            second.mReleaseProcessing.countDown();
            first.stop();
            second.stop();
        }
    }

    private static ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer(AtomicInteger recycled,
                                                                               CountDownLatch allRecycled)
    {
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer =
            new ComplexPolyphaseChannelizerM2.ChannelResultsBuffer(1, ignored -> {
                recycled.incrementAndGet();
                allRecycled.countDown();
            });
        buffer.add(new float[2]);
        buffer.prepareForConsumers(1);
        return buffer;
    }

    private static ComplexPolyphaseChannelizerM2.ChannelResultsBuffer sharedBuffer(List<AtomicInteger> recycleCounts,
                                                                                    CountDownLatch allRecycled,
                                                                                    int consumers)
    {
        AtomicInteger recycleCount = new AtomicInteger();
        recycleCounts.add(recycleCount);
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer =
            new ComplexPolyphaseChannelizerM2.ChannelResultsBuffer(1, ignored -> {
                recycleCount.incrementAndGet();
                allRecycled.countDown();
            });
        buffer.add(new float[2]);
        buffer.prepareForConsumers(consumers);
        return buffer;
    }

    private static class BlockingOutputProcessor extends ChannelOutputProcessor
    {
        private final CountDownLatch mProcessingStarted = new CountDownLatch(1);
        private final CountDownLatch mReleaseProcessing = new CountDownLatch(1);

        private BlockingOutputProcessor()
        {
            super(1, new HeartbeatManager(), "channel output backpressure test");
        }

        @Override
        public void process(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer channelResultsBuffer)
        {
            mProcessingStarted.countDown();

            try
            {
                mReleaseProcessing.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public String getStateDescription()
        {
            return "test";
        }

        @Override
        public void setFrequencyOffset(long frequency)
        {
        }

        @Override
        public void setPolyphaseChannelIndices(List<Integer> indexes)
        {
        }

        @Override
        public void setSynthesisFilter(float[] filter)
        {
        }
    }
}
