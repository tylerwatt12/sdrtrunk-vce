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

import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelizerPoolCapacityTest
{
    @Test
    void arrayPoolRemainsFixedWhenMoreThanAQueueWorkingSetReturns() throws Exception
    {
        ComplexPolyphaseChannelizerM2 channelizer = new ComplexPolyphaseChannelizerM2(50_000.0, 12);
        int arrayLength = channelizer.getSubChannelCount();

        for(int x = 0; x < ComplexPolyphaseChannelizerM2.CHANNEL_RESULTS_POOL_CAPACITY + 100; x++)
        {
            channelizer.recycleChannelResultsArray(new float[arrayLength]);
        }

        assertEquals(ComplexPolyphaseChannelizerM2.CHANNEL_RESULTS_POOL_CAPACITY,
            channelizer.getChannelResultsPoolSize());
    }

    @Test
    void wrapperPoolRemainsFixedAtEightIndependentOfLiveQueuePolicy() throws Exception
    {
        ComplexPolyphaseChannelizerM2 channelizer = new ComplexPolyphaseChannelizerM2(50_000.0, 12);
        List<ComplexPolyphaseChannelizerM2.ChannelResultsBuffer> buffers = new ArrayList<>();

        for(int x = 0; x < 20; x++)
        {
            ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer =
                new ComplexPolyphaseChannelizerM2.ChannelResultsBuffer(1, ignored -> {});
            buffer.add(new float[channelizer.getSubChannelCount()]);
            buffers.add(buffer);
        }

        for(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer: buffers)
        {
            channelizer.recycleChannelResultsBuffer(buffer);
        }

        assertEquals(8, channelizer.getChannelResultsBufferPoolSize());
    }

    @Test
    void oldNativeCallbackTokenCannotEnterRestartedGeneration() throws Exception
    {
        ComplexPolyphaseChannelizerM2 channelizer = new ComplexPolyphaseChannelizerM2(50_000.0, 12);
        CountDownLatch firstOldFragmentProcessed = new CountDownLatch(1);
        CountDownLatch allowOldCallbackToContinue = new CountDownLatch(1);
        CountDownLatch oldCallbackFinished = new CountDownLatch(1);
        channelizer.start();
        long oldGeneration = channelizer.getProducerGeneration();

        Thread oldNativeCallback = new Thread(() -> {
            //Both fragments deliberately retain the one token captured at native-buffer callback entry.
            channelizer.receive(new InterleavedComplexSamples(new float[] {1.0f, -1.0f, 0.5f}, 1000),
                oldGeneration);
            firstOldFragmentProcessed.countDown();

            try
            {
                allowOldCallbackToContinue.await(5, TimeUnit.SECONDS);
                channelizer.receive(new InterleavedComplexSamples(new float[] {0.75f}, 1001), oldGeneration);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                oldCallbackFinished.countDown();
            }
        }, "old native buffer callback test");

        try
        {
            oldNativeCallback.start();
            assertTrue(firstOldFragmentProcessed.await(5, TimeUnit.SECONDS));
            assertEquals(1, channelizer.getPendingChannelResultsCount());
            assertEquals(1, channelizer.getPendingInputSampleCount());

            channelizer.stop();

            //Stop only invalidates the generation and IFFT queue; it must not mutate producer-owned partial state.
            assertEquals(1, channelizer.getPendingChannelResultsCount());
            assertEquals(1, channelizer.getPendingInputSampleCount());

            channelizer.start();
            long restartedGeneration = channelizer.getProducerGeneration();
            allowOldCallbackToContinue.countDown();
            assertTrue(oldCallbackFinished.await(5, TimeUnit.SECONDS));

            //The old callback's second fragment is rejected even though the channelizer has restarted.
            assertEquals(1, channelizer.getPendingChannelResultsCount());
            assertEquals(1, channelizer.getPendingInputSampleCount());

            //The first fragment carrying the new native-buffer token performs the reset on this producer thread, then
            //accepts only new-run samples.
            channelizer.receive(new InterleavedComplexSamples(new float[] {0.25f, -0.25f}, 2000),
                restartedGeneration);
            assertEquals(1, channelizer.getPendingChannelResultsCount());
            assertEquals(0, channelizer.getPendingInputSampleCount());
        }
        finally
        {
            allowOldCallbackToContinue.countDown();
            oldNativeCallback.join(TimeUnit.SECONDS.toMillis(5));
            channelizer.stop();
        }
    }
}
