/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceiverHealthIncidentTrackerTest
{
    @Test
    void deduplicatesActiveConditionAndMovesItToBoundedResolvedHistory()
    {
        ReceiverHealthIncidentTracker tracker = new ReceiverHealthIncidentTracker(10);
        tracker.beginSample();
        tracker.observe("usb-sample-loss", "critical", "USB loss", "Airspy", 100, 1,
            "one short transfer", "USB pressure", "decode loss", "check USB");
        tracker.endSample(100);
        tracker.beginSample();
        tracker.observe("usb-sample-loss", "critical", "USB loss", "Airspy", 105, 2,
            "two short transfers", "USB pressure", "decode loss", "check USB");
        tracker.endSample(105);
        assertEquals(1, tracker.active().size());
        Map<String,Object> active = tracker.active().getFirst();
        assertEquals(2L, active.get("count"));
        assertEquals(100L, active.get("opened_at_ms"));
        assertEquals(105L, active.get("last_seen_ms"));

        tracker.beginSample();
        tracker.observe("usb-sample-loss", "warning", "USB delivery recovered partially", "Airspy", 106, 2,
            "delivery restored", "USB pressure", "decode at risk", "keep watching");
        tracker.endSample(106);
        assertEquals("warning", tracker.active().getFirst().get("severity"));
        assertEquals("USB delivery recovered partially", tracker.active().getFirst().get("title"));

        tracker.beginSample();
        tracker.endSample(115);
        assertEquals(1, tracker.active().size());
        tracker.beginSample();
        tracker.endSample(116);
        assertTrue(tracker.active().isEmpty());
        assertEquals(1, tracker.resolved().size());
        assertEquals(116L, tracker.resolved().getFirst().get("resolved_at_ms"));
    }

    @Test
    void capsAndExpiresResolvedHistory()
    {
        ReceiverHealthIncidentTracker tracker = new ReceiverHealthIncidentTracker(0);

        for(int index = 0; index < ReceiverHealthIncidentTracker.MAXIMUM_RESOLVED_INCIDENTS + 5; index++)
        {
            tracker.beginSample();
            tracker.observe("drop-" + index, "warning", "Drop", "scope", index, 1,
                "observed", "cause", "impact", "next");
            tracker.endSample(index);
            tracker.beginSample();
            tracker.endSample(index + 1);
        }

        assertEquals(ReceiverHealthIncidentTracker.MAXIMUM_RESOLVED_INCIDENTS, tracker.resolved().size());
        tracker.beginSample();
        tracker.endSample(ReceiverHealthIncidentTracker.RESOLVED_RETENTION_MILLISECONDS + 1_000);
        assertTrue(tracker.resolved().isEmpty());
    }
}
