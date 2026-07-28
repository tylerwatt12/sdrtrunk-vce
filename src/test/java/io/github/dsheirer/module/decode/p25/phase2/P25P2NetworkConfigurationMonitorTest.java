/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import org.junit.jupiter.api.Test;

class P25P2NetworkConfigurationMonitorTest
{
    @Test
    void emitsOnlyTheStatusFieldsObservedByTheCurrentMessage()
    {
        P25P2NetworkConfigurationMonitor monitor = new P25P2NetworkConfigurationMonitor();
        P25NetworkConfigurationSnapshot.SiteStatus services =
            new P25NetworkConfigurationSnapshot.SiteStatus(null, null, true, null, null, true, null, true);
        P25NetworkConfigurationSnapshot.SiteStatus timing =
            new P25NetworkConfigurationSnapshot.SiteStatus(1234L, 2, null, null, null, null, null, null);

        monitor.statusObservation(services);
        P25NetworkConfigurationSnapshot observation = monitor.statusObservation(timing);

        assertEquals(1234L, observation.siteStatus().broadcastClockEpochMilliseconds());
        assertEquals(2, observation.siteStatus().microSlots());
        assertNull(observation.siteStatus().dataService());
        assertNull(observation.siteStatus().registrationService());
        assertNull(observation.siteStatus().voiceService());

        P25NetworkConfigurationSnapshot current = monitor.getSnapshot();
        assertTrue(current.siteStatus().dataService());
        assertTrue(current.siteStatus().registrationService());
        assertTrue(current.siteStatus().voiceService());
    }
}
