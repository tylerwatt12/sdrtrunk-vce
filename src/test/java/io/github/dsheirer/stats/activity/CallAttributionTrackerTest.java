/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.Form;
import java.util.List;
import org.junit.jupiter.api.Test;

class CallAttributionTrackerTest
{
    @Test
    void emitsEachLateFactOnceAndKeepsTheOriginalCallStart()
    {
        CallAttributionTracker tracker = new CallAttributionTracker();
        tracker.register(activity(1_000L, null, null, false, true));

        CallAttributionTracker.AttributionResult identified =
            tracker.enrich(activity(1_100L, 91, 101, true, false));

        assertTrue(identified.tracked());
        assertEquals(1_000L, identified.attribution().callStartEpochMilliseconds());
        assertEquals(91, identified.attribution().destinationId());
        assertEquals(101, identified.attribution().sourceRadioId());
        assertTrue(identified.attribution().destinationBecameKnown());
        assertTrue(identified.attribution().sourceBecameKnown());
        assertTrue(identified.attribution().encryptionBecameKnown());
        assertFalse(identified.attribution().encryptedBeforeObservation());

        CallAttributionTracker.AttributionResult repeated =
            tracker.enrich(activity(1_200L, 91, 101, true, false));
        assertTrue(repeated.tracked());
        assertNull(repeated.attribution());

        CallAttributionTracker.AttributionResult laterTalker =
            tracker.enrich(activity(1_300L, 91, 102, true, false));
        assertTrue(laterTalker.tracked());
        assertNull(laterTalker.attribution());

        CallAttributionTracker.AttributionResult targetChange =
            tracker.enrich(activity(1_400L, 92, 102, true, false));
        assertTrue(targetChange.tracked());
        assertNull(targetChange.attribution());

        CallAttributionTracker.AttributionResult afterTargetChange =
            tracker.enrich(activity(1_500L, 92, 102, true, false));
        assertFalse(afterTargetChange.tracked());
        assertNull(afterTargetChange.attribution());
    }

    @Test
    void reportsEncryptionWasAlreadyKnownWhenDestinationArrivesLater()
    {
        CallAttributionTracker tracker = new CallAttributionTracker();
        tracker.register(activity(2_000L, null, 101, true, true));

        CallAttributionTracker.AttributionResult identified =
            tracker.enrich(activity(2_100L, 91, 101, true, false));

        assertTrue(identified.tracked());
        assertTrue(identified.attribution().destinationBecameKnown());
        assertFalse(identified.attribution().sourceBecameKnown());
        assertFalse(identified.attribution().encryptionBecameKnown());
        assertTrue(identified.attribution().encryptedBeforeObservation());
    }

    private static P25ActivityLogRecords.ActivityEvent activity(long timestamp, Integer target, Integer source,
                                                                 boolean encrypted, boolean countedCall)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:test-site", "test-site",
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25",
            countedCall ? P25ActivityLogRecords.Action.CALL : P25ActivityLogRecords.Action.ACTIVE,
            encrypted ? "CALL_GROUP_ENCRYPTED" : "CALL_GROUP",
            source != null ? source.toString() : null, target != null ? target.toString() : null,
            target != null ? Form.TALKGROUP.name() : null, List.of(), 851_012_500L, "1-100", 1, encrypted,
            null, null, 0xbee00, 0x123, 0x293, 1, 2, "P25 Site", "P25_PHASE2", null, countedCall, null, null);
    }
}
