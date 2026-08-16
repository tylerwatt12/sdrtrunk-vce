/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.audio.call;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.SiteGuidConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.duplicate.ICallManagementProvider;
import io.github.dsheirer.preference.duplicate.TestCallManagementProvider;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCallCoordinatorTest
{
    @Test
    void completedCallRetainsAudioWithoutReceiverSpeakerPlayback() throws Exception
    {
        List<CompletedAudioCall> completedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(false, false), completedCalls::add, null, null);

        try
        {
            float[] audio = new float[] {0.25f, -0.5f};
            AudioCallSnapshot active = snapshot(1, 1, 1200, false, false);
            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, active, audio));

            assertTrue(completedCalls.isEmpty(), "A live frame must remain buffered until call completion");

            coordinator.receive(completionEvent(active));
            awaitCondition(() -> completedCalls.size() == 1,
                "Expected one completed call to fan out");
            assertEquals(active.callId(), completedCalls.getFirst().snapshot().callId());
            assertArrayEquals(audio, completedCalls.getFirst().audioBuffers().getFirst());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void completedCallFreezesUnmatchedTalkgroupEvidenceForWebDelivery() throws Exception
    {
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        definition.setId(10);
        AliasList aliasList = new AliasList(definition);
        List<CompletedAudioCall> completedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(false, false), null, null, completedCalls::add);

        try
        {
            AudioCallSnapshot active = withAliasList(snapshot(2, 1, 999, false, false), aliasList);
            coordinator.receive(audioEvent(active, 160));
            coordinator.receive(completionEvent(active));
            awaitCondition(() -> completedCalls.size() == 1, "Expected one completed web call");

            ResolvedCallPolicy.MatchContext context =
                completedCalls.getFirst().resolvedPolicy().matchContexts().getFirst();
            assertEquals(10L, context.aliasListId());
            assertEquals(AliasList.TalkgroupMatchStatus.UNMATCHED, context.talkgroupMatchStatus());

            Alias subsequentlyCreated = new Alias("Now known");
            subsequentlyCreated.setId(101);
            subsequentlyCreated.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 999));
            aliasList.addAlias(subsequentlyCreated);
            assertEquals(AliasList.TalkgroupMatchStatus.UNMATCHED, context.talkgroupMatchStatus(),
                "A completed call must not be reclassified by a later alias edit");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void finalQualityElectionSelectsTheCleanerCompletedCall() throws Exception
    {
        String liveWinnerGuid = "00000000-0000-0000-0000-000000000201";
        String finalWinnerGuid = "00000000-0000-0000-0000-000000000202";
        List<CompletedAudioCall> completedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), completedCalls::add, null, null,
            DuplicateCallPriorityProvider.NONE,
            100L, 1_000L);

        try
        {
            AudioCallSnapshot liveWinner = withVoiceQuality(
                snapshot(21, 1, 1200, 9001, "Test System", liveWinnerGuid,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(40, 0, 10, 0, 20, 6_850));
            AudioCallSnapshot finalWinner = withVoiceQuality(
                snapshot(22, 2, 1200, 9002, "Test System", finalWinnerGuid,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(49, 1, 0, 0, 5, 6_850));
            coordinator.receive(audioEvent(liveWinner, 800));
            coordinator.receive(audioEvent(finalWinner, 800));

            coordinator.receive(completionEvent(liveWinner));
            coordinator.receive(completionEvent(finalWinner));
            awaitCondition(() -> completedCalls.size() == 1, "Expected one final logical call");
            assertEquals(finalWinner.callId(), completedCalls.getFirst().snapshot().callId(),
                "Completed outputs still use the current quality election");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void coordinatorApiHasNoLivePlaybackOrBrowserReservationDependencies()
    {
        for(var constructor : AudioCallCoordinator.class.getDeclaredConstructors())
        {
            for(Class<?> parameterType : constructor.getParameterTypes())
            {
                assertFalse(parameterType.getPackageName().equals("io.github.dsheirer.audio.playback"),
                    "The coordinator must not accept a receiver-speaker playback dependency");
                assertFalse(parameterType.getName().equals("io.github.dsheirer.audio.call.WebCallDeliveryListener"),
                    "The coordinator must not accept the retired browser reservation lifecycle");
            }
        }

        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("io.github.dsheirer.audio.call.WebCallDeliveryEvent"));
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("io.github.dsheirer.audio.call.WebCallDeliveryListener"));
    }

    @Test
    void duplicateCohortProducesOneResolvedCallWithWinnerAudio() throws Exception
    {
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(true, true),
            recorded::add, null, null);

        try
        {
            float[] audio1 = new float[] {1.0f, 2.0f};
            float[] audio2 = new float[] {3.0f, 4.0f};

            AudioCallSnapshot snapshot1 = snapshot(1, 100, 1200, false, false);
            AudioCallSnapshot snapshot2 = snapshot(2, 200, 1200, false, false);

            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, snapshot1, audio1));
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, snapshot2, audio2));
            coordinator.receive(completionEvent(snapshot1));
            coordinator.receive(completionEvent(snapshot2));

            awaitCondition(() -> recorded.size() == 1, "Expected one resolved logical call");
            CompletedAudioCall resolved = recorded.getFirst();

            assertEquals(snapshot1.callId(), resolved.snapshot().callId());
            assertEquals(1, resolved.audioBuffers().size());
            assertArrayEquals(audio1, resolved.audioBuffers().getFirst());
            assertFalse(resolved.snapshot().duplicate());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void completionFansOutImmutableCallToAllCompletedCallConsumers() throws Exception
    {
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> webCalls = new CopyOnWriteArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(3);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(false, false),
            call -> {
                recorded.add(call);
                completionLatch.countDown();
            }, call -> {
                streamed.add(call);
                completionLatch.countDown();
            }, call -> {
                webCalls.add(call);
                completionLatch.countDown();
            });

        try
        {
            float[] audio = new float[] {5.0f, 6.0f, 7.0f};
            AudioCallSnapshot active = snapshot(10, 300, 4400, false, false);
            AudioCallSnapshot completed = snapshot(10, 300, 4400, true, false);

            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, active, audio));
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, completed, null));

            assertTrue(completionLatch.await(1, TimeUnit.SECONDS), "Expected completed call fanout");
            assertEquals(1, recorded.size());
            assertEquals(1, streamed.size());
            assertEquals(1, webCalls.size());

            CompletedAudioCall recordedCall = recorded.getFirst();
            CompletedAudioCall streamedCall = streamed.getFirst();

            assertTrue(recordedCall.snapshot().complete());
            assertFalse(recordedCall.snapshot().duplicate());
            assertEquals(1, recordedCall.audioBuffers().size());
            assertArrayEquals(audio, recordedCall.audioBuffers().getFirst());
            assertSame(recordedCall, streamedCall);
            assertSame(recordedCall, webCalls.getFirst());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void resolvedElectionUsesEarlierStartAfterQualityAndCompletenessTie() throws Exception
    {
        String firstGuid = "00000000-0000-0000-0000-000000000001";
        String preferredGuid = "00000000-0000-0000-0000-000000000002";
        String lateHigherPriorityGuid = "00000000-0000-0000-0000-000000000003";
        Map<String, Integer> priorities = Map.of(firstGuid, 20, preferredGuid, 10, lateHigherPriorityGuid, 0);
        List<CompletedAudioCall> completedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(true, false),
            completedCalls::add, null, null, guid -> priorities.getOrDefault(guid, Integer.MAX_VALUE), 25L);

        try
        {
            AudioCallSnapshot first = snapshot(101, 1, 1200, 9001, "Test System", firstGuid,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot preferred = snapshot(102, 2, 1200, 9002, "Test System", preferredGuid,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot lateHigherPriority = snapshot(103, 3, 1200, 9003, "Test System",
                lateHigherPriorityGuid, 900L, 1_900L, 1, false);

            coordinator.receive(audioEvent(first, 160));
            coordinator.receive(audioEvent(preferred, 160));
            coordinator.receive(audioEvent(lateHigherPriority, 160));
            coordinator.receive(completionEvent(preferred));
            coordinator.receive(completionEvent(first));
            coordinator.receive(completionEvent(lateHigherPriority));

            awaitCondition(() -> completedCalls.size() == 1, "Expected one resolved logical call");
            assertEquals(lateHigherPriority.callId(), completedCalls.getFirst().snapshot().callId(),
                "Earlier call start is the stable fallback after playable audio, quality, and completeness tie");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void earlierStartAndRegistrationOrderAreIndependentOfCallIdHashOrder() throws Exception
    {
        List<CompletedAudioCall> completedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(true, false),
            completedCalls::add, null, null);

        try
        {
            AudioCallSnapshot registeredFirst = snapshot(Long.MAX_VALUE - 17, 65_537, 1200, 9001,
                "Test System", null, 2_000L, 3_000L, 1, false);
            AudioCallSnapshot earlierStart = snapshot(1, -31, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);

            coordinator.receive(audioEvent(registeredFirst, 160));
            coordinator.receive(audioEvent(earlierStart, 160));
            coordinator.receive(completionEvent(registeredFirst));
            coordinator.receive(completionEvent(earlierStart));

            awaitCondition(() -> completedCalls.size() == 1, "Expected one resolved logical call");
            assertEquals(earlierStart.callId(), completedCalls.getFirst().snapshot().callId(),
                "Earlier call start should win regardless of map/hash or completion order");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void registrationOrdinalAndStableCallIdProvideDeterministicFallbacks()
    {
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(true, false),
            null, null, null);

        try
        {
            AudioCallSnapshot lowCallId = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot highCallId = snapshot(9_999_999, 8_888_888, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);

            assertTrue(coordinator.compareElectionOrder(highCallId, 1L, lowCallId, 2L) < 0,
                "Registration ordinal should precede call ID");
            assertTrue(coordinator.compareElectionOrder(lowCallId, 7L, highCallId, 7L) < 0,
                "Stable call ID should break a fully tied election");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void completingStickySurvivorDoesNotPromoteDuplicateMidCall() throws Exception
    {
        List<CompletedAudioCall> completedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(true, false),
            completedCalls::add, null, null);

        try
        {
            AudioCallSnapshot survivor = withVoiceQuality(
                snapshot(1, 1, 1200, 9001, "Test System", null,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(40, 0, 10, 0, 0, 6_850));
            AudioCallSnapshot duplicate = withVoiceQuality(
                snapshot(2, 2, 1200, 9002, "Test System", null,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(50, 0, 0, 0, 0, 6_850));

            coordinator.receive(audioEvent(survivor, 160));
            coordinator.receive(audioEvent(duplicate, 160));
            coordinator.receive(completionEvent(survivor));
            coordinator.receive(audioEvent(duplicate, 160));

            AudioCallSnapshot newTransmission = snapshot(3, 3, 1200, 9003, "Test System", null,
                3_000L, 4_000L, 1, false);
            AudioCallSnapshot newTransmissionDuplicate = snapshot(4, 4, 1200, 9004, "Test System", null,
                3_000L, 4_000L, 1, false);
            coordinator.receive(audioEvent(newTransmission, 160));
            coordinator.receive(audioEvent(newTransmissionDuplicate, 160));
            coordinator.receive(completionEvent(duplicate));
            coordinator.receive(completionEvent(newTransmission));
            coordinator.receive(completionEvent(newTransmissionDuplicate));

            awaitCondition(() -> completedCalls.size() == 2, "Expected one result from each duplicate cohort");
            assertTrue(completedCalls.stream()
                    .anyMatch(call -> call.snapshot().callId().equals(duplicate.callId())),
                "The more complete duplicate may win after the sticky live survivor completes");
            assertTrue(completedCalls.stream()
                .anyMatch(call -> call.snapshot().callId().equals(newTransmission.callId())),
                "A sealed old cohort must not absorb a later transmission");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void mixedTalkgroupAndRadioMatchesDoNotFormATransitiveDuplicateGroup() throws Exception
    {
        List<CompletedAudioCall> completedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(true, true),
            completedCalls::add, null, null);

        try
        {
            AudioCallSnapshot talkgroupAnchor = snapshot(1, 1, 100, 1, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot bridge = snapshot(2, 2, 100, 2, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot radioAnchor = snapshot(3, 3, 200, 2, "Test System", null,
                1_000L, 2_000L, 1, false);

            coordinator.receive(audioEvent(talkgroupAnchor, 160));
            coordinator.receive(audioEvent(bridge, 160));
            coordinator.receive(audioEvent(radioAnchor, 160));
            coordinator.receive(completionEvent(talkgroupAnchor));
            coordinator.receive(completionEvent(bridge));
            coordinator.receive(completionEvent(radioAnchor));

            awaitCondition(() -> completedCalls.size() == 2,
                "One duplicate pair and one unrelated endpoint should produce two calls");
            assertTrue(completedCalls.stream()
                .anyMatch(call -> call.snapshot().callId().equals(talkgroupAnchor.callId())));
            assertTrue(completedCalls.stream()
                .anyMatch(call -> call.snapshot().callId().equals(radioAnchor.callId())),
                "A talkgroup edge followed by a radio edge must not suppress the unrelated endpoint");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void bridgeFirstCannotAttachUnrelatedEndpointToOpenCohort() throws Exception
    {
        List<CompletedAudioCall> completedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(true, true),
            completedCalls::add, null, null);

        try
        {
            AudioCallSnapshot bridge = snapshot(1, 1, 100, 2, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot talkgroupEndpoint = snapshot(2, 2, 100, 1, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot radioEndpoint = snapshot(3, 3, 200, 2, "Test System", null,
                1_000L, 2_000L, 1, false);

            coordinator.receive(audioEvent(bridge, 160));
            coordinator.receive(audioEvent(talkgroupEndpoint, 160));
            coordinator.receive(audioEvent(radioEndpoint, 160));
            coordinator.receive(completionEvent(bridge));
            coordinator.receive(completionEvent(talkgroupEndpoint));
            coordinator.receive(completionEvent(radioEndpoint));

            awaitCondition(() -> completedCalls.size() == 2,
                "One duplicate pair and one unrelated endpoint should produce two calls");
            assertTrue(completedCalls.stream()
                .anyMatch(call -> call.snapshot().callId().equals(bridge.callId())));
            assertTrue(completedCalls.stream()
                .anyMatch(call -> call.snapshot().callId().equals(radioEndpoint.callId())));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void configuredPriorityBridgeCannotCreateNonCliqueForEitherEndpointOrder() throws Exception
    {
        assertConfiguredPriorityBridgePermutation(false);
        assertConfiguredPriorityBridgePermutation(true);
    }

    @Test
    void sealedCohortDoesNotDelayNewSingleTransmissionStreaming() throws Exception
    {
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(true, false),
            null, streamed::add, null, DuplicateCallPriorityProvider.NONE, 500L);

        try
        {
            AudioCallSnapshot oldWinner = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot oldLingeringLoser = snapshot(2, 2, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot newTransmission = snapshot(3, 3, 1200, 9003, "Test System", null,
                3_000L, 4_000L, 1, false);
            coordinator.receive(audioEvent(oldWinner, 160));
            coordinator.receive(audioEvent(oldLingeringLoser, 160));
            coordinator.receive(completionEvent(oldWinner));
            coordinator.receive(audioEvent(newTransmission, 160));
            coordinator.receive(completionEvent(newTransmission));

            awaitCondition(() -> streamed.stream()
                .anyMatch(call -> call.snapshot().callId().equals(newTransmission.callId())),
                "A later single transmission should stream immediately outside the sealed old cohort");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void exactConfiguredSystemNameStillBoundsDuplicateGroups() throws Exception
    {
        List<CompletedAudioCall> completedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(true, true),
            completedCalls::add, null, null);

        try
        {
            AudioCallSnapshot first = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot whitespaceDifference = snapshot(2, 2, 1200, 9001, "Test System ", null,
                1_000L, 2_000L, 1, false);

            coordinator.receive(audioEvent(first, 160));
            coordinator.receive(audioEvent(whitespaceDifference, 160));
            coordinator.receive(completionEvent(first));
            coordinator.receive(completionEvent(whitespaceDifference));

            awaitCondition(() -> completedCalls.size() == 2, "Expected both completed calls");
            assertFalse(isCompletedDuplicate(completedCalls, first.callId()));
            assertFalse(isCompletedDuplicate(completedCalls, whitespaceDifference.callId()));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void singleStreamingCallIsNotDelayedByDuplicateWatchdog() throws Exception
    {
        CountDownLatch streamed = new CountDownLatch(1);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(true, false),
            null, call -> streamed.countDown(), null, DuplicateCallPriorityProvider.NONE, 2_000L);

        try
        {
            AudioCallSnapshot call = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            coordinator.receive(audioEvent(call, 160));
            coordinator.receive(completionEvent(call));

            assertTrue(streamed.await(1, TimeUnit.SECONDS),
                "A call without an actual duplicate group should stream immediately");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void oldStreamingSuppressionToggleCannotSplitGlobalResolution() throws Exception
    {
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> webCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, false), recorded::add, streamed::add, webCalls::add,
            DuplicateCallPriorityProvider.NONE, 500L);

        try
        {
            AudioCallSnapshot first = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot second = snapshot(2, 2, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);
            coordinator.receive(audioEvent(first, 160));
            coordinator.receive(audioEvent(second, 160));
            coordinator.receive(completionEvent(first));
            coordinator.receive(completionEvent(second));

            awaitCondition(() -> recorded.size() == 1 && streamed.size() == 1 && webCalls.size() == 1,
                "A detected cohort must resolve globally regardless of the old streaming-only toggle");
            assertSame(recorded.getFirst(), streamed.getFirst());
            assertSame(recorded.getFirst(), webCalls.getFirst());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void duplicateResolutionWaitsForKnownMembersAndFansOutSameBestCall() throws Exception
    {
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> webCalls = new CopyOnWriteArrayList<>();
        CountDownLatch resolvedFanout = new CountDownLatch(3);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), call -> {
                recorded.add(call);
                resolvedFanout.countDown();
            }, call -> {
                streamed.add(call);
                resolvedFanout.countDown();
            }, call -> {
                webCalls.add(call);
                resolvedFanout.countDown();
            }, DuplicateCallPriorityProvider.NONE, 1_000L);

        try
        {
            AudioCallSnapshot sparse = withVoiceQuality(
                snapshot(1, 1, 1200, 9001, "Test System", null,
                    1_000L, 2_000L, 2, false),
                new VoiceCallQuality(40, 0, 10, 0, 0, 6_850));
            AudioCallSnapshot complete = withVoiceQuality(
                snapshot(2, 2, 1200, 9002, "Test System", null,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(50, 0, 0, 0, 0, 6_850));

            coordinator.receive(audioEvent(sparse, 160));
            coordinator.receive(audioEvent(complete, 160));
            coordinator.receive(audioEvent(complete, 3_840));
            coordinator.receive(completionEvent(sparse));

            assertFalse(resolvedFanout.await(40, TimeUnit.MILLISECONDS),
                "Every output should wait while a known cohort member is still active");
            assertTrue(recorded.isEmpty());
            assertTrue(streamed.isEmpty());
            assertTrue(webCalls.isEmpty());

            coordinator.receive(completionEvent(complete));
            assertTrue(resolvedFanout.await(1, TimeUnit.SECONDS),
                "Every output should receive the resolution once all known members complete");
            assertEquals(1, recorded.size());
            assertEquals(1, streamed.size());
            assertEquals(1, webCalls.size());
            assertEquals(complete.callId(), streamed.getFirst().snapshot().callId());
            assertSame(recorded.getFirst(), streamed.getFirst());
            assertSame(recorded.getFirst(), webCalls.getFirst());
            assertFalse(recorded.getFirst().snapshot().duplicate());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void fewerMissingAndConcealedFramesWinsRegardlessOfCompletionOrder() throws Exception
    {
        String lowerQualityGuid = "00000000-0000-0000-0000-000000000101";
        String higherQualityGuid = "00000000-0000-0000-0000-000000000102";
        List<CompletedAudioCall> resolvedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), resolvedCalls::add, null, null,
            DuplicateCallPriorityProvider.NONE, 100L, 1_000L);

        try
        {
            AudioCallSnapshot lowerQuality = withVoiceQuality(
                snapshot(201, 1, 1200, 9001, "Test System", lowerQualityGuid,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(45, 0, 5, 0, 0, 6_850));
            AudioCallSnapshot higherQuality = withVoiceQuality(
                snapshot(202, 2, 1200, 9002, "Test System", higherQualityGuid,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(50, 0, 0, 0, 100, 6_850));
            coordinator.receive(audioEvent(lowerQuality, 800));
            coordinator.receive(audioEvent(higherQuality, 800));
            coordinator.receive(completionEvent(higherQuality));
            coordinator.receive(completionEvent(lowerQuality));

            awaitCondition(() -> resolvedCalls.size() == 1, "Expected one resolved logical call");
            assertEquals(higherQuality.callId(), resolvedCalls.getFirst().snapshot().callId());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void candidateWithoutPlayableAudioIsFullyMissing() throws Exception
    {
        String playableGuid = "00000000-0000-0000-0000-000000000111";
        String emptyGuid = "00000000-0000-0000-0000-000000000112";
        List<CompletedAudioCall> resolvedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), resolvedCalls::add, null, null,
            DuplicateCallPriorityProvider.NONE, 100L, 1_000L);

        try
        {
            AudioCallSnapshot playable = withVoiceQuality(
                snapshot(211, 1, 1200, 9001, "Test System", playableGuid,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(50, 0, 0, 0, 0, 6_850));
            AudioCallSnapshot empty = snapshot(212, 2, 1200, 9002, "Test System", emptyGuid,
                900L, 1_900L, 1, false);
            coordinator.receive(audioEvent(playable, 160));
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, empty, null));
            coordinator.receive(completionEvent(empty));
            coordinator.receive(completionEvent(playable));

            awaitCondition(() -> resolvedCalls.size() == 1, "Expected one resolved logical call");
            assertEquals(playable.callId(), resolvedCalls.getFirst().snapshot().callId());
            assertTrue(resolvedCalls.getFirst().hasAudio());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void sharedCohortWindowPreventsShortCleanCaptureFromBeatingCompleteCall() throws Exception
    {
        String sparseGuid = "00000000-0000-0000-0000-000000000121";
        String completeGuid = "00000000-0000-0000-0000-000000000122";
        List<CompletedAudioCall> resolvedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), resolvedCalls::add, null, null,
            DuplicateCallPriorityProvider.NONE, 100L, 1_000L);

        try
        {
            AudioCallSnapshot sparse = withVoiceQuality(
                snapshot(221, 1, 1200, 9001, "Test System", sparseGuid,
                    1_000L, 1_200L, 1, false),
                new VoiceCallQuality(10, 0, 0, 0, 0, 1_370));
            AudioCallSnapshot complete = withVoiceQuality(
                snapshot(222, 2, 1200, 9002, "Test System", completeGuid,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(45, 0, 5, 0, 100, 6_850));
            coordinator.receive(audioEvent(sparse, 160));
            coordinator.receive(audioEvent(complete, 800));
            coordinator.receive(completionEvent(complete));
            coordinator.receive(completionEvent(sparse));

            awaitCondition(() -> resolvedCalls.size() == 1, "Expected one resolved logical call");
            assertEquals(complete.callId(), resolvedCalls.getFirst().snapshot().callId());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void fewerRepeatedFramesWinsAfterCompletenessTie() throws Exception
    {
        List<CompletedAudioCall> resolvedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), resolvedCalls::add, null, null,
            DuplicateCallPriorityProvider.NONE, 100L, 1_000L);

        try
        {
            AudioCallSnapshot moreRepeated = withVoiceQuality(
                snapshot(223, 1, 1200, 9001, "Test System", null,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(48, 2, 0, 0, 0, 6_850));
            AudioCallSnapshot fewerRepeated = withVoiceQuality(
                snapshot(224, 2, 1200, 9002, "Test System", null,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(49, 1, 0, 0, 100, 6_850));
            coordinator.receive(audioEvent(moreRepeated, 800));
            coordinator.receive(audioEvent(fewerRepeated, 800));
            coordinator.receive(completionEvent(fewerRepeated));
            coordinator.receive(completionEvent(moreRepeated));

            awaitCondition(() -> resolvedCalls.size() == 1, "Expected one resolved logical call");
            assertEquals(fewerRepeated.callId(), resolvedCalls.getFirst().snapshot().callId());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void lowerNormalizedFecCorrectionsWinsAfterFrameOutcomeTie() throws Exception
    {
        List<CompletedAudioCall> resolvedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), resolvedCalls::add, null, null,
            DuplicateCallPriorityProvider.NONE, 100L, 1_000L);

        try
        {
            AudioCallSnapshot moreCorrections = withVoiceQuality(
                snapshot(225, 1, 1200, 9001, "Test System", null,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(50, 0, 0, 0, 25, 6_850));
            AudioCallSnapshot fewerCorrections = withVoiceQuality(
                snapshot(226, 2, 1200, 9002, "Test System", null,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(50, 0, 0, 0, 4, 6_850));
            coordinator.receive(audioEvent(moreCorrections, 800));
            coordinator.receive(audioEvent(fewerCorrections, 800));
            coordinator.receive(completionEvent(fewerCorrections));
            coordinator.receive(completionEvent(moreCorrections));

            awaitCondition(() -> resolvedCalls.size() == 1, "Expected one resolved logical call");
            assertEquals(fewerCorrections.callId(), resolvedCalls.getFirst().snapshot().callId());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void resolvedCallUnionsRecordAndStreamingPoliciesButKeepsWinnerMetadata() throws Exception
    {
        String winnerGuid = "00000000-0000-0000-0000-000000000131";
        String recordGuid = "00000000-0000-0000-0000-000000000132";
        String destinationRecordGuid = "00000000-0000-0000-0000-000000000133";
        BroadcastChannel streamA = new BroadcastChannel("Stream A");
        BroadcastChannel streamB = new BroadcastChannel("Stream B");
        BroadcastChannel streamC = new BroadcastChannel("Stream C");
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> webCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(false, true, true), recorded::add, streamed::add, webCalls::add,
            DuplicateCallPriorityProvider.NONE, 100L, 1_000L);

        try
        {
            AudioCallSnapshot winner = withPolicy(snapshot(231, 1, 1200, 9001, "Test System", winnerGuid,
                1_000L, 2_000L, 1, false), false, false, Set.of(streamA));
            AudioCallSnapshot recordMember = withPolicy(snapshot(232, 2, 1300, 9001, "Test System", recordGuid,
                1_000L, 2_000L, 1, false), true, false, Set.of(streamB));
            AudioCallSnapshot destinationRecordMember = withPolicy(snapshot(233, 3, 1400, 9001, "Test System",
                destinationRecordGuid, 1_000L, 2_000L, 1, false), false, true,
                Set.of(new BroadcastChannel("Stream A"), streamC));
            float[] winnerAudio = new float[800];
            winnerAudio[0] = 0.75f;
            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, winner, winnerAudio));
            coordinator.receive(audioEvent(recordMember, 800));
            coordinator.receive(audioEvent(destinationRecordMember, 800));
            coordinator.receive(completionEvent(destinationRecordMember));
            coordinator.receive(completionEvent(recordMember));
            coordinator.receive(completionEvent(winner));

            awaitCondition(() -> recorded.size() == 1 && streamed.size() == 1 && webCalls.size() == 1,
                "Expected one shared logical-call fanout");
            CompletedAudioCall resolved = recorded.getFirst();
            assertSame(resolved, streamed.getFirst());
            assertSame(resolved, webCalls.getFirst());
            assertEquals(winner.callId(), resolved.snapshot().callId());
            assertArrayEquals(winnerAudio, resolved.audioBuffers().getFirst());
            assertEquals(winnerGuid, resolved.snapshot().recordingMetadata().siteIdentity());
            assertTrue(resolved.snapshot().recordAudio());
            assertTrue(resolved.snapshot().recordingMetadata().destinationTalkgroupRecordEnabled());
            assertEquals(Set.of(streamA, streamB, streamC), resolved.snapshot().broadcastChannels());
            assertTrue(resolved.resolvedPolicy().recordAudio());
            assertTrue(resolved.resolvedPolicy().destinationTalkgroupRecordEnabled());
            assertEquals(Set.of("Stream A", "Stream B", "Stream C"),
                resolved.resolvedPolicy().broadcastRoutingKeys());
            assertEquals(3, resolved.resolvedPolicy().matchContexts().size());
            assertTrue(hasDestination(resolved.resolvedPolicy(), "1200"));
            assertTrue(hasDestination(resolved.resolvedPolicy(), "1300"));
            assertTrue(hasDestination(resolved.resolvedPolicy(), "1400"));
            assertTrue(resolved.resolvedPolicy().matchContexts().stream()
                .anyMatch(ResolvedCallPolicy.MatchContext::recordAudio));
            assertTrue(resolved.resolvedPolicy().matchContexts().stream()
                .anyMatch(ResolvedCallPolicy.MatchContext::destinationTalkgroupRecordEnabled));
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void destinationRecordingPolicyCarriesRecordEnabledMembersRecordingIdentity() throws Exception
    {
        String winnerGuid = "00000000-0000-0000-0000-000000000141";
        String recordingGuid = "00000000-0000-0000-0000-000000000142";
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(false, true, true), recorded::add, null, null,
            DuplicateCallPriorityProvider.NONE, 100L, 1_000L);

        try
        {
            AudioCallSnapshot winner = withRecordingMetadata(
                snapshot(241, 1, 1200, 9001, "Test System", winnerGuid,
                    1_000L, 2_000L, 1, false),
                "Winner Alias List", "Winner Talkgroup", "exact:APCO25:1200", false, "Winner Radio");
            AudioCallSnapshot recordingMember = withRecordingMetadata(
                snapshot(242, 2, 1300, 9001, "Test System", recordingGuid,
                    1_000L, 2_000L, 1, false),
                "Recording Alias List", "Recorded Talkgroup", "exact:APCO25:1300", true, "Losing Radio");
            coordinator.receive(audioEvent(winner, 800));
            coordinator.receive(audioEvent(recordingMember, 800));
            coordinator.receive(completionEvent(recordingMember));
            coordinator.receive(completionEvent(winner));

            awaitCondition(() -> recorded.size() == 1, "Expected one resolved logical call");
            AudioCallRecordingMetadata metadata = recorded.getFirst().snapshot().recordingMetadata();
            assertEquals(winner.callId(), recorded.getFirst().snapshot().callId());
            assertEquals(winnerGuid, metadata.siteIdentity(), "RF/site metadata must remain the winner's");
            assertEquals("Winner Radio", metadata.sourceAlias(),
                "Source-radio metadata must remain the winner's");
            assertEquals("APCO25", metadata.destinationProtocol());
            assertEquals("1300", metadata.destinationValue());
            assertEquals("Recorded Talkgroup", metadata.destinationAlias());
            assertEquals("exact:APCO25:1300", metadata.destinationMatcherIdentity());
            assertEquals("Recording Alias List", metadata.aliasListName());
            assertTrue(metadata.destinationTalkgroupRecordEnabled());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void destinationRecordingPolicyPrefersRecordEnabledCopyOfWinnersLogicalDestination() throws Exception
    {
        String winnerGuid = "00000000-0000-0000-0000-000000000151";
        String otherGuid = "00000000-0000-0000-0000-000000000152";
        String sameGuid = "00000000-0000-0000-0000-000000000153";
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(false, true, true), recorded::add, null, null,
            DuplicateCallPriorityProvider.NONE, 100L, 1_000L);

        try
        {
            AudioCallSnapshot winner = withRecordingMetadata(
                snapshot(251, 1, 1200, 9001, "Test System", winnerGuid,
                    1_000L, 2_000L, 1, false),
                "Winner List", "Winner Unrecorded", "exact:APCO25:1200", false, "Winner Radio");
            AudioCallSnapshot otherDestination = withRecordingMetadata(
                snapshot(252, 2, 1100, 9001, "Test System", otherGuid,
                    1_000L, 2_000L, 1, false),
                "Other List", "Other Recorded", "exact:APCO25:1100", true, "Other Radio");
            AudioCallSnapshot sameDestination = withRecordingMetadata(
                snapshot(253, 3, 1200, 9001, "Test System", sameGuid,
                    1_000L, 2_000L, 1, false),
                "Same List", "Same Recorded", "exact:APCO25:1200", true, "Same Radio");
            coordinator.receive(audioEvent(winner, 800));
            coordinator.receive(audioEvent(otherDestination, 800));
            coordinator.receive(audioEvent(sameDestination, 800));
            coordinator.receive(completionEvent(otherDestination));
            coordinator.receive(completionEvent(sameDestination));
            coordinator.receive(completionEvent(winner));

            awaitCondition(() -> recorded.size() == 1, "Expected one resolved logical call");
            AudioCallRecordingMetadata metadata = recorded.getFirst().snapshot().recordingMetadata();
            assertEquals(winner.callId(), recorded.getFirst().snapshot().callId());
            assertEquals("1200", metadata.destinationValue());
            assertEquals("Same Recorded", metadata.destinationAlias());
            assertEquals("exact:APCO25:1200", metadata.destinationMatcherIdentity());
            assertEquals("Same List", metadata.aliasListName());
            assertEquals("Winner Radio", metadata.sourceAlias());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void progressingCleanerPastInitialWatchdogCompletesAndWins() throws Exception
    {
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        CountDownLatch streamingFanout = new CountDownLatch(1);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), null, call -> {
                streamed.add(call);
                streamingFanout.countDown();
            }, null, DuplicateCallPriorityProvider.NONE, 1_000L, 5_000L);

        try
        {
            AudioCallSnapshot sparse = withVoiceQuality(
                snapshot(1, 1, 1200, 9001, "Test System", null,
                    1_000L, 2_000L, 2, false),
                new VoiceCallQuality(40, 0, 10, 0, 0, 6_850));
            AudioCallSnapshot cleaner = withVoiceQuality(
                snapshot(2, 2, 1200, 9002, "Test System", null,
                    1_000L, 2_000L, 1, false),
                new VoiceCallQuality(50, 0, 0, 0, 0, 6_850));
            coordinator.receive(audioEvent(sparse, 160));
            coordinator.receive(audioEvent(cleaner, 160));
            coordinator.receive(completionEvent(sparse));
            assertFalse(streamingFanout.await(100, TimeUnit.MILLISECONDS),
                "The first completed member must remain pending while its peer progresses");

            for(int progress = 0; progress < 4; progress++)
            {
                coordinator.receive(audioEvent(cleaner, 2_000));
                Thread.sleep(300);
            }
            assertTrue(streamed.isEmpty(),
                "A known member that is still progressing must not be treated as an orphan");

            coordinator.receive(completionEvent(cleaner));
            assertTrue(streamingFanout.await(1, TimeUnit.SECONDS));
            assertEquals(1, streamed.size());
            assertEquals(cleaner.callId(), streamed.getFirst().snapshot().callId(),
                "The progressing cleaner candidate should participate in the final quality election");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void duplicateCompletionWatchdogBoundsDelayForLingeringMember() throws Exception
    {
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> webCalls = new CopyOnWriteArrayList<>();
        CountDownLatch streamingFanout = new CountDownLatch(1);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), null, call -> {
                streamed.add(call);
                streamingFanout.countDown();
            }, webCalls::add, DuplicateCallPriorityProvider.NONE, 500L);

        try
        {
            AudioCallSnapshot completed = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot lingering = snapshot(2, 2, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);
            coordinator.receive(audioEvent(completed, 160));
            coordinator.receive(audioEvent(lingering, 160));
            coordinator.receive(completionEvent(completed));

            assertFalse(streamingFanout.await(100, TimeUnit.MILLISECONDS),
                "The watchdog should not fire before its bounded timeout");
            assertTrue(streamingFanout.await(2, TimeUnit.SECONDS),
                "A lingering member must not block completed-call fanout forever");
            assertEquals(completed.callId(), streamed.getFirst().snapshot().callId());

            coordinator.receive(completionEvent(lingering));
            awaitCondition(() -> webCalls.size() == 1,
                "Browser output should receive the same bounded resolution");
            assertEquals(1, streamed.size(),
                "A member completing after the watchdog decision remains suppressed");
            assertSame(streamed.getFirst(), webCalls.getFirst());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void detectionToggleReleasesLiveAndPendingCalls() throws Exception
    {
        MutableCallManagementProvider preferences = new MutableCallManagementProvider(true, false, true);
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        CountDownLatch firstCompleted = new CountDownLatch(1);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(preferences,
            call -> {
                recorded.add(call);
                firstCompleted.countDown();
            }, streamed::add, null, DuplicateCallPriorityProvider.NONE, 500L);

        try
        {
            AudioCallSnapshot first = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot second = snapshot(2, 2, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);
            coordinator.receive(audioEvent(first, 160));
            coordinator.receive(audioEvent(second, 160));
            coordinator.receive(completionEvent(first));
            assertTrue(firstCompleted.await(1, TimeUnit.SECONDS));

            preferences.setDetectionEnabled(false);
            coordinator.receive(audioEvent(second, 160));
            coordinator.receive(completionEvent(second));

            awaitCondition(() -> streamed.size() == 2 && recorded.size() == 2,
                "Disabling detection should release live state and pending completion candidates");
            assertFalse(streamed.getFirst().snapshot().duplicate());
            assertFalse(streamed.get(1).snapshot().duplicate());
            assertFalse(getCompletedCall(recorded, second.callId()).snapshot().duplicate(),
                "The formerly suppressed live call should complete normally after detection is disabled");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void disposeCancelsPendingDuplicateSelection() throws Exception
    {
        CountDownLatch streamed = new CountDownLatch(1);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), null,
            call -> streamed.countDown(), null, DuplicateCallPriorityProvider.NONE, 500L);

        try
        {
            AudioCallSnapshot first = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot second = snapshot(2, 2, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);
            coordinator.receive(audioEvent(first, 160));
            coordinator.receive(audioEvent(second, 160));
            coordinator.receive(completionEvent(first));

            assertFalse(streamed.await(100, TimeUnit.MILLISECONDS));
            coordinator.dispose();
            assertFalse(streamed.await(700, TimeUnit.MILLISECONDS),
                "Disposal should cancel delayed duplicate completion work");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void fullIngressAbortsPartialCallWithoutCallerRunsAndLaterCallSucceeds() throws Exception
    {
        BlockingCallManagementProvider preferences = new BlockingCallManagementProvider(false, 1);
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        AtomicReference<Thread> consumerThread = new AtomicReference<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(preferences, call -> {
            consumerThread.set(Thread.currentThread());
            recorded.add(call);
        }, null, null, DuplicateCallPriorityProvider.NONE, 100L, 1_000L, 8, 2);

        try
        {
            AudioCallSnapshot blocked = snapshot(301, 1, 1001, false, false);
            AudioCallSnapshot overflowed = snapshot(302, 1, 1002, false, false);
            AudioCallSnapshot later = snapshot(303, 1, 1003, false, false);
            coordinator.receive(audioEvent(blocked, 160));
            assertTrue(preferences.awaitBlocked(), "The owner worker should be blocked off the producer thread");
            assertFalse(preferences.blockedThread().equals(Thread.currentThread()));

            long started = System.nanoTime();

            for(int frame = 0; frame < 7; frame++)
            {
                coordinator.receive(audioEvent(overflowed, 160));
            }

            coordinator.receive(completionEvent(overflowed));
            long elapsedMilliseconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMilliseconds < 500,
                "A full coordinator must shed work without running coordinator logic on the producer");
            AudioCallCoordinator.CoordinatorQueueStatus saturated = coordinator.getQueueStatus();
            assertEquals(8, saturated.ingressDepth());
            assertEquals(6, saturated.regularIngressCapacity());
            assertEquals(1, saturated.abortedCalls());
            assertTrue(saturated.droppedIngress() >= 2);
            assertTrue(saturated.droppedOperations() >= saturated.droppedIngress(),
                "unique dropped operations must include every rejected ingress event without double-counting subtypes");

            preferences.release();
            awaitCondition(() -> coordinator.getQueueStatus().ingressDepth() == 0,
                "The bounded ingress should drain after the owner resumes");
            coordinator.receive(audioEvent(later, 160));
            coordinator.receive(completionEvent(later));
            awaitCondition(() -> recorded.size() == 1, "The next healthy call should complete normally");
            assertEquals(later.callId(), recorded.getFirst().snapshot().callId());
            assertFalse(consumerThread.get().equals(Thread.currentThread()),
                "Completed-call projection must stay off the producing thread");
        }
        finally
        {
            preferences.release();
            coordinator.dispose();
        }
    }

    @Test
    void throwingConsumerIsIsolatedAndSubsequentCallsStillFanOut() throws Exception
    {
        AtomicInteger recordingAttempts = new AtomicInteger();
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> webCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(false, false),
            call -> {
                recordingAttempts.incrementAndGet();
                throw new IllegalStateException("expected test failure");
            }, streamed::add, webCalls::add);

        try
        {
            AudioCallSnapshot first = snapshot(311, 1, 1101, false, false);
            AudioCallSnapshot second = snapshot(312, 1, 1102, false, false);
            coordinator.receive(audioEvent(first, 160));
            coordinator.receive(completionEvent(first));
            coordinator.receive(audioEvent(second, 160));
            coordinator.receive(completionEvent(second));

            awaitCondition(() -> recordingAttempts.get() == 2 && streamed.size() == 2 && webCalls.size() == 2,
                "One failed consumer must not skip other consumers or later calls");
            assertEquals(first.callId(), streamed.get(0).snapshot().callId());
            assertEquals(second.callId(), streamed.get(1).snapshot().callId());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void saturatedNonblockingConsumerDoesNotBlockOtherConsumersOrProducer() throws Exception
    {
        ArrayBlockingQueue<CompletedAudioCall> recordingQueue = new ArrayBlockingQueue<>(1);
        AtomicInteger droppedRecordings = new AtomicInteger();
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> webCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(false, false),
            call -> {
                if(!recordingQueue.offer(call))
                {
                    droppedRecordings.incrementAndGet();
                }
            }, streamed::add, webCalls::add);

        try
        {
            long receiveStarted = System.nanoTime();
            AudioCallSnapshot first = snapshot(321, 1, 1201, false, false);
            AudioCallSnapshot second = snapshot(322, 1, 1202, false, false);
            coordinator.receive(audioEvent(first, 160));
            coordinator.receive(completionEvent(first));
            coordinator.receive(audioEvent(second, 160));
            coordinator.receive(completionEvent(second));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - receiveStarted) < 250,
                "A saturated downstream queue cannot backpressure the decoder callback");
            awaitCondition(() -> streamed.size() == 2 && webCalls.size() == 2,
                "A saturated recording queue must not skip the other nonblocking consumers");
            assertEquals(1, recordingQueue.size());
            assertEquals(1, droppedRecordings.get());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void disposeDrainsAcceptedCompletionBeforeDownstreamManagersStop()
    {
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> webCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(false, false),
            recorded::add, streamed::add, webCalls::add);
        AudioCallSnapshot call = snapshot(323, 1, 1203, false, false);

        coordinator.receive(audioEvent(call, 160));
        coordinator.receive(completionEvent(call));
        coordinator.dispose();

        assertEquals(1, recorded.size(), "Shutdown must preserve an already-accepted recording handoff");
        assertEquals(1, streamed.size(), "Shutdown must preserve an already-accepted streaming handoff");
        assertEquals(1, webCalls.size(), "Shutdown must preserve an already-accepted browser handoff");
    }

    @Test
    void overflowedDuplicateMemberIsDiscardedAndHealthyPeerCompletesOnce() throws Exception
    {
        BlockingCallManagementProvider preferences = new BlockingCallManagementProvider(true, 3);
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(preferences, recorded::add, null, null,
            DuplicateCallPriorityProvider.NONE, 100L, 1_000L, 8, 2);

        try
        {
            AudioCallSnapshot overflowed = snapshot(331, 1, 1300, false, false);
            AudioCallSnapshot healthy = snapshot(332, 1, 1300, false, false);
            coordinator.receive(audioEvent(overflowed, 160));
            coordinator.receive(audioEvent(healthy, 160));
            coordinator.receive(audioEvent(overflowed, 160));
            assertTrue(preferences.awaitBlocked(), "The duplicate cohort should exist before saturation");

            for(int frame = 0; frame < 7; frame++)
            {
                coordinator.receive(audioEvent(overflowed, 160));
            }

            coordinator.receive(completionEvent(healthy));
            preferences.release();
            awaitCondition(() -> recorded.size() == 1,
                "The healthy duplicate member should survive its peer's overload");
            assertEquals(healthy.callId(), recorded.getFirst().snapshot().callId());
            assertFalse(recorded.getFirst().snapshot().duplicate());

            coordinator.receive(completionEvent(overflowed));
            Thread.sleep(100);
            assertEquals(1, recorded.size(), "The overflowed partial call must never fan out");
        }
        finally
        {
            preferences.release();
            coordinator.dispose();
        }
    }

    private static AudioCallSnapshot snapshot(long producerId, long sequence, int talkgroup, boolean complete,
                                              boolean duplicate)
    {
        AudioCallSnapshot snapshot = snapshot(producerId, sequence, talkgroup, 9001, "Test System", null,
            1_000L, 1_500L, 1, complete);
        return duplicate ? snapshot.withDuplicate(true) : snapshot;
    }

    private static AudioCallSnapshot snapshot(long producerId, long sequence, int talkgroup, int radio,
                                              String system, String sourceGuid, long startTimestamp,
                                              long lastActivityTimestamp, int burstCount, boolean complete)
    {
        AudioCallId callId = new AudioCallId(producerId, sequence, 0);
        List<Identifier> identifiers = new ArrayList<>();
        identifiers.add(SystemConfigurationIdentifier.create(system));
        identifiers.add(APCO25Talkgroup.create(talkgroup));
        identifiers.add(APCO25RadioIdentifier.createFrom(radio));

        if(sourceGuid != null)
        {
            identifiers.add(SiteGuidConfigurationIdentifier.create(sourceGuid));
        }

        return new AudioCallSnapshot(callId, null, null, new IdentifierCollection(identifiers), Set.of(),
            startTimestamp, lastActivityTimestamp, burstCount, burstCount, startTimestamp,
            lastActivityTimestamp, false, complete, false, true, false);
    }

    private static AudioCallEvent audioEvent(AudioCallSnapshot snapshot, int sampleCount)
    {
        return new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, snapshot, new float[sampleCount]);
    }

    private static AudioCallEvent completionEvent(AudioCallSnapshot snapshot)
    {
        AudioCallSnapshot completed = new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(),
            snapshot.aliasList(), snapshot.identifierCollection(), snapshot.broadcastChannels(),
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), snapshot.burstCount(),
            snapshot.burstGeneration(), snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(),
            false, true, snapshot.encrypted(), snapshot.recordAudio(), snapshot.duplicate(),
            snapshot.recordingMetadata(), snapshot.voiceCallQuality());
        return new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, completed, null);
    }

    private static AudioCallSnapshot withVoiceQuality(AudioCallSnapshot snapshot, VoiceCallQuality quality)
    {
        return new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(), snapshot.aliasList(),
            snapshot.identifierCollection(), snapshot.broadcastChannels(), snapshot.startTimestamp(),
            snapshot.lastActivityTimestamp(), snapshot.burstCount(), snapshot.burstGeneration(),
            snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(), snapshot.burstActive(),
            snapshot.complete(), snapshot.encrypted(), snapshot.recordAudio(), snapshot.duplicate(),
            snapshot.recordingMetadata(), quality);
    }

    private static AudioCallSnapshot withAliasList(AudioCallSnapshot snapshot, AliasList aliasList)
    {
        return new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(), aliasList,
            snapshot.identifierCollection(), snapshot.broadcastChannels(), snapshot.startTimestamp(),
            snapshot.lastActivityTimestamp(), snapshot.burstCount(), snapshot.burstGeneration(),
            snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(), snapshot.burstActive(),
            snapshot.complete(), snapshot.encrypted(), snapshot.recordAudio(), snapshot.duplicate(),
            snapshot.recordingMetadata(), snapshot.voiceCallQuality());
    }

    private static AudioCallSnapshot withPolicy(AudioCallSnapshot snapshot, boolean recordAudio,
                                                boolean destinationRecordEnabled,
                                                Set<BroadcastChannel> broadcastChannels)
    {
        AudioCallRecordingMetadata metadata = snapshot.recordingMetadata();
        AudioCallRecordingMetadata policyMetadata = new AudioCallRecordingMetadata(metadata.systemName(),
            metadata.systemIdentity(), metadata.siteName(), metadata.siteIdentity(), metadata.channelName(),
            metadata.channelIdentity(), metadata.aliasListName(), metadata.destinationProtocol(),
            metadata.destinationValue(), metadata.destinationIdentity(), metadata.destinationAlias(),
            metadata.destinationMatcherIdentity(), destinationRecordEnabled, metadata.sourceProtocol(),
            metadata.sourceValue(), metadata.sourceAlias());
        return new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(), snapshot.aliasList(),
            snapshot.identifierCollection(), broadcastChannels, snapshot.startTimestamp(),
            snapshot.lastActivityTimestamp(), snapshot.burstCount(), snapshot.burstGeneration(),
            snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(), snapshot.burstActive(),
            snapshot.complete(), snapshot.encrypted(), recordAudio, snapshot.duplicate(), policyMetadata,
            snapshot.voiceCallQuality());
    }

    private static AudioCallSnapshot withRecordingMetadata(AudioCallSnapshot snapshot, String aliasListName,
                                                           String destinationAlias,
                                                           String destinationMatcherIdentity,
                                                           boolean destinationRecordEnabled,
                                                           String sourceAlias)
    {
        AudioCallRecordingMetadata metadata = snapshot.recordingMetadata();
        AudioCallRecordingMetadata recordingMetadata = new AudioCallRecordingMetadata(metadata.systemName(),
            metadata.systemIdentity(), metadata.siteName(), metadata.siteIdentity(), metadata.channelName(),
            metadata.channelIdentity(), aliasListName, metadata.destinationProtocol(), metadata.destinationValue(),
            metadata.destinationIdentity(), destinationAlias, destinationMatcherIdentity, destinationRecordEnabled,
            metadata.sourceProtocol(), metadata.sourceValue(), sourceAlias);
        return new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(), snapshot.aliasList(),
            snapshot.identifierCollection(), snapshot.broadcastChannels(), snapshot.startTimestamp(),
            snapshot.lastActivityTimestamp(), snapshot.burstCount(), snapshot.burstGeneration(),
            snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(), snapshot.burstActive(),
            snapshot.complete(), snapshot.encrypted(), snapshot.recordAudio(), snapshot.duplicate(),
            recordingMetadata, snapshot.voiceCallQuality());
    }

    private static boolean hasDestination(ResolvedCallPolicy policy, String value)
    {
        return policy.matchContexts().stream().flatMap(context -> context.destinationIdentities().stream())
            .anyMatch(destination -> value.equals(Integer.toString(destination.talkgroup())));
    }

    private static boolean isCompletedDuplicate(List<CompletedAudioCall> completedCalls, AudioCallId callId)
    {
        return getCompletedCall(completedCalls, callId).snapshot().duplicate();
    }

    private static CompletedAudioCall getCompletedCall(List<CompletedAudioCall> completedCalls, AudioCallId callId)
    {
        return completedCalls.stream().filter(call -> call.snapshot().callId().equals(callId)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing completed call " + callId));
    }

    private static void assertConfiguredPriorityBridgePermutation(boolean radioEndpointFirst) throws Exception
    {
        String talkgroupGuid = "00000000-0000-0000-0000-000000000011";
        String radioGuid = "00000000-0000-0000-0000-000000000012";
        String bridgeGuid = "00000000-0000-0000-0000-000000000013";
        Map<String, Integer> priorities = Map.of(talkgroupGuid, 10, radioGuid, 10, bridgeGuid, 0);
        List<CompletedAudioCall> completedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(true, true),
            completedCalls::add, null, null, guid -> priorities.getOrDefault(guid, Integer.MAX_VALUE), 25L);

        try
        {
            AudioCallSnapshot talkgroupEndpoint = snapshot(11, 1, 100, 1, "Test System", talkgroupGuid,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot radioEndpoint = snapshot(12, 2, 200, 2, "Test System", radioGuid,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot preferredBridge = snapshot(13, 3, 100, 2, "Test System", bridgeGuid,
                1_000L, 2_000L, 1, false);

            if(radioEndpointFirst)
            {
                coordinator.receive(audioEvent(radioEndpoint, 160));
                coordinator.receive(audioEvent(talkgroupEndpoint, 160));
            }
            else
            {
                coordinator.receive(audioEvent(talkgroupEndpoint, 160));
                coordinator.receive(audioEvent(radioEndpoint, 160));
            }

            coordinator.receive(audioEvent(preferredBridge, 160));
            coordinator.receive(completionEvent(preferredBridge));
            coordinator.receive(completionEvent(talkgroupEndpoint));
            coordinator.receive(completionEvent(radioEndpoint));

            awaitCondition(() -> completedCalls.size() == 2,
                "A bridge must form one pairwise cohort and leave the incompatible endpoint independent");
            assertTrue(completedCalls.stream()
                .anyMatch(call -> call.snapshot().callId().equals(talkgroupEndpoint.callId())));
            assertTrue(completedCalls.stream()
                .anyMatch(call -> call.snapshot().callId().equals(radioEndpoint.callId())));
            assertFalse(completedCalls.stream()
                    .anyMatch(call -> call.snapshot().callId().equals(preferredBridge.callId())),
                "Configured live priority must not bypass stable resolved-call fallbacks");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    private static void awaitCondition(BooleanSupplier condition, String message) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while(System.nanoTime() < deadline)
        {
            if(condition.getAsBoolean())
            {
                return;
            }

            Thread.sleep(10);
        }

        assertTrue(condition.getAsBoolean(), message);
    }

    private static class MutableCallManagementProvider implements ICallManagementProvider
    {
        private final boolean mByTalkgroup;
        private final boolean mByRadio;
        private final boolean mSuppressStreaming;
        private volatile boolean mDetectionEnabled;

        private MutableCallManagementProvider(boolean byTalkgroup, boolean byRadio, boolean suppressStreaming)
        {
            mByTalkgroup = byTalkgroup;
            mByRadio = byRadio;
            mSuppressStreaming = suppressStreaming;
            mDetectionEnabled = byTalkgroup || byRadio;
        }

        private void setDetectionEnabled(boolean enabled)
        {
            mDetectionEnabled = enabled;
        }

        @Override
        public boolean isDuplicateCallDetectionEnabled()
        {
            return mDetectionEnabled;
        }

        @Override
        public boolean isDuplicateCallDetectionByTalkgroupEnabled()
        {
            return mDetectionEnabled && mByTalkgroup;
        }

        @Override
        public boolean isDuplicateCallDetectionByRadioEnabled()
        {
            return mDetectionEnabled && mByRadio;
        }

        @Override
        public boolean isDuplicateStreamingSuppressionEnabled()
        {
            return mSuppressStreaming;
        }
    }

    private static class BlockingCallManagementProvider implements ICallManagementProvider
    {
        private final boolean mDuplicateEnabled;
        private final int mBlockInvocation;
        private final AtomicInteger mInvocations = new AtomicInteger();
        private final CountDownLatch mBlocked = new CountDownLatch(1);
        private final CountDownLatch mRelease = new CountDownLatch(1);
        private final AtomicReference<Thread> mBlockedThread = new AtomicReference<>();

        private BlockingCallManagementProvider(boolean duplicateEnabled, int blockInvocation)
        {
            mDuplicateEnabled = duplicateEnabled;
            mBlockInvocation = blockInvocation;
        }

        @Override
        public boolean isDuplicateCallDetectionEnabled()
        {
            if(mInvocations.incrementAndGet() == mBlockInvocation)
            {
                mBlockedThread.set(Thread.currentThread());
                mBlocked.countDown();

                try
                {
                    mRelease.await(2, TimeUnit.SECONDS);
                }
                catch(InterruptedException _)
                {
                    Thread.currentThread().interrupt();
                }
            }

            return mDuplicateEnabled;
        }

        @Override
        public boolean isDuplicateCallDetectionByTalkgroupEnabled()
        {
            return mDuplicateEnabled;
        }

        @Override
        public boolean isDuplicateCallDetectionByRadioEnabled()
        {
            return false;
        }

        private boolean awaitBlocked() throws InterruptedException
        {
            return mBlocked.await(1, TimeUnit.SECONDS);
        }

        private Thread blockedThread()
        {
            return mBlockedThread.get();
        }

        private void release()
        {
            mRelease.countDown();
        }
    }
}
