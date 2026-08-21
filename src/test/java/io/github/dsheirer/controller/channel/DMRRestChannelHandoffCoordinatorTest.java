/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DMRRestChannelHandoffRequest;
import io.github.dsheirer.module.decode.dmr.DMRTrafficChannelManager;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.DMRAbsoluteChannel;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DMRRestChannelHandoffCoordinatorTest
{
    @Test
    void blockedHandlerNeverRunsOnProducerAndWorkerIsPrestartedLowPriorityDaemon() throws Exception
    {
        RequestFactory requests = new RequestFactory();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<Thread> createdWorker = new AtomicReference<>();
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        AtomicReference<Thread> producerThread = new AtomicReference<>();
        ThreadFactory threadFactory = runnable ->
        {
            Thread worker = new ObserverThreadFactory("DMR handoff test worker").newThread(() ->
            {
                workerStarted.countDown();
                runnable.run();
            });
            createdWorker.set(worker);
            return worker;
        };
        DMRRestChannelHandoffCoordinator coordinator = new DMRRestChannelHandoffCoordinator(2, threadFactory,
            request ->
            {
                handlerThread.set(Thread.currentThread());
                handlerStarted.countDown();
                releaseHandler.await();
                handled.countDown();
            });

        try
        {
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "worker was not prestarted");
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1),
                () ->
                {
                    producerThread.set(Thread.currentThread());
                    assertTrue(coordinator.offer(requests.create(1)));
                });
            assertTrue(handlerStarted.await(5, TimeUnit.SECONDS), "handler did not start");
            assertNotSame(producerThread.get(), handlerThread.get());
            assertSame(createdWorker.get(), handlerThread.get());
            assertTrue(handlerThread.get().isDaemon());
            assertEquals(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1),
                handlerThread.get().getPriority());
        }
        finally
        {
            releaseHandler.countDown();
            boolean completed = handled.await(5, TimeUnit.SECONDS);
            coordinator.close();
            assertTrue(completed);
        }
    }

    @Test
    void saturationRejectsWithoutCallerRunsAndWorkerContinuesDraining() throws Exception
    {
        RequestFactory requests = new RequestFactory();
        CountDownLatch firstHandlerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstHandler = new CountDownLatch(1);
        CountDownLatch firstBatchHandled = new CountDownLatch(3);
        CountDownLatch continuedHandled = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        List<Long> handledGenerations = new CopyOnWriteArrayList<>();
        DMRRestChannelHandoffCoordinator coordinator = new DMRRestChannelHandoffCoordinator(2,
            new ObserverThreadFactory("DMR handoff saturation test"), request ->
            {
                int invocation = invocations.incrementAndGet();

                if(invocation == 1)
                {
                    firstHandlerStarted.countDown();
                    releaseFirstHandler.await();
                }

                handledGenerations.add(request.generation());

                if(request.generation() <= 3)
                {
                    firstBatchHandled.countDown();
                }
                else
                {
                    continuedHandled.countDown();
                }
            });

        try
        {
            assertTrue(coordinator.offer(requests.create(1)));
            assertTrue(firstHandlerStarted.await(5, TimeUnit.SECONDS), "first handler did not block");
            assertTrue(coordinator.offer(requests.create(2)));
            assertTrue(coordinator.offer(requests.create(3)));
            assertFalse(coordinator.offer(requests.create(4)));
            assertEquals(1, coordinator.getDroppedCount());
            assertEquals(1, invocations.get(), "rejected request ran on the producer");

            releaseFirstHandler.countDown();
            assertTrue(firstBatchHandled.await(5, TimeUnit.SECONDS), "accepted requests did not drain");
            assertEquals(List.of(1L, 2L, 3L), handledGenerations);

            assertTrue(coordinator.offer(requests.create(5)));
            assertTrue(continuedHandled.await(5, TimeUnit.SECONDS), "worker did not continue after saturation");
            assertEquals(List.of(1L, 2L, 3L, 5L), handledGenerations);
        }
        finally
        {
            releaseFirstHandler.countDown();
            coordinator.close();
        }
    }

    @Test
    void closeRejectsNewWorkAndJoinsAfterDrainingAcceptedRequests() throws Exception
    {
        RequestFactory requests = new RequestFactory();
        CountDownLatch firstHandlerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstHandler = new CountDownLatch(1);
        CountDownLatch handled = new CountDownLatch(2);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        DMRRestChannelHandoffCoordinator coordinator = new DMRRestChannelHandoffCoordinator(2,
            new ObserverThreadFactory("DMR handoff close test"), request ->
            {
                workerThread.set(Thread.currentThread());

                if(request.generation() == 1)
                {
                    firstHandlerStarted.countDown();
                    releaseFirstHandler.await();
                }

                handled.countDown();
            });

        AtomicReference<Thread> closerReference = new AtomicReference<>();

        try
        {
            assertTrue(coordinator.offer(requests.create(1)));
            assertTrue(firstHandlerStarted.await(5, TimeUnit.SECONDS), "first handler did not block");
            assertTrue(coordinator.offer(requests.create(2)));
            Thread closer = Thread.ofPlatform().name("DMR handoff test closer").start(() ->
            {
                coordinator.close();
                closeReturned.countDown();
            });
            closerReference.set(closer);
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS),
                "close returned before accepted work drained");
            awaitNotAccepting(coordinator);
            assertFalse(coordinator.offer(requests.create(3)));
            assertEquals(1, coordinator.getDroppedCount());
            releaseFirstHandler.countDown();
            assertTrue(closeReturned.await(5, TimeUnit.SECONDS), "close did not join the worker");
            assertTrue(handled.await(5, TimeUnit.SECONDS), "accepted work was not drained");
            closer.join(1_000);
            assertFalse(closer.isAlive());
            assertFalse(workerThread.get().isAlive());
            assertTrue(coordinator.isClosed());
            assertFalse(coordinator.offer(requests.create(4)));
            assertEquals(2, coordinator.getDroppedCount());
            coordinator.close();
        }
        finally
        {
            releaseFirstHandler.countDown();
            coordinator.close();
            Thread closer = closerReference.get();

            if(closer != null)
            {
                closer.join(1_000);
            }
        }
    }

    @Test
    void handlerFailureDoesNotTerminateTheWorker() throws Exception
    {
        RequestFactory requests = new RequestFactory();
        CountDownLatch secondHandled = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        DMRRestChannelHandoffCoordinator coordinator = new DMRRestChannelHandoffCoordinator(2,
            new ObserverThreadFactory("DMR handoff failure test"), request ->
            {
                if(invocations.incrementAndGet() == 1)
                {
                    throw new IllegalStateException("expected test failure");
                }

                secondHandled.countDown();
            });

        try
        {
            assertTrue(coordinator.offer(requests.create(1)));
            assertTrue(coordinator.offer(requests.create(2)));
            assertTrue(secondHandled.await(5, TimeUnit.SECONDS));
            assertEquals(2, invocations.get());
            assertTrue(coordinator.isAccepting());
        }
        finally
        {
            coordinator.close();
        }
    }

    private static void awaitNotAccepting(DMRRestChannelHandoffCoordinator coordinator) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);

        while(coordinator.isAccepting() && System.nanoTime() < deadline)
        {
            Thread.sleep(1);
        }

        assertFalse(coordinator.isAccepting(), "close did not stop ingress");
    }

    private static final class RequestFactory
    {
        private static final long CURRENT_FREQUENCY = 451_000_000L;
        private static final long REST_FREQUENCY = 452_000_000L;
        private final Channel mParent;
        private final DMRTrafficChannelManager mOwner;

        private RequestFactory()
        {
            mParent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
            DecodeConfigDMR config = new DecodeConfigDMR();
            config.setChannelMode(DMRChannelMode.TRUNKED);
            config.setTrafficChannelPoolSize(0);
            mParent.setDecodeConfiguration(config);
            mOwner = new DMRTrafficChannelManager(mParent);
        }

        private DMRRestChannelHandoffRequest create(long generation)
        {
            return new DMRRestChannelHandoffRequest(mOwner, mParent, CURRENT_FREQUENCY,
                new DMRAbsoluteChannel(1, 1, REST_FREQUENCY, 0), generation);
        }
    }
}
