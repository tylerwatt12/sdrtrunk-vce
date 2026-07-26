/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.metadata.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StableFactTrackerTest
{
    private static final FactConfirmationPolicy GUARDED =
        new FactConfirmationPolicy(2, 10, 100, false);

    @Test
    void promotesOnlyAfterMatchingCountAndSpan()
    {
        StableFactTracker<Value,Integer> tracker = new StableFactTracker<>(Value::key);

        assertEquals(StableFactTracker.Result.NONE, tracker.observe(new Value(1, "first"), 100, GUARDED,
            ignored -> true));
        assertEquals(StableFactTracker.Result.NONE, tracker.observe(new Value(1, "too soon"), 105, GUARDED,
            ignored -> true));
        assertFalse(tracker.hasStableValue());
        assertEquals(StableFactTracker.Result.PROMOTED, tracker.observe(new Value(1, "latest"), 110, GUARDED,
            ignored -> true));
        assertEquals("latest", tracker.getStableValue().label());
    }

    @Test
    void expiresCandidateAndDoesNotCountOutOfOrderObservations()
    {
        StableFactTracker<Value,Integer> tracker = new StableFactTracker<>(Value::key);
        tracker.observe(new Value(1, "candidate"), 100, GUARDED, ignored -> true);
        tracker.observe(new Value(1, "older"), 99, GUARDED, ignored -> true);

        assertEquals(1, tracker.getCandidateObservationCount());
        assertTrue(tracker.expireCandidate(201, 100));
        assertTrue(tracker.isEmpty());
    }

    @Test
    void oldIncumbentObservationDoesNotEraseNewerChallenger()
    {
        StableFactTracker<Value,Integer> tracker = new StableFactTracker<>(Value::key);
        FactConfirmationPolicy immediate = new FactConfirmationPolicy(1, 0, 100, true);
        tracker.observe(new Value(1, "stable"), 100, immediate, ignored -> true);
        tracker.observe(new Value(2, "challenger"), 200, GUARDED, ignored -> true);
        tracker.observe(new Value(1, "late incumbent"), 150, GUARDED, ignored -> true);

        assertEquals(2, tracker.getCandidateKey());
        assertEquals(1, tracker.getCandidateObservationCount());
    }

    @Test
    void candidateResetRetainsStableValue()
    {
        StableFactTracker<Value,Integer> tracker = new StableFactTracker<>(Value::key);
        tracker.observe(new Value(1, "stable"), 100,
            new FactConfirmationPolicy(1, 0, 100, true), ignored -> true);
        tracker.observe(new Value(2, "candidate"), 200, GUARDED, ignored -> true);

        tracker.resetCandidate();

        assertEquals(1, tracker.getStableValue().key());
        assertNull(tracker.getCandidateKey());
        assertFalse(tracker.isEmpty());
    }

    private record Value(int key, String label)
    {
    }
}
