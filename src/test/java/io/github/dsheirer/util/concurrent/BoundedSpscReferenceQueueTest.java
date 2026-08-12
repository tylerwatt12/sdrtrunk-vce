/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.util.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoundedSpscReferenceQueueTest
{
    @Test
    void preservesOrderAndPrimitiveMetadataAndRejectsWhenFull()
    {
        BoundedSpscReferenceQueue<String> queue = new BoundedSpscReferenceQueue<>(4);
        assertTrue(queue.offer("one", 11, 111));
        assertTrue(queue.offer("two", 22, 222));
        assertTrue(queue.offer("three", 33));
        assertTrue(queue.offer("four", 44));
        assertFalse(queue.offer("drop", 55));
        assertEquals(4, queue.size());

        assertEquals("one", queue.poll());
        assertEquals(11, queue.lastPolledMetadata());
        assertEquals(111, queue.lastPolledSecondaryMetadata());
        assertEquals("two", queue.poll());
        assertEquals(22, queue.lastPolledMetadata());
        assertEquals(222, queue.lastPolledSecondaryMetadata());
        assertEquals("three", queue.poll());
        assertEquals(33, queue.lastPolledMetadata());
        assertEquals("four", queue.poll());
        assertEquals(44, queue.lastPolledMetadata());
        assertNull(queue.poll());
        assertEquals(0, queue.size());
    }

    @Test
    void reusesEverySlotAfterConsumerProgress()
    {
        BoundedSpscReferenceQueue<Object> queue = new BoundedSpscReferenceQueue<>(2);

        for(int cycle = 0; cycle < 100; cycle++)
        {
            Object first = new Object();
            Object second = new Object();
            assertTrue(queue.offer(first));
            assertTrue(queue.offer(second));
            assertFalse(queue.offer(new Object()));
            assertEquals(first, queue.poll());
            assertEquals(second, queue.poll());
        }
    }
}
