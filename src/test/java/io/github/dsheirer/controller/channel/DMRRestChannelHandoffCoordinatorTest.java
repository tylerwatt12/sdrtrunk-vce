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

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DMRRestChannelHandoffRequest;
import io.github.dsheirer.module.decode.dmr.DMRTrafficChannelManager;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.DMRAbsoluteChannel;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.time.Duration;
import java.util.List;
import java.util.Set;
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
        RequestFactory requests = new RequestFactory("A");
        DMRRestChannelHandoffRequest request = requests.nominate(1, 452_000_000L);
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
        DMRRestChannelHandoffCoordinator coordinator = new DMRRestChannelHandoffCoordinator(threadFactory,
            () -> List.of(requests.owner()), offered ->
            {
                handlerThread.set(Thread.currentThread());
                handlerStarted.countDown();
                releaseHandler.await();
                handled.countDown();
            });

        try
        {
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "worker was not prestarted");
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
            {
                producerThread.set(Thread.currentThread());
                assertTrue(coordinator.offer(request));
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
    void saturationCoalescesLatestPerOwnerWithoutCallerRunsOrCrossSiteLoss() throws Exception
    {
        RequestFactory siteA = new RequestFactory("A");
        RequestFactory siteB = new RequestFactory("B");
        RequestFactory siteC = new RequestFactory("C");
        DMRRestChannelHandoffRequest firstA = siteA.nominate(1, 452_000_000L);
        CountDownLatch firstHandlerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstHandler = new CountDownLatch(1);
        CountDownLatch remainingHandled = new CountDownLatch(3);
        AtomicInteger invocations = new AtomicInteger();
        List<DMRRestChannelHandoffRequest> handled = new CopyOnWriteArrayList<>();
        AtomicReference<Thread> producerThread = new AtomicReference<>();
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        DMRRestChannelHandoffCoordinator coordinator = new DMRRestChannelHandoffCoordinator(
            new ObserverThreadFactory("DMR handoff saturation test"),
            () -> List.of(siteA.owner(), siteB.owner(), siteC.owner()), request ->
            {
                handlerThread.set(Thread.currentThread());

                if(invocations.incrementAndGet() == 1)
                {
                    firstHandlerStarted.countDown();
                    releaseFirstHandler.await();
                }

                handled.add(request);

                if(request != firstA)
                {
                    remainingHandled.countDown();
                }
            });

        try
        {
            assertTrue(coordinator.offer(firstA));
            assertTrue(firstHandlerStarted.await(5, TimeUnit.SECONDS), "first handler did not block");
            DMRRestChannelHandoffRequest latestA = siteA.nominate(3, 453_000_000L);
            DMRRestChannelHandoffRequest onlyB = siteB.nominate(5, 454_000_000L);
            DMRRestChannelHandoffRequest onlyC = siteC.nominate(7, 455_000_000L);

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
            {
                producerThread.set(Thread.currentThread());

                for(int x = 0; x < 10_000; x++)
                {
                    assertTrue(coordinator.offer(latestA));
                }

                assertTrue(coordinator.offer(onlyB));
                assertTrue(coordinator.offer(onlyC));
            });

            assertEquals(1, invocations.get(), "coalesced work ran on a producer");
            assertNotSame(producerThread.get(), handlerThread.get());
            assertEquals(0, coordinator.getDroppedCount());
            assertTrue(coordinator.getCoalescedCount() > 0);

            releaseFirstHandler.countDown();
            assertTrue(remainingHandled.await(5, TimeUnit.SECONDS), "latest per-site requests did not drain");
            assertEquals(firstA, handled.getFirst());
            assertEquals(4, handled.size());
            assertEquals(Set.of(firstA, latestA, onlyB, onlyC), Set.copyOf(handled));
        }
        finally
        {
            releaseFirstHandler.countDown();
            coordinator.close();
        }
    }

    @Test
    void closeRejectsNewWorkAndJoinsAfterDrainingAcceptedLatestValues() throws Exception
    {
        RequestFactory siteA = new RequestFactory("A");
        RequestFactory siteB = new RequestFactory("B");
        DMRRestChannelHandoffRequest first = siteA.nominate(1, 452_000_000L);
        DMRRestChannelHandoffRequest second = siteB.nominate(3, 453_000_000L);
        CountDownLatch firstHandlerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstHandler = new CountDownLatch(1);
        CountDownLatch handled = new CountDownLatch(2);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        DMRRestChannelHandoffCoordinator coordinator = new DMRRestChannelHandoffCoordinator(
            new ObserverThreadFactory("DMR handoff close test"), () -> List.of(siteA.owner(), siteB.owner()), request ->
            {
                workerThread.set(Thread.currentThread());

                if(request == first)
                {
                    firstHandlerStarted.countDown();
                    releaseFirstHandler.await();
                }

                handled.countDown();
            });

        AtomicReference<Thread> closerReference = new AtomicReference<>();

        try
        {
            assertTrue(coordinator.offer(first));
            assertTrue(firstHandlerStarted.await(5, TimeUnit.SECONDS), "first handler did not block");
            assertTrue(coordinator.offer(second));
            Thread closer = Thread.ofPlatform().name("DMR handoff test closer").start(() ->
            {
                coordinator.close();
                closeReturned.countDown();
            });
            closerReference.set(closer);
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS),
                "close returned before accepted work drained");
            awaitNotAccepting(coordinator);
            assertFalse(coordinator.offer(second));
            assertEquals(1, coordinator.getDroppedCount());
            releaseFirstHandler.countDown();
            assertTrue(closeReturned.await(5, TimeUnit.SECONDS), "close did not join the worker");
            assertTrue(handled.await(5, TimeUnit.SECONDS), "accepted work was not drained");
            closer.join(1_000);
            assertFalse(closer.isAlive());
            assertFalse(workerThread.get().isAlive());
            assertTrue(coordinator.isClosed());
            assertFalse(coordinator.offer(second));
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
        RequestFactory siteA = new RequestFactory("A");
        RequestFactory siteB = new RequestFactory("B");
        DMRRestChannelHandoffRequest first = siteA.nominate(1, 452_000_000L);
        DMRRestChannelHandoffRequest second = siteB.nominate(3, 453_000_000L);
        CountDownLatch secondHandled = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        DMRRestChannelHandoffCoordinator coordinator = new DMRRestChannelHandoffCoordinator(
            new ObserverThreadFactory("DMR handoff failure test"), () -> List.of(siteA.owner(), siteB.owner()), request ->
            {
                if(invocations.incrementAndGet() == 1)
                {
                    throw new IllegalStateException("expected test failure");
                }

                secondHandled.countDown();
            });

        try
        {
            assertTrue(coordinator.offer(first));
            assertTrue(coordinator.offer(second));
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
        private final Channel mParent;
        private final DMRTrafficChannelManager mOwner;
        private final RequestSubscriber mSubscriber = new RequestSubscriber();

        private RequestFactory(String name)
        {
            mParent = new Channel("DMR Site " + name, Channel.ChannelType.STANDARD);
            DecodeConfigDMR config = new DecodeConfigDMR();
            config.setChannelMode(DMRChannelMode.TRUNKED);
            config.setTrafficChannelPoolSize(1);
            mParent.setDecodeConfiguration(config);
            mOwner = new DMRTrafficChannelManager(mParent);
            EventBus eventBus = new EventBus();
            eventBus.register(mSubscriber);
            mOwner.setInterModuleEventBus(eventBus);
            mOwner.setCurrentControlFrequency(CURRENT_FREQUENCY, mParent);
        }

        private DMRTrafficChannelManager owner()
        {
            return mOwner;
        }

        private DMRRestChannelHandoffRequest nominate(int channelNumber, long restFrequency)
        {
            int previousCount = mSubscriber.mRequests.size();
            mOwner.requestRestChannelHandoff(mParent, CURRENT_FREQUENCY,
                new DMRAbsoluteChannel(channelNumber, 1, restFrequency, 0));
            assertEquals(previousCount + 1, mSubscriber.mRequests.size());
            return mSubscriber.mRequests.getLast();
        }
    }

    private static final class RequestSubscriber
    {
        private final List<DMRRestChannelHandoffRequest> mRequests = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(DMRRestChannelHandoffRequest request)
        {
            mRequests.add(request);
        }
    }
}
