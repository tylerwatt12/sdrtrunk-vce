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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DispatcherTest
{
    @Test
    public void boundedArrivalQueueRunsOffProducerAndCleansUpTheOldestElement() throws Exception
    {
        List<Integer> received = new CopyOnWriteArrayList<>();
        List<Integer> discarded = new CopyOnWriteArrayList<>();
        CountDownLatch firstProcessing = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch processed = new CountDownLatch(3);
        Thread producerThread = Thread.currentThread();
        AtomicReference<Thread> consumerThread = new AtomicReference<>();
        Dispatcher<Integer> dispatcher = new Dispatcher<>("bounded dispatcher test", 1,
            Dispatcher.ExecutorType.PRIVATE, 2, discarded::add, Dispatcher.Scheduling.ON_ARRIVAL);

        dispatcher.setListener(value -> {
            consumerThread.compareAndSet(null, Thread.currentThread());
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
            assertNotSame(producerThread, consumerThread.get(),
                "arrival processing must never run on the receiver producer");

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
    public void arrivalProcessingReusesOnePrivateWorker() throws Exception
    {
        Dispatcher<Integer> dispatcher = new Dispatcher<>("arrival worker reuse test", 1,
            Dispatcher.ExecutorType.PRIVATE, 2, ignored -> {}, Dispatcher.Scheduling.ON_ARRIVAL);
        AtomicReference<Thread> firstWorker = new AtomicReference<>();
        AtomicReference<Thread> secondWorker = new AtomicReference<>();
        CountDownLatch firstProcessed = new CountDownLatch(1);
        CountDownLatch secondProcessed = new CountDownLatch(1);

        dispatcher.setListener(value -> {
            if(value == 1)
            {
                firstWorker.set(Thread.currentThread());
                firstProcessed.countDown();
            }
            else
            {
                secondWorker.set(Thread.currentThread());
                secondProcessed.countDown();
            }
        });

        try
        {
            dispatcher.start();
            dispatcher.receive(1);
            assertTrue(firstProcessed.await(5, TimeUnit.SECONDS));
            dispatcher.receive(2);
            assertTrue(secondProcessed.await(5, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), firstWorker.get());
            assertSame(firstWorker.get(), secondWorker.get(),
                "separate arrivals must reuse the same permanent worker");
        }
        finally
        {
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
            Dispatcher.ExecutorType.PRIVATE, 4, discarded::add, Dispatcher.Scheduling.ON_ARRIVAL);

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
            Dispatcher.ExecutorType.PRIVATE, 4, ignored -> {}, Dispatcher.Scheduling.ON_ARRIVAL);
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
