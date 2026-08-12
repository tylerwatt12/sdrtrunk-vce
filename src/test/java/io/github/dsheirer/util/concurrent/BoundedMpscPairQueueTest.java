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

import org.junit.jupiter.api.Test;

class BoundedMpscPairQueueTest
{
    @Test
    void publishesBothReferencesAndDropsWhenFull()
    {
        BoundedMpscPairQueue<String,Integer> queue = new BoundedMpscPairQueue<>(2);

        assertTrue(queue.offer("one", 1));
        assertTrue(queue.offer("two", 2));
        assertFalse(queue.offer("three", 3));

        assertEquals(new BoundedMpscPairQueue.Entry<>("one", 1), queue.poll());
        assertEquals(new BoundedMpscPairQueue.Entry<>("two", 2), queue.poll());
        assertNull(queue.poll());
    }
}
