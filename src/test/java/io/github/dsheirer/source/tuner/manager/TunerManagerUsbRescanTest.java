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

package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

class TunerManagerUsbRescanTest
{
    @Test
    void concurrentRequestsShareOneBackgroundScan() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger scanCount = new AtomicInteger();
        AtomicReference<Thread> scanThread = new AtomicReference<>();
        TestTunerManager manager = new TestTunerManager(() ->
        {
            scanCount.incrementAndGet();
            scanThread.set(Thread.currentThread());
            started.countDown();
            await(release);
            return 1;
        });

        try
        {
            Thread requestingThread = Thread.currentThread();
            CompletableFuture<Integer> first = manager.requestUsbTunerRescan();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            CompletableFuture<Integer> second = manager.requestUsbTunerRescan();

            assertSame(first, second);
            assertNotSame(requestingThread, scanThread.get());
            release.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertEquals(1, scanCount.get());
        }
        finally
        {
            release.countDown();
            manager.stopUsbRescanWorker(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void completedScanAllowsOneNewRequest() throws Exception
    {
        AtomicInteger scanCount = new AtomicInteger();
        TestTunerManager manager = new TestTunerManager(() ->
        {
            scanCount.incrementAndGet();
            return 0;
        });

        try
        {
            assertEquals(0, manager.requestUsbTunerRescan().get(5, TimeUnit.SECONDS));
            assertEquals(0, manager.requestUsbTunerRescan().get(5, TimeUnit.SECONDS));
            assertEquals(2, scanCount.get());
        }
        finally
        {
            manager.stopUsbRescanWorker(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeCompletesActiveRequestAndRejectsNewWork() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        TestTunerManager manager = new TestTunerManager(() ->
        {
            started.countDown();

            try
            {
                new CountDownLatch(1).await();
            }
            catch(InterruptedException ie)
            {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }

            return 0;
        });
        CompletableFuture<Integer> active = manager.requestUsbTunerRescan();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(manager.stopUsbRescanWorker(1, TimeUnit.SECONDS));
        assertEquals(TunerManager.USB_RESCAN_UNAVAILABLE, active.get(5, TimeUnit.SECONDS));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(TunerManager.USB_RESCAN_UNAVAILABLE,
                manager.requestUsbTunerRescan().get(5, TimeUnit.SECONDS));
    }

    @Test
    void closeTimeoutIsBoundedWhenScanIgnoresInterrupts() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestTunerManager manager = new TestTunerManager(() ->
        {
            started.countDown();
            await(release);
            return 0;
        });

        try
        {
            CompletableFuture<Integer> active = manager.requestUsbTunerRescan();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            long startNanos = System.nanoTime();
            assertFalse(manager.stopUsbRescanWorker(50, TimeUnit.MILLISECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos) < 1000);
            assertEquals(TunerManager.USB_RESCAN_UNAVAILABLE, active.get(5, TimeUnit.SECONDS));
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    void hotplugStopRetainsLiveThreadAndReportsTimeout() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger interruptCount = new AtomicInteger();
        Thread eventThread = new Thread(() ->
        {
            started.countDown();
            await(release);
        });
        eventThread.setDaemon(true);
        eventThread.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        TunerManager tunerManager = new TunerManager(null);
        TunerManager.HotplugEventSupport support = tunerManager.new HotplugEventSupport(
                interruptCount::incrementAndGet, eventThread);

        try
        {
            assertFalse(support.stop(50));
            assertEquals(1, interruptCount.get());
        }
        finally
        {
            release.countDown();
            eventThread.join(5000);
        }

        assertTrue(support.stop(50));
        assertEquals(2, interruptCount.get());
    }

    private static void await(CountDownLatch latch)
    {
        boolean released = false;

        while(!released)
        {
            try
            {
                released = latch.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException ie)
            {
                //Simulate native work that does not necessarily respond to Java interruption.
            }
        }
    }

    private static class TestTunerManager extends TunerManager
    {
        private final IntSupplier mScan;

        private TestTunerManager(IntSupplier scan)
        {
            super(null);
            mScan = scan;
        }

        @Override
        int discoverUSBTuners()
        {
            return mScan.getAsInt();
        }
    }
}
