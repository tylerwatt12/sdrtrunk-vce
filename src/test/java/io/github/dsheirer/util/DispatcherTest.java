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

package io.github.dsheirer.util;

import io.github.dsheirer.util.concurrent.ThreadQoS;
import io.github.dsheirer.util.concurrent.ThreadQoS.QoSClass;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DispatcherTest
{
    @Test
    public void sharedChannelWorkerUsesReceiverQoS() throws Exception
    {
        Dispatcher<Integer> dispatcher = new Dispatcher<>("shared receiver qos", 1);
        CountDownLatch processed = new CountDownLatch(1);
        AtomicReference<QoSClass> observed = new AtomicReference<>();
        dispatcher.setListener(value ->
        {
            observed.set(ThreadQoS.currentClass());
            processed.countDown();
        });

        try
        {
            dispatcher.start();
            dispatcher.receive(1);
            assertTrue(processed.await(2, TimeUnit.SECONDS));
            assertEquals(QoSClass.USER_INITIATED, observed.get());
        }
        finally
        {
            dispatcher.stop();
        }
    }

    @Test
    public void privateReceiverWorkerRequiresExplicitQoS() throws Exception
    {
        assertEquals(QoSClass.USER_INITIATED, observePrivateWorker(QoSClass.USER_INITIATED));
        assertEquals(null, observePrivateWorker(null));
    }

    @Test
    public void explicitQoSIsRejectedForTheAlreadyClassifiedSharedPool()
    {
        assertThrows(IllegalArgumentException.class, () -> new Dispatcher<Integer>("shared explicit qos", 1,
            Dispatcher.ExecutorType.SHARED, 2, ignored -> {}, QoSClass.UTILITY));
    }

    private QoSClass observePrivateWorker(QoSClass qosClass) throws Exception
    {
        Dispatcher<Integer> dispatcher = new Dispatcher<>("private receiver qos", 1,
            Dispatcher.ExecutorType.PRIVATE, 2, ignored -> {}, qosClass);
        CountDownLatch processed = new CountDownLatch(1);
        AtomicReference<QoSClass> observed = new AtomicReference<>();
        dispatcher.setListener(value ->
        {
            observed.set(ThreadQoS.currentClass());
            processed.countDown();
        });

        try
        {
            dispatcher.start();
            dispatcher.receive(1);
            assertTrue(processed.await(2, TimeUnit.SECONDS));
            return observed.get();
        }
        finally
        {
            dispatcher.stop();
        }
    }

    @Test
    public void boundedQueueDropsAndCleansUpTheOldestElement() throws Exception
    {
        List<Integer> received = new CopyOnWriteArrayList<>();
        List<Integer> discarded = new CopyOnWriteArrayList<>();
        CountDownLatch firstProcessing = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch processed = new CountDownLatch(3);
        Dispatcher<Integer> dispatcher = new Dispatcher<>("bounded dispatcher test", 1,
            Dispatcher.ExecutorType.PRIVATE, 2, discarded::add);

        dispatcher.setListener(value -> {
            received.add(value);

            if(value == 0)
            {
                firstProcessing.countDown();

                try
                {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }

            processed.countDown();
        });

        try
        {
            dispatcher.start();
            dispatcher.receive(0);
            assertTrue(firstProcessing.await(5, TimeUnit.SECONDS));

            dispatcher.receive(1);
            dispatcher.receive(2);
            dispatcher.receive(3);

            assertEquals(List.of(1), discarded);
            assertEquals(2, dispatcher.getQueueSize());
            assertEquals(2, dispatcher.getHighWaterQueueSize());
            assertEquals(2, dispatcher.getMaximumQueueSize());
            assertEquals(1, dispatcher.getDroppedElementCount());

            releaseFirst.countDown();
            assertTrue(processed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(0, 2, 3), received);
        }
        finally
        {
            releaseFirst.countDown();
            dispatcher.stop();
        }
    }

    @Test
    public void stopCleansUpQueuedElements() throws Exception
    {
        List<Integer> discarded = new CopyOnWriteArrayList<>();
        CountDownLatch firstProcessing = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Dispatcher<Integer> dispatcher = new Dispatcher<>("dispatcher stop cleanup test", 1,
            Dispatcher.ExecutorType.PRIVATE, 4, discarded::add);

        dispatcher.setListener(value -> {
            if(value == 0)
            {
                firstProcessing.countDown();

                try
                {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try
        {
            dispatcher.start();
            dispatcher.receive(0);
            assertTrue(firstProcessing.await(5, TimeUnit.SECONDS));
            dispatcher.receive(1);
            dispatcher.receive(2);
            dispatcher.stop();

            assertEquals(List.of(1, 2), discarded);
            assertEquals(0, dispatcher.getQueueSize());
            assertEquals(0, dispatcher.getDroppedElementCount());
        }
        finally
        {
            releaseFirst.countDown();
            dispatcher.stop();
        }
    }

    @Test
    public void stoppedDispatcherCleansUpNewElements()
    {
        List<Integer> discarded = new CopyOnWriteArrayList<>();
        Dispatcher<Integer> dispatcher = new Dispatcher<>("stopped dispatcher cleanup test", 1,
            Dispatcher.ExecutorType.PRIVATE, 4, discarded::add);

        dispatcher.receive(1);

        assertEquals(List.of(1), discarded);
        assertEquals(0, dispatcher.getQueueSize());
    }

    @Test
    public void restartNeverRunsTwoConsumerGenerationsConcurrently() throws Exception
    {
        Dispatcher<Integer> dispatcher = new Dispatcher<>("dispatcher restart isolation test", 1,
            Dispatcher.ExecutorType.PRIVATE, 4, ignored -> {});
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondProcessed = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        dispatcher.setListener(value -> {
            int concurrent = active.incrementAndGet();
            maximumActive.accumulateAndGet(concurrent, Math::max);

            try
            {
                if(value == 1)
                {
                    firstEntered.countDown();
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                else
                {
                    secondProcessed.countDown();
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                active.decrementAndGet();
            }
        });

        try
        {
            dispatcher.start();
            dispatcher.receive(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            dispatcher.stop();
            dispatcher.start();
            dispatcher.receive(2);
            Thread.sleep(50);
            assertEquals(1, maximumActive.get(), "a restarted generation must not overlap the prior callback");
            releaseFirst.countDown();
            assertTrue(secondProcessed.await(5, TimeUnit.SECONDS));
            assertEquals(1, maximumActive.get());
        }
        finally
        {
            releaseFirst.countDown();
            dispatcher.stop();
        }
    }

    @Test
    public void flushWaitsForTheActiveConsumerBeforeDeliveringQueuedElements() throws Exception
    {
        Dispatcher<Integer> dispatcher = new Dispatcher<>("dispatcher serialized flush test", 1,
            Dispatcher.ExecutorType.PRIVATE, 4, ignored -> {});
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondProcessed = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        dispatcher.setListener(value -> {
            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);

            try
            {
                if(value == 1)
                {
                    firstEntered.countDown();
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                else
                {
                    secondProcessed.countDown();
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                active.decrementAndGet();
            }
        });
        Thread flusher = new Thread(dispatcher::flushAndStop, "dispatcher flush race");

        try
        {
            dispatcher.start();
            dispatcher.receive(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            dispatcher.receive(2);
            flusher.start();
            Thread.sleep(50);
            assertEquals(1, maximumActive.get());
            assertEquals(1, secondProcessed.getCount(), "flush must wait instead of invoking the listener concurrently");
            releaseFirst.countDown();
            flusher.join(TimeUnit.SECONDS.toMillis(5));
            assertTrue(secondProcessed.await(5, TimeUnit.SECONDS));
            assertEquals(1, maximumActive.get());
        }
        finally
        {
            releaseFirst.countDown();
            flusher.join(TimeUnit.SECONDS.toMillis(5));
            dispatcher.stop();
        }
    }
}
