/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HistoryModuleTest
{
    @Test
    void neverExceedsConfiguredBound()
    {
        TestHistory history = new TestHistory(3);

        for(int x = 0; x < 10; x++)
        {
            history.receive(x);
        }

        assertEquals(List.of(7, 8, 9), history.getItems());
    }

    @Test
    void duplicateItemsAreBroadcastButNotStoredTwice()
    {
        TestHistory history = new TestHistory(3);
        AtomicInteger deliveries = new AtomicInteger();
        history.addListener(item -> deliveries.incrementAndGet());
        history.receive(1);
        history.receive(1);

        assertEquals(List.of(1), history.getItems());
        assertEquals(2, deliveries.get());
    }

    @Test
    void zeroCapacityRetainsNothingAndStillBroadcasts()
    {
        TestHistory history = new TestHistory(0);
        AtomicInteger deliveries = new AtomicInteger();
        history.addListener(item -> deliveries.incrementAndGet());
        history.receive(1);

        assertTrue(history.getItems().isEmpty());
        assertEquals(1, deliveries.get());
    }

    @Test
    void concurrentSnapshotsRemainBoundedAndConsistent() throws Exception
    {
        int capacity = 64;
        TestHistory history = new TestHistory(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> {
            await(start);

            for(int x = 0; x < 20_000; x++)
            {
                history.receive(x);
            }
        }));

        for(int reader = 0; reader < 4; reader++)
        {
            futures.add(executor.submit(() -> {
                await(start);

                for(int x = 0; x < 2_000; x++)
                {
                    List<Integer> snapshot = history.getItems();
                    assertTrue(snapshot.size() <= capacity);
                }
            }));
        }

        start.countDown();

        try
        {
            for(Future<?> future: futures)
            {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        finally
        {
            executor.shutdownNow();
        }

        assertEquals(capacity, history.getItems().size());
        assertEquals(19_999, history.getItems().getLast());
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            latch.await();
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static class TestHistory extends HistoryModule<Integer>
    {
        private TestHistory(int maximumHistorySize)
        {
            super(maximumHistorySize);
        }
    }
}
