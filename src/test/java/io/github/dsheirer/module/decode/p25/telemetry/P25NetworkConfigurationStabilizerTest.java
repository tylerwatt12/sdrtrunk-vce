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
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class P25NetworkConfigurationStabilizerTest
{
    @Test
    public void blockedObserverProjectionNeverDelaysDecoderObservationResetOrSaturation() throws Exception
    {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicBoolean blockFirstProjection = new AtomicBoolean(true);
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1", () -> {
            if(blockFirstProjection.compareAndSet(true, false))
            {
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        });
        stabilizer.observe(snapshot(primary(856137500L)), 1_000L);
        ExecutorService observer = Executors.newSingleThreadExecutor();
        ExecutorService decoder = Executors.newSingleThreadExecutor();

        try
        {
            Future<P25NetworkConfigurationSnapshot> projected = observer.submit(stabilizer::getSnapshot);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));

            Future<?> decoderWork = decoder.submit(() -> {
                P25NetworkConfigurationSnapshot observation = snapshot(secondary(855987500L));

                for(int index = 0; index < 1_000; index++)
                {
                    stabilizer.observe(observation, 2_000L + index);

                    if(index % 100 == 0)
                    {
                        stabilizer.resetCandidates();
                    }
                }

                stabilizer.reset();
                stabilizer.observe(snapshot(primary(851012500L)), 10_000L);
            });

            decoderWork.get(2, TimeUnit.SECONDS);
            releaseProjection.countDown();
            P25NetworkConfigurationSnapshot snapshot = projected.get(2, TimeUnit.SECONDS);
            assertTrue(hasChannel(snapshot, "primary_control", 851012500L));
        }
        finally
        {
            releaseProjection.countDown();
            observer.shutdownNow();
            decoder.shutdownNow();
        }
    }

    @Test
    public void concurrentPatchMutationFailsClosedInsteadOfPublishingMixedCompleteness()
    {
        AtomicReference<P25NetworkConfigurationStabilizer> reference = new AtomicReference<>();
        AtomicLong timestamp = new AtomicLong(71_000L);
        AtomicInteger patchId = new AtomicInteger(65192);
        AtomicBoolean mutateDuringProjection = new AtomicBoolean(true);
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1", () -> {
            if(mutateDuringProjection.get())
            {
                PatchGroupIdentifier concurrentPatch = patchGroup(patchId.getAndIncrement(), 10, 40003);
                long firstObservation = timestamp.addAndGet(20_000L);
                reference.get().observePatchGroup(concurrentPatch, firstObservation);
                reference.get().observePatchGroup(concurrentPatch, firstObservation + 10_000L);
            }
        });
        reference.set(stabilizer);
        P25NetworkConfigurationSnapshot.PatchGroup patchSnapshot =
            new P25NetworkConfigurationSnapshot.PatchGroup(65191, 9, List.of(40002), List.of());
        P25NetworkConfigurationSnapshot observation = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x123, null), null,
            List.of(primary(856137500L)), List.of(), List.of(),
            List.of(patchSnapshot), List.of());
        stabilizer.observe(observation, 1_000L);
        stabilizer.observe(observation, 11_000L);
        stabilizer.observe(observation, 61_000L);

        P25NetworkConfigurationSnapshot projected = stabilizer.getSnapshot();

        assertNull(projected,
            "bounded optimistic retries must fail closed while every projection overlaps decoder mutation");

        mutateDuringProjection.set(false);
        P25NetworkConfigurationSnapshot coherent = stabilizer.getSnapshot();
        assertNotNull(coherent);
        assertEquals(2, coherent.patchGroups().size(), "both fresh patches added during projection became stable");
        assertEquals(121_000L, coherent.activePatchesObservedAtMs());
    }

    @Test
    public void discoveryPromotesIdentityAndCurrentControlImmediately()
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
        assertEquals(1, stable.channels().size());
        assertTrue(hasChannel(stable, "primary_control", 856137500L));
        assertFalse(hasChannel(stable, "secondary_control", 855987500L));
        assertTrue(stable.neighborSites().isEmpty());
        assertTrue(stable.frequencyBands().isEmpty());

        stabilizer.observe(snapshot, 31_000L);
        stabilizer.observe(snapshot, 61_000L);
        stable = stabilizer.getSnapshot();

        assertEquals(2, stable.channels().size());
        assertTrue(hasChannel(stable, "secondary_control", 855987500L));
        assertEquals(1, stable.neighborSites().size());
        assertEquals(1, stable.frequencyBands().size());
        assertEquals(61_000L, stable.neighborSites().getFirst().observedAtMs());
        assertEquals(61_000L, stable.frequencyBands().getFirst().observedAtMs());
    }

    @Test
    public void secondaryControlRequiresThreeObservationsOverSixtySeconds()
    {
        P25NetworkConfigurationStabilizer stabilizer = seededStabilizer();

        stabilizer.observe(snapshot(secondary(851462500L)), 70000L);
        assertFalse(hasChannel(stabilizer.getSnapshot(), "secondary_control", 851462500L));

        stabilizer.observe(snapshot(secondary(851462500L)), 100000L);
        assertFalse(hasChannel(stabilizer.getSnapshot(), "secondary_control", 851462500L));

        stabilizer.observe(snapshot(secondary(851462500L)), 130000L);

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
    public void candidateResetRetainsStableFactsWithoutTrustingOneSecondaryObservation()
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
        assertFalse(hasChannel(stable, "secondary_control", 851462500L));
        assertEquals(1, stable.neighborSites().size());
        assertEquals(1, stable.frequencyBands().size());
    }

    @Test
    public void capsPromotedControlFrequenciesAtEight()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        List<P25NetworkConfigurationSnapshot.Channel> channels = java.util.stream.LongStream.range(0, 9)
            .mapToObj(index -> new P25NetworkConfigurationSnapshot.Channel("secondary_control", "0-" + index,
                851000000L + index * 12500L, null, false, 1))
            .toList();

        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null, channels,
            List.of(), List.of(), List.of(), List.of()), 1000L);
        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null, channels,
            List.of(), List.of(), List.of(), List.of()), 31_000L);
        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null, channels,
            List.of(), List.of(), List.of(), List.of()), 61_000L);

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
        assertNull(stabilizer.getSnapshot().neighborSites().get(0).downlink());

        stabilizer.observe(snapshot(resolved), 70_000L);
        stabilizer.observe(snapshot(resolved), 100_000L);
        stabilizer.observe(snapshot(resolved), 130_000L);

        assertEquals(1, stabilizer.getSnapshot().neighborSites().size());
        assertEquals(855237500L, stabilizer.getSnapshot().neighborSites().get(0).downlink());
    }

    @Test
    public void neighborChannelChangeUpdatesOneCanonicalNativeSite()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        P25NetworkConfigurationSnapshot.NeighborSite original = neighbor(855237500L);
        P25NetworkConfigurationSnapshot.NeighborSite changed = new P25NetworkConfigurationSnapshot.NeighborSite(
            original.system(), original.nac(), original.rfss(), original.site(), original.lra(), "1-100",
            856_000_000L, 811_000_000L, original.status());

        stabilizer.observe(snapshot(original), 1_000L);
        stabilizer.observe(snapshot(original), 31_000L);
        stabilizer.observe(snapshot(original), 61_000L);
        stabilizer.observe(snapshot(changed), 70_000L);
        stabilizer.observe(snapshot(changed), 100_000L);
        stabilizer.observe(snapshot(changed), 130_000L);

        assertEquals(1, stabilizer.getSnapshot().neighborSites().size());
        assertEquals("1-100", stabilizer.getSnapshot().neighborSites().getFirst().channel());
    }

    @Test
    public void changingChannelRoleDoesNotDuplicateItsNativeDescriptor()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        P25NetworkConfigurationSnapshot.Channel secondary = secondary(855987500L);
        P25NetworkConfigurationSnapshot.Channel primary = new P25NetworkConfigurationSnapshot.Channel(
            "primary_control", secondary.descriptor(), secondary.downlink(), secondary.uplink(), secondary.tdma(),
            secondary.timeslots());

        stabilizer.observe(snapshot(secondary), 1_000L);
        stabilizer.observe(snapshot(primary), 2_000L);

        assertEquals(1, stabilizer.getSnapshot().channels().size());
        assertEquals("primary_control", stabilizer.getSnapshot().channels().getFirst().role());
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
        assertTrue(stabilizer.getSnapshot().foreignSystemBands().stream()
            .map(P25NetworkConfigurationSnapshot.ForeignSystemBand::withoutObservedAt).toList()
            .containsAll(bands));
        assertTrue(stabilizer.getSnapshot().foreignSystemBands().stream()
            .allMatch(band -> band.observedAtMs() == 61_000L));
        assertTrue(stabilizer.getSnapshot().frequencyBands().isEmpty());
    }

    @Test
    public void cachedMergeFactsRetainTheirActualLastObservationTimes()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        P25NetworkConfigurationSnapshot neighbor = snapshot(neighbor(855237500L));
        stabilizer.observe(neighbor, 1_000L);
        stabilizer.observe(neighbor, 31_000L);
        stabilizer.observe(neighbor, 61_000L);
        stabilizer.observeTalkerAlias(700_001, "UNIT 7", 62_000L);
        stabilizer.observeTalkerAlias(700_001, "UNIT 7", 72_000L);

        stabilizer.observe(snapshot(primary(856137500L)), 80_000L);
        P25NetworkConfigurationSnapshot stable = stabilizer.getSnapshot();

        assertEquals(61_000L, stable.neighborSites().getFirst().observedAtMs(),
            "an unrelated newer root observation must not re-stamp a cached neighbor");
        assertEquals(72_000L, stable.talkerAliases().getFirst().observedAtMs(),
            "OTA aliases retain the time that exact alias was last decoded");
        assertEquals(80_000L, stable.channels().getFirst().observedAtMs());
    }

    @Test
    public void expiresDeliveredTalkerAliasesFromTheBoundedReceiverSnapshot()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        stabilizer.observeTalkerAlias(700_001, "UNIT 7", 1_000L);
        stabilizer.observeTalkerAlias(700_001, "UNIT 7", 11_000L);
        assertEquals(1, stabilizer.getSnapshot().talkerAliases().size());

        //A later unrelated observation drives bounded cache maintenance. The server's merge-only canonical alias is
        //not deleted merely because the receiver stops repeating it.
        stabilizer.observe(snapshot(primary(856137500L)), 611_001L);
        assertTrue(stabilizer.getSnapshot().talkerAliases().isEmpty());
    }

    @Test
    public void activePatchSnapshotRequiresSixtySecondsOfContinuousFreshEvidence()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        P25NetworkConfigurationSnapshot empty = new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null,
            List.of(), List.of(), List.of(), List.of(), List.of());

        stabilizer.observe(empty, 1_000L);
        stabilizer.observe(empty, 31_000L);
        assertNull(stabilizer.getSnapshot().activePatchesObservedAtMs());
        stabilizer.observe(empty, 61_000L);
        assertEquals(61_000L, stabilizer.getSnapshot().activePatchesObservedAtMs());

        stabilizer.resetCandidates();
        assertNull(stabilizer.getSnapshot().activePatchesObservedAtMs());
        stabilizer.observe(empty, 70_000L);
        stabilizer.observe(empty, 100_000L);
        stabilizer.observe(empty, 130_000L);
        assertEquals(130_000L, stabilizer.getSnapshot().activePatchesObservedAtMs());
    }

    @Test
    public void patchFreshnessGapRestartsCompletenessAndExpiresOldPatchState()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        PatchGroupIdentifier patch = patchGroup(65191, 9, 40002);
        stabilizer.observePatchGroup(patch, 1_000L);
        stabilizer.observePatchGroup(patch, 11_000L);
        assertEquals(1, stabilizer.getSnapshot().patchGroups().size());

        P25NetworkConfigurationSnapshot empty = new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null,
            List.of(), List.of(), List.of(), List.of(), List.of());
        stabilizer.observe(empty, 41_001L);

        assertTrue(stabilizer.getSnapshot().patchGroups().isEmpty(),
            "the telemetry list uses the PatchGroupManager 30-second freshness boundary");
        assertNull(stabilizer.getSnapshot().activePatchesObservedAtMs(),
            "a control-channel evidence gap restarts complete patch discovery");
    }

    @Test
    public void promotesAccumulatedAlternatingHarrisPhaseOneMembers()
    {
        PatchGroupManager manager = new PatchGroupManager();
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");

        observeAccumulatedPatchGroup(manager, stabilizer, patchGroup(65191, 9, 40002), 1_000L);
        observeAccumulatedPatchGroup(manager, stabilizer, patchGroup(65191, 9, 40003), 11_000L);
        observeAccumulatedPatchGroup(manager, stabilizer, patchGroup(65191, 9, 40002), 21_000L);

        assertEquals(List.of(40002, 40003),
            stabilizer.getSnapshot().patchGroups().getFirst().localTalkgroupIds());
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

        assertEquals(List.of(40002), stabilizer.getSnapshot().patchGroups().getFirst().localTalkgroupIds());
    }

    @Test
    public void fullyQualifiedPatchRadioKeepsItsObservedWorkingAddress()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(65191), 9);
        patchGroup.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createFrom(0xFFFD26, 0xBEE00, 0x954,
            831_102));
        PatchGroupIdentifier identifier = APCO25PatchGroup.create(patchGroup);

        stabilizer.observePatchGroup(identifier, 1_000L);
        stabilizer.observePatchGroup(identifier, 11_000L);

        assertEquals(List.of(0xFFFD26),
            stabilizer.getSnapshot().patchGroups().getFirst().localRadioIds());
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
