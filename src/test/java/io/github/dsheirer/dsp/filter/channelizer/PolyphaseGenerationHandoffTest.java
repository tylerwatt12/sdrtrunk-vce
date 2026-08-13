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
import io.github.dsheirer.buffer.INativeBufferProvider;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolyphaseGenerationHandoffTest
{
    @Test
    void oldNativeBufferCannotCaptureNewChannelizerGenerationAfterRestart() throws Exception
    {
        PolyphaseChannelManager manager = new PolyphaseChannelManager(new NoOpNativeBufferProvider(), 100_000_000L,
            50_000.0);
        ComplexPolyphaseChannelizerM2 channelizer = new ComplexPolyphaseChannelizerM2(50_000.0, 12);
        NativeBufferProcessor processor = field(manager, "mBufferProcessor", NativeBufferProcessor.class);
        setField(manager, "mPolyphaseChannelizer", channelizer);
        CountDownLatch oldIteratorEntered = new CountDownLatch(1);
        CountDownLatch releaseOldIterator = new CountDownLatch(1);
        BlockingNativeBuffer oldBuffer = new BlockingNativeBuffer(
            new InterleavedComplexSamples(new float[] {1.0f, -1.0f, 0.5f, -0.5f}, 1000), oldIteratorEntered,
            releaseOldIterator);

        try
        {
            channelizer.start();
            processor.start();
            processor.receive(oldBuffer);

            //The NBP worker has already classified this as current and entered NativeBufferReceiver, but iterator
            //creation pauses before that receiver captures and validates the channelizer token.
            assertTrue(oldIteratorEntered.await(5, TimeUnit.SECONDS));
            processor.stop();
            channelizer.stop();
            channelizer.start();
            processor.start();
            releaseOldIterator.countDown();
            assertTrue(awaitProcessedBuffers(processor, 1));

            //The immutable queued NBP generation rejects the old buffer.  Without the second validation, it would
            //capture the restarted channelizer generation and leave two old channel results here.
            assertEquals(0, channelizer.getPendingChannelResultsCount());
            assertEquals(0, channelizer.getPendingInputSampleCount());

            processor.receive(new BlockingNativeBuffer(
                new InterleavedComplexSamples(new float[] {0.25f, -0.25f}, 2000), new CountDownLatch(0),
                new CountDownLatch(0)));
            assertTrue(awaitProcessedBuffers(processor, 2));
            assertEquals(1, channelizer.getPendingChannelResultsCount());
            assertEquals(0, channelizer.getPendingInputSampleCount());
        }
        finally
        {
            releaseOldIterator.countDown();
            processor.dispose();
            channelizer.stop();
            assertTrue(processor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static boolean awaitProcessedBuffers(NativeBufferProcessor processor, long expected)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while(System.nanoTime() < deadline)
        {
            if(processor.getQueueMetrics().processedBuffers() >= expected)
            {
                return true;
            }

            Thread.sleep(5);
        }

        return processor.getQueueMetrics().processedBuffers() >= expected;
    }

    private static <T> T field(Object owner, String name, Class<T> type) throws ReflectiveOperationException
    {
        Field field = PolyphaseChannelManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }

    private static void setField(Object owner, String name, Object value) throws ReflectiveOperationException
    {
        Field field = PolyphaseChannelManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static class BlockingNativeBuffer implements INativeBuffer
    {
        private final InterleavedComplexSamples mSamples;
        private final CountDownLatch mIteratorEntered;
        private final CountDownLatch mReleaseIterator;

        private BlockingNativeBuffer(InterleavedComplexSamples samples, CountDownLatch iteratorEntered,
                                     CountDownLatch releaseIterator)
        {
            mSamples = samples;
            mIteratorEntered = iteratorEntered;
            mReleaseIterator = releaseIterator;
        }

        @Override
        public Iterator<ComplexSamples> iterator()
        {
            return Collections.emptyIterator();
        }

        @Override
        public Iterator<InterleavedComplexSamples> iteratorInterleaved()
        {
            mIteratorEntered.countDown();

            try
            {
                mReleaseIterator.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            return List.of(mSamples).iterator();
        }

        @Override
        public int sampleCount()
        {
            return mSamples.samples().length / 2;
        }

        @Override
        public long getTimestamp()
        {
            return mSamples.timestamp();
        }
    }

    private static class NoOpNativeBufferProvider implements INativeBufferProvider
    {
        @Override
        public void addBufferListener(Listener<INativeBuffer> listener)
        {
        }

        @Override
        public void removeBufferListener(Listener<INativeBuffer> listener)
        {
        }

        @Override
        public boolean hasBufferListeners()
        {
            return false;
        }
    }
}
