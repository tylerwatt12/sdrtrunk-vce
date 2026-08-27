/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AudioCallIngressQueueTest
{
    @Test
    void sizeObservesSingleConsumerProgressAcrossThreads() throws Exception
    {
        AudioCallIngressQueue queue = new AudioCallIngressQueue(8, 2);

        for(int value = 0; value < 6; value++)
        {
            assertTrue(queue.offer(1, false, value, value));
        }

        CountDownLatch start = new CountDownLatch(1);
        Thread consumer = Thread.ofPlatform().start(() -> {
            try
            {
                start.await();

                while(queue.poll() != null)
                {
                    //Single consumer drains the published entries.
                }
            }
            catch(InterruptedException _)
            {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            while(queue.size() != 0)
            {
                Thread.onSpinWait();
            }
        });
        consumer.join(1_000L);
        assertFalse(consumer.isAlive());
    }

    @Test
    void ordinaryCommandsCannotConsumeLifecycleReserveAndAcceptedOrderIsStable()
    {
        AudioCallIngressQueue queue = new AudioCallIngressQueue(8, 2);

        for(int value = 0; value < 6; value++)
        {
            assertTrue(queue.offer(1, false, value, value));
        }

        assertFalse(queue.offer(1, false, 6, 6L));
        assertTrue(queue.offer(2, true, 6, 6L));
        assertTrue(queue.offer(2, true, 7, 7L));
        assertFalse(queue.offer(2, true, 8, 8L));
        assertEquals(8, queue.size());

        for(int value = 0; value < 8; value++)
        {
            AudioCallIngressQueue.Entry entry = queue.poll();
            assertEquals(value, entry.payload());
            assertEquals(value, entry.value());
        }

        assertEquals(0, queue.size());
        assertEquals(6, queue.regularCapacity());
    }

    @Test
    void contendedOffersUseBoundedAttemptsAndPublishOnlyCompleteUniqueEntries() throws Exception
    {
        int producerCount = 8;
        int offersPerProducer = 64;
        AudioCallIngressQueue queue = new AudioCallIngressQueue(1_024, 64);
        CountDownLatch ready = new CountDownLatch(producerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(producerCount);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for(int producer = 0; producer < producerCount; producer++)
        {
            int producerId = producer;
            Thread.ofPlatform().start(() -> {
                ready.countDown();

                try
                {
                    start.await();

                    for(int sequence = 0; sequence < offersPerProducer; sequence++)
                    {
                        long value = (long)producerId * offersPerProducer + sequence;

                        if(queue.offer(1, false, value, value))
                        {
                            accepted.incrementAndGet();
                        }
                        else
                        {
                            rejected.incrementAndGet();
                        }
                    }
                }
                catch(InterruptedException _)
                {
                    Thread.currentThread().interrupt();
                }
                finally
                {
                    finished.countDown();
                }
            });
        }

        assertTrue(ready.await(1, TimeUnit.SECONDS));
        long started = System.nanoTime();
        start.countDown();
        assertTrue(finished.await(2, TimeUnit.SECONDS), "Fixed-attempt producers must never wait for one another");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000L);
        assertEquals(producerCount * offersPerProducer, accepted.get() + rejected.get());
        assertEquals(accepted.get(), queue.size());
        Set<Long> published = new HashSet<>();
        AudioCallIngressQueue.Entry entry;

        while((entry = queue.poll()) != null)
        {
            assertEquals(entry.payload(), entry.value());
            assertTrue(published.add(entry.value()), "Each accepted producer command must publish exactly once");
        }

        assertEquals(accepted.get(), published.size());
    }
}
