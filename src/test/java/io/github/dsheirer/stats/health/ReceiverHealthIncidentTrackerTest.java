/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ReceiverHealthIncidentTrackerTest
{
    @Test
    void deduplicatesActiveConditionAndMovesItToBoundedResolvedHistory()
    {
        ReceiverHealthIncidentTracker tracker = new ReceiverHealthIncidentTracker(10);
        begin(tracker, 100);
        tracker.observe("usb-sample-loss", "critical", "USB loss", "usb-1", "Airspy", 100, 1,
            "one short transfer", "USB pressure", "decode loss", "check USB");
        tracker.endSample(100);
        assertEquals(1, tracker.lifecycleChanges().size());
        begin(tracker, 105);
        tracker.observe("usb-sample-loss", "critical", "USB loss", "usb-1", "Airspy", 105, 2,
            "two short transfers", "USB pressure", "decode loss", "check USB");
        tracker.endSample(105);
        assertTrue(tracker.lifecycleChanges().isEmpty());
        assertEquals(1, tracker.active().size());
        Map<String,Object> active = tracker.active().getFirst();
        assertEquals(2L, active.get("count"));
        assertEquals(100L, active.get("opened_at_ms"));
        assertEquals(105L, active.get("last_seen_ms"));

        begin(tracker, 106);
        tracker.observe("usb-sample-loss", "warning", "USB delivery recovered partially", "usb-1", "Airspy", 106, 2,
            "delivery restored", "USB pressure", "decode at risk", "keep watching");
        tracker.endSample(106);
        assertEquals(1, tracker.lifecycleChanges().size());
        assertEquals("warning", tracker.active().getFirst().get("severity"));
        assertEquals("USB delivery recovered partially", tracker.active().getFirst().get("title"));

        begin(tracker, 115);
        tracker.endSample(115);
        assertTrue(tracker.lifecycleChanges().isEmpty());
        assertEquals(1, tracker.active().size());
        begin(tracker, 116);
        tracker.endSample(116);
        assertEquals(1, tracker.lifecycleChanges().size());
        assertTrue(tracker.active().isEmpty());
        assertEquals(1, tracker.resolved().size());
        assertEquals(116L, tracker.resolved().getFirst().get("resolved_at_ms"));
    }

    @Test
    void oneBlipExpiresWithoutOpeningOrCreatingHistory() throws Exception
    {
        ReceiverHealthIncidentTracker tracker = new ReceiverHealthIncidentTracker();
        begin(tracker, 0);
        recurringLoss(tracker, "usb-1", 1_000);
        tracker.endSample(1_000);
        begin(tracker, 9_999);
        tracker.endSample(10_999);
        assertTrue(tracker.active().isEmpty());
        begin(tracker, 10_000);
        tracker.endSample(11_000);
        assertTrue(tracker.active().isEmpty());
        assertTrue(tracker.resolved().isEmpty());
        assertTrue(tracker.lifecycleChanges().isEmpty());
        var pending = ReceiverHealthIncidentTracker.class.getDeclaredField("mPending");
        pending.setAccessible(true);
        assertTrue(((Map<?,?>)pending.get(tracker)).isEmpty(), "Expired pending scopes must not accumulate");
    }

    @Test
    void newRecurringEvidenceConfirmsAtTenSecondsAndRecoversAfterTenQuietSeconds()
    {
        ReceiverHealthIncidentTracker tracker = new ReceiverHealthIncidentTracker();
        begin(tracker, 0);
        recurringLoss(tracker, "usb-1", 1_000);
        tracker.endSample(1_000);
        begin(tracker, 9_999);
        recurringLoss(tracker, "usb-1", 10_999);
        tracker.endSample(10_999);
        assertTrue(tracker.active().isEmpty());
        begin(tracker, 10_000);
        recurringLoss(tracker, "usb-1", 11_000);
        tracker.endSample(11_000);
        assertEquals(1, tracker.active().size());
        assertEquals(11_000L, tracker.active().getFirst().get("opened_at_ms"));
        begin(tracker, 19_999);
        tracker.endSample(20_999);
        assertEquals(1, tracker.active().size());
        begin(tracker, 20_000);
        tracker.endSample(21_000);
        assertTrue(tracker.active().isEmpty());
        assertEquals(1, tracker.resolved().size());
    }

    @Test
    void aTenSecondQuietGapRequiresFreshConfirmation()
    {
        ReceiverHealthIncidentTracker tracker = new ReceiverHealthIncidentTracker();
        for(long time: new long[]{0, 5_000, 15_000, 24_000})
        {
            begin(tracker, time);
            recurringLoss(tracker, "usb-1", time + 1_000);
            tracker.endSample(time + 1_000);
            assertTrue(tracker.active().isEmpty());
        }
        begin(tracker, 25_000);
        recurringLoss(tracker, "usb-1", 26_000);
        tracker.endSample(26_000);
        assertEquals(1, tracker.active().size());
    }

    @Test
    void wallClockStepsDoNotConfirmOrResolveIncidentsAndPayloadRemainsOrdered()
    {
        ReceiverHealthIncidentTracker tracker = new ReceiverHealthIncidentTracker();
        begin(tracker, 0);
        recurringLoss(tracker, "usb-1", 100_000);
        tracker.endSample(100_000);
        begin(tracker, 1_000);
        recurringLoss(tracker, "usb-1", 1_000_000);
        tracker.endSample(1_000_000);
        assertTrue(tracker.active().isEmpty(), "A forward wall step is not ten seconds of recurring loss");
        begin(tracker, 9_000);
        recurringLoss(tracker, "usb-1", 1_000);
        tracker.endSample(1_000);
        begin(tracker, 10_000);
        recurringLoss(tracker, "usb-1", 2_000);
        tracker.endSample(2_000);
        assertEquals(1, tracker.active().size());
        begin(tracker, 11_000);
        immediateLoss(tracker, "usb-1", 1_000);
        tracker.endSample(1_000);
        assertEquals(2_000L, tracker.active().getFirst().get("last_seen_ms"));
        begin(tracker, 12_000);
        tracker.endSample(10_000_000);
        assertEquals(1, tracker.active().size(), "Forward wall time cannot resolve an active incident");
        begin(tracker, 21_000);
        tracker.endSample(500);
        assertTrue(tracker.active().isEmpty());
        assertEquals(2_000L, tracker.resolved().getFirst().get("resolved_at_ms"));
    }

    @Test
    void monotonicTimingSupportsNegativeOriginsAndSignedWrap()
    {
        for(long origin: new long[]{Long.MIN_VALUE, -1_000_000_000L, -1, 0,
            Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(5)})
        {
            ReceiverHealthIncidentTracker tracker = new ReceiverHealthIncidentTracker();
            for(long seconds: new long[]{0, 9, 10})
            {
                tracker.beginSample(origin + TimeUnit.SECONDS.toNanos(seconds));
                recurringLoss(tracker, "usb-1", 1_000 + seconds);
                tracker.endSample(1_000 + seconds);
            }
            assertEquals(1, tracker.active().size(), "origin=" + origin);
            tracker.beginSample(origin + TimeUnit.SECONDS.toNanos(20));
            tracker.endSample(1_020);
            assertTrue(tracker.active().isEmpty(), "origin=" + origin);
        }
    }

    @Test
    void stableScopeIdsKeepSameNamedTunersIndependent()
    {
        ReceiverHealthIncidentTracker tracker = new ReceiverHealthIncidentTracker();
        begin(tracker, 0);
        immediateLoss(tracker, "usb-1", 1_000);
        immediateLoss(tracker, "usb-2", 1_000);
        tracker.endSample(1_000);
        assertEquals(2, tracker.active().size());
        assertTrue(tracker.active().stream().allMatch(row -> "Airspy".equals(row.get("scope"))));
        assertEquals(2, tracker.active().stream().map(row -> row.get("occurrence_id")).distinct().count());
    }

    @Test
    void unavailableScopePreservesOnlyItsActiveIncidentsWithoutRefreshingEvidence()
    {
        ReceiverHealthIncidentTracker tracker = new ReceiverHealthIncidentTracker();
        begin(tracker, 0);
        immediateLoss(tracker, "usb-1", 1_000);
        immediateLoss(tracker, "usb-2", 1_000);
        tracker.endSample(1_000);
        begin(tracker, 10_000);
        tracker.preserve("usb-1");
        tracker.endSample(11_000);
        assertEquals(1, tracker.active().size());
        assertEquals(1_000L, tracker.active().getFirst().get("last_seen_ms"));
        assertEquals(1, tracker.resolved().size());
        begin(tracker, 10_001);
        tracker.endSample(11_001);
        assertTrue(tracker.active().isEmpty(), "Preserving unavailable evidence must not restart recovery timing");
    }

    @Test
    void unavailableEvidenceClearsPendingConfirmationAndDoesNotCreateAnIncident()
    {
        ReceiverHealthIncidentTracker tracker = new ReceiverHealthIncidentTracker();
        begin(tracker, 0);
        recurringLoss(tracker, "usb-1", 1_000);
        tracker.endSample(1_000);
        begin(tracker, 9_000);
        tracker.preserve("usb-1");
        tracker.preserve("unseen-scope");
        tracker.endSample(10_000);
        begin(tracker, 10_000);
        recurringLoss(tracker, "usb-1", 11_000);
        tracker.endSample(11_000);
        assertTrue(tracker.active().isEmpty(), "Unknown time cannot confirm the old pending episode");
        assertTrue(tracker.resolved().isEmpty());
    }

    @Test
    void capsAndExpiresResolvedHistory()
    {
        ReceiverHealthIncidentTracker tracker = new ReceiverHealthIncidentTracker(0);
        for(int index = 0; index < ReceiverHealthIncidentTracker.MAXIMUM_RESOLVED_INCIDENTS + 5; index++)
        {
            begin(tracker, index);
            tracker.observe("drop-" + index, "warning", "Drop", "scope-id", "scope", index, 1,
                "observed", "cause", "impact", "next");
            tracker.endSample(index);
            begin(tracker, index + 1);
            tracker.endSample(index + 1);
        }
        assertEquals(ReceiverHealthIncidentTracker.MAXIMUM_RESOLVED_INCIDENTS, tracker.resolved().size());
        begin(tracker, ReceiverHealthIncidentTracker.RESOLVED_RETENTION_MILLISECONDS + 1_000);
        tracker.endSample(ReceiverHealthIncidentTracker.RESOLVED_RETENTION_MILLISECONDS + 1_000);
        assertTrue(tracker.resolved().isEmpty());
    }

    private static void begin(ReceiverHealthIncidentTracker tracker, long milliseconds)
    {
        tracker.beginSample(TimeUnit.MILLISECONDS.toNanos(milliseconds));
    }

    private static void recurringLoss(ReceiverHealthIncidentTracker tracker, String scopeId, long wallTime)
    {
        tracker.observeRecurring("receiver-iq-drop", "critical", "Radio data lost", scopeId, "Airspy", wallTime, 1,
            "new sample loss", "receiver could not keep up", "radio data was discarded", "check receiver");
    }

    private static void immediateLoss(ReceiverHealthIncidentTracker tracker, String scopeId, long wallTime)
    {
        tracker.observe("receiver-iq-drop", "critical", "Radio data lost", scopeId, "Airspy", wallTime, 1,
            "new sample loss", "receiver could not keep up", "radio data was discarded", "check receiver");
    }
}
