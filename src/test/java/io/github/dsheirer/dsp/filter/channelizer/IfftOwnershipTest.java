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

import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IfftOwnershipTest
{
    private static final double SAMPLE_RATE = 50_000.0;
    private static final long CENTER_FREQUENCY = 100_000_000L;
    private static final int TAPS_PER_CHANNEL = 12;

    @Test
    void ifftFailureRecyclesBatchExactlyOnce() throws Exception
    {
        ComplexPolyphaseChannelizerM2 channelizer =
            new ComplexPolyphaseChannelizerM2(SAMPLE_RATE, TAPS_PER_CHANNEL);
        AtomicInteger recycleCount = new AtomicInteger();
        CountDownLatch recycled = new CountDownLatch(1);
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer = new ComplexPolyphaseChannelizerM2.ChannelResultsBuffer(
            1, ignored -> {
                recycleCount.incrementAndGet();
                recycled.countDown();
            });
        buffer.add(new float[channelizer.getSubChannelCount()]);

        try
        {
            channelizer.start();
            buffer.setGeneration(channelizer.getProducerGeneration());
            ComplexPolyphaseChannelizerM2.IFFTProcessorDispatcher dispatcher = getIfftDispatcher(channelizer);
            Field fft = ComplexPolyphaseChannelizerM2.IFFTProcessorDispatcher.class.getDeclaredField("mFFT");
            fft.setAccessible(true);
            fft.set(dispatcher, null);

            dispatcher.receive(buffer);

            assertTrue(recycled.await(5, TimeUnit.SECONDS), "IFFT failure did not recycle the batch");
            assertEquals(1, recycleCount.get(), "IFFT failure recycled the same batch more than once");
        }
        finally
        {
            channelizer.stop();
        }
    }

    @Test
    void pendingProcessorUpdateFailureReleasesOnlyItsShareAndContinuesFanout() throws Exception
    {
        ComplexPolyphaseChannelizerM2 channelizer =
            new ComplexPolyphaseChannelizerM2(SAMPLE_RATE, TAPS_PER_CHANNEL);
        PendingUpdateThrowingSource failing = new PendingUpdateThrowingSource();
        TrackingSource healthy = new TrackingSource();
        AtomicInteger recycleCount = new AtomicInteger();
        CountDownLatch recycled = new CountDownLatch(1);
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer = new ComplexPolyphaseChannelizerM2.ChannelResultsBuffer(
            1, ignored -> {
                recycleCount.incrementAndGet();
                recycled.countDown();
            });
        buffer.add(new float[channelizer.getSubChannelCount()]);

        try
        {
            failing.failPendingUpdate();
            channelizer.dispatch(buffer, new PolyphaseChannelSource[] {failing, healthy});

            assertTrue(recycled.await(5, TimeUnit.SECONDS), "fan-out did not release every reserved share");
            assertEquals(1, failing.getFailedUpdateCount());
            assertEquals(1, healthy.getReceiveCount(), "a failed target prevented a later target from receiving");
            assertEquals(1, recycleCount.get(), "shared batch was recycled more than once");
        }
        finally
        {
            failing.stop();
            failing.dispose();
            healthy.stop();
            healthy.dispose();
            channelizer.stop();
        }
    }

    @Test
    void stopRestartDuringFanoutCannotRedirectOldBatchToNewChannel() throws Exception
    {
        PausingChannelizer channelizer = new PausingChannelizer();
        TrackingSource oldChannel = new TrackingSource();
        TrackingSource newChannel = new TrackingSource();
        AtomicInteger recycleCount = new AtomicInteger();
        CountDownLatch recycled = new CountDownLatch(1);
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer = new ComplexPolyphaseChannelizerM2.ChannelResultsBuffer(
            1, ignored -> {
                recycleCount.incrementAndGet();
                recycled.countDown();
            });
        buffer.add(new float[channelizer.getSubChannelCount()]);

        try
        {
            channelizer.addChannel(oldChannel);
            channelizer.start();
            buffer.setGeneration(channelizer.getProducerGeneration());
            getIfftDispatcher(channelizer).receive(buffer);
            assertTrue(channelizer.awaitDispatch(5, TimeUnit.SECONDS), "IFFT batch did not reach the dispatch boundary");

            channelizer.removeChannel(oldChannel);
            channelizer.addChannel(newChannel);
            channelizer.stop();
            channelizer.start();
            channelizer.continueDispatch();

            assertTrue(recycled.await(5, TimeUnit.SECONDS), "old batch was not fully released");
            assertEquals(1, oldChannel.getReceiveCount(), "captured old-run target did not receive its reserved share");
            assertEquals(0, newChannel.getReceiveCount(), "old batch was redirected to a newly registered channel");
            assertEquals(1, recycleCount.get(), "old batch was recycled more than once");
        }
        finally
        {
            channelizer.continueDispatch();
            channelizer.stop();
            oldChannel.stop();
            oldChannel.dispose();
            newChannel.stop();
            newChannel.dispose();
        }
    }

    private static ComplexPolyphaseChannelizerM2.IFFTProcessorDispatcher getIfftDispatcher(
        ComplexPolyphaseChannelizerM2 channelizer) throws ReflectiveOperationException
    {
        Field field = ComplexPolyphaseChannelizerM2.class.getDeclaredField("mIFFTProcessorDispatcher");
        field.setAccessible(true);
        return (ComplexPolyphaseChannelizerM2.IFFTProcessorDispatcher)field.get(channelizer);
    }

    private static ChannelCalculator createChannelCalculator()
    {
        return new ChannelCalculator(SAMPLE_RATE, 2, CENTER_FREQUENCY, 2.0);
    }

    private static TunerChannel createTunerChannel()
    {
        return new TunerChannel(CENTER_FREQUENCY, 12_500);
    }

    private static class TrackingSource extends PolyphaseChannelSource
    {
        private final AtomicInteger mReceiveCount = new AtomicInteger();

        private TrackingSource()
        {
            super(createTunerChannel(), createChannelCalculator(), new SynthesisFilterManager(),
                (SourceEvent event) -> {}, "ifft ownership test", null);
        }

        @Override
        public void receiveChannelResults(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer channelResultsBuffer)
        {
            mReceiveCount.incrementAndGet();
            channelResultsBuffer.release();
        }

        private int getReceiveCount()
        {
            return mReceiveCount.get();
        }
    }

    private static class PendingUpdateThrowingSource extends PolyphaseChannelSource
    {
        private final AtomicInteger mFailedUpdateCount = new AtomicInteger();
        private boolean mFailUpdate;

        private PendingUpdateThrowingSource()
        {
            super(createTunerChannel(), createChannelCalculator(), new SynthesisFilterManager(),
                (SourceEvent event) -> {}, "ifft ownership failure test", null);
        }

        private void failPendingUpdate()
        {
            mFailUpdate = true;
            updateOutputProcessor(createChannelCalculator(), new SynthesisFilterManager());
        }

        @Override
        public void doUpdateOutputProcessor(ChannelCalculator channelCalculator, SynthesisFilterManager filterManager)
        {
            if(mFailUpdate)
            {
                mFailedUpdateCount.incrementAndGet();
                throw new IllegalStateException("injected pending output processor update failure");
            }

            super.doUpdateOutputProcessor(channelCalculator, filterManager);
        }

        private int getFailedUpdateCount()
        {
            return mFailedUpdateCount.get();
        }
    }

    private static class PausingChannelizer extends ComplexPolyphaseChannelizerM2
    {
        private final CountDownLatch mDispatchEntered = new CountDownLatch(1);
        private final CountDownLatch mContinueDispatch = new CountDownLatch(1);

        private PausingChannelizer() throws Exception
        {
            super(SAMPLE_RATE, TAPS_PER_CHANNEL);
        }

        @Override
        protected void dispatch(ChannelResultsBuffer channelResultsBuffer, PolyphaseChannelSource[] channels)
        {
            mDispatchEntered.countDown();

            try
            {
                mContinueDispatch.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            super.dispatch(channelResultsBuffer, channels);
        }

        private boolean awaitDispatch(long timeout, TimeUnit timeUnit) throws InterruptedException
        {
            return mDispatchEntered.await(timeout, timeUnit);
        }

        private void continueDispatch()
        {
            mContinueDispatch.countDown();
        }
    }
}
