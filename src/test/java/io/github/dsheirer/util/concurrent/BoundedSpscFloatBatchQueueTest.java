/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.util.concurrent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoundedSpscFloatBatchQueueTest
{
    @Test
    void reusesPreallocatedBatchesAndDropsAtCapacity()
    {
        BoundedSpscFloatBatchQueue queue = new BoundedSpscFloatBatchQueue(2, 2);
        assertTrue(queue.offer(1.0f));
        assertTrue(queue.offer(2.0f));
        assertTrue(queue.offer(3.0f));
        assertTrue(queue.offer(4.0f));
        assertFalse(queue.offer(5.0f));

        float[] first = queue.poll();
        assertArrayEquals(new float[]{1.0f, 2.0f}, first);
        queue.release();
        float[] second = queue.poll();
        assertArrayEquals(new float[]{3.0f, 4.0f}, second);
        queue.release();

        assertTrue(queue.offer(5.0f));
        assertTrue(queue.offer(6.0f));
        float[] reused = queue.poll();
        assertSame(first, reused, "The symbol producer must reuse a preallocated batch instead of allocating");
        assertArrayEquals(new float[]{5.0f, 6.0f}, reused);
        queue.release();
    }
}
