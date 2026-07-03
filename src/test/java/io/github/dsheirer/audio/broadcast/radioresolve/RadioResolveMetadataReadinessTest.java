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

package io.github.dsheirer.audio.broadcast.radioresolve;

import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RadioResolveMetadataReadinessTest
{
    @Test
    public void partialSnapshotIsNotUploadReady()
    {
        RadioResolveMetadataReadiness readiness = RadioResolveMetadataReadiness.evaluate("guid",
            new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null, List.of(), List.of(),
                List.of(), List.of(), List.of()));

        assertFalse(readiness.ready());
        assertTrue(readiness.message().contains("WACN"));
        assertTrue(readiness.message().contains("Current Control"));
    }

    @Test
    public void completeIdentityWithoutBandPlanIsNotUploadReady()
    {
        RadioResolveMetadataReadiness readiness = RadioResolveMetadataReadiness.evaluate("guid",
            snapshot(completeNetwork(), completeSite(), List.of(primaryControl()), List.of()));

        assertFalse(readiness.ready());
        assertTrue(readiness.message().contains("Frequency Band"));
    }

    @Test
    public void completeIdentityAndBandPlanWithoutResolvedControlIsNotUploadReady()
    {
        RadioResolveMetadataReadiness readiness = RadioResolveMetadataReadiness.evaluate("guid",
            snapshot(completeNetwork(), completeSite(),
                List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", "0-493", 0L, 0L, false, 1)),
                List.of(frequencyBand())));

        assertFalse(readiness.ready());
        assertTrue(readiness.message().contains("Current Control"));
    }

    @Test
    public void completeProfileIsUploadReady()
    {
        RadioResolveMetadataReadiness readiness = RadioResolveMetadataReadiness.evaluate("guid",
            snapshot(completeNetwork(), completeSite(), List.of(primaryControl()), List.of(frequencyBand())));

        assertTrue(readiness.ready());
    }

    private static P25NetworkConfigurationSnapshot snapshot(P25NetworkConfigurationSnapshot.Network network,
                                                           P25NetworkConfigurationSnapshot.CurrentSite site,
                                                           List<P25NetworkConfigurationSnapshot.Channel> channels,
                                                           List<P25NetworkConfigurationSnapshot.FrequencyBand> bands)
    {
        return new P25NetworkConfigurationSnapshot("P25_PHASE_1", network, site, channels, List.of(), bands,
            List.of(), List.of());
    }

    private static P25NetworkConfigurationSnapshot.Network completeNetwork()
    {
        return new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x348, null);
    }

    private static P25NetworkConfigurationSnapshot.CurrentSite completeSite()
    {
        return new P25NetworkConfigurationSnapshot.CurrentSite(0x348, 0x348, 2, 1, null, true);
    }

    private static P25NetworkConfigurationSnapshot.Channel primaryControl()
    {
        return new P25NetworkConfigurationSnapshot.Channel("primary_control", "0-493", 854087500L, 809087500L,
            false, 1);
    }

    private static P25NetworkConfigurationSnapshot.FrequencyBand frequencyBand()
    {
        return new P25NetworkConfigurationSnapshot.FrequencyBand(0, false, 851006250L, 12500, 6250L,
            -45000000L, 1);
    }
}
