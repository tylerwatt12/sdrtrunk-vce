/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.Test;

class BoundedSwingHistoryHandoffTest
{
    @Test
    void blockedEdtBoundsIngressAndNeverProjectsOnProducer() throws Exception
    {
        AtomicInteger projected = new AtomicInteger();
        AtomicBoolean projectedOnlyOnEdt = new AtomicBoolean(true);
        BoundedSwingHistoryHandoff<String,Integer> handoff = new BoundedSwingHistoryHandoff<>(8,
            (history, item, generation) -> {
                projectedOnlyOnEdt.compareAndSet(true, EventQueue.isDispatchThread());
                projected.incrementAndGet();
            });
        Timer[] timerReference = new Timer[1];
        SwingUtilities.invokeAndWait(() -> {
            timerReference[0] = new Timer(10, event -> handoff.drain());
            timerReference[0].setCoalesce(true);
            timerReference[0].start();
        });
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        EventQueue.invokeLater(() -> {
            edtBlocked.countDown();

            try
            {
                releaseEdt.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtBlocked.await(5, TimeUnit.SECONDS));
        Thread[] producers = new Thread[4];

        for(int producer = 0; producer < producers.length; producer++)
        {
            int producerNumber = producer;
            producers[producer] = new Thread(() -> {
                String history = "history-" + producerNumber;

                for(int item = 0; item < 10_000; item++)
                {
                    handoff.offer(history, item, 1);
                }
            }, "test-receiver-producer-" + producer);
        }

        try
        {
            for(Thread producer: producers)
            {
                producer.start();
            }

            for(Thread producer: producers)
            {
                producer.join(2_000);
                assertFalse(producer.isAlive(), "bounded producer callback must not wait for the blocked EDT");
            }

            assertTrue(handoff.size() <= 8);
            assertTrue(handoff.getDroppedItemCount() > 0);
            assertEquals(0, projected.get(), "projection must not run on the receiver producer");
        }
        finally
        {
            releaseEdt.countDown();
        }

        SwingUtilities.invokeAndWait(() -> {
            handoff.drain();
            timerReference[0].stop();
        });
        assertTrue(projected.get() > 0);
        assertTrue(projectedOnlyOnEdt.get());
    }

    @Test
    void eachTimerTickDrainsAtMostCapacityAndLeavesNewArrivalForNextTick()
    {
        List<Integer> handled = new java.util.ArrayList<>();
        @SuppressWarnings("unchecked")
        BoundedSwingHistoryHandoff<String,Integer>[] reference = new BoundedSwingHistoryHandoff[1];
        reference[0] = new BoundedSwingHistoryHandoff<>(2, (history, item, generation) -> {
            handled.add(item);

            if(item == 1)
            {
                assertTrue(reference[0].offer("history", 3, generation));
            }
        });
        BoundedSwingHistoryHandoff<String,Integer> handoff = reference[0];

        assertTrue(handoff.offer("history", 1, 7));
        assertTrue(handoff.offer("history", 2, 7));
        assertFalse(handoff.offer("history", 99, 7));

        handoff.drain();
        assertEquals(List.of(1, 2), handled);
        assertEquals(1, handoff.size());

        handoff.drain();
        assertEquals(List.of(1, 2, 3), handled);
        assertEquals(0, handoff.size());
        assertEquals(1, handoff.getDroppedItemCount());
    }
}
