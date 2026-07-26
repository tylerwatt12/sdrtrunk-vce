/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class P25GrantFactConfirmationTrackerTest
{
    @Test
    void requiresConfirmedBandAndTwoMatchingGrants()
    {
        P25GrantFactConfirmationTracker tracker = new P25GrantFactConfirmationTracker();

        assertNull(tracker.observe(event(1000L, false), activity(1000L)));
        assertNull(tracker.observe(event(2000L, true), activity(2000L)));
        assertNotNull(tracker.observe(event(3000L, true), activity(3000L)));
    }

    private static P25GrantObservationEvent event(long timestamp, boolean confirmedBand)
    {
        return new P25GrantObservationEvent(null, null, null, DecodeEventType.CALL_GROUP, timestamp, false,
            confirmedBand);
    }

    private static P25ActivityLogRecords.ActivityEvent activity(long timestamp)
    {
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", P25ActivityLogRecords.Action.GRANT,
            "CALL_GROUP", "1811524", "56138", "TALKGROUP", 854_187_500L, "0-509", 0, false,
            null, null, 0xBEE00, 0x348, 0x348, 2, 1, "Example Site", "P25-1", null,
            false, null, null);
    }
}
