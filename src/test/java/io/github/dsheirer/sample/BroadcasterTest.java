/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BroadcasterTest
{
    @Test
    void guardedBroadcastUsesTheListenerSnapshotCapturedBeforeItsGuard()
    {
        Broadcaster<Integer> broadcaster = new Broadcaster<>();
        AtomicInteger originalCalls = new AtomicInteger();
        AtomicInteger replacementCalls = new AtomicInteger();
        Listener<Integer> original = ignored -> originalCalls.incrementAndGet();
        Listener<Integer> replacement = ignored -> replacementCalls.incrementAndGet();
        broadcaster.addListener(original);

        assertEquals(0, broadcaster.broadcastIf(1, () -> {
            broadcaster.removeListener(original);
            broadcaster.addListener(replacement);
            return true;
        }));
        assertEquals(1, originalCalls.get());
        assertEquals(0, replacementCalls.get());

        broadcaster.broadcast(2);
        assertEquals(1, originalCalls.get());
        assertEquals(1, replacementCalls.get());
    }

    @Test
    void oneNonfatalListenerFailureDoesNotBlockRemainingListeners()
    {
        Broadcaster<Integer> broadcaster = new Broadcaster<>();
        AtomicInteger healthyCalls = new AtomicInteger();
        broadcaster.addListener(ignored -> { throw new AssertionError("injected listener failure"); });
        broadcaster.addListener(ignored -> healthyCalls.incrementAndGet());

        assertEquals(1, broadcaster.broadcastIf(1, () -> true));
        assertEquals(1, healthyCalls.get());
    }
}
