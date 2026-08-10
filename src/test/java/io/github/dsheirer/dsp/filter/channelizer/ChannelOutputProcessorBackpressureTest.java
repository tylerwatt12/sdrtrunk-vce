/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.dsp.filter.channelizer;

import io.github.dsheirer.dsp.filter.channelizer.output.ChannelOutputProcessor;
import io.github.dsheirer.source.heartbeat.HeartbeatManager;
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
    void boundedOutputQueueReleasesOverflowAndStoppedBuffers() throws Exception
    {
        BlockingOutputProcessor processor = new BlockingOutputProcessor();
        int overflow = 4;
        int queued = processor.queueCapacity() + overflow;
        int total = queued + 1;
        AtomicInteger recycled = new AtomicInteger();
        CountDownLatch allRecycled = new CountDownLatch(total);

        try
        {
            processor.start();
            processor.receiveChannelResults(buffer(recycled, allRecycled));
            assertTrue(processor.processingStarted.await(5, TimeUnit.SECONDS));

            for(int x = 0; x < queued; x++)
            {
                processor.receiveChannelResults(buffer(recycled, allRecycled));
            }

            assertEquals(overflow, recycled.get());
            processor.stop();
            assertEquals(queued, recycled.get());
            processor.releaseProcessing.countDown();
            assertTrue(allRecycled.await(5, TimeUnit.SECONDS));
            assertEquals(total, recycled.get());
        }
        finally
        {
            processor.releaseProcessing.countDown();
            processor.stop();
        }
    }

    @Test
    void channelResultArrayPoolRetainsOnlyItsBoundedWorkingSet() throws Exception
    {
        ComplexPolyphaseChannelizerM2 channelizer = new ComplexPolyphaseChannelizerM2(50_000.0, 12);
        int arrayLength = channelizer.getSubChannelCount();

        for(int x = 0; x < ComplexPolyphaseChannelizerM2.CHANNEL_RESULTS_POOL_CAPACITY + 100; x++)
        {
            channelizer.recycleChannelResultsArray(new float[arrayLength]);
        }

        assertEquals(ComplexPolyphaseChannelizerM2.CHANNEL_RESULTS_POOL_CAPACITY,
            channelizer.getChannelResultsPoolSize());

        for(int x = 0; x < ComplexPolyphaseChannelizerM2.CHANNEL_RESULTS_POOL_CAPACITY; x++)
        {
            channelizer.acquireChannelResultsArray();
        }

        assertEquals(0, channelizer.getChannelResultsPoolSize());
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

    private static class BlockingOutputProcessor extends ChannelOutputProcessor
    {
        private final CountDownLatch processingStarted = new CountDownLatch(1);
        private final CountDownLatch releaseProcessing = new CountDownLatch(1);

        private BlockingOutputProcessor()
        {
            super(1, new HeartbeatManager(), "channel output backpressure test");
        }

        private int queueCapacity()
        {
            return CHANNEL_RESULTS_QUEUE_CAPACITY;
        }

        @Override
        public void process(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer channelResultsBuffer)
        {
            processingStarted.countDown();

            try
            {
                releaseProcessing.await(5, TimeUnit.SECONDS);
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
