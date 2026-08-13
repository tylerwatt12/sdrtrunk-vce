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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DispatcherTest
{
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
            assertEquals(1, dispatcher.getDroppedElementCount());
            Dispatcher.Metrics metrics = dispatcher.getQueueMetrics();
            assertEquals(2, metrics.waitingCount());
            assertEquals(1, metrics.inFlightCount());
            assertEquals(3, metrics.outstandingCount());
            assertTrue(metrics.callbackActive());
            assertTrue(metrics.callbackAgeNanos() >= 0);
            assertEquals(4, metrics.receivedCount());
            assertEquals(4, metrics.acceptedCount());
            assertEquals(1, metrics.discardedCount());
            assertEquals(1, metrics.droppedCount());
            assertTrue(metrics.highWaterCount() >= 3);
            assertFalse(metrics.unbounded());

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
    public void unboundedQueueRetainsAllElementsAndReportsWorkOutsideTheQueue() throws Exception
    {
        List<Integer> discarded = new CopyOnWriteArrayList<>();
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessing = new CountDownLatch(1);
        CountDownLatch allProcessed = new CountDownLatch(7);
        Dispatcher<Integer> dispatcher = new Dispatcher<>("unbounded dispatcher test", 1,
            Dispatcher.ExecutorType.PRIVATE, 0, discarded::add);

        dispatcher.setListener(value -> {
            if(value == 0)
            {
                processingStarted.countDown();

                try
                {
                    releaseProcessing.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }

            allProcessed.countDown();
        });

        try
        {
            dispatcher.start();
            dispatcher.receive(0);
            assertTrue(processingStarted.await(5, TimeUnit.SECONDS));

            for(int x = 1; x <= 6; x++)
            {
                dispatcher.receive(x);
            }

            Dispatcher.Metrics blocked = dispatcher.getQueueMetrics();
            assertTrue(blocked.unbounded());
            assertEquals(6, blocked.waitingCount());
            assertEquals(1, blocked.inFlightCount());
            assertEquals(7, blocked.outstandingCount());
            assertTrue(blocked.callbackActive());
            assertEquals(0, blocked.droppedCount());
            assertEquals(List.of(), discarded);

            releaseProcessing.countDown();
            assertTrue(allProcessed.await(5, TimeUnit.SECONDS));
            Dispatcher.Metrics completed = dispatcher.getQueueMetrics();
            assertEquals(0, completed.outstandingCount());
            assertEquals(7, completed.processedCount());
            assertFalse(completed.callbackActive());
            assertTrue(completed.lastCompletionAgeNanos() >= 0);
        }
        finally
        {
            releaseProcessing.countDown();
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
            Dispatcher.Metrics metrics = dispatcher.getQueueMetrics();
            assertEquals(1, metrics.inFlightCount());
            assertEquals(2, metrics.discardedCount());
        }
        finally
        {
            releaseFirst.countDown();
            dispatcher.stop();
        }
    }

    @Test
    public void flushAndStopDoesNotOverlapAnActiveCallback() throws Exception
    {
        List<Integer> received = new CopyOnWriteArrayList<>();
        List<Integer> discarded = new CopyOnWriteArrayList<>();
        CountDownLatch firstProcessing = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Dispatcher<Integer> dispatcher = new Dispatcher<>("dispatcher flush metrics test", 1,
            Dispatcher.ExecutorType.PRIVATE, 4, discarded::add);

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
        });

        try
        {
            dispatcher.start();
            dispatcher.receive(0);
            assertTrue(firstProcessing.await(5, TimeUnit.SECONDS));
            dispatcher.receive(1);
            dispatcher.receive(2);
            dispatcher.flushAndStop();

            Dispatcher.Metrics stopped = dispatcher.getQueueMetrics();
            assertEquals(0, stopped.waitingCount());
            assertEquals(1, stopped.inFlightCount());
            assertEquals(0, stopped.processedCount());
            assertEquals(List.of(0), received);
            assertEquals(List.of(1, 2), discarded);

            releaseFirst.countDown();
            assertTrue(waitFor(() -> dispatcher.getQueueMetrics().inFlightCount() == 0));
            assertEquals(1, dispatcher.getQueueMetrics().processedCount());
        }
        finally
        {
            releaseFirst.countDown();
            dispatcher.stop();
        }
    }

    @Test
    public void stoppedRunCannotOverlapRestartOrProcessItsRemainingLocalDrain() throws Exception
    {
        List<Integer> received = new CopyOnWriteArrayList<>();
        List<Integer> discarded = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch oldRunBlocked = new CountDownLatch(1);
        CountDownLatch releaseOldRun = new CountDownLatch(1);
        CountDownLatch restartedProcessed = new CountDownLatch(1);
        AtomicBoolean restartedOverlappedOldCallback = new AtomicBoolean();
        Dispatcher<Integer> dispatcher = new Dispatcher<>("dispatcher restart epoch test", 1,
            Dispatcher.ExecutorType.PRIVATE, 10, discarded::add);

        dispatcher.setListener(value -> {
            received.add(value);

            try
            {
                if(value == -1)
                {
                    firstStarted.countDown();
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                else if(value == 0)
                {
                    oldRunBlocked.countDown();
                    releaseOldRun.await(5, TimeUnit.SECONDS);
                }
                else if(value == 100)
                {
                    restartedOverlappedOldCallback.set(releaseOldRun.getCount() > 0);
                    restartedProcessed.countDown();
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        });

        try
        {
            dispatcher.start();
            dispatcher.receive(-1);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            dispatcher.receive(0);
            dispatcher.receive(1);
            dispatcher.receive(2);
            releaseFirst.countDown();
            assertTrue(oldRunBlocked.await(5, TimeUnit.SECONDS));

            //The old processor has drained 0, 1 and 2 into its local list and is blocked on 0.
            assertEquals(3, dispatcher.getQueueMetrics().inFlightCount());
            dispatcher.stop();
            dispatcher.start();
            dispatcher.receive(100);

            assertEquals(1, dispatcher.getQueueMetrics().waitingCount());
            assertEquals(3, dispatcher.getQueueMetrics().inFlightCount());
            assertEquals(List.of(-1, 0), received);

            releaseOldRun.countDown();
            assertTrue(restartedProcessed.await(5, TimeUnit.SECONDS));
            assertFalse(restartedOverlappedOldCallback.get());
            assertEquals(List.of(-1, 0, 100), received);
            assertEquals(List.of(1, 2), discarded);
            assertTrue(waitFor(() -> dispatcher.getQueueMetrics().outstandingCount() == 0));
        }
        finally
        {
            releaseFirst.countDown();
            releaseOldRun.countDown();
            dispatcher.stop();
        }
    }

    @Test
    public void lateProducerPerformsOnlyBoundedCleanupWhileStopDrainsALargeQueue() throws Exception
    {
        int capacity = 5_000;
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessing = new CountDownLatch(1);
        CountDownLatch producerOverflowCleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseProducerOverflowCleanup = new CountDownLatch(1);
        CountDownLatch stopCleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseStopCleanup = new CountDownLatch(1);
        AtomicInteger producerCleanupCount = new AtomicInteger();
        AtomicInteger stopCleanupCount = new AtomicInteger();
        AtomicInteger totalCleanupCount = new AtomicInteger();
        Dispatcher<Integer> dispatcher = new Dispatcher<>("large stop race test", 1,
            Dispatcher.ExecutorType.PRIVATE, capacity, value -> {
                totalCleanupCount.incrementAndGet();
                String threadName = Thread.currentThread().getName();

                try
                {
                    if(threadName.equals("late receiver producer"))
                    {
                        if(producerCleanupCount.incrementAndGet() == 1)
                        {
                            producerOverflowCleanupStarted.countDown();
                            releaseProducerOverflowCleanup.await(5, TimeUnit.SECONDS);
                        }
                    }
                    else if(threadName.equals("dispatcher stop caller"))
                    {
                        if(stopCleanupCount.incrementAndGet() == 1)
                        {
                            stopCleanupStarted.countDown();
                            releaseStopCleanup.await(5, TimeUnit.SECONDS);
                        }
                    }
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            });

        dispatcher.setListener(value -> {
            if(value == -1)
            {
                processingStarted.countDown();

                try
                {
                    releaseProcessing.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Thread lateProducer = null;
        Thread stopCaller = null;

        try
        {
            dispatcher.start();
            dispatcher.receive(-1);
            assertTrue(processingStarted.await(5, TimeUnit.SECONDS));

            for(int value = 0; value < capacity; value++)
            {
                dispatcher.receive(value);
            }

            lateProducer = new Thread(() -> dispatcher.receive(capacity), "late receiver producer");
            lateProducer.start();
            assertTrue(producerOverflowCleanupStarted.await(5, TimeUnit.SECONDS));

            stopCaller = new Thread(dispatcher::stop, "dispatcher stop caller");
            stopCaller.start();
            assertTrue(stopCleanupStarted.await(5, TimeUnit.SECONDS));

            long releaseNanos = System.nanoTime();
            releaseProducerOverflowCleanup.countDown();
            lateProducer.join(TimeUnit.SECONDS.toMillis(2));
            long producerCompletionMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - releaseNanos);

            assertFalse(lateProducer.isAlive());
            assertTrue(producerCompletionMillis < 1_000,
                "late producer must not drain the retained old queue");
            assertTrue(producerCleanupCount.get() <= 2,
                "one overflow cleanup plus at most one lifecycle-race cleanup is bounded");

            releaseStopCleanup.countDown();
            stopCaller.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(stopCaller.isAlive());
            assertEquals(capacity + 1, totalCleanupCount.get());
            assertEquals(1, dispatcher.getDroppedElementCount());

            releaseProcessing.countDown();
            assertTrue(waitFor(() -> dispatcher.getQueueMetrics().outstandingCount() == 0));
        }
        finally
        {
            releaseProducerOverflowCleanup.countDown();
            releaseStopCleanup.countDown();
            releaseProcessing.countDown();

            if(lateProducer != null)
            {
                lateProducer.join(TimeUnit.SECONDS.toMillis(5));
            }

            if(stopCaller != null)
            {
                stopCaller.join(TimeUnit.SECONDS.toMillis(5));
            }

            dispatcher.stop();
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while(System.nanoTime() < deadline)
        {
            if(condition.getAsBoolean())
            {
                return true;
            }

            Thread.sleep(5);
        }

        return condition.getAsBoolean();
    }
}
