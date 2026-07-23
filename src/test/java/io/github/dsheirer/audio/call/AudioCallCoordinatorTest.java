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

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.SiteGuidConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.duplicate.ICallManagementProvider;
import io.github.dsheirer.preference.duplicate.TestCallManagementProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCallCoordinatorTest
{
    @Test
    void coordinatorApiCannotReconnectDecodedCallSpeakerPlayback()
    {
        for(var constructor : AudioCallCoordinator.class.getDeclaredConstructors())
        {
            for(Class<?> parameterType : constructor.getParameterTypes())
            {
                assertFalse(parameterType.getPackageName().equals("io.github.dsheirer.audio.playback"),
                    "The call coordinator must not accept a receiver-speaker playback dependency");
            }
        }
    }

    @Test
    void duplicateStateAndAudioBuffersReachCompletedCallConsumers() throws Exception
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

            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, snapshot1,
                System.currentTimeMillis(), audio1));
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, snapshot2,
                System.currentTimeMillis(), audio2));
            coordinator.receive(completionEvent(snapshot1));
            coordinator.receive(completionEvent(snapshot2));

            awaitCondition(() -> recorded.size() == 2, "Expected both completed calls");
            CompletedAudioCall first = getCompletedCall(recorded, snapshot1.callId());
            CompletedAudioCall second = getCompletedCall(recorded, snapshot2.callId());

            assertEquals(1, first.audioBuffers().size());
            assertEquals(1, second.audioBuffers().size());
            assertArrayEquals(audio1, first.audioBuffers().getFirst());
            assertArrayEquals(audio2, second.audioBuffers().getFirst());
            assertTrue(first.snapshot().duplicate() || second.snapshot().duplicate(),
                "One completed call should carry the duplicate designation");
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

            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, active,
                System.currentTimeMillis(), audio));
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, completed,
                System.currentTimeMillis(), null));

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
    void configuredSourcePriorityWinsInitialElectionAndLiveWinnerRemainsSticky() throws Exception
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
                lateHigherPriorityGuid, 900L, 2_000L, 1, false);

            coordinator.receive(audioEvent(first, 160));
            coordinator.receive(audioEvent(preferred, 160));
            coordinator.receive(audioEvent(lateHigherPriority, 160));
            coordinator.receive(completionEvent(preferred));
            coordinator.receive(completionEvent(first));
            coordinator.receive(completionEvent(lateHigherPriority));

            awaitCondition(() -> completedCalls.size() == 3, "Expected all completed calls");
            assertTrue(isCompletedDuplicate(completedCalls, first.callId()),
                "Configured source priority should select the preferred source");
            assertFalse(isCompletedDuplicate(completedCalls, preferred.callId()));
            assertTrue(isCompletedDuplicate(completedCalls, lateHigherPriority.callId()),
                "A late higher-priority source must not preempt the sticky live survivor");
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
                1_000L, 3_000L, 1, false);

            coordinator.receive(audioEvent(registeredFirst, 160));
            coordinator.receive(audioEvent(earlierStart, 160));
            coordinator.receive(completionEvent(earlierStart));
            coordinator.receive(completionEvent(registeredFirst));

            awaitCondition(() -> completedCalls.size() == 2, "Expected both completed calls");
            assertTrue(isCompletedDuplicate(completedCalls, registeredFirst.callId()));
            assertFalse(isCompletedDuplicate(completedCalls, earlierStart.callId()),
                "Earlier call start should win regardless of map/hash order");
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
            AudioCallSnapshot survivor = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot duplicate = snapshot(2, 2, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);

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

            awaitCondition(() -> completedCalls.size() == 4, "Expected all completed calls");
            assertFalse(isCompletedDuplicate(completedCalls, survivor.callId()));
            assertTrue(isCompletedDuplicate(completedCalls, duplicate.callId()),
                "Remaining call must stay suppressed after the sticky survivor completes");
            assertFalse(isCompletedDuplicate(completedCalls, newTransmission.callId()),
                "A sealed old cohort must not black-hole a new call while its loser lingers");
            assertTrue(isCompletedDuplicate(completedCalls, newTransmissionDuplicate.callId()),
                "The new temporal cohort should independently suppress its own duplicate");
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

            awaitCondition(() -> completedCalls.size() == 3, "Expected all completed calls");
            assertEquals(1, completedCalls.stream().filter(call -> call.snapshot().duplicate()).count(),
                "Only calls that directly match the elected anchor should be grouped");
            assertFalse(isCompletedDuplicate(completedCalls, talkgroupAnchor.callId()));
            assertTrue(isCompletedDuplicate(completedCalls, bridge.callId()));
            assertFalse(isCompletedDuplicate(completedCalls, radioAnchor.callId()),
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

            awaitCondition(() -> completedCalls.size() == 3, "Expected all completed calls");
            assertEquals(1, completedCalls.stream().filter(call -> call.snapshot().duplicate()).count(),
                "An open cohort must remain pairwise compatible");
            assertFalse(isCompletedDuplicate(completedCalls, bridge.callId()));
            assertTrue(isCompletedDuplicate(completedCalls, talkgroupEndpoint.callId()));
            assertFalse(isCompletedDuplicate(completedCalls, radioEndpoint.callId()));
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
            null, call -> streamed.countDown(), null, DuplicateCallPriorityProvider.NONE, 500L);

        try
        {
            AudioCallSnapshot call = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            coordinator.receive(audioEvent(call, 160));
            coordinator.receive(completionEvent(call));

            assertTrue(streamed.await(200, TimeUnit.MILLISECONDS),
                "A call without an actual duplicate group should stream immediately");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void disabledDuplicateStreamingSuppressionKeepsExistingImmediateFanout() throws Exception
    {
        CountDownLatch streamed = new CountDownLatch(2);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, false), null, call -> streamed.countDown(), null,
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

            assertTrue(streamed.await(200, TimeUnit.MILLISECONDS),
                "Disabling streaming suppression should keep immediate fanout for every duplicate");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void duplicateStreamingWaitsForKnownMembersAndSelectsBetterPcmCompleteness() throws Exception
    {
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> webCalls = new CopyOnWriteArrayList<>();
        CountDownLatch immediateFanout = new CountDownLatch(4);
        CountDownLatch streamingFanout = new CountDownLatch(1);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), call -> {
                recorded.add(call);
                immediateFanout.countDown();
            }, call -> {
                streamed.add(call);
                streamingFanout.countDown();
            }, call -> {
                webCalls.add(call);
                immediateFanout.countDown();
            }, DuplicateCallPriorityProvider.NONE, 150L);

        try
        {
            AudioCallSnapshot sparse = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 2, false);
            AudioCallSnapshot complete = snapshot(2, 2, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);

            coordinator.receive(audioEvent(sparse, 160));
            coordinator.receive(audioEvent(complete, 160));
            coordinator.receive(audioEvent(complete, 3_840));
            coordinator.receive(completionEvent(sparse));

            awaitCondition(() -> recorded.size() == 1 && webCalls.size() == 1,
                "Expected immediate completion fanout for the first member");
            assertFalse(streamingFanout.await(40, TimeUnit.MILLISECONDS),
                "Streaming should wait while a known cohort member is still active");

            coordinator.receive(completionEvent(complete));
            assertTrue(immediateFanout.await(1, TimeUnit.SECONDS),
                "Recording and web completion fanout should remain immediate for every candidate");
            assertEquals(2, recorded.size());
            assertEquals(2, webCalls.size());
            assertTrue(streamingFanout.await(200, TimeUnit.MILLISECONDS),
                "Streaming should select immediately once every sealed member completes");
            assertEquals(1, streamed.size());
            assertEquals(complete.callId(), streamed.getFirst().snapshot().callId());
            assertFalse(streamed.getFirst().snapshot().duplicate(),
                "The selected streaming winner must pass the existing suppression filter");
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
        CountDownLatch firstCompletion = new CountDownLatch(1);
        CountDownLatch streamingFanout = new CountDownLatch(1);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), call -> firstCompletion.countDown(), call -> {
                streamed.add(call);
                streamingFanout.countDown();
            }, null, DuplicateCallPriorityProvider.NONE, 300L, 3_000L);

        try
        {
            AudioCallSnapshot sparse = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 2, false);
            AudioCallSnapshot cleaner = snapshot(2, 2, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);
            coordinator.receive(audioEvent(sparse, 160));
            coordinator.receive(audioEvent(cleaner, 160));
            coordinator.receive(completionEvent(sparse));
            assertTrue(firstCompletion.await(1, TimeUnit.SECONDS));

            Thread.sleep(100);
            coordinator.receive(audioEvent(cleaner, 2_000));
            Thread.sleep(200);
            coordinator.receive(audioEvent(cleaner, 2_000));
            Thread.sleep(100);
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
    void streamingWatchdogBoundsDelayForLingeringMember() throws Exception
    {
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        CountDownLatch completedFanout = new CountDownLatch(2);
        CountDownLatch streamingFanout = new CountDownLatch(1);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), null, call -> {
                streamed.add(call);
                streamingFanout.countDown();
            }, call -> completedFanout.countDown(), DuplicateCallPriorityProvider.NONE, 120L);

        try
        {
            AudioCallSnapshot completed = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot lingering = snapshot(2, 2, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);
            coordinator.receive(audioEvent(completed, 160));
            coordinator.receive(audioEvent(lingering, 160));
            coordinator.receive(completionEvent(completed));

            assertFalse(streamingFanout.await(40, TimeUnit.MILLISECONDS),
                "The watchdog should not fire before its bounded timeout");
            assertTrue(streamingFanout.await(1, TimeUnit.SECONDS),
                "A lingering member must not block streaming forever");
            assertEquals(completed.callId(), streamed.getFirst().snapshot().callId());

            coordinator.receive(completionEvent(lingering));
            assertTrue(completedFanout.await(1, TimeUnit.SECONDS));
            assertEquals(1, streamed.size(),
                "A member completing after the watchdog decision remains suppressed");
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
                "Disabling detection should release live state and pending streaming candidates");
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
    void disposeCancelsPendingStreamingSelection() throws Exception
    {
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch streamed = new CountDownLatch(1);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), call -> completed.countDown(),
            call -> streamed.countDown(), null, DuplicateCallPriorityProvider.NONE, 100L);

        try
        {
            AudioCallSnapshot first = snapshot(1, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot second = snapshot(2, 2, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);
            coordinator.receive(audioEvent(first, 160));
            coordinator.receive(audioEvent(second, 160));
            coordinator.receive(completionEvent(first));

            assertTrue(completed.await(1, TimeUnit.SECONDS));
            coordinator.dispose();
            assertFalse(streamed.await(200, TimeUnit.MILLISECONDS),
                "Disposal should cancel delayed streaming work");
        }
        finally
        {
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
            lastActivityTimestamp, false, complete, false, true, 50, false);
    }

    private static AudioCallEvent audioEvent(AudioCallSnapshot snapshot, int sampleCount)
    {
        return new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, snapshot, System.currentTimeMillis(),
            new float[sampleCount]);
    }

    private static AudioCallEvent completionEvent(AudioCallSnapshot snapshot)
    {
        AudioCallSnapshot completed = new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(),
            snapshot.aliasList(), snapshot.identifierCollection(), snapshot.broadcastChannels(),
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), snapshot.burstCount(),
            snapshot.burstGeneration(), snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(),
            false, true, snapshot.encrypted(), snapshot.recordAudio(), snapshot.monitorPriority(),
            snapshot.duplicate());
        return new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, completed, System.currentTimeMillis(), null);
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

            awaitCondition(() -> completedCalls.size() == 3, "Expected all completed calls");
            assertEquals(1, completedCalls.stream().filter(call -> call.snapshot().duplicate()).count(),
                "Configured bridge priority must still produce a pairwise clique");
            assertFalse(isCompletedDuplicate(completedCalls, preferredBridge.callId()));
            assertTrue(isCompletedDuplicate(completedCalls, talkgroupEndpoint.callId()) ^
                    isCompletedDuplicate(completedCalls, radioEndpoint.callId()),
                "Exactly one mutually incompatible endpoint may join the preferred bridge");
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
}
