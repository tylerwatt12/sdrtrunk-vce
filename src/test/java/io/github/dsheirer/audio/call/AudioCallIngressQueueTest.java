/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AudioCallIngressQueueTest
{
    @Test
    void ordinaryCommandsCannotConsumeLifecycleReserveAndAcceptedOrderIsStable()
    {
        AudioCallIngressQueue queue = new AudioCallIngressQueue(8, 2);

        for(int value = 0; value < 6; value++)
        {
            assertTrue(queue.offer(1, false, value, value, 7L));
        }

        assertFalse(queue.offer(1, false, 6, 6L, 7L));
        assertTrue(queue.offer(2, true, 6, 6L, 7L));
        assertTrue(queue.offer(2, true, 7, 7L, 7L));
        assertFalse(queue.offer(2, true, 8, 8L, 7L));
        assertEquals(8, queue.size());

        for(int value = 0; value < 8; value++)
        {
            AudioCallIngressQueue.Entry entry = queue.poll();
            assertEquals(value, entry.payload());
            assertEquals(value, entry.value());
            assertEquals(7L, entry.generation());
        }

        assertEquals(0, queue.size());
        assertEquals(6, queue.regularCapacity());
    }
}
