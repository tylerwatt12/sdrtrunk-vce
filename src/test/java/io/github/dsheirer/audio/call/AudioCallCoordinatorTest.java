/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.call;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDecisionOutcome;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticDecision;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticSnapshot;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallMergeProof;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallSeparationReason;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallWinnerCriterion;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCallCoordinatorTest
{
    private static final VoiceCallQuality GOOD_QUALITY = new VoiceCallQuality(50, 0, 0, 0, 2, 1_000);
    private static final VoiceCallQuality DAMAGED_QUALITY = new VoiceCallQuality(15, 5, 25, 5, 250, 1_000);

    @Test
    void productionConfigurationUsesShortQuietSettleAndBoundedActiveWait()
    {
        assertEquals(500L, AudioCallCoordinator.ResolverConfiguration.DEFAULT.settleQuietMilliseconds());
        assertEquals(10_000L,
            AudioCallCoordinator.ResolverConfiguration.DEFAULT.activeLegWaitCeilingMilliseconds());
    }

    @Test
    void combinesThreeSiteCopiesIntoOneLogicalCallForEveryConsumer() throws Exception
    {
        AliasList aliasList = aliasList(71);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> web = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, recorded, streamed, web);

        try
        {
            emitLeg(coordinator, leg(1, aliasList, 0x1, 1, 10, 101, 9001, 1_000, 4_000,
                GOOD_QUALITY, true, Set.of(new BroadcastChannel("Calls"))), fingerprints(10));
            emitLeg(coordinator, leg(2, aliasList, 0x1, 1, 11, 102, 9001, 1_100, 4_100,
                GOOD_QUALITY, true, Set.of(new BroadcastChannel("Calls"))), fingerprints(10));
            emitLeg(coordinator, leg(3, aliasList, 0x1, 1, 12, 103, 9001, 1_200, 4_200,
                GOOD_QUALITY, true, Set.of(new BroadcastChannel("Calls"))), fingerprints(10));

            await(() -> resolved.size() == 1 && recorded.size() == 1 && streamed.size() == 1 && web.size() == 1);
            CompletedAudioCall call = resolved.getFirst();
            assertSame(call, recorded.getFirst());
            assertSame(call, streamed.getFirst());
            assertSame(call, web.getFirst());
            assertEquals(3, call.receiverLegCount());
            assertEquals(3, call.callLegSummaries().stream()
                .map(summary -> summary.source().p25SiteIdentity()).distinct().count());
            assertEquals(1, call.callLegSummaries().stream().filter(CallLegSummary::winner).count());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void badlyDamagedCopyIsRetainedAsSiteEvidenceButGoodAudioWins() throws Exception
    {
        AliasList aliasList = aliasList(72);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            Leg bad = leg(10, aliasList, 0x2, 2, 20, 201, 9100, 2_000, 5_000,
                DAMAGED_QUALITY, true, Set.of());
            Leg good = leg(11, aliasList, 0x2, 2, 21, 202, 9100, 2_080, 5_080,
                GOOD_QUALITY, true, Set.of());
            emitLeg(coordinator, bad, fingerprints(30));
            emitLeg(coordinator, good, fingerprints(30));

            await(() -> resolved.size() == 1);
            CompletedAudioCall call = resolved.getFirst();
            assertEquals(good.callId(), call.snapshot().callId(), "The lower-loss copy must own output audio");
            assertEquals(2, call.callLegSummaries().size(), "The damaged site remains an observation");
            CallLegSummary damaged = call.callLegSummaries().stream()
                .filter(summary -> summary.callLegId().equals(bad.callLegId())).findFirst().orElseThrow();
            assertFalse(damaged.winner());
            assertEquals(DAMAGED_QUALITY, damaged.voiceCallQuality());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void usableVoiceCoverageWinsBeforeOneExtraConcealedFrame() throws Exception
    {
        AliasList aliasList = aliasList(720);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<LogicalCallDiagnosticDecision> decisions = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            256, 32, 25, 150, 32, 16, 1_000_000, 4_000_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null,
            configuration, decision -> {
                decisions.add(decision);
                return true;
            });
        VoiceCallQuality slightlyShorter = new VoiceCallQuality(164, 7, 0, 9, 704, 23_427);
        VoiceCallQuality betterCoverage = new VoiceCallQuality(167, 3, 1, 9, 101, 23_427);

        try
        {
            Leg shorter = leg(14, aliasList, 0xBEE00, 0x348, 2, 7, 9_102, 2_000, 5_600,
                slightlyShorter, true, Set.of());
            Leg better = leg(15, aliasList, 0xBEE00, 0x348, 2, 1, 9_102, 2_040, 5_640,
                betterCoverage, true, Set.of());
            emitLeg(coordinator, shorter, fingerprints(40));
            emitLeg(coordinator, better, fingerprints(40));

            await(() -> resolved.size() == 1 && decisions.size() == 1);
            LogicalCallDiagnosticDecision decision = decisions.getFirst();
            assertEquals(better.callId(), resolved.getFirst().snapshot().callId(),
                "Three additional usable frames must outweigh one additional concealed frame");
            assertEquals(LogicalCallWinnerCriterion.USABLE_FRAME_COUNT, decision.winner().criterion());
            assertEquals(Long.valueOf(167L), decision.winner().winnerValue().numerator());
            assertEquals(Long.valueOf(180L), decision.winner().winnerValue().denominator());
            assertEquals(Long.valueOf(164L), decision.winner().runnerUpValue().numerator());
            assertEquals(Long.valueOf(180L), decision.winner().runnerUpValue().denominator());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void exactIdentifiersFromDamagedLegArePromotedWithoutChangingAudioWinner() throws Exception
    {
        AliasList aliasList = aliasList(721);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);
        int wacn = 0xABCDE;
        int system = 0x123;
        int talkgroup = 9_101;
        int localRadio = 7_777;
        int homeRadio = 0x456789;

        try
        {
            Leg good = leg(12, aliasList, wacn, system, 22, 203, talkgroup, localRadio,
                2_000, 5_000, GOOD_QUALITY, true, Set.of());
            Leg damaged = leg(13, aliasList, wacn, system, 23, 204, talkgroup, localRadio,
                2_080, 5_080, DAMAGED_QUALITY, true, Set.of());
            IdentifierCollection exactIdentifiers = new IdentifierCollection(List.of(
                APCO25FullyQualifiedTalkgroupIdentifier.createTo(talkgroup, wacn, system, talkgroup),
                APCO25FullyQualifiedRadioIdentifier.createFrom(localRadio, wacn, system, homeRadio)));

            emitLeg(coordinator, good, fingerprints(35));
            emitLeg(coordinator, damaged, fingerprints(35), exactIdentifiers);

            await(() -> resolved.size() == 1);
            CompletedAudioCall call = resolved.getFirst();
            assertEquals(good.callId(), call.snapshot().callId(), "Good audio remains the global winner");
            assertTrue(call.snapshot().identifierCollection().getToIdentifier() instanceof
                APCO25FullyQualifiedTalkgroupIdentifier);
            assertTrue(call.snapshot().identifierCollection().getFromIdentifier() instanceof
                APCO25FullyQualifiedRadioIdentifier);
            APCO25FullyQualifiedTalkgroupIdentifier target =
                (APCO25FullyQualifiedTalkgroupIdentifier)call.snapshot().identifierCollection().getToIdentifier();
            APCO25FullyQualifiedRadioIdentifier source =
                (APCO25FullyQualifiedRadioIdentifier)call.snapshot().identifierCollection().getFromIdentifier();
            assertEquals(wacn, target.getWacn());
            assertEquals(system, target.getSystem());
            assertEquals(talkgroup, target.getTalkgroup());
            assertEquals(wacn, source.getWacn());
            assertEquals(system, source.getSystem());
            assertEquals(homeRadio, source.getRadio());
            assertEquals("APCO25:fq:" + wacn + ':' + system + ':' + talkgroup,
                call.snapshot().recordingMetadata().destinationIdentity());
            assertEquals(wacn + "." + system + "." + homeRadio,
                call.snapshot().recordingMetadata().sourceValue());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void contradictoryFullyQualifiedSourcesRemainSeparate() throws Exception
    {
        AliasList aliasList = aliasList(722);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);
        int wacn = 0xABCDE;
        int system = 0x124;
        int talkgroup = 9_102;
        int localRadio = 7_778;

        try
        {
            Leg first = leg(14, aliasList, wacn, system, 24, 205, talkgroup, localRadio,
                2_000, 5_000, GOOD_QUALITY, true, Set.of());
            Leg second = leg(15, aliasList, wacn, system, 25, 206, talkgroup, localRadio,
                2_050, 5_050, GOOD_QUALITY, true, Set.of());
            IdentifierCollection firstIdentifiers = new IdentifierCollection(List.of(
                APCO25Talkgroup.create(talkgroup),
                APCO25FullyQualifiedRadioIdentifier.createFrom(localRadio, wacn, system, 100_001)));
            IdentifierCollection secondIdentifiers = new IdentifierCollection(List.of(
                APCO25Talkgroup.create(talkgroup),
                APCO25FullyQualifiedRadioIdentifier.createFrom(localRadio, wacn, system, 100_002)));

            emitLeg(coordinator, first, fingerprints(36), firstIdentifiers);
            emitLeg(coordinator, second, fingerprints(36), secondIdentifiers);

            await(() -> resolved.size() == 2);
            assertTrue(resolved.stream().allMatch(call -> call.receiverLegCount() == 1));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void missingLearnedIdentityOrAliasIdentityFailsOpen() throws Exception
    {
        AliasList aliasList = aliasList(73);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            Leg missingSite = leg(20, aliasList, null, 0, 0, 0, 9200, 1_000, 2_000,
                GOOD_QUALITY, true, Set.of());
            AliasList unassigned = aliasList(0);
            Leg missingAliasId = leg(21, unassigned, 0x3, 3, 30, 31, 9200, 1_020, 2_020,
                GOOD_QUALITY, true, Set.of());
            emitLeg(coordinator, missingSite, fingerprints(40));
            emitLeg(coordinator, missingAliasId, fingerprints(40));

            await(() -> resolved.size() == 2);
            assertNotEquals(resolved.get(0).logicalCallId(), resolved.get(1).logicalCallId());
            assertTrue(resolved.stream().allMatch(call -> call.receiverLegCount() == 1));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void unknownEncryptionStateNeverQualifiesForMerging() throws Exception
    {
        AliasList aliasList = aliasList(731);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            Leg first = leg(22, aliasList, 0x3, 3, 31, 32, 9201, 9_201,
                1_000, 3_000, GOOD_QUALITY, true, Set.of());
            Leg second = leg(23, aliasList, 0x3, 3, 32, 33, 9201, 9_201,
                1_050, 3_050, GOOD_QUALITY, true, Set.of());
            emitLegWithEncryptionState(coordinator, first, CallEncryptionState.UNKNOWN);
            emitLegWithEncryptionState(coordinator, second, CallEncryptionState.UNKNOWN);

            await(() -> resolved.size() == 2);
            assertTrue(resolved.stream().allMatch(call -> call.receiverLegCount() == 1));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void differentWacnSystemOrAliasListNeverCombines() throws Exception
    {
        AliasList aliasList = aliasList(74);
        AliasList otherAliasList = aliasList(75);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            emitLeg(coordinator, leg(30, aliasList, 0x4, 4, 40, 41, 9300, 1_000, 2_500,
                GOOD_QUALITY, true, Set.of()), fingerprints(50));
            emitLeg(coordinator, leg(31, aliasList, 0x5, 4, 41, 42, 9300, 1_010, 2_510,
                GOOD_QUALITY, true, Set.of()), fingerprints(50));
            emitLeg(coordinator, leg(32, aliasList, 0x4, 5, 42, 43, 9300, 1_020, 2_520,
                GOOD_QUALITY, true, Set.of()), fingerprints(50));
            emitLeg(coordinator, leg(33, otherAliasList, 0x4, 4, 43, 44, 9300, 1_030, 2_530,
                GOOD_QUALITY, true, Set.of()), fingerprints(50));

            await(() -> resolved.size() == 4);
            assertTrue(resolved.stream().allMatch(call -> call.receiverLegCount() == 1));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void quickReplyFromSameRadioDoesNotCombineWithoutIntervalOverlap() throws Exception
    {
        AliasList aliasList = aliasList(76);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            emitLeg(coordinator, leg(40, aliasList, 0x6, 6, 60, 61, 9400, 1_000, 1_800,
                GOOD_QUALITY, true, Set.of()), fingerprints(60));
            emitLeg(coordinator, leg(41, aliasList, 0x6, 6, 61, 62, 9400, 1_801, 2_600,
                GOOD_QUALITY, true, Set.of()), fingerprints(60));

            await(() -> resolved.size() == 2);
            assertTrue(resolved.stream().allMatch(call -> call.receiverLegCount() == 1));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void matchingVoiceFramesCanProveDuplicateWhenSourceRadioIsMissing() throws Exception
    {
        AliasList aliasList = aliasList(77);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            emitLeg(coordinator, leg(50, aliasList, 0x7, 7, 70, 71, 9500, null,
                1_000, 3_000, GOOD_QUALITY, true, Set.of()), fingerprints(70));
            emitLeg(coordinator, leg(51, aliasList, 0x7, 7, 71, 72, 9500, null,
                1_100, 3_100, GOOD_QUALITY, true, Set.of()), fingerprints(70));

            await(() -> resolved.size() == 1);
            assertEquals(2, resolved.getFirst().receiverLegCount());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void alignedVoiceFramesProveLateEntryWithoutCallStartLimit() throws Exception
    {
        AliasList aliasList = aliasList(771);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            Leg full = leg(52, aliasList, 0x7, 7, 70, 73, 9501, null,
                1_000, 10_000, GOOD_QUALITY, true, Set.of());
            Leg late = leg(53, aliasList, 0x7, 7, 71, 74, 9501, null,
                7_000, 10_100, GOOD_QUALITY, true, Set.of());
            List<Long> shared = fingerprints(73);
            emitLegWithFrameEvidence(coordinator, full, shared, List.of(8_000L, 8_020L, 8_040L));
            emitLegWithFrameEvidence(coordinator, late, shared, List.of(8_075L, 8_095L, 8_115L));

            await(() -> resolved.size() == 1);
            assertEquals(2, resolved.getFirst().receiverLegCount());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void sharedHashesWithoutConsistentCarrierTimingFailOpen() throws Exception
    {
        AliasList aliasList = aliasList(772);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            Leg first = leg(54, aliasList, 0x7, 7, 72, 75, 9502, null,
                1_000, 4_000, GOOD_QUALITY, true, Set.of());
            Leg second = leg(55, aliasList, 0x7, 7, 73, 76, 9502, null,
                1_000, 4_000, GOOD_QUALITY, true, Set.of());
            List<Long> shared = fingerprints(76);
            emitLegWithFrameEvidence(coordinator, first, shared, List.of(2_000L, 2_020L, 2_040L));
            emitLegWithFrameEvidence(coordinator, second, shared, List.of(2_100L, 2_300L, 2_500L));

            await(() -> resolved.size() == 2);
            assertTrue(resolved.stream().allMatch(call -> call.receiverLegCount() == 1));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void unknownSourceWithoutSharedContentFailsOpen() throws Exception
    {
        AliasList aliasList = aliasList(78);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            emitLeg(coordinator, leg(60, aliasList, 0x8, 8, 80, 81, 9600, null,
                1_000, 3_000, GOOD_QUALITY, true, Set.of()), fingerprints(80));
            emitLeg(coordinator, leg(61, aliasList, 0x8, 8, 81, 82, 9600, null,
                1_100, 3_100, GOOD_QUALITY, true, Set.of()), fingerprints(90));

            await(() -> resolved.size() == 2);
            assertTrue(resolved.stream().allMatch(call -> call.receiverLegCount() == 1));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void contentRichCallsWithoutSharedVoiceProofStaySeparateDespiteMatchingSource() throws Exception
    {
        AliasList aliasList = aliasList(84);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            emitLeg(coordinator, leg(120, aliasList, 0xD, 13, 130, 131, 10_200, 7_700,
                1_000, 3_000, GOOD_QUALITY, true, Set.of()), fingerprints(150));
            emitLeg(coordinator, leg(121, aliasList, 0xD, 13, 131, 132, 10_200, 7_700,
                1_050, 3_050, GOOD_QUALITY, true, Set.of()), fingerprints(160));

            await(() -> resolved.size() == 2);
            assertTrue(resolved.stream().allMatch(call -> call.receiverLegCount() == 1));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void overlappingDamagedCopyWithoutContentStillCombinesByExactSource() throws Exception
    {
        AliasList aliasList = aliasList(85);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            Leg good = leg(130, aliasList, 0xE, 14, 140, 141, 10_300, 8_800,
                1_000, 10_000, GOOD_QUALITY, true, Set.of());
            Leg damaged = leg(131, aliasList, 0xE, 14, 141, 142, 10_300, 8_800,
                7_000, 7_901, DAMAGED_QUALITY, true, Set.of());
            emitLeg(coordinator, good, fingerprints(170));
            emitLeg(coordinator, damaged, List.of());

            await(() -> resolved.size() == 1);
            assertEquals(2, resolved.getFirst().receiverLegCount());
            assertEquals(good.callId(), resolved.getFirst().snapshot().callId());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void sourceFallbackRejectsWeakBoundaryOverlap() throws Exception
    {
        AliasList aliasList = aliasList(851);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            emitLeg(coordinator, leg(132, aliasList, 0xE, 14, 142, 143, 10_301, 8_801,
                1_000, 3_000, GOOD_QUALITY, true, Set.of()), List.of());
            emitLeg(coordinator, leg(133, aliasList, 0xE, 14, 143, 144, 10_301, 8_801,
                2_600, 4_000, DAMAGED_QUALITY, true, Set.of()), List.of());

            await(() -> resolved.size() == 2);
            assertTrue(resolved.stream().allMatch(call -> call.receiverLegCount() == 1));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void sourceFallbackMergesContentRichDamagedCopyWhenCarrierTimelinesOverlap() throws Exception
    {
        AliasList aliasList = aliasList(852);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            Leg good = leg(134, aliasList, 0xE, 14, 144, 145, 10_302, 8_802,
                1_000, 4_000, GOOD_QUALITY, true, Set.of());
            Leg damaged = leg(135, aliasList, 0xE, 14, 145, 146, 10_302, 8_802,
                1_050, 4_050, DAMAGED_QUALITY, true, Set.of());
            emitLegWithFrameEvidence(coordinator, good, fingerprintRange(1_000L, 31),
                frameTimestamps(2_000L, 31));
            emitLegWithFrameEvidence(coordinator, damaged, fingerprintRange(2_000L, 31),
                frameTimestamps(2_050L, 31));

            await(() -> resolved.size() == 1);
            assertEquals(2, resolved.getFirst().receiverLegCount());
            assertEquals(good.callId(), resolved.getFirst().snapshot().callId());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void sourceFallbackRejectsSequentialFrameTimelinesDespiteOverlappingCallObjects() throws Exception
    {
        AliasList aliasList = aliasList(853);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            Leg first = leg(136, aliasList, 0xE, 14, 146, 147, 10_303, 8_803,
                1_000, 4_000, GOOD_QUALITY, true, Set.of());
            Leg reply = leg(137, aliasList, 0xE, 14, 147, 148, 10_303, 8_803,
                1_000, 4_000, GOOD_QUALITY, true, Set.of());
            emitLegWithFrameEvidence(coordinator, first, fingerprintRange(3_000L, 31),
                frameTimestamps(2_000L, 31));
            emitLegWithFrameEvidence(coordinator, reply, fingerprintRange(4_000L, 31),
                frameTimestamps(2_700L, 31));

            await(() -> resolved.size() == 2);
            assertTrue(resolved.stream().allMatch(call -> call.receiverLegCount() == 1));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void matchingEncryptedMessageIndicatorCombinesUnknownSourceMetadataLegs() throws Exception
    {
        AliasList aliasList = aliasList(86);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> web = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, recorded, streamed, web);

        try
        {
            CallEncryptionEvidence firstEvidence = new CallEncryptionEvidence(0x84, 0x1001, 0x1234L);
            CallEncryptionEvidence secondEvidence = new CallEncryptionEvidence(0x81, 0x2002, 0x1234L);
            emitLeg(coordinator, encryptedLeg(140, aliasList, 0xF, 15, 150, 151, 10_400,
                1_000, 3_000, firstEvidence), List.of());
            emitLeg(coordinator, encryptedLeg(141, aliasList, 0xF, 15, 151, 152, 10_400,
                1_100, 3_100, secondEvidence), List.of());

            await(() -> resolved.size() == 1);
            assertEquals(2, resolved.getFirst().receiverLegCount());
            assertFalse(resolved.getFirst().hasAudio());
            assertTrue(recorded.isEmpty(), "Metadata-only calls must not reach recording");
            assertTrue(streamed.isEmpty(), "Metadata-only calls must not reach streaming");
            assertTrue(web.isEmpty(), "Metadata-only calls must not reach audio-oriented browser output");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void missingOrDifferentEncryptedMessageIndicatorFailsOpenForUnknownSource() throws Exception
    {
        AliasList aliasList = aliasList(87);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            emitLeg(coordinator, encryptedLeg(150, aliasList, 0x10, 16, 160, 161, 10_500,
                1_000, 3_000, new CallEncryptionEvidence(0x84, 0x1001, 0L)), List.of());
            emitLeg(coordinator, encryptedLeg(151, aliasList, 0x10, 16, 161, 162, 10_500,
                1_050, 3_050, new CallEncryptionEvidence(0x84, 0x1001, 0x1111L)), List.of());
            emitLeg(coordinator, encryptedLeg(152, aliasList, 0x10, 16, 162, 163, 10_500,
                1_100, 3_100, new CallEncryptionEvidence(0x84, 0x1001, 0x2222L)), List.of());

            await(() -> resolved.size() == 3);
            assertTrue(resolved.stream().allMatch(call -> call.receiverLegCount() == 1));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void pairwiseCliqueRulePreventsTransitiveBridge() throws Exception
    {
        AliasList aliasList = aliasList(79);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            emitLeg(coordinator, leg(70, aliasList, 0x9, 9, 90, 91, 9700, null,
                1_000, 3_000, GOOD_QUALITY, true, Set.of()), List.of(10L, 11L, 12L));
            emitLeg(coordinator, leg(71, aliasList, 0x9, 9, 91, 92, 9700, null,
                1_050, 3_050, GOOD_QUALITY, true, Set.of()),
                List.of(10L, 11L, 12L, 20L, 21L, 22L));
            emitLeg(coordinator, leg(72, aliasList, 0x9, 9, 92, 93, 9700, null,
                1_100, 3_100, GOOD_QUALITY, true, Set.of()), List.of(20L, 21L, 22L));

            await(() -> resolved.size() == 2);
            assertEquals(List.of(1, 2), resolved.stream().map(CompletedAudioCall::receiverLegCount).sorted().toList());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void linkedDurationChunksRemainOnePhysicalLeg() throws Exception
    {
        AliasList aliasList = aliasList(80);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);
        CallLegId stableLegId = new CallLegId(80, 900, 0);

        try
        {
            Leg first = leg(80, 1, stableLegId, aliasList, 0xA, 10, 100, 101, 9800,
                9_001, 1_000, 2_000, GOOD_QUALITY, true, Set.of());
            Leg second = leg(80, 2, stableLegId, aliasList, 0xA, 10, 100, 101, 9800,
                9_001, 2_001, 3_000, GOOD_QUALITY, true, Set.of());
            emitChunk(coordinator, first, fingerprints(100), true);
            emitChunk(coordinator, second, fingerprints(110), false);

            await(() -> resolved.size() == 1);
            assertEquals(1, resolved.getFirst().receiverLegCount());
            assertEquals(6, resolved.getFirst().audioBuffers().size());
            assertEquals(1_000L, resolved.getFirst().snapshot().startTimestamp());
            assertEquals(3_000L, resolved.getFirst().snapshot().lastActivityTimestamp());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void outputPolicyIsUnionedBeforeFanout() throws Exception
    {
        AliasList aliasList = aliasList(81);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            emitLeg(coordinator, leg(90, aliasList, 0xB, 11, 110, 111, 9900, 1_000, 3_000,
                GOOD_QUALITY, false, Set.of(new BroadcastChannel("North"))), fingerprints(120));
            emitLeg(coordinator, leg(91, aliasList, 0xB, 11, 111, 112, 9900, 1_100, 3_100,
                GOOD_QUALITY, true, Set.of(new BroadcastChannel("South"))), fingerprints(120));

            await(() -> resolved.size() == 1);
            CompletedAudioCall call = resolved.getFirst();
            assertTrue(call.snapshot().recordAudio());
            assertEquals(Set.of("North", "South"), call.resolvedPolicy().broadcastRoutingKeys());
            assertEquals(Set.of(new BroadcastChannel("North"), new BroadcastChannel("South")),
                call.snapshot().broadcastChannels());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void tiedQualityUsesExistingSiteGuidDeterministically() throws Exception
    {
        AliasList aliasList = aliasList(82);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = coordinator(resolved, null, null, null);

        try
        {
            Leg zulu = leg(100, aliasList, 0xC, 12, 120, 121, 10_000, 1_000, 3_000,
                GOOD_QUALITY, true, Set.of(), "zulu-site");
            Leg alpha = leg(101, aliasList, 0xC, 12, 121, 122, 10_000, 1_050, 3_050,
                GOOD_QUALITY, true, Set.of(), "alpha-site");
            emitLeg(coordinator, zulu, fingerprints(130));
            emitLeg(coordinator, alpha, fingerprints(130));

            await(() -> resolved.size() == 1);
            assertEquals(alpha.callId(), resolved.getFirst().snapshot().callId());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void blockedObserverNeverRunsOnOrBlocksReceiverThread() throws Exception
    {
        CountDownLatch consumerEntered = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        AtomicReference<Thread> consumerThread = new AtomicReference<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            8, 2, 0, 0, 8, 8, 100_000, 200_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(call -> {
            consumerThread.set(Thread.currentThread());
            consumerEntered.countDown();

            try
            {
                releaseConsumer.await(2, TimeUnit.SECONDS);
            }
            catch(InterruptedException _)
            {
                Thread.currentThread().interrupt();
            }
        }, null, null, null, configuration);

        try
        {
            AliasList aliasList = aliasList(83);
            Leg independent = leg(110, aliasList, null, 0, 0, 0, 10_100, 1_000, 2_000,
                GOOD_QUALITY, true, Set.of());
            emitLeg(coordinator, independent, fingerprints(140));
            assertTrue(consumerEntered.await(1, TimeUnit.SECONDS));
            Thread receiverThread = Thread.currentThread();
            AudioCallSnapshot active = snapshot(leg(111, aliasList, null, 0, 0, 0, 10_101,
                3_000, 4_000, GOOD_QUALITY, true, Set.of()), false);
            long started = System.nanoTime();

            for(int index = 0; index < 2_000; index++)
            {
                coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, active,
                    new float[160], false, index + 1L, active.lastActivityTimestamp() + index));
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 1_000L, "Bounded offers must return while the observer is blocked");
            assertNotEquals(receiverThread, consumerThread.get());
            assertTrue(coordinator.getQueueStatus().droppedIngress() > 0L);
        }
        finally
        {
            releaseConsumer.countDown();
            coordinator.dispose();
        }
    }

    @Test
    void timedOutDisposeFencesOptionalFanoutBeforeDownstreamClosure() throws Exception
    {
        CountDownLatch consumerEntered = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        AtomicBoolean downstreamClosed = new AtomicBoolean();
        AtomicInteger resolvedInvocations = new AtomicInteger();
        AtomicInteger recordingAfterClose = new AtomicInteger();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            64, 8, 0, 0, 8, 8, 100_000, 200_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(call -> {
            resolvedInvocations.incrementAndGet();
            consumerEntered.countDown();

            while(releaseConsumer.getCount() > 0)
            {
                try
                {
                    releaseConsumer.await(100, TimeUnit.MILLISECONDS);
                }
                catch(InterruptedException _)
                {
                    //Simulate a downstream handoff that does not return within the coordinator's bounded drain.
                }
            }
        }, call -> {
            if(downstreamClosed.get())
            {
                recordingAfterClose.incrementAndGet();
            }
        }, null, null, configuration);

        try
        {
            AliasList aliasList = aliasList(843);
            emitLeg(coordinator, leg(501, aliasList, null, 0, 0, 0, 20_200,
                1_000, 2_000, GOOD_QUALITY, true, Set.of()), fingerprints(266));
            assertTrue(consumerEntered.await(1, TimeUnit.SECONDS));
            emitLeg(coordinator, leg(502, aliasList, null, 0, 0, 0, 20_201,
                3_000, 4_000, GOOD_QUALITY, true, Set.of()), fingerprints(276));

            coordinator.dispose();
            downstreamClosed.set(true);
            releaseConsumer.countDown();
            await(() -> coordinator.getDiagnosticSnapshot().disposed());
            assertEquals(1, resolvedInvocations.get(),
                "Queued calls must be discarded after the bounded shutdown expires");
            assertEquals(0, recordingAfterClose.get(),
                "The in-progress call must not continue into optional fanout after downstream closure");
        }
        finally
        {
            releaseConsumer.countDown();
            coordinator.dispose();
        }
    }

    @Test
    void rejectedLegIsAbortedWithoutDiscardingAcceptedUnrelatedCall() throws Exception
    {
        CountDownLatch consumerEntered = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            8, 2, 0, 0, 8, 8, 100_000, 200_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(call -> {
            if(consumerEntered.getCount() > 0)
            {
                consumerEntered.countDown();

                try
                {
                    releaseConsumer.await(2, TimeUnit.SECONDS);
                }
                catch(InterruptedException _)
                {
                    Thread.currentThread().interrupt();
                }
            }

            resolved.add(call);
        }, null, null, null, configuration);

        try
        {
            AliasList aliasList = aliasList(831);
            Leg seed = leg(112, aliasList, null, 0, 0, 0, 10_110, 1_000, 2_000,
                GOOD_QUALITY, true, Set.of());
            emitLeg(coordinator, seed, fingerprints(141));
            assertTrue(consumerEntered.await(1, TimeUnit.SECONDS));

            Leg compromised = leg(113, aliasList, null, 0, 0, 0, 10_111, 3_000, 4_000,
                GOOD_QUALITY, true, Set.of());
            Leg healthy = leg(114, aliasList, null, 0, 0, 0, 10_112, 3_000, 4_000,
                GOOD_QUALITY, true, Set.of());
            AudioCallSnapshot compromisedSnapshot = snapshot(compromised, false);
            AudioCallSnapshot healthySnapshot = snapshot(healthy, false);
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, compromisedSnapshot, null,
                false, 0L, 0L));
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, healthySnapshot, null,
                false, 0L, 0L));
            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, healthySnapshot,
                new float[160], false, 1L, healthySnapshot.lastActivityTimestamp()));
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, snapshot(healthy, true), null,
                false, 0L, 0L));

            for(int index = 0; index < 4; index++)
            {
                coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, compromisedSnapshot,
                    new float[160], false, index + 10L, compromisedSnapshot.lastActivityTimestamp() + index));
            }

            //The queue is physically full.  This ordinary rejection also forces the reserved ABORT_LEG offer to
            //fail, exercising the stable per-leg latch fallback without clearing accepted unrelated commands.
            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, compromisedSnapshot,
                new float[160], false, 99L, compromisedSnapshot.lastActivityTimestamp() + 99L));
            AudioCallCoordinator.CoordinatorQueueStatus saturated = coordinator.getQueueStatus();
            assertEquals(8, saturated.ingressDepth());
            assertEquals(1, saturated.abortedCalls());
            assertTrue(saturated.droppedIngress() >= 1L);
            assertTrue(saturated.droppedLifecycle() >= 1L,
                "The rejected reserved abort command must be observable");

            releaseConsumer.countDown();
            await(() -> resolved.stream().anyMatch(call -> healthy.callId().equals(call.snapshot().callId())));
            Thread.sleep(50L);
            assertFalse(resolved.stream().anyMatch(call -> compromised.callId().equals(call.snapshot().callId())),
                "A partial compromised leg must never fan out");
            assertTrue(resolved.stream().anyMatch(call -> seed.callId().equals(call.snapshot().callId())));
        }
        finally
        {
            releaseConsumer.countDown();
            coordinator.dispose();
        }
    }

    @Test
    void activeLegLimitPermanentlyCompromisesRejectedLegOnly() throws Exception
    {
        AliasList aliasList = aliasList(832);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            16, 4, 0, 0, 1, 4, 100_000, 200_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null, configuration);

        try
        {
            Leg occupying = leg(115, aliasList, null, 0, 0, 0, 10_113, 1_000, 2_000,
                GOOD_QUALITY, true, Set.of());
            Leg rejected = leg(116, aliasList, null, 0, 0, 0, 10_114, 1_000, 2_000,
                GOOD_QUALITY, true, Set.of());
            Leg laterHealthy = leg(117, aliasList, null, 0, 0, 0, 10_115, 3_000, 4_000,
                GOOD_QUALITY, true, Set.of());
            AudioCallSnapshot occupyingActive = snapshot(occupying, false);
            AudioCallSnapshot rejectedActive = snapshot(rejected, false);
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, occupyingActive, null,
                false, 0L, 0L));
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, rejectedActive, null,
                false, 0L, 0L));
            await(() -> coordinator.getQueueStatus().abortedCalls() == 1L);

            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, occupyingActive,
                new float[160], false, 1L, occupyingActive.lastActivityTimestamp()));
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED,
                snapshot(occupying, true), null, false, 0L, 0L));
            await(() -> resolved.stream().anyMatch(call -> occupying.callId().equals(call.snapshot().callId())));

            //Capacity is free now, but the stable rejection latch must prevent these later events from constructing a
            //partial call out of the tail of the rejected leg.
            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, rejectedActive,
                new float[160], false, 2L, rejectedActive.lastActivityTimestamp()));
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED,
                snapshot(rejected, true), null, false, 0L, 0L));
            emitLeg(coordinator, laterHealthy, fingerprints(142));

            await(() -> resolved.stream().anyMatch(call -> laterHealthy.callId().equals(call.snapshot().callId())));
            Thread.sleep(50L);
            assertFalse(resolved.stream().anyMatch(call -> rejected.callId().equals(call.snapshot().callId())));
            assertEquals(2, resolved.size(), "Only the occupying and later healthy calls should fan out");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void cohortSweepReplacesRatherThanMultipliesPendingDeadlineTasks() throws Exception
    {
        AliasList aliasList = aliasList(833);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            64, 8, 5_000, 5_000, 8, 4, 100_000, 200_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null, configuration);

        try
        {
            emitLeg(coordinator, leg(118, aliasList, 0x11, 17, 170, 171, 10_116,
                1_000, 2_000, GOOD_QUALITY, true, Set.of()), fingerprints(143));
            emitLeg(coordinator, leg(119, aliasList, 0x11, 17, 171, 172, 10_117,
                1_000, 2_000, GOOD_QUALITY, true, Set.of()), fingerprints(144));
            ScheduledThreadPoolExecutor scheduler = deadlineScheduler(coordinator);
            await(() -> scheduler.getQueue().size() == 2);
            AtomicBoolean sweepRequested = cohortSweepRequested(coordinator);
            sweepRequested.set(true);

            //Any accepted command unparks the owner.  The sweep occurs before this unrelated active leg is handled.
            Leg wake = leg(120, aliasList, null, 0, 0, 0, 10_118, 3_000, 4_000,
                GOOD_QUALITY, true, Set.of());
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, snapshot(wake, false), null,
                false, 0L, 0L));
            //The request flag is claimed before the owner finishes rescheduling every cohort.  Wait for the
            //observable sweep result as well so this assertion cannot sample the scheduler midway through the sweep.
            await(() -> !sweepRequested.get() && scheduler.getQueue().size() == 2);
            assertEquals(2, scheduler.getQueue().size(),
                "Each unresolved cohort must retain exactly one pending deadline after an owner sweep");
            assertTrue(resolved.isEmpty(), "The five-second deadline must still be pending");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void longFingerprintHistoriesDoNotRefreshAwaitedLegScopePerFrame() throws Exception
    {
        AliasList aliasList = aliasList(834);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            16_384, 512, 60_000, 60_000, 8, 8, 2_000_000, 4_000_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null, configuration);

        try
        {
            List<Long> longHistory = fingerprintRange(2_000, 6_000);
            Leg first = leg(120, aliasList, 0x11, 17, 170, 171, 10_118,
                1_000, 121_000, GOOD_QUALITY, true, Set.of());
            Leg second = leg(121, aliasList, 0x11, 17, 171, 172, 10_118,
                1_020, 121_020, GOOD_QUALITY, true, Set.of());
            emitLeg(coordinator, first, longHistory);
            ScheduledThreadPoolExecutor scheduler = deadlineScheduler(coordinator);
            await(() -> scheduler.getQueue().size() == 1 &&
                coordinator.getDiagnosticSnapshot().counters().completedReceiverLegs() == 1L);
            long refreshBaseline = coordinatorLongField(coordinator, "mAwaitedLegFullRefreshCount");
            long comparisonBaseline = coordinatorLongField(coordinator, "mAwaitedLegScopeComparisonCount");

            emitLeg(coordinator, second, longHistory);
            await(() -> coordinator.getDiagnosticSnapshot().counters().completedReceiverLegs() == 2L);

            assertEquals(1L,
                coordinatorLongField(coordinator, "mAwaitedLegFullRefreshCount") - refreshBaseline,
                "One active scope refresh is enough for all 6,000 frames that reuse the same snapshot");
            assertEquals(1L,
                coordinatorLongField(coordinator, "mAwaitedLegScopeComparisonCount") - comparisonBaseline,
                "Awaited-leg matching must compare cached scope once, not scan/copy fingerprints per frame");
            assertEquals(0L, coordinator.getQueueStatus().droppedIngress());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void totalCohortsAndDeadlineTasksStayBoundedByMaximumActiveLegs() throws Exception
    {
        AliasList aliasList = aliasList(835);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<LogicalCallDiagnosticDecision> decisions = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            256, 32, 60_000, 60_000, 4, 4, 100_000, 400_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null, configuration,
            decision -> {
                decisions.add(decision);
                return true;
            });

        try
        {
            for(int index = 0; index < 12; index++)
            {
                emitLeg(coordinator, leg(130 + index, aliasList, 0x12, 18, 180, 181 + index,
                    20_000 + index, 1_000, 2_000, GOOD_QUALITY, true, Set.of()), List.of());
            }

            ScheduledThreadPoolExecutor scheduler = deadlineScheduler(coordinator);
            await(() -> resolved.size() == 8 && scheduler.getQueue().size() == 4);
            assertEquals(4, coordinatorMapSize(coordinator, "mCohorts"));
            assertEquals(4, scheduler.getQueue().size(),
                "Each retained cohort may own at most one pending deadline task");
            assertEquals(8, decisions.stream()
                .filter(decision -> decision.outcome() == LogicalCallDecisionOutcome.FAIL_OPEN &&
                    decision.decisionReasons().contains(LogicalCallSeparationReason.COHORT_CAPACITY)).count(),
                "Every call beyond the fixed cohort bound must be preserved as an independent fail-open output");
            assertEquals(0L, coordinator.getQueueStatus().droppedIngress());

            coordinator.dispose();
            assertEquals(12, resolved.size(), "Normal shutdown must flush the four bounded retained cohorts");
            assertEquals(0, scheduler.getQueue().size(), "Shutdown must remove every retained deadline task");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void diagnosticsExposeMergeProofWinnerMetadataAndWorkerOnlySink() throws Exception
    {
        AliasList aliasList = aliasList(834);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> web = new CopyOnWriteArrayList<>();
        List<LogicalCallDiagnosticDecision> decisions = new CopyOnWriteArrayList<>();
        AtomicReference<Thread> sinkThread = new AtomicReference<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            256, 32, 25, 150, 32, 16, 1_000_000, 4_000_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, web::add,
            configuration, decision -> {
                sinkThread.compareAndSet(null, Thread.currentThread());
                decisions.add(decision);
                return true;
            });

        try
        {
            Leg damaged = leg(121, aliasList, 0x12345, 0x234, 18, 181, 10_119, 9_001,
                1_000, 4_000, DAMAGED_QUALITY, true, Set.of(new BroadcastChannel("South")));
            Leg good = leg(122, aliasList, 0x12345, 0x234, 18, 182, 10_119, 9_001,
                1_080, 4_080, GOOD_QUALITY, true, Set.of(new BroadcastChannel("North")));
            emitLeg(coordinator, damaged, fingerprints(145));
            emitLeg(coordinator, good, fingerprints(145));

            await(() -> decisions.size() == 1 && resolved.size() == 1 && web.size() == 1);
            LogicalCallDiagnosticDecision decision = decisions.getFirst();
            assertEquals(LogicalCallDecisionOutcome.MERGED, decision.outcome());
            assertNotNull(decision.logicalCallId(), "The in-memory view keeps the coordinator call identity");
            assertEquals(2, decision.legs().size());
            assertEquals(1L, decision.evidence().confirmedDuplicatePairCount());
            assertEquals(1L,
                decision.evidence().mergeProofCount(LogicalCallMergeProof.SHARED_VOICE_CONTENT));
            assertEquals(good.callLegId().toString(), decision.winner().winnerLegId());
            assertEquals(damaged.callLegId().toString(), decision.winner().runnerUpLegId());
            assertEquals(LogicalCallWinnerCriterion.USABLE_FRAME_COUNT,
                decision.winner().criterion());
            assertNotNull(decision.winner().winnerValue().numerator());
            assertNotNull(decision.winner().winnerValue().denominator());
            assertNotNull(decision.winner().runnerUpValue().numerator());
            assertTrue(decision.winner().winnerValue().numerator() >
                decision.winner().runnerUpValue().numerator());

            assertEquals("APCO25", decision.callIdentity().protocol());
            assertEquals(DecoderType.P25_PHASE1.name(), decision.callIdentity().decoder());
            assertEquals(1_000L, decision.callIdentity().startTimestamp());
            assertEquals(4_080L, decision.callIdentity().endTimestamp());
            assertTrue(decision.callIdentity().resolvedTimestamp() >= decision.callIdentity().endTimestamp());
            assertEquals("10119", decision.callIdentity().destinationValue());
            assertEquals("9001", decision.callIdentity().sourceValue());
            assertEquals(CallEncryptionState.CLEAR, decision.callIdentity().encryptionState());
            assertEquals(Integer.valueOf(0x12345), decision.callIdentity().wacn());
            assertEquals(Integer.valueOf(0x234), decision.callIdentity().system());
            assertEquals(aliasList.getId(), decision.callIdentity().durableAliasListId());
            assertEquals(aliasList.getName(), decision.callIdentity().aliasListName());
            assertEquals(2, decision.callIdentity().uniqueLearnedSiteCount());

            assertTrue(decision.outputPolicy().recordRequested());
            assertEquals(List.of("North", "South"), decision.outputPolicy().streamRoutingKeys());
            assertEquals(2, decision.outputPolicy().streamRoutingKeyCount());
            assertTrue(decision.outputPolicy().browserOffered());
            assertNotEquals(Thread.currentThread(), sinkThread.get());
            assertTrue(sinkThread.get().getName().contains("logical-call resolver"));

            await(() -> coordinator.getDiagnosticSnapshot().counters().mergedLogicalCalls() == 1L);
            LogicalCallDiagnosticSnapshot snapshot = coordinator.getDiagnosticSnapshot();
            assertEquals(1L, snapshot.counters().mergedLogicalCalls());
            assertEquals(1L, snapshot.counters().mergedReceiverCopies());
            assertEquals(1L, snapshot.counters().diagnosticDecisionsOffered());
            assertEquals(0L, snapshot.counters().diagnosticDecisionsRejected());
            assertEquals(0, snapshot.activeLegCount());
            assertEquals(0, snapshot.activeCohortCount());
            assertEquals(0L, snapshot.retainedAudioSampleCount());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void diagnosticsDistinguishKnownSeparationP25FailOpenAndNonP25Independent() throws Exception
    {
        AliasList aliasList = aliasList(835);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<LogicalCallDiagnosticDecision> decisions = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            256, 32, 25, 150, 32, 16, 1_000_000, 4_000_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null,
            configuration, decision -> {
                decisions.add(decision);
                return true;
            });

        try
        {
            emitLeg(coordinator, leg(123, aliasList, 0x12346, 0x235, 19, 191, 10_120, 9_002,
                1_000, 3_000, GOOD_QUALITY, true, Set.of()), fingerprints(146));
            emitLeg(coordinator, leg(124, aliasList, 0x12346, 0x235, 19, 192, 10_120, 9_002,
                1_050, 3_050, GOOD_QUALITY, true, Set.of()), fingerprints(156));
            await(() -> decisions.size() == 2);

            List<LogicalCallDiagnosticDecision> failOpen = decisions.stream()
                .filter(decision -> decision.decisionReasons()
                    .contains(LogicalCallSeparationReason.INSUFFICIENT_DUPLICATE_PROOF)).toList();
            assertEquals(2, failOpen.size());
            assertTrue(failOpen.stream().allMatch(decision ->
                decision.outcome() == LogicalCallDecisionOutcome.FAIL_OPEN));
            assertTrue(failOpen.stream().allMatch(decision -> decision.evidence().uncertainPairCount() == 1L));
            assertTrue(failOpen.stream().allMatch(decision -> decision.evidence()
                .rejectionReasonCount(LogicalCallSeparationReason.INSUFFICIENT_DUPLICATE_PROOF) == 1L));

            Leg missingSite = leg(125, aliasList, null, 0, 0, 0, 10_121, 9_003,
                4_000, 5_000, GOOD_QUALITY, true, Set.of());
            emitLeg(coordinator, missingSite, fingerprints(166));
            Leg p25Shape = leg(126, aliasList, 0x12346, 0x235, 19, 193, 10_122, 9_004,
                6_000, 7_000, GOOD_QUALITY, true, Set.of());
            emitLeg(coordinator, withDecoder(p25Shape, DecoderType.DMR), fingerprints(176));
            await(() -> decisions.size() == 4 && resolved.size() == 4);

            LogicalCallDiagnosticDecision missingIdentity = decisions.stream()
                .filter(decision -> decision.decisionReasons()
                    .contains(LogicalCallSeparationReason.MISSING_LEARNED_SITE_IDENTITY))
                .findFirst().orElseThrow();
            assertEquals(LogicalCallDecisionOutcome.FAIL_OPEN, missingIdentity.outcome());
            LogicalCallDiagnosticDecision nonP25 = decisions.stream()
                .filter(decision -> decision.decisionReasons()
                    .contains(LogicalCallSeparationReason.NON_P25_RESOLUTION_NOT_APPLICABLE))
                .findFirst().orElseThrow();
            assertEquals(LogicalCallDecisionOutcome.INDEPENDENT, nonP25.outcome());
            assertEquals(DecoderType.DMR.name(), nonP25.callIdentity().decoder());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void diagnosticSnapshotExposesActiveStateAndRejectedSinkWithoutAffectingResolution() throws Exception
    {
        AliasList aliasList = aliasList(836);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<LogicalCallDiagnosticDecision> decisions = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            256, 32, 250, 500, 32, 16, 1_000_000, 4_000_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null,
            configuration, decision -> {
                decisions.add(decision);
                return false;
            });

        try
        {
            Leg completed = leg(127, aliasList, 0x12347, 0x236, 20, 201, 10_123, 9_005,
                1_000, 3_000, GOOD_QUALITY, true, Set.of());
            Leg active = leg(128, aliasList, 0x12347, 0x236, 20, 202, 10_123, 9_005,
                1_050, 3_050, GOOD_QUALITY, true, Set.of());
            emitLeg(coordinator, completed, fingerprints(186));
            AudioCallSnapshot activeSnapshot = snapshot(active, false);
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, activeSnapshot, null,
                false, 0L, 0L));

            List<Long> activeFingerprints = fingerprints(186);

            for(int index = 0; index < activeFingerprints.size(); index++)
            {
                long fingerprint = activeFingerprints.get(index);
                coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, activeSnapshot,
                    new float[160], false, fingerprint, active.start() + index * 20L));
            }

            await(() -> {
                LogicalCallDiagnosticSnapshot snapshot = coordinator.getDiagnosticSnapshot();
                return snapshot.activeLegCount() == 1 && snapshot.activeCohortCount() == 1 &&
                    snapshot.retainedAudioSampleCount() > 0L;
            });
            LogicalCallDiagnosticSnapshot activeState = coordinator.getDiagnosticSnapshot();
            assertEquals(active.callLegId().toString(), activeState.activeLegs().getFirst().legId());
            assertEquals(1, activeState.activeCohorts().getFirst().completedLegs().size());
            assertTrue(activeState.activeCohorts().getFirst().awaitedActiveLegIds()
                .contains(active.callLegId().toString()));

            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED,
                snapshot(active, true), null, false, 0L, 0L));
            await(() -> resolved.size() == 1 && decisions.size() == 1 &&
                coordinator.getDiagnosticSnapshot().counters().diagnosticDecisionsRejected() == 1L);
            assertEquals(LogicalCallDecisionOutcome.MERGED, decisions.getFirst().outcome());
            LogicalCallDiagnosticSnapshot finalState = coordinator.getDiagnosticSnapshot();
            assertEquals(0, finalState.activeLegCount());
            assertEquals(0, finalState.activeCohortCount());
            assertEquals(0L, finalState.retainedAudioSampleCount());
            assertEquals(1L, finalState.counters().diagnosticDecisionsOffered());
            assertEquals(1L, finalState.counters().diagnosticDecisionsRejected());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void diagnosticEvidenceRetainsExactCountsBeyondFormerPairTranscriptLimit() throws Exception
    {
        AliasList aliasList = aliasList(837);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<LogicalCallDiagnosticDecision> decisions = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            512, 32, 250, 500, 64, 32, 1_000_000, 8_000_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null,
            configuration, decision -> {
                decisions.add(decision);
                return true;
            });

        try
        {
            for(int index = 0; index < 17; index++)
            {
                emitLeg(coordinator, leg(200 + index, aliasList, 0x12348, 0x237, 21, 210 + index,
                    10_124, 9_006, 1_000 + index, 3_000 + index, GOOD_QUALITY, true, Set.of()),
                    fingerprints(196));
            }

            await(() -> decisions.size() == 1 && resolved.size() == 1);
            LogicalCallDiagnosticDecision decision = decisions.getFirst();
            assertEquals(LogicalCallDecisionOutcome.MERGED, decision.outcome());
            assertEquals(17, decision.legs().size());
            assertEquals(136L, decision.evidence().candidateComparisonCount());
            assertEquals(136L, decision.evidence().confirmedDuplicatePairCount());
            assertEquals(136L,
                decision.evidence().mergeProofCount(LogicalCallMergeProof.SHARED_VOICE_CONTENT));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void distinctMergedCohortsRetainTheirCrossCohortSeparationEvidence() throws Exception
    {
        AliasList aliasList = aliasList(841);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<LogicalCallDiagnosticDecision> decisions = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            256, 32, 50, 150, 32, 16, 1_000_000, 4_000_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null,
            configuration, decision -> {
                decisions.add(decision);
                return true;
            });

        try
        {
            emitLeg(coordinator, leg(308, aliasList, 0x1234B, 0x23A, 24, 241, 10_129, 9_009,
                1_000, 3_000, GOOD_QUALITY, true, Set.of()), fingerprints(246));
            emitLeg(coordinator, leg(309, aliasList, 0x1234B, 0x23A, 24, 242, 10_129, 9_009,
                1_020, 3_020, GOOD_QUALITY, true, Set.of()), fingerprints(246));
            emitLeg(coordinator, leg(310, aliasList, 0x1234B, 0x23A, 24, 243, 10_129, 9_009,
                1_040, 3_040, GOOD_QUALITY, true, Set.of()), fingerprints(256));
            emitLeg(coordinator, leg(311, aliasList, 0x1234B, 0x23A, 24, 244, 10_129, 9_009,
                1_060, 3_060, GOOD_QUALITY, true, Set.of()), fingerprints(256));

            await(() -> resolved.size() == 2 && decisions.size() == 2);
            assertTrue(decisions.stream().allMatch(decision ->
                decision.outcome() == LogicalCallDecisionOutcome.MERGED));
            assertTrue(decisions.stream().allMatch(decision -> decision.decisionReasons().isEmpty()),
                "Rejected unrelated candidates are evidence, not the final reason for a successful merge");
            assertTrue(decisions.stream().allMatch(decision ->
                decision.evidence().confirmedDuplicatePairCount() == 1L));
            assertTrue(decisions.stream().allMatch(decision -> decision.evidence().uncertainPairCount() > 0L));
            assertTrue(decisions.stream().allMatch(decision -> decision.evidence()
                .rejectionReasonCount(LogicalCallSeparationReason.INSUFFICIENT_DUPLICATE_PROOF) > 0L));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void failOpenOutcomeRetainsExactUncertainEvidenceAtHighCandidateVolume() throws Exception
    {
        AliasList aliasList = aliasList(842);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<LogicalCallDiagnosticDecision> decisions = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            512, 32, 1_500, 2_000, 64, 16, 1_000_000, 4_000_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null,
            configuration, decision -> {
                decisions.add(decision);
                return true;
            });

        try
        {
            Leg first = leg(312, aliasList, 0x1234C, 0x23B, 25, 251, 20_000, null,
                1_000, 3_000, GOOD_QUALITY, true, Set.of());
            emitLeg(coordinator, first, List.of());

            for(int index = 0; index < 128; index++)
            {
                emitLeg(coordinator, leg(313 + index, aliasList, 0x1234C, 0x23B, 25,
                    252 + index % 4, 20_001 + index, null, 1_000, 3_000,
                    GOOD_QUALITY, true, Set.of()), List.of());
            }

            emitLeg(coordinator, leg(500, aliasList, 0x1234C, 0x23B, 25, 255, 20_000, null,
                1_020, 3_020, GOOD_QUALITY, true, Set.of()), List.of());
            await(() -> decisions.size() == 130 && resolved.size() == 130);

            LogicalCallDiagnosticDecision firstDecision = decisions.stream()
                .filter(decision -> decision.legs().stream()
                    .anyMatch(leg -> first.callLegId().toString().equals(leg.legId())))
                .findFirst().orElseThrow();
            assertEquals(LogicalCallDecisionOutcome.FAIL_OPEN, firstDecision.outcome());
            assertTrue(firstDecision.decisionReasons()
                .contains(LogicalCallSeparationReason.INSUFFICIENT_DUPLICATE_PROOF));
            assertEquals(129L, firstDecision.evidence().candidateComparisonCount());
            assertEquals(128L, firstDecision.evidence().separatedPairCount());
            assertEquals(1L, firstDecision.evidence().uncertainPairCount());
            assertEquals(1L, firstDecision.evidence()
                .rejectionReasonCount(LogicalCallSeparationReason.INSUFFICIENT_DUPLICATE_PROOF));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void winnerElectionAndExplanationUseOneCohortWideQualityBaseline() throws Exception
    {
        AliasList aliasList = aliasList(838);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<LogicalCallDiagnosticDecision> decisions = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            256, 32, 25, 150, 32, 16, 1_000_000, 4_000_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null,
            configuration, decision -> {
                decisions.add(decision);
                return true;
            });
        VoiceCallQuality broadCoverage = new VoiceCallQuality(800, 0, 0, 100, 0, 1_000);
        VoiceCallQuality locallyCleanButShort = new VoiceCallQuality(190, 0, 0, 10, 0, 1_000);
        VoiceCallQuality fullDurationDamaged = new VoiceCallQuality(1, 0, 0, 999, 0, 1_000);

        try
        {
            Leg broad = leg(300, aliasList, 0x12349, 0x238, 22, 221, 10_125, 9_007,
                1_000, 5_000, broadCoverage, true, Set.of());
            Leg shortClean = leg(301, aliasList, 0x12349, 0x238, 22, 222, 10_125, 9_007,
                1_000, 5_000, locallyCleanButShort, true, Set.of());
            Leg longDamaged = leg(302, aliasList, 0x12349, 0x238, 22, 223, 10_125, 9_007,
                1_000, 21_000, fullDurationDamaged, true, Set.of());
            emitLeg(coordinator, broad, fingerprints(206));
            emitLeg(coordinator, shortClean, fingerprints(206));
            emitLeg(coordinator, longDamaged, fingerprints(206));

            await(() -> resolved.size() == 1 && decisions.size() == 1);
            LogicalCallDiagnosticDecision decision = decisions.getFirst();
            assertEquals(broad.callId(), resolved.getFirst().snapshot().callId(),
                "The global call duration must be used for every receiver copy");
            assertEquals(broad.callLegId().toString(), decision.winner().winnerLegId());
            assertEquals(LogicalCallWinnerCriterion.USABLE_FRAME_COUNT,
                decision.winner().criterion());
            assertEquals(Long.valueOf(1_000L), decision.winner().winnerValue().denominator());
            assertEquals(Long.valueOf(1_000L), decision.winner().runnerUpValue().denominator());
            assertTrue(decision.legs().stream().allMatch(leg -> leg.expectedFrameCount() == 1_000L));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void unknownFecQualityCannotBeatMeasuredFecQualityAsIfItWerePerfect() throws Exception
    {
        AliasList aliasList = aliasList(844);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<LogicalCallDiagnosticDecision> decisions = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            256, 32, 25, 150, 32, 16, 1_000_000, 4_000_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null,
            configuration, decision -> {
                decisions.add(decision);
                return true;
            });
        VoiceCallQuality unknownFec = new VoiceCallQuality(50, 0, 0, 0, 0, 0);

        try
        {
            Leg unknown = leg(503, aliasList, 0x1234D, 0x23C, 26, 241, 20_202, 9_010,
                1_000, 4_000, unknownFec, true, Set.of());
            Leg measured = leg(504, aliasList, 0x1234D, 0x23C, 26, 242, 20_202, 9_010,
                1_020, 4_020, GOOD_QUALITY, true, Set.of());
            emitLeg(coordinator, unknown, fingerprints(286));
            emitLeg(coordinator, measured, fingerprints(286));

            await(() -> resolved.size() == 1 && decisions.size() == 1);
            assertEquals(measured.callId(), resolved.getFirst().snapshot().callId());
            assertEquals(LogicalCallWinnerCriterion.NORMALIZED_FEC_ERROR_RATE,
                decisions.getFirst().winner().criterion());
            assertEquals("not measured", decisions.getFirst().winner().runnerUpValue().display());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void throwingDiagnosticSinkCannotSuppressOutputOrStopTheResolver() throws Exception
    {
        AliasList aliasList = aliasList(839);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            64, 8, 0, 0, 8, 8, 100_000, 200_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null,
            configuration, decision -> {
                throw new IllegalStateException("synthetic diagnostic failure");
            });

        try
        {
            emitLeg(coordinator, leg(303, aliasList, null, 0, 0, 0, 10_126,
                1_000, 2_000, GOOD_QUALITY, true, Set.of()), fingerprints(216));
            emitLeg(coordinator, leg(304, aliasList, null, 0, 0, 0, 10_127,
                3_000, 4_000, GOOD_QUALITY, true, Set.of()), fingerprints(226));

            await(() -> resolved.size() == 2 &&
                coordinator.getDiagnosticSnapshot().counters().diagnosticDecisionsRejected() == 2L);
            assertEquals(2, resolved.size(), "Diagnostic failures must not suppress current or later call output");
            assertEquals(2L, coordinator.getDiagnosticSnapshot().counters().emittedLogicalCalls());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void abortedCohortMemberIsRemovedFromFinalMergeProof() throws Exception
    {
        AliasList aliasList = aliasList(840);
        List<CompletedAudioCall> resolved = new CopyOnWriteArrayList<>();
        List<LogicalCallDiagnosticDecision> decisions = new CopyOnWriteArrayList<>();
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            256, 32, 250, 500, 32, 16, 1_000_000, 4_000_000);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(resolved::add, null, null, null,
            configuration, decision -> {
                decisions.add(decision);
                return true;
            });

        try
        {
            Leg first = leg(305, aliasList, 0x1234A, 0x239, 23, 231, 10_128, 9_008,
                1_000, 3_000, GOOD_QUALITY, true, Set.of());
            Leg second = leg(306, aliasList, 0x1234A, 0x239, 23, 232, 10_128, 9_008,
                1_020, 3_020, GOOD_QUALITY, true, Set.of());
            Leg aborted = leg(307, aliasList, 0x1234A, 0x239, 23, 233, 10_128, 9_008,
                1_040, 3_040, GOOD_QUALITY, true, Set.of());
            emitLeg(coordinator, first, fingerprints(236));
            emitLeg(coordinator, second, fingerprints(236));
            emitLeg(coordinator, aborted, fingerprints(236));
            await(() -> coordinator.getDiagnosticSnapshot().activeCohorts().stream()
                .anyMatch(cohort -> cohort.completedLegs().size() == 3));

            assertTrue(aborted.callLegId().markIngressCompromised());
            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, snapshot(aborted, false),
                new float[160], false, 999L, aborted.end()));
            await(() -> decisions.stream().anyMatch(decision ->
                decision.outcome() == LogicalCallDecisionOutcome.ABORTED));
            await(() -> decisions.stream().anyMatch(decision ->
                decision.outcome() == LogicalCallDecisionOutcome.MERGED));

            LogicalCallDiagnosticDecision abortedDecision = decisions.stream()
                .filter(decision -> decision.outcome() == LogicalCallDecisionOutcome.ABORTED)
                .findFirst().orElseThrow();
            assertNull(abortedDecision.winner(), "A discarded leg must not be presented as an output winner");
            assertTrue(abortedDecision.legs().stream().noneMatch(leg -> leg.winner()));
            assertEquals(480L, abortedDecision.legs().getFirst().retainedAudioSampleCount(),
                "Abort diagnostics must capture worker-owned audio facts before releasing the leg");
            assertEquals(GOOD_QUALITY.decodedFrameCount(),
                abortedDecision.legs().getFirst().decodedFrameCount());
            LogicalCallDiagnosticDecision merged = decisions.stream()
                .filter(decision -> decision.outcome() == LogicalCallDecisionOutcome.MERGED)
                .findFirst().orElseThrow();
            assertEquals(2, merged.legs().size());
            assertEquals(1L, merged.evidence().confirmedDuplicatePairCount());
            assertEquals(1L,
                merged.evidence().mergeProofCount(LogicalCallMergeProof.SHARED_VOICE_CONTENT));
            assertFalse(merged.legs().stream()
                .anyMatch(leg -> aborted.callLegId().toString().equals(leg.legId())));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    private static AudioCallCoordinator coordinator(List<CompletedAudioCall> resolved,
                                                    List<CompletedAudioCall> recorded,
                                                    List<CompletedAudioCall> streamed,
                                                    List<CompletedAudioCall> web)
    {
        AudioCallCoordinator.ResolverConfiguration configuration = new AudioCallCoordinator.ResolverConfiguration(
            256, 32, 25, 150, 32, 16, 1_000_000, 4_000_000);
        return new AudioCallCoordinator(resolved != null ? resolved::add : null,
            recorded != null ? recorded::add : null, streamed != null ? streamed::add : null,
            web != null ? web::add : null, configuration);
    }

    private static AliasList aliasList(long id)
    {
        AliasListDefinition definition = new AliasListDefinition("List-" + id, AliasListFamily.P25);
        definition.setId(id);
        return new AliasList(definition);
    }

    private static Leg leg(long producerId, AliasList aliasList, Integer wacn, int system, int rfss, int site,
                           int talkgroup, long start, long end, VoiceCallQuality quality, boolean record,
                           Set<BroadcastChannel> routes)
    {
        return leg(producerId, aliasList, wacn, system, rfss, site, talkgroup, 9001, start, end, quality,
            record, routes);
    }

    private static Leg leg(long producerId, AliasList aliasList, Integer wacn, int system, int rfss, int site,
                           int talkgroup, Integer radio, long start, long end, VoiceCallQuality quality,
                           boolean record, Set<BroadcastChannel> routes)
    {
        return leg(producerId, 1, new CallLegId(producerId, 1, 0), aliasList, wacn, system, rfss, site,
            talkgroup, radio, start, end, quality, record, routes, "site-" + site);
    }

    private static Leg leg(long producerId, AliasList aliasList, Integer wacn, int system, int rfss, int site,
                           int talkgroup, long start, long end, VoiceCallQuality quality, boolean record,
                           Set<BroadcastChannel> routes, String siteGuid)
    {
        return leg(producerId, 1, new CallLegId(producerId, 1, 0), aliasList, wacn, system, rfss, site,
            talkgroup, 9001, start, end, quality, record, routes, siteGuid);
    }

    private static Leg leg(long producerId, long callSequence, CallLegId callLegId, AliasList aliasList,
                           Integer wacn, int system, int rfss, int site, int talkgroup, Integer radio,
                           long start, long end, VoiceCallQuality quality, boolean record,
                           Set<BroadcastChannel> routes)
    {
        return leg(producerId, callSequence, callLegId, aliasList, wacn, system, rfss, site, talkgroup,
            radio, start, end, quality, record, routes, "site-" + site);
    }

    private static Leg leg(long producerId, long callSequence, CallLegId callLegId, AliasList aliasList,
                           Integer wacn, int system, int rfss, int site, int talkgroup, Integer radio,
                           long start, long end, VoiceCallQuality quality, boolean record,
                           Set<BroadcastChannel> routes, String siteGuid)
    {
        AudioCallId callId = new AudioCallId(producerId, callSequence, 0);
        P25SiteIdentity siteIdentity = wacn != null ? new P25SiteIdentity(wacn, system, rfss, site) : null;
        CallLegSource source = new CallLegSource(DecoderType.P25_PHASE1, "channel-" + producerId,
            "Site " + site, siteGuid, aliasList.getId(), siteIdentity, true);
        return new Leg(callId, callLegId, aliasList, source, talkgroup, radio, start, end,
            quality, record, routes, false, null);
    }

    private static Leg encryptedLeg(long producerId, AliasList aliasList, Integer wacn, int system,
                                    int rfss, int site, int talkgroup, long start, long end,
                                    CallEncryptionEvidence evidence)
    {
        Leg clearShape = leg(producerId, aliasList, wacn, system, rfss, site, talkgroup, null,
            start, end, VoiceCallQuality.EMPTY, true, Set.of());
        return new Leg(clearShape.callId(), clearShape.callLegId(), clearShape.aliasList(), clearShape.source(),
            clearShape.talkgroup(), null, clearShape.start(), clearShape.end(), clearShape.quality(),
            clearShape.record(), clearShape.routes(), true, evidence);
    }

    private static Leg withDecoder(Leg leg, DecoderType decoderType)
    {
        CallLegSource source = new CallLegSource(decoderType, leg.source().channelConfigurationId(),
            leg.source().channelName(), leg.source().siteGuid(), leg.source().aliasListId(), null, true);
        return new Leg(leg.callId(), leg.callLegId(), leg.aliasList(), source, leg.talkgroup(), leg.radio(),
            leg.start(), leg.end(), leg.quality(), leg.record(), leg.routes(), leg.encrypted(),
            leg.callEncryptionEvidence());
    }

    private static AudioCallSnapshot snapshot(Leg leg, boolean complete)
    {
        List<Identifier> identifiers = new ArrayList<>();
        identifiers.add(APCO25Talkgroup.create(leg.talkgroup()));

        if(leg.radio() != null)
        {
            identifiers.add(APCO25RadioIdentifier.createFrom(leg.radio()));
        }

        return snapshot(leg, complete, new IdentifierCollection(identifiers));
    }

    private static AudioCallSnapshot snapshot(Leg leg, boolean complete, IdentifierCollection collection)
    {
        return snapshot(leg, complete, collection, CallEncryptionState.fromEncrypted(leg.encrypted()));
    }

    private static AudioCallSnapshot snapshot(Leg leg, boolean complete, IdentifierCollection collection,
                                              CallEncryptionState encryptionState)
    {
        return new AudioCallSnapshot(leg.callId(), null, leg.aliasList(), collection, leg.routes(),
            leg.start(), leg.end(), 1, 1, leg.start(), leg.end(), false, complete,
            encryptionState,
            leg.record(), AudioCallRecordingMetadata.captureAtSnapshot(leg.aliasList(), collection),
            leg.quality(), leg.callLegId(), leg.source(), leg.callEncryptionEvidence());
    }

    private static void emitLegWithEncryptionState(AudioCallCoordinator coordinator, Leg leg,
                                                   CallEncryptionState encryptionState)
    {
        List<Identifier> identifiers = new ArrayList<>();
        identifiers.add(APCO25Talkgroup.create(leg.talkgroup()));

        if(leg.radio() != null)
        {
            identifiers.add(APCO25RadioIdentifier.createFrom(leg.radio()));
        }

        IdentifierCollection collection = new IdentifierCollection(identifiers);
        coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED,
            snapshot(leg, false, collection, encryptionState), null, false, 0L, 0L));
        coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED,
            snapshot(leg, true, collection, encryptionState), null, false, 0L, 0L));
    }

    private static void emitLeg(AudioCallCoordinator coordinator, Leg leg, List<Long> fingerprints)
    {
        emitChunk(coordinator, leg, fingerprints, false);
    }

    private static void emitLeg(AudioCallCoordinator coordinator, Leg leg, List<Long> fingerprints,
                                IdentifierCollection identifiers)
    {
        AudioCallSnapshot active = snapshot(leg, false, identifiers);
        coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, active, null, false, 0L, 0L));

        for(int index = 0; index < fingerprints.size(); index++)
        {
            long fingerprint = fingerprints.get(index);
            float[] frame = new float[160];
            frame[0] = fingerprint / 10_000.0f;
            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, active, frame, false,
                fingerprint, leg.start() + index * 20L));
        }

        coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED,
            snapshot(leg, true, identifiers), null, false, 0L, 0L));
    }

    private static void emitChunk(AudioCallCoordinator coordinator, Leg leg, List<Long> fingerprints,
                                  boolean continuationExpected)
    {
        AudioCallSnapshot active = snapshot(leg, false);
        coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, active, null, false, 0L, 0L));

        for(int index = 0; index < fingerprints.size(); index++)
        {
            long fingerprint = fingerprints.get(index);
            float[] frame = new float[160];
            frame[0] = fingerprint / 10_000.0f;
            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, active, frame, false,
                fingerprint, leg.start() + index * 20L));
        }

        coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, snapshot(leg, true), null,
            continuationExpected, 0L, 0L));
    }

    private static void emitLegWithFrameEvidence(AudioCallCoordinator coordinator, Leg leg,
                                                 List<Long> fingerprints, List<Long> carrierTimestamps)
    {
        assertEquals(fingerprints.size(), carrierTimestamps.size());
        AudioCallSnapshot active = snapshot(leg, false);
        coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, active, null, false, 0L, 0L));

        for(int index = 0; index < fingerprints.size(); index++)
        {
            long fingerprint = fingerprints.get(index);
            float[] frame = new float[160];
            frame[0] = fingerprint / 10_000.0f;
            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, active, frame, false,
                fingerprint, carrierTimestamps.get(index)));
        }

        coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, snapshot(leg, true), null,
            false, 0L, 0L));
    }

    private static List<Long> fingerprints(long seed)
    {
        return List.of(seed + 1L, seed + 2L, seed + 3L);
    }

    private static List<Long> fingerprintRange(long seed, int count)
    {
        List<Long> fingerprints = new ArrayList<>(count);

        for(int index = 0; index < count; index++)
        {
            fingerprints.add(seed + index + 1L);
        }

        return List.copyOf(fingerprints);
    }

    private static List<Long> frameTimestamps(long startTimestamp, int count)
    {
        List<Long> timestamps = new ArrayList<>(count);

        for(int index = 0; index < count; index++)
        {
            timestamps.add(startTimestamp + index * 20L);
        }

        return List.copyOf(timestamps);
    }

    private static ScheduledThreadPoolExecutor deadlineScheduler(AudioCallCoordinator coordinator) throws Exception
    {
        Field field = AudioCallCoordinator.class.getDeclaredField("mDeadlineScheduler");
        field.setAccessible(true);
        return (ScheduledThreadPoolExecutor)field.get(coordinator);
    }

    private static AtomicBoolean cohortSweepRequested(AudioCallCoordinator coordinator) throws Exception
    {
        Field field = AudioCallCoordinator.class.getDeclaredField("mCohortSweepRequested");
        field.setAccessible(true);
        return (AtomicBoolean)field.get(coordinator);
    }

    private static long coordinatorLongField(AudioCallCoordinator coordinator, String fieldName)
    {
        try
        {
            Field field = AudioCallCoordinator.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getLong(coordinator);
        }
        catch(ReflectiveOperationException exception)
        {
            throw new AssertionError("Unable to inspect coordinator field " + fieldName, exception);
        }
    }

    private static int coordinatorMapSize(AudioCallCoordinator coordinator, String fieldName)
    {
        try
        {
            Field field = AudioCallCoordinator.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return ((java.util.Map<?,?>)field.get(coordinator)).size();
        }
        catch(ReflectiveOperationException exception)
        {
            throw new AssertionError("Unable to inspect coordinator field " + fieldName, exception);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);

        while(!condition.getAsBoolean() && System.nanoTime() < deadline)
        {
            Thread.sleep(5L);
        }

        assertTrue(condition.getAsBoolean(), "Timed out waiting for logical-call resolution");
    }

    private record Leg(AudioCallId callId, CallLegId callLegId, AliasList aliasList, CallLegSource source,
                       int talkgroup, Integer radio, long start, long end, VoiceCallQuality quality,
                       boolean record, Set<BroadcastChannel> routes, boolean encrypted,
                       CallEncryptionEvidence callEncryptionEvidence)
    {
    }
}
