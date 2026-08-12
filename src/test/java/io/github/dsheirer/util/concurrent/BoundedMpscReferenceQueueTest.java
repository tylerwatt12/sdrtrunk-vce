/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.util.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedMpscReferenceQueueTest
{
    @Test
    void rejectsWithoutReplacingWhenFull()
    {
        BoundedMpscReferenceQueue<Integer> queue = new BoundedMpscReferenceQueue<>(4);

        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertTrue(queue.offer(3));
        assertTrue(queue.offer(4));
        assertFalse(queue.offer(5));
        assertEquals(4, queue.size());
        assertEquals(1, queue.poll());
        assertEquals(2, queue.poll());
        assertEquals(3, queue.poll());
        assertEquals(4, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    void supportsConcurrentProducersWithoutBlockingOrDuplication() throws Exception
    {
        int producers = 4;
        int valuesPerProducer = 2_000;
        BoundedMpscReferenceQueue<Integer> queue = new BoundedMpscReferenceQueue<>(16_384);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(producers);
        AtomicInteger rejected = new AtomicInteger();
        Set<Integer> consumed = ConcurrentHashMap.newKeySet();

        for(int producer = 0; producer < producers; producer++)
        {
            int base = producer * valuesPerProducer;
            Thread.ofPlatform().start(() -> {
                try
                {
                    start.await();

                    for(int value = 0; value < valuesPerProducer; value++)
                    {
                        if(!queue.offer(base + value))
                        {
                            rejected.incrementAndGet();
                        }
                    }
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
                finally
                {
                    finished.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        Integer value;

        while((value = queue.poll()) != null)
        {
            assertTrue(consumed.add(value), "duplicate value " + value);
        }

        assertEquals(producers * valuesPerProducer - rejected.get(), consumed.size());
    }
}
