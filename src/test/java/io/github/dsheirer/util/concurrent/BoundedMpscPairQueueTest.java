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

    @Test
    void carriesAnOptionalPrimitiveStampWithoutChangingTheDefaultOffer()
    {
        BoundedMpscPairQueue<String,Integer> queue = new BoundedMpscPairQueue<>(2);

        assertTrue(queue.offer("stamped", 1, 42));
        assertTrue(queue.offer("default", 2));
        assertEquals(new BoundedMpscPairQueue.Entry<>("stamped", 1, 42), queue.poll());
        assertEquals(new BoundedMpscPairQueue.Entry<>("default", 2, 0), queue.poll());
    }

    @Test
    void retainsBothPrimitiveStampsAcrossOverflowAndCellReuseWithoutLeakingOldOrigins()
    {
        BoundedMpscPairQueue<String,Integer> queue = new BoundedMpscPairQueue<>(2);
        assertTrue(queue.offer("old", 1, 7, 451_012_500));
        assertTrue(queue.offer("other", 2, 8, 452_012_500));
        assertFalse(queue.offer("overflow", 3, 9, 999));
        assertEquals(new BoundedMpscPairQueue.Entry<>("old", 1, 7, 451_012_500), queue.poll());
        assertTrue(queue.offer("default", 4));
        assertEquals(new BoundedMpscPairQueue.Entry<>("other", 2, 8, 452_012_500), queue.poll());
        assertTrue(queue.offer("single-stamp", 5, 11));
        assertEquals(new BoundedMpscPairQueue.Entry<>("default", 4), queue.poll());
        assertEquals(new BoundedMpscPairQueue.Entry<>("single-stamp", 5, 11), queue.poll());
        assertNull(queue.poll());
    }
}
