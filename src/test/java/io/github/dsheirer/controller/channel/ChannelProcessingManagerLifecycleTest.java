/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.controller.channel;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelProcessingManagerLifecycleTest
{
    @Test
    void reusableStopAllowsReloadedChannelStartsButTerminalShutdownRejectsThem() throws Exception
    {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        ChannelProcessingManager manager = manager(lifecycle);
        Channel channel = channel("Reloadable");

        try
        {
            manager.start(channel);
            assertEquals(1, lifecycle.getStartCount());
            assertTrue(lifecycle.isActive());

            manager.stopAllChannels();
            assertTrue(manager.isAcceptingChannelStarts());
            assertFalse(lifecycle.isActive());
            manager.start(channel);
            assertEquals(2, lifecycle.getStartCount(),
                "A configuration reload must be able to start channels on the reused manager");

            manager.shutdown();
            assertFalse(manager.isAcceptingChannelStarts());
            assertFalse(lifecycle.isActive());
            assertThrows(ChannelException.class, () -> manager.start(channel));
            manager.receive(new ChannelEvent(channel, ChannelEvent.Event.REQUEST_ENABLE));
            manager.startChannelRequest(new ChannelStartProcessingRequest(channel));
            assertEquals(2, lifecycle.getStartCount(),
                "No public, model, or event-bus start path may run after terminal shutdown begins");
        }
        finally
        {
            manager.shutdown();
        }
    }

    @Test
    void startAlreadyInsideProcessingIsStoppedBeforeTerminalShutdownReturns() throws Exception
    {
        BlockingSuccessfulLifecycle lifecycle = new BlockingSuccessfulLifecycle();
        ChannelProcessingManager manager = manager(lifecycle);
        Channel channel = channel("Started during shutdown");
        ExecutorService startCaller = Executors.newSingleThreadExecutor();
        ExecutorService shutdownCaller = Executors.newSingleThreadExecutor();

        try
        {
            Future<?> start = startCaller.submit(() -> {
                manager.start(channel);
                return null;
            });
            assertTrue(lifecycle.awaitStartEntered(5, TimeUnit.SECONDS));

            Future<?> shutdown = shutdownCaller.submit(manager::shutdown);
            assertTrue(awaitCondition(() -> !manager.isAcceptingChannelStarts(), 5, TimeUnit.SECONDS));
            assertFalse(shutdown.isDone(), "Shutdown must include a start already inside startProcessing");

            lifecycle.releaseStart();
            start.get(5, TimeUnit.SECONDS);
            shutdown.get(5, TimeUnit.SECONDS);
            assertTrue(lifecycle.wasStopped());
            assertFalse(lifecycle.isActive(),
                "The start that won the gate race must be stopped before shutdown returns");
        }
        finally
        {
            lifecycle.releaseStart();
            manager.shutdown();
            startCaller.shutdownNow();
            shutdownCaller.shutdownNow();
        }
    }

    @Test
    void persistentFailureRacingShutdownCannotScheduleDelayedRetry() throws Exception
    {
        BlockingFailedLifecycle lifecycle = new BlockingFailedLifecycle();
        ChannelProcessingManager manager = manager(lifecycle);
        ChannelStartProcessingRequest request = new ChannelStartProcessingRequest(channel("Persistent race"));
        request.setPersistentAttempt(true);
        ExecutorService startCaller = Executors.newSingleThreadExecutor();
        ExecutorService shutdownCaller = Executors.newSingleThreadExecutor();

        try
        {
            Future<?> start = startCaller.submit(() -> manager.startChannelRequest(request));
            assertTrue(lifecycle.awaitStartEntered(5, TimeUnit.SECONDS));

            Future<?> shutdown = shutdownCaller.submit(manager::shutdown);
            assertTrue(awaitCondition(() -> !manager.isAcceptingChannelStarts(), 5, TimeUnit.SECONDS));
            assertFalse(shutdown.isDone());

            lifecycle.releaseStart();
            start.get(5, TimeUnit.SECONDS);
            shutdown.get(5, TimeUnit.SECONDS);
            assertEquals(0, manager.getPendingDelayedChannelStartCount());

            Thread.sleep(650);
            assertEquals(1, lifecycle.getStartCount());
            assertThrows(ChannelException.class, () -> manager.start(request.getChannel()));
            assertEquals(1, lifecycle.getStartCount());
        }
        finally
        {
            lifecycle.releaseStart();
            manager.shutdown();
            startCaller.shutdownNow();
            shutdownCaller.shutdownNow();
        }
    }

    @Test
    void shutdownCancelsAlreadyScheduledPersistentRetry() throws Exception
    {
        FailedLifecycle lifecycle = new FailedLifecycle();
        ChannelProcessingManager manager = manager(lifecycle);
        ChannelStartProcessingRequest request = new ChannelStartProcessingRequest(channel("Delayed retry"));
        request.setPersistentAttempt(true);

        try
        {
            manager.startChannelRequest(request);
            assertEquals(1, lifecycle.getStartCount());
            assertEquals(1, manager.getPendingDelayedChannelStartCount());

            manager.shutdown();
            assertEquals(0, manager.getPendingDelayedChannelStartCount());
            Thread.sleep(650);
            assertEquals(1, lifecycle.getStartCount(),
                "A delayed persistent request must stay cancelled after terminal shutdown");
        }
        finally
        {
            manager.shutdown();
        }
    }

    private static ChannelProcessingManager manager(ChannelProcessingManager.ChannelLifecycle lifecycle)
    {
        return new ChannelProcessingManager(null, null, new AliasModel(), new UserPreferences(), lifecycle);
    }

    private static Channel channel(String name)
    {
        Channel channel = new Channel(name);
        channel.setDecodeConfiguration(new DecodeConfigDMR());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(460_000_000L);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static boolean awaitCondition(BooleanSupplier condition, long timeout, TimeUnit unit)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        while(!condition.getAsBoolean() && System.nanoTime() < deadline)
        {
            Thread.sleep(5);
        }

        return condition.getAsBoolean();
    }

    private static class RecordingLifecycle implements ChannelProcessingManager.ChannelLifecycle
    {
        private final AtomicInteger mStartCount = new AtomicInteger();
        private final AtomicBoolean mActive = new AtomicBoolean();
        private final AtomicBoolean mStopped = new AtomicBoolean();

        @Override
        public void start(ChannelStartProcessingRequest request) throws ChannelException
        {
            mStartCount.incrementAndGet();
            mActive.set(true);
        }

        @Override
        public void stopAllChannels()
        {
            if(mActive.getAndSet(false))
            {
                mStopped.set(true);
            }
        }

        protected int getStartCount()
        {
            return mStartCount.get();
        }

        protected boolean isActive()
        {
            return mActive.get();
        }

        protected boolean wasStopped()
        {
            return mStopped.get();
        }
    }

    private static class BlockingSuccessfulLifecycle extends RecordingLifecycle
    {
        private final CountDownLatch mStartEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseStart = new CountDownLatch(1);

        @Override
        public void start(ChannelStartProcessingRequest request) throws ChannelException
        {
            mStartEntered.countDown();
            awaitUninterruptibly(mReleaseStart);
            super.start(request);
        }

        protected boolean awaitStartEntered(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mStartEntered.await(timeout, unit);
        }

        protected void releaseStart()
        {
            mReleaseStart.countDown();
        }
    }

    private static class FailedLifecycle implements ChannelProcessingManager.ChannelLifecycle
    {
        private final AtomicInteger mStartCount = new AtomicInteger();

        @Override
        public void start(ChannelStartProcessingRequest request) throws ChannelException
        {
            mStartCount.incrementAndGet();
            throw new ChannelException("Test start failure");
        }

        @Override
        public void stopAllChannels()
        {
        }

        protected int getStartCount()
        {
            return mStartCount.get();
        }
    }

    private static class BlockingFailedLifecycle extends FailedLifecycle
    {
        private final CountDownLatch mStartEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseStart = new CountDownLatch(1);

        @Override
        public void start(ChannelStartProcessingRequest request) throws ChannelException
        {
            mStartEntered.countDown();
            awaitUninterruptibly(mReleaseStart);
            super.start(request);
        }

        private boolean awaitStartEntered(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mStartEntered.await(timeout, unit);
        }

        private void releaseStart()
        {
            mReleaseStart.countDown();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch)
    {
        boolean interrupted = false;

        while(latch.getCount() > 0)
        {
            try
            {
                latch.await();
            }
            catch(InterruptedException exception)
            {
                interrupted = true;
            }
        }

        if(interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }
}
