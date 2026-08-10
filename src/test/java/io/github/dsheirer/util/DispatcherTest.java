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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
