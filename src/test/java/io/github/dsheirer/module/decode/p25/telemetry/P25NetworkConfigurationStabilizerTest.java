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

package io.github.dsheirer.module.decode.p25.telemetry;

import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class P25NetworkConfigurationStabilizerTest
{
    @Test
    public void discoveryPromotesIdentityAndAllControlChannelsImmediately()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x123, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x348, 0x123, 2, 1, null, true),
            List.of(primary(856137500L), secondary(855987500L)),
            List.of(neighbor(855237500L)),
            List.of(new P25NetworkConfigurationSnapshot.FrequencyBand(0, false, 851006250L, 12500,
                6250L, -45000000L, 1)),
            List.of(), List.of());

        stabilizer.observe(snapshot, 1000L);
        P25NetworkConfigurationSnapshot stable = stabilizer.getSnapshot();

        assertNotNull(stable.network());
        assertEquals(0xBEE00, stable.network().wacn());
        assertNotNull(stable.currentSite());
        assertEquals(new P25SiteIdentity(0xBEE00, 0x348, 2, 1), stabilizer.getStableSiteIdentity());
        assertEquals(2, stable.channels().size());
        assertTrue(hasChannel(stable, "primary_control", 856137500L));
        assertTrue(hasChannel(stable, "secondary_control", 855987500L));
        assertTrue(stable.neighborSites().isEmpty());
        assertTrue(stable.frequencyBands().isEmpty());

        stabilizer.observe(snapshot, 31_000L);
        stabilizer.observe(snapshot, 61_000L);
        stable = stabilizer.getSnapshot();

        assertEquals(2, stable.channels().size());
        assertEquals(1, stable.neighborSites().size());
        assertEquals(1, stable.frequencyBands().size());
    }

    @Test
    public void controlChannelsPromoteImmediatelyOutsideDiscovery()
    {
        P25NetworkConfigurationStabilizer stabilizer = seededStabilizer();

        stabilizer.observe(snapshot(secondary(851462500L)), 70000L);

        assertTrue(hasChannel(stabilizer.getSnapshot(), "secondary_control", 851462500L));
    }

    @Test
    public void currentControlReplacementPromotesImmediately()
    {
        P25NetworkConfigurationStabilizer stabilizer = seededStabilizer();

        stabilizer.observe(snapshot(primary(856162500L)), 70000L);

        P25NetworkConfigurationSnapshot.Channel current = getChannel(stabilizer.getSnapshot(), "primary_control");
        assertNotNull(current);
        assertEquals(856162500L, current.downlink());
        assertEquals(1, stabilizer.getSnapshot().channels().stream()
            .filter(channel -> "primary_control".equals(channel.role()))
            .count());
    }

    @Test
    public void resetStartsNewIdentityAndCurrentControlDiscoveryWindow()
    {
        P25NetworkConfigurationStabilizer stabilizer = seededStabilizer();

        stabilizer.reset();
        stabilizer.observe(snapshot(primary(851462500L)), 200000L);

        assertTrue(hasChannel(stabilizer.getSnapshot(), "primary_control", 851462500L));
    }

    @Test
    public void candidateResetRetainsGuardedFactsWhileControlsRemainAuthoritative()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        P25NetworkConfigurationSnapshot initial = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x123, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x348, 0x123, 2, 1, null, true),
            List.of(primary(856137500L)), List.of(neighbor(855237500L)),
            List.of(new P25NetworkConfigurationSnapshot.FrequencyBand(0, false, 851006250L, 12500,
                6250L, -45000000L, 1)), List.of(), List.of());
        stabilizer.observe(initial, 1_000L);
        stabilizer.observe(initial, 31_000L);
        stabilizer.observe(initial, 61_000L);

        stabilizer.resetCandidates();
        stabilizer.observe(snapshot(secondary(851462500L)), 200_000L);

        P25NetworkConfigurationSnapshot stable = stabilizer.getSnapshot();
        assertEquals(initial.network(), stable.network());
        assertEquals(initial.currentSite(), stable.currentSite());
        assertTrue(hasChannel(stable, "primary_control", 856137500L));
        assertTrue(hasChannel(stable, "secondary_control", 851462500L));
        assertEquals(1, stable.neighborSites().size());
        assertEquals(1, stable.frequencyBands().size());
    }

    @Test
    public void capsPromotedControlFrequenciesAtEight()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        List<P25NetworkConfigurationSnapshot.Channel> channels = java.util.stream.LongStream.range(0, 9)
            .mapToObj(index -> secondary(851000000L + index * 12500L))
            .toList();

        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null, channels,
            List.of(), List.of(), List.of(), List.of()), 1000L);

        assertEquals(8, stabilizer.getStableCurrentSiteControlFrequencies().size());
    }

    @Test
    public void retiresBroadcastFactsThatAreNoLongerObserved()
    {
        P25NetworkConfigurationStabilizer stabilizer = seededStabilizer();
        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null, List.of(), List.of(),
            List.of(), List.of(), List.of()), 601002L);

        assertTrue(stabilizer.getSnapshot().channels().isEmpty());
    }

    @Test
    public void siteStatusUsesLatestBroadcastAndExpires()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        P25NetworkConfigurationSnapshot.SiteStatus first = new P25NetworkConfigurationSnapshot.SiteStatus(
            946_684_860_000L, 85, false, "Request Only", null, true, 0x90, true);
        P25NetworkConfigurationSnapshot.SiteStatus latest = new P25NetworkConfigurationSnapshot.SiteStatus(
            946_684_964_000L, 110, true, "Autonomous and by Request", 240, true, 0x90, true);

        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null, List.of(), List.of(),
            List.of(), List.of(), List.of(), first), 1000L);
        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null, List.of(), List.of(),
            List.of(), List.of(), List.of(), latest), 2000L);

        assertEquals(latest, stabilizer.getSnapshot().siteStatus());

        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null, List.of(), List.of(),
            List.of(), List.of(), List.of()), 602_001L);
        assertNull(stabilizer.getSnapshot().siteStatus());
    }

    @Test
    public void mergesPartialSiteStatusFromPhaseTwoTimeslots()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_2");
        P25NetworkConfigurationSnapshot.SiteStatus timing = new P25NetworkConfigurationSnapshot.SiteStatus(
            946_684_860_000L, 85, null, null, null, null, null, null);
        P25NetworkConfigurationSnapshot.SiteStatus services = new P25NetworkConfigurationSnapshot.SiteStatus(
            null, null, true, "Autonomous and by Request", 240, true, 0x90, true);

        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_2", null, null, List.of(), List.of(),
            List.of(), List.of(), List.of(), timing), 1_000L);
        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_2", null, null, List.of(), List.of(),
            List.of(), List.of(), List.of(), services), 2_000L);

        P25NetworkConfigurationSnapshot.SiteStatus merged = stabilizer.getSnapshot().siteStatus();
        assertEquals(timing.broadcastClockEpochMilliseconds(), merged.broadcastClockEpochMilliseconds());
        assertEquals(timing.microSlots(), merged.microSlots());
        assertEquals(services.dataService(), merged.dataService());
        assertEquals(services.dataAccess(), merged.dataAccess());
        assertEquals(services.voiceService(), merged.voiceService());
    }

    @Test
    public void neighborIsUpdatedInPlaceWhenFrequencyResolvesLater()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        P25NetworkConfigurationSnapshot.NeighborSite unresolved = neighbor(0L);
        P25NetworkConfigurationSnapshot.NeighborSite resolved = neighbor(855237500L);

        stabilizer.observe(snapshot(unresolved), 1000L);
        stabilizer.observe(snapshot(unresolved), 31_000L);
        stabilizer.observe(snapshot(unresolved), 61_000L);

        assertEquals(1, stabilizer.getSnapshot().neighborSites().size());
        assertEquals(0L, stabilizer.getSnapshot().neighborSites().get(0).downlink());

        stabilizer.observe(snapshot(resolved), 70_000L);
        stabilizer.observe(snapshot(resolved), 100_000L);
        stabilizer.observe(snapshot(resolved), 130_000L);

        assertEquals(1, stabilizer.getSnapshot().neighborSites().size());
        assertEquals(855237500L, stabilizer.getSnapshot().neighborSites().get(0).downlink());
    }

    @Test
    public void keepsForeignBandsScopedByWacnSystemAndBand()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        List<P25NetworkConfigurationSnapshot.ForeignSystemBand> bands = List.of(
            new P25NetworkConfigurationSnapshot.ForeignSystemBand(0xBEE00, 0x9EF, 4, 1,
                935_012_500L, 12_500L, -39_000_000L),
            new P25NetworkConfigurationSnapshot.ForeignSystemBand(0xBEE00, 0x9EF, 5, 3,
                935_012_500L, 12_500L, -39_000_000L),
            new P25NetworkConfigurationSnapshot.ForeignSystemBand(0xBEE00, 0x954, 0, 1,
                851_006_250L, 6_250L, -45_000_000L));
        P25NetworkConfigurationSnapshot observation = new P25NetworkConfigurationSnapshot("P25_PHASE_1", null,
            null, List.of(), List.of(), List.of(), List.of(), List.of(), null, bands);

        stabilizer.observe(observation, 1_000L);
        stabilizer.observe(observation, 31_000L);
        stabilizer.observe(observation, 61_000L);

        assertEquals(3, stabilizer.getSnapshot().foreignSystemBands().size());
        assertTrue(stabilizer.getSnapshot().foreignSystemBands().containsAll(bands));
        assertTrue(stabilizer.getSnapshot().frequencyBands().isEmpty());
    }

    @Test
    public void promotesAccumulatedAlternatingHarrisPhaseOneMembers()
    {
        PatchGroupManager manager = new PatchGroupManager();
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");

        observeAccumulatedPatchGroup(manager, stabilizer, patchGroup(65191, 9, 40002), 1_000L);
        observeAccumulatedPatchGroup(manager, stabilizer, patchGroup(65191, 9, 40003), 11_000L);
        observeAccumulatedPatchGroup(manager, stabilizer, patchGroup(65191, 9, 40002), 21_000L);

        assertEquals(List.of(40002, 40003), stabilizer.getSnapshot().patchGroups().getFirst().talkgroups());
    }

    @Test
    public void repeatedUnchangedHarrisPhaseTwoObservationCanPromote()
    {
        PatchGroupManager manager = new PatchGroupManager();
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_2");
        PatchGroupIdentifier first = patchGroup(65191, 9, 40002);
        PatchGroupIdentifier repeated = patchGroup(65191, 9, 40002);

        assertTrue(manager.addPatchGroup(first, 1_000L));
        stabilizer.observePatchGroup((PatchGroupIdentifier)manager.update(first, 1_000L), 1_000L);
        assertFalse(manager.addPatchGroup(repeated, 11_000L));
        stabilizer.observePatchGroup((PatchGroupIdentifier)manager.update(repeated, 11_000L), 11_000L);

        assertEquals(List.of(40002), stabilizer.getSnapshot().patchGroups().getFirst().talkgroups());
    }

    private static void observeAccumulatedPatchGroup(PatchGroupManager manager,
                                                     P25NetworkConfigurationStabilizer stabilizer,
                                                     PatchGroupIdentifier patchGroup, long timestamp)
    {
        manager.addPatchGroup(patchGroup, timestamp);
        stabilizer.observePatchGroup((PatchGroupIdentifier)manager.update(patchGroup, timestamp), timestamp);
    }

    private static PatchGroupIdentifier patchGroup(int supergroup, int version, int member)
    {
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(supergroup), version);
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(member));
        return APCO25PatchGroup.create(patchGroup);
    }

    private static P25NetworkConfigurationSnapshot snapshot(P25NetworkConfigurationSnapshot.NeighborSite neighbor)
    {
        return new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null, List.of(), List.of(neighbor),
            List.of(), List.of(), List.of());
    }

    private static P25NetworkConfigurationSnapshot snapshot(P25NetworkConfigurationSnapshot.Channel channel)
    {
        return new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null, List.of(channel), List.of(),
            List.of(), List.of(), List.of());
    }

    private static P25NetworkConfigurationSnapshot.NeighborSite neighbor(long downlink)
    {
        return new P25NetworkConfigurationSnapshot.NeighborSite(0x348, null, 2, 3, null, "0-493",
            downlink, 810237500L, "VALID");
    }

    private static P25NetworkConfigurationSnapshot.Channel primary(long downlink)
    {
        return new P25NetworkConfigurationSnapshot.Channel("primary_control", "0-821", downlink, null,
            false, 1);
    }

    private static P25NetworkConfigurationSnapshot.Channel secondary(long downlink)
    {
        return new P25NetworkConfigurationSnapshot.Channel("secondary_control", "0-797", downlink, null,
            false, 1);
    }

    private static P25NetworkConfigurationStabilizer seededStabilizer()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null,
            List.of(primary(856137500L), secondary(855987500L)), List.of(), List.of(), List.of(), List.of()),
            1000L);
        return stabilizer;
    }

    private static boolean hasChannel(P25NetworkConfigurationSnapshot snapshot, String role, long downlink)
    {
        return snapshot.channels().stream().anyMatch(channel -> role.equals(channel.role()) &&
            channel.downlink() != null && channel.downlink() == downlink);
    }

    private static P25NetworkConfigurationSnapshot.Channel getChannel(P25NetworkConfigurationSnapshot snapshot,
                                                                      String role)
    {
        return snapshot.channels().stream()
            .filter(channel -> role.equals(channel.role()))
            .findFirst()
            .orElse(null);
    }
}
