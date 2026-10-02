/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.channel.ChannelAdministrationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpectrumDiscoveryServiceTest
{
    @Test
    void eligibilityReasonNamesTheOwningChannel()
    {
        var configured = new ChannelAdministrationService.DiscoveryFrequencyMatch(
            "one", "County Control", "County P25", "North", "configured");
        assertEquals("This frequency belongs to County Control.",
            SpectrumDiscoveryService.eligibilityReason(List.of(configured)));
        assertEquals("This frequency belongs to County Control and 1 other channel.",
            SpectrumDiscoveryService.eligibilityReason(List.of(configured,
                Map.of("configuration_id", "two", "name", "City Control"))));
    }

    @Test
    void eligibilityReasonDeduplicatesOwnersAndKeepsAGenericFallback()
    {
        assertEquals("This frequency belongs to County Control.",
            SpectrumDiscoveryService.eligibilityReason(List.of(
                Map.of("configuration_id", "one", "name", "County Control"),
                Map.of("configuration_id", "one", "name", "County Control"))));
        assertEquals("This frequency belongs to a known channel.",
            SpectrumDiscoveryService.eligibilityReason(List.of(Map.of("configuration_id", "one"))));
    }
}
