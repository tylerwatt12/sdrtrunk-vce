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

import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChannelProcessingManagerSiteMetadataTest
{
    @Test
    public void siteMetadataListenersDoNotBlockTheCallingThread() throws Exception
    {
        ChannelProcessingManager manager = manager();
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicReference<Thread> listenerThread = new AtomicReference<>();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();

        manager.addSiteMetadataListener(event -> {
            listenerThread.set(Thread.currentThread());
            listenerStarted.countDown();

            try
            {
                releaseListener.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        });

        try
        {
            Future<?> submitted = caller.submit(() -> {
                callerThread.set(Thread.currentThread());
                manager.process((SiteMetadataEvent)null);
            });

            assertTrue(listenerStarted.await(5, TimeUnit.SECONDS));
            submitted.get(1, TimeUnit.SECONDS);
            assertNotEquals(callerThread.get(), listenerThread.get());
        }
        finally
        {
            releaseListener.countDown();
            manager.shutdown();
            caller.shutdownNow();
        }
    }

    @Test
    public void protocolListenerReceivesDirectDmrAndBridgedLegacyP25Events() throws Exception
    {
        ChannelProcessingManager manager = manager();
        CountDownLatch received = new CountDownLatch(2);
        List<Protocol> protocols = new CopyOnWriteArrayList<>();
        manager.addProtocolSiteMetadataListener(event -> {
            protocols.add(event.snapshot().protocol());
            received.countDown();
        });
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        DMRNetworkConfigurationSnapshot dmr = dmrSnapshot();
        P25NetworkConfigurationSnapshot p25 = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(1, 2, 3, 4), null, List.of(), List.of(),
            List.of(), List.of(), List.of());

        try
        {
            manager.process(new ProtocolSiteMetadataEvent(channel, dmr, 1_000));
            manager.process(new SiteMetadataEvent(channel, p25, 1_001));

            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(Protocol.DMR, Protocol.APCO25), protocols);
        }
        finally
        {
            manager.shutdown();
        }
    }

    @Test
    public void pausedSubmitterIsIncludedInDrainAndNewSubmissionsDropDuringBarrier() throws Exception
    {
        PausingExecutorService metadataExecutor = new PausingExecutorService();
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences(),
            metadataExecutor);
        ExecutorService producer = Executors.newSingleThreadExecutor();
        ExecutorService barrierCaller = Executors.newSingleThreadExecutor();
        AtomicInteger received = new AtomicInteger();
        manager.addSiteMetadataListener(event -> received.incrementAndGet());

        try
        {
            Future<?> pausedSubmission = producer.submit(() -> manager.process((SiteMetadataEvent)null));
            assertTrue(metadataExecutor.awaitPausedSubmission(5, TimeUnit.SECONDS));

            Future<Boolean> barrier = barrierCaller.submit(() -> manager.suspendSiteMetadataAndAwait(5,
                TimeUnit.SECONDS));
            assertTrue(awaitCondition(() -> !manager.isAcceptingSiteMetadata(), 5, TimeUnit.SECONDS));

            long droppedBefore = manager.getDroppedSiteMetadataEventCount();
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> manager.process((SiteMetadataEvent)null));
            assertEquals(droppedBefore + 1, manager.getDroppedSiteMetadataEventCount());
            assertFalse(barrier.isDone());

            metadataExecutor.releasePausedSubmission();
            pausedSubmission.get(5, TimeUnit.SECONDS);
            assertTrue(barrier.get(5, TimeUnit.SECONDS));
            assertEquals(1, received.get());
        }
        finally
        {
            metadataExecutor.releasePausedSubmission();
            manager.shutdown();
            producer.shutdownNow();
            barrierCaller.shutdownNow();
        }
    }

    @Test
    public void drainWaitsForSlowListenerAndQueuedListenerWork() throws Exception
    {
        ChannelProcessingManager manager = manager();
        ExecutorService barrierCaller = Executors.newSingleThreadExecutor();
        CountDownLatch firstListenerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstListener = new CountDownLatch(1);
        CountDownLatch bothReceived = new CountDownLatch(2);

        manager.addSiteMetadataListener(event -> {
            if(firstListenerStarted.getCount() > 0)
            {
                firstListenerStarted.countDown();
                awaitInterruptibly(releaseFirstListener);
            }

            bothReceived.countDown();
        });

        try
        {
            manager.process((SiteMetadataEvent)null);
            assertTrue(firstListenerStarted.await(5, TimeUnit.SECONDS));
            manager.process((SiteMetadataEvent)null);

            Future<Boolean> barrier = barrierCaller.submit(() -> manager.suspendSiteMetadataAndAwait(5,
                TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> barrier.get(100, TimeUnit.MILLISECONDS));

            releaseFirstListener.countDown();
            assertTrue(barrier.get(5, TimeUnit.SECONDS));
            assertTrue(bothReceived.await(1, TimeUnit.SECONDS));
        }
        finally
        {
            releaseFirstListener.countDown();
            manager.shutdown();
            barrierCaller.shutdownNow();
        }
    }

    @Test
    public void saturatedQueueDropsOldObserverWorkWithoutCallerRuns() throws Exception
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences(), 1);
        ExecutorService producer = Executors.newSingleThreadExecutor();
        CountDownLatch firstListenerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstListener = new CountDownLatch(1);
        AtomicInteger received = new AtomicInteger();
        AtomicReference<Thread> producerThread = new AtomicReference<>();
        List<Thread> listenerThreads = new CopyOnWriteArrayList<>();
        ProtocolSiteMetadataEvent event = protocolEvent(1_000);

        manager.addProtocolSiteMetadataListener(ignored -> {
            listenerThreads.add(Thread.currentThread());

            if(received.incrementAndGet() == 1)
            {
                firstListenerStarted.countDown();
                awaitInterruptibly(releaseFirstListener);
            }
        });

        try
        {
            manager.process(event);
            assertTrue(firstListenerStarted.await(5, TimeUnit.SECONDS));

            Future<?> submissions = producer.submit(() -> {
                producerThread.set(Thread.currentThread());

                for(int x = 0; x < 100; x++)
                {
                    manager.process(event);
                }
            });

            //The receiver-side producer must complete while the only observer worker remains blocked.
            submissions.get(1, TimeUnit.SECONDS);
            assertEquals(99, manager.getDroppedSiteMetadataEventCount());
            releaseFirstListener.countDown();
            assertTrue(manager.suspendSiteMetadataAndAwait(5, TimeUnit.SECONDS));
            assertEquals(2, received.get());
            assertTrue(listenerThreads.stream().noneMatch(thread -> thread == producerThread.get()),
                "Queue rejection must never execute observer work on the producer thread");
        }
        finally
        {
            releaseFirstListener.countDown();
            manager.shutdown();
            producer.shutdownNow();
        }
    }

    @Test
    public void drainTimeoutFailsClosedUntilBlockedListenerFinishes() throws Exception
    {
        ChannelProcessingManager manager = manager();
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);

        manager.addSiteMetadataListener(event -> {
            listenerStarted.countDown();
            awaitUninterruptibly(releaseListener);
        });

        try
        {
            manager.process((SiteMetadataEvent)null);
            assertTrue(listenerStarted.await(5, TimeUnit.SECONDS));

            assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertFalse(manager.suspendSiteMetadataAndAwait(100, TimeUnit.MILLISECONDS)));
            assertFalse(manager.isAcceptingSiteMetadata());

            long droppedBefore = manager.getDroppedSiteMetadataEventCount();
            manager.process((SiteMetadataEvent)null);
            manager.process(protocolEvent(2_000));
            assertEquals(droppedBefore + 2, manager.getDroppedSiteMetadataEventCount());

            releaseListener.countDown();
            assertTrue(manager.suspendSiteMetadataAndAwait(5, TimeUnit.SECONDS));
        }
        finally
        {
            releaseListener.countDown();
            manager.shutdown();
        }
    }

    @Test
    public void ordinaryShutdownInterruptsWorkerClearsQueueAndTerminatesExecutor() throws Exception
    {
        ChannelProcessingManager manager = manager();
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch listenerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicInteger received = new AtomicInteger();

        manager.addSiteMetadataListener(event -> {
            received.incrementAndGet();
            listenerStarted.countDown();

            try
            {
                releaseListener.await();
            }
            catch(InterruptedException exception)
            {
                listenerInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });

        try
        {
            manager.process((SiteMetadataEvent)null);
            assertTrue(listenerStarted.await(5, TimeUnit.SECONDS));

            for(int x = 0; x < 4; x++)
            {
                manager.process((SiteMetadataEvent)null);
            }

            assertFalse(manager.isSiteMetadataObserverTerminated());
            manager.shutdown();
            assertTrue(listenerInterrupted.await(1, TimeUnit.SECONDS));
            assertTrue(manager.isSiteMetadataObserverTerminated(),
                "Ordinary shutdown must not leave its prestarted observer worker alive");
            assertEquals(1, received.get());
            assertEquals(4, manager.getDroppedSiteMetadataEventCount());
        }
        finally
        {
            releaseListener.countDown();
            manager.shutdown();
        }
    }

    private static ChannelProcessingManager manager()
    {
        return new ChannelProcessingManager(null, null, null, new UserPreferences());
    }

    private static DMRNetworkConfigurationSnapshot dmrSnapshot()
    {
        return new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 1, 2, "Tier III Trunking", "TINY",
            null, "Control", 1, 1, List.of(), List.of());
    }

    private static ProtocolSiteMetadataEvent protocolEvent(long timestamp)
    {
        return new ProtocolSiteMetadataEvent(new Channel("control", Channel.ChannelType.STANDARD), dmrSnapshot(),
            timestamp);
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

    private static void awaitInterruptibly(CountDownLatch latch)
    {
        try
        {
            latch.await();
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
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

    private static class PausingExecutorService extends AbstractExecutorService
    {
        private final ExecutorService mDelegate = Executors.newSingleThreadExecutor();
        private final CountDownLatch mSubmissionPaused = new CountDownLatch(1);
        private final CountDownLatch mReleaseSubmission = new CountDownLatch(1);
        private final AtomicBoolean mPauseNextSubmission = new AtomicBoolean(true);

        @Override
        public void execute(Runnable command)
        {
            if(mPauseNextSubmission.compareAndSet(true, false))
            {
                mSubmissionPaused.countDown();
                awaitUninterruptibly(mReleaseSubmission);
            }

            mDelegate.execute(command);
        }

        @Override
        public void shutdown()
        {
            mDelegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow()
        {
            return mDelegate.shutdownNow();
        }

        @Override
        public boolean isShutdown()
        {
            return mDelegate.isShutdown();
        }

        @Override
        public boolean isTerminated()
        {
            return mDelegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mDelegate.awaitTermination(timeout, unit);
        }

        private boolean awaitPausedSubmission(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mSubmissionPaused.await(timeout, unit);
        }

        private void releasePausedSubmission()
        {
            mReleaseSubmission.countDown();
        }
    }
}
