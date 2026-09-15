/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast.radioresolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RadioResolveClockSynchronizerTest
{
    @Test
    void goodRoundTripProducesBoundedOffsetAndRefreshesBeforeExpiry()
    {
        MutableTime time = new MutableTime(1_000_000L, TimeUnit.SECONDS.toNanos(10L));
        RadioResolveClockSynchronizer synchronizer = time.synchronizer();
        RadioResolveClockSynchronizer.RequestTiming request = synchronizer.beginRequest();

        time.advance(10L);
        assertTrue(synchronizer.completeRequest(request, 1_000_255L));

        RadioResolveClockSynchronizer.ClockProof proof = synchronizer.currentProof();
        assertEquals(250L, proof.offsetMilliseconds());
        assertEquals(6L, proof.uncertaintyMilliseconds());
        assertEquals(1_000_260L, proof.adjust(1_000_010L));
        assertFalse(synchronizer.needsRefresh());

        time.advance(TimeUnit.SECONDS.toMillis(30L));
        assertTrue(synchronizer.needsRefresh());
        assertTrue(synchronizer.currentProof() != null,
            "refresh begins before the existing proof expires");
    }

    @Test
    void missingAndStaleProofFailClosed()
    {
        MutableTime time = new MutableTime(1_000_000L, TimeUnit.SECONDS.toNanos(10L));
        RadioResolveClockSynchronizer synchronizer = time.synchronizer();
        assertNull(synchronizer.currentProof());
        assertTrue(synchronizer.needsRefresh());

        RadioResolveClockSynchronizer.RequestTiming request = synchronizer.beginRequest();
        time.advance(4L);
        assertTrue(synchronizer.completeRequest(request, 1_000_102L));
        assertTrue(synchronizer.currentProof() != null);

        time.advance(TimeUnit.SECONDS.toMillis(61L));
        assertNull(synchronizer.currentProof());
    }

    @Test
    void remoteRoundTripWithinDedupeToleranceProducesProof()
    {
        MutableTime remote = new MutableTime(1_000_000L, TimeUnit.SECONDS.toNanos(10L));
        RadioResolveClockSynchronizer synchronizer = remote.synchronizer();
        RadioResolveClockSynchronizer.RequestTiming request = synchronizer.beginRequest();

        remote.advance(800L);
        assertTrue(synchronizer.completeRequest(request, 1_001_400L));

        RadioResolveClockSynchronizer.ClockProof proof = synchronizer.currentProof();
        assertEquals(1_000L, proof.offsetMilliseconds());
        assertEquals(401L, proof.uncertaintyMilliseconds());
    }

    @Test
    void excessiveRoundTripOrWallClockMovementCannotBecomeProof()
    {
        MutableTime slow = new MutableTime(1_000_000L, TimeUnit.SECONDS.toNanos(10L));
        RadioResolveClockSynchronizer slowSynchronizer = slow.synchronizer();
        RadioResolveClockSynchronizer.RequestTiming slowRequest = slowSynchronizer.beginRequest();
        slow.advance(1_000L);
        assertFalse(slowSynchronizer.completeRequest(slowRequest, 1_000_500L));
        assertNull(slowSynchronizer.currentProof(),
            "an uncertainty over 500 ms could consume too much of the two-second dedupe window");

        MutableTime stepped = new MutableTime(2_000_000L, TimeUnit.SECONDS.toNanos(20L));
        RadioResolveClockSynchronizer steppedSynchronizer = stepped.synchronizer();
        RadioResolveClockSynchronizer.RequestTiming steppedRequest = steppedSynchronizer.beginRequest();
        stepped.monotonicNanoseconds.addAndGet(TimeUnit.MILLISECONDS.toNanos(10L));
        stepped.epochMilliseconds.addAndGet(1_000L);
        assertFalse(steppedSynchronizer.completeRequest(steppedRequest, 2_000_005L));
        assertNull(steppedSynchronizer.currentProof());
    }

    @Test
    void impreciseNewSampleDoesNotDestroyStillFreshProof()
    {
        MutableTime time = new MutableTime(3_000_000L, TimeUnit.SECONDS.toNanos(30L));
        RadioResolveClockSynchronizer synchronizer = time.synchronizer();
        RadioResolveClockSynchronizer.RequestTiming good = synchronizer.beginRequest();
        time.advance(6L);
        assertTrue(synchronizer.completeRequest(good, 3_000_103L));
        assertEquals(100L, synchronizer.currentProof().offsetMilliseconds());

        RadioResolveClockSynchronizer.RequestTiming slow = synchronizer.beginRequest();
        time.advance(1_000L);
        assertFalse(synchronizer.completeRequest(slow, 3_000_603L));
        assertEquals(100L, synchronizer.currentProof().offsetMilliseconds());
    }

    private static final class MutableTime
    {
        private final AtomicLong epochMilliseconds;
        private final AtomicLong monotonicNanoseconds;

        private MutableTime(long epochMilliseconds, long monotonicNanoseconds)
        {
            this.epochMilliseconds = new AtomicLong(epochMilliseconds);
            this.monotonicNanoseconds = new AtomicLong(monotonicNanoseconds);
        }

        private RadioResolveClockSynchronizer synchronizer()
        {
            return new RadioResolveClockSynchronizer(epochMilliseconds::get, monotonicNanoseconds::get);
        }

        private void advance(long milliseconds)
        {
            epochMilliseconds.addAndGet(milliseconds);
            monotonicNanoseconds.addAndGet(TimeUnit.MILLISECONDS.toNanos(milliseconds));
        }
    }
}
