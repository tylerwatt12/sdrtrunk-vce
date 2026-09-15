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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteReceiverContext;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelConfirmationEvent;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.SourceType;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioResolveP25FrequencyEvidenceTrackerTest
{
    private static final String CONFIGURATION_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OTHER_CONFIGURATION_ID = "123e4567-e89b-12d3-a456-426614174001";
    private static final int WACN = 0xBEE00;
    private static final int SYSTEM = 0x348;
    private static final long INCARNATION = 17L;
    private static final long TUNING_GENERATION = 3L;
    private static final long FREQUENCY = 854_187_500L;

    @Test
    void requiresConfirmedBandAndTwoMatchingGrants()
    {
        RadioResolveP25FrequencyEvidenceTracker tracker = new RadioResolveP25FrequencyEvidenceTracker();

        tracker.observe(grant(1_000L, false, 10, 201, 1, true, FREQUENCY, INCARNATION,
            TUNING_GENERATION, RadioSystemKey.p25(WACN, SYSTEM)));
        tracker.observe(grant(2_000L, true, 10, 201, 1, true, FREQUENCY, INCARNATION,
            TUNING_GENERATION, RadioSystemKey.p25(WACN, SYSTEM)));
        assertTrue(tracker.select(siteEvent(INCARNATION, TUNING_GENERATION, WACN, SYSTEM), 2_000L).isEmpty());

        tracker.observe(grant(3_000L, true, 10, 200, 2, true, FREQUENCY, INCARNATION,
            TUNING_GENERATION, RadioSystemKey.p25(WACN, SYSTEM)));

        List<RadioResolveP25FrequencyEvidenceTracker.Evidence> evidence =
            tracker.select(siteEvent(INCARNATION, TUNING_GENERATION, WACN, SYSTEM), 3_000L);
        assertEquals(1, evidence.size());
        assertEquals("10-200", evidence.getFirst().channelDescriptor());
        assertFalse(evidence.getFirst().channelDescriptor().contains("TS"));
        assertEquals(FREQUENCY, evidence.getFirst().frequencyHertz());
        assertEquals(3_000L, evidence.getFirst().observedAtMs());
    }

    @Test
    void trafficConfirmationPromotesOnlyTheMatchingReceiverGenerationAndTimeslot()
    {
        RadioResolveP25FrequencyEvidenceTracker tracker = new RadioResolveP25FrequencyEvidenceTracker();
        tracker.observe(grant(1_000L, true, 10, 200, 2, true, FREQUENCY, INCARNATION,
            TUNING_GENERATION, RadioSystemKey.p25(WACN, SYSTEM)));

        tracker.confirm(new P25TrafficChannelConfirmationEvent(CONFIGURATION_ID, FREQUENCY, 2, 2_000L,
            INCARNATION + 1, TUNING_GENERATION));
        tracker.confirm(new P25TrafficChannelConfirmationEvent(CONFIGURATION_ID, FREQUENCY, 1, 3_000L,
            INCARNATION, TUNING_GENERATION));
        assertTrue(tracker.select(siteEvent(INCARNATION, TUNING_GENERATION, WACN, SYSTEM), 3_000L).isEmpty());

        tracker.confirm(new P25TrafficChannelConfirmationEvent(CONFIGURATION_ID, FREQUENCY, 2, 4_000L,
            INCARNATION, TUNING_GENERATION));

        List<RadioResolveP25FrequencyEvidenceTracker.Evidence> evidence =
            tracker.select(siteEvent(INCARNATION, TUNING_GENERATION, WACN, SYSTEM), 4_000L);
        assertEquals(1, evidence.size());
        assertEquals(4_000L, evidence.getFirst().observedAtMs());
    }

    @Test
    void ignoresFdmaGrantEvidence()
    {
        RadioResolveP25FrequencyEvidenceTracker tracker = new RadioResolveP25FrequencyEvidenceTracker();
        String systemKey = RadioSystemKey.p25(WACN, SYSTEM);
        tracker.observe(grant(1_000L, true, 2, 493, 1, false, FREQUENCY, INCARNATION,
            TUNING_GENERATION, systemKey));
        tracker.observe(grant(1_001L, true, 2, 493, 1, false, FREQUENCY, INCARNATION,
            TUNING_GENERATION, systemKey));

        assertTrue(tracker.select(siteEvent(INCARNATION, TUNING_GENERATION, WACN, SYSTEM),
            1_001L).isEmpty());
    }

    @Test
    void selectionRequiresExactReceiverGenerationAndRadioSystem()
    {
        RadioResolveP25FrequencyEvidenceTracker tracker = new RadioResolveP25FrequencyEvidenceTracker();
        tracker.observe(grant(900L, true, 10, 200, 1, true, FREQUENCY, INCARNATION,
            TUNING_GENERATION, RadioSystemKey.p25(WACN, SYSTEM)));
        tracker.observe(grant(901L, true, 10, 200, 1, true, FREQUENCY, INCARNATION,
            TUNING_GENERATION, RadioSystemKey.p25(WACN + 1, SYSTEM)));
        tracker.confirm(new P25TrafficChannelConfirmationEvent(CONFIGURATION_ID, FREQUENCY, 1, 902L,
            INCARNATION, TUNING_GENERATION));
        assertTrue(tracker.select(siteEvent(INCARNATION, TUNING_GENERATION, WACN, SYSTEM), 902L).isEmpty());
        assertTrue(tracker.select(siteEvent(INCARNATION, TUNING_GENERATION, WACN + 1, SYSTEM), 902L).isEmpty());

        promote(tracker, 1_000L, 10, 200, FREQUENCY, INCARNATION, TUNING_GENERATION,
            RadioSystemKey.p25(WACN, SYSTEM));

        assertTrue(tracker.select(siteEvent(OTHER_CONFIGURATION_ID, INCARNATION, TUNING_GENERATION, WACN, SYSTEM),
            2_000L).isEmpty());
        assertTrue(tracker.select(siteEvent(INCARNATION + 1, TUNING_GENERATION, WACN, SYSTEM), 2_000L).isEmpty());
        assertTrue(tracker.select(siteEvent(INCARNATION, TUNING_GENERATION + 1, WACN, SYSTEM), 2_000L).isEmpty());
        assertTrue(tracker.select(siteEvent(INCARNATION, TUNING_GENERATION, WACN + 1, SYSTEM), 2_001L).isEmpty());
        assertEquals(1,
            tracker.select(siteEvent(INCARNATION, TUNING_GENERATION, WACN, SYSTEM), 2_001L).size());
    }

    @Test
    void expiresEvidenceAndEvictsTheLeastRecentlyObservedDescriptorAtCapacity()
    {
        RadioResolveP25FrequencyEvidenceTracker tracker =
            new RadioResolveP25FrequencyEvidenceTracker(2, 100L, 10_000L);
        String systemKey = RadioSystemKey.p25(WACN, SYSTEM);
        promote(tracker, 1_000L, 10, 200, FREQUENCY, INCARNATION, TUNING_GENERATION, systemKey);
        promote(tracker, 2_000L, 10, 202, FREQUENCY + 12_500L, INCARNATION, TUNING_GENERATION, systemKey);
        promote(tracker, 3_000L, 10, 204, FREQUENCY + 25_000L, INCARNATION, TUNING_GENERATION, systemKey);

        List<RadioResolveP25FrequencyEvidenceTracker.Evidence> bounded =
            tracker.select(siteEvent(INCARNATION, TUNING_GENERATION, WACN, SYSTEM), 3_001L);
        assertEquals(2, bounded.size());
        assertFalse(bounded.stream().anyMatch(item -> "10-200".equals(item.channelDescriptor())));

        List<RadioResolveP25FrequencyEvidenceTracker.Evidence> atExpirationBoundary =
            tracker.select(siteEvent(INCARNATION, TUNING_GENERATION, WACN, SYSTEM), 13_001L);
        assertEquals(1, atExpirationBoundary.size());
        assertEquals("10-204", atExpirationBoundary.getFirst().channelDescriptor());
        assertTrue(tracker.select(siteEvent(INCARNATION, TUNING_GENERATION, WACN, SYSTEM), 13_002L).isEmpty());
    }

    private static void promote(RadioResolveP25FrequencyEvidenceTracker tracker, long timestamp, int band,
                                int channelNumber, long frequency, long incarnation, long tuningGeneration,
                                String systemKey)
    {
        tracker.observe(grant(timestamp, true, band, channelNumber, 1, true, frequency, incarnation,
            tuningGeneration, systemKey));
        tracker.observe(grant(timestamp + 1, true, band, channelNumber, 2, true, frequency, incarnation,
            tuningGeneration, systemKey));
    }

    private static P25GrantObservationEvent grant(long timestamp, boolean confirmedBand, int band,
                                                  int channelNumber, int timeslot, boolean tdma, long frequency,
                                                  long incarnation, long tuningGeneration, String systemKey)
    {
        return new P25GrantObservationEvent(CONFIGURATION_ID, DecoderType.P25_PHASE2, DecodeEventType.CALL_GROUP,
            timestamp, false, confirmedBand, List.of(), frequency, band, channelNumber, timeslot, tdma, systemKey,
            incarnation, tuningGeneration);
    }

    private static SiteMetadataEvent siteEvent(long incarnation, long tuningGeneration, int wacn, int system)
    {
        return siteEvent(CONFIGURATION_ID, incarnation, tuningGeneration, wacn, system);
    }

    private static SiteMetadataEvent siteEvent(String configurationId, long incarnation, long tuningGeneration,
                                               int wacn, int system)
    {
        SiteReceiverContext context = new SiteReceiverContext(configurationId, 7, incarnation, tuningGeneration,
            Channel.ChannelType.STANDARD, DecoderType.P25_PHASE2, Protocol.APCO25_PHASE2,
            SiteReceiverContext.ReceiverMode.TRUNKED, TrunkedIdentityDomain.STANDARD, SourceType.TUNER,
            FREQUENCY, null, FREQUENCY, null, "Control", "System", "Site", 1L, "Aliases");
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot("P25_PHASE_2",
            new P25NetworkConfigurationSnapshot.Network(wacn, system, 0x123, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(system, 0x123, 2, 1, null, true),
            List.of(new P25NetworkConfigurationSnapshot.Channel("current_control", "2-1", FREQUENCY,
                809_187_500L, false, 1)), List.of(),
            List.of(new P25NetworkConfigurationSnapshot.FrequencyBand(2, false, 851_000_000L, 12_500,
                6_250L, -45_000_000L, 1)), List.of(), List.of());
        return new SiteMetadataEvent(null, context, snapshot, 5_000L);
    }
}
