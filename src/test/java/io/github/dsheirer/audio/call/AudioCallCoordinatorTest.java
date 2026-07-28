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

import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.playback.ManagedPlayableAudioCall;
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
    void liveSpeakerCallReceivesAudioAndCompletionWithoutChangingCompletedFanout() throws Exception
    {
        List<ManagedPlayableAudioCall> playbackCalls = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> completedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(false, false), playbackCalls::add,
            completedCalls::add, null, null);

        try
        {
            float[] audio = new float[] {0.25f, -0.5f};
            AudioCallSnapshot active = snapshot(1, 1, 1200, false, false);
            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, active,
                System.currentTimeMillis(), audio));

            awaitCondition(() -> playbackCalls.size() == 1 &&
                playbackCalls.getFirst().getAudioBufferCount() == 1,
                "Expected one live speaker call with its first audio frame");
            ManagedPlayableAudioCall playbackCall = playbackCalls.getFirst();
            assertEquals(active.callId(), playbackCall.callId());
            assertArrayEquals(audio, playbackCall.getAudioBuffer(0));
            assertFalse(playbackCall.isComplete());

            coordinator.receive(completionEvent(active));
            awaitCondition(() -> playbackCall.isComplete() && completedCalls.size() == 1,
                "Expected the same live call to close and one completed call to fan out");
            assertEquals(active.callId(), completedCalls.getFirst().snapshot().callId());
            assertArrayEquals(audio, completedCalls.getFirst().audioBuffers().getFirst());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void stickyLiveSpeakerWinnerRemainsIndependentFromFinalQualityElection() throws Exception
    {
        String liveWinnerGuid = "00000000-0000-0000-0000-000000000201";
        String finalWinnerGuid = "00000000-0000-0000-0000-000000000202";
        List<ManagedPlayableAudioCall> playbackCalls = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> completedCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), playbackCalls::add,
            completedCalls::add, null, null, DuplicateCallPriorityProvider.NONE,
            100L, 1_000L, null);

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

            awaitCondition(() -> playbackCalls.size() == 2 &&
                playbackCalls.stream().anyMatch(ManagedPlayableAudioCall::isDuplicate),
                "Expected duplicate state on the live speaker calls");
            ManagedPlayableAudioCall livePlayback = playbackCalls.stream()
                .filter(call -> call.callId().equals(liveWinner.callId())).findFirst().orElseThrow();
            ManagedPlayableAudioCall suppressedPlayback = playbackCalls.stream()
                .filter(call -> call.callId().equals(finalWinner.callId())).findFirst().orElseThrow();
            assertFalse(livePlayback.isDuplicate(), "The first stable live candidate remains audible");
            assertTrue(suppressedPlayback.isDuplicate(), "The other live candidate is suppressed at the speaker");

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

            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, snapshot1,
                System.currentTimeMillis(), audio1));
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, snapshot2,
                System.currentTimeMillis(), audio2));
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
    void webLifecycleExposesEarlierActiveReservationWhenLaterCallResolves() throws Exception
    {
        List<WebCallDeliveryEvent> webEvents = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(false, false), recorded::add, null, null,
            DuplicateCallPriorityProvider.NONE, 25L, 500L, webEvents::add);

        try
        {
            AudioCallSnapshot earlier = snapshot(31, 1, 1200, 9001, "First System", null,
                1_000L, 1_500L, 1, false);
            AudioCallSnapshot later = snapshot(32, 2, 2200, 9002, "Second System", null,
                2_000L, 2_500L, 1, false);
            coordinator.receive(audioEvent(earlier, 160));
            coordinator.receive(audioEvent(later, 160));
            awaitCondition(() -> webEventsOfType(webEvents, WebCallDeliveryEvent.Opened.class).size() == 2,
                "Expected chronological reservations for both active calls");

            coordinator.receive(completionEvent(later));
            awaitCondition(() -> webEventsOfType(webEvents, WebCallDeliveryEvent.Resolved.class).size() == 1,
                "The later completed call should be available to spool immediately");
            List<WebCallDeliveryEvent.Opened> opened =
                webEventsOfType(webEvents, WebCallDeliveryEvent.Opened.class);
            WebCallDeliveryEvent.OrderKey earlierKey = opened.stream()
                .map(WebCallDeliveryEvent.Opened::orderKey)
                .filter(key -> key.callId().equals(earlier.callId())).findFirst().orElseThrow();
            WebCallDeliveryEvent.Resolved laterResolved =
                webEventsOfType(webEvents, WebCallDeliveryEvent.Resolved.class).getFirst();
            assertEquals(Set.of(later.callId()), laterResolved.sourceCallIds());
            assertTrue(earlierKey.compareTo(laterResolved.orderKey()) < 0,
                "The lifecycle must leave an earlier reservation visible so publication waits");
            assertEquals(1, recorded.size(), "Core recording fanout must not wait for web publication order");

            coordinator.receive(completionEvent(earlier));
            awaitCondition(() -> webEventsOfType(webEvents, WebCallDeliveryEvent.Resolved.class).size() == 2,
                "Expected the earlier reservation to resolve");
            List<WebCallDeliveryEvent.Resolved> resolved =
                webEventsOfType(webEvents, WebCallDeliveryEvent.Resolved.class);
            assertEquals(later.callId(), resolved.get(0).call().snapshot().callId(),
                "Resolution callbacks carry completed calls immediately, even in reverse start order");
            assertEquals(earlier.callId(), resolved.get(1).call().snapshot().callId());
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void webLifecycleClosesDuplicatePhysicalReservationsWithOneLogicalCall() throws Exception
    {
        List<WebCallDeliveryEvent> webEvents = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false), recorded::add, null, null,
            DuplicateCallPriorityProvider.NONE, 25L, 500L, webEvents::add);

        try
        {
            AudioCallSnapshot first = snapshot(33, 1, 3300, 9001, "Test System", null,
                1_000L, 1_500L, 1, false);
            AudioCallSnapshot second = snapshot(34, 2, 3300, 9002, "Test System", null,
                1_100L, 1_600L, 1, false);
            coordinator.receive(audioEvent(first, 160));
            coordinator.receive(audioEvent(second, 160));
            coordinator.receive(completionEvent(second));
            coordinator.receive(completionEvent(first));

            awaitCondition(() -> webEventsOfType(webEvents, WebCallDeliveryEvent.Resolved.class).size() == 1,
                "Expected one browser lifecycle resolution for the logical duplicate");
            assertEquals(2, webEventsOfType(webEvents, WebCallDeliveryEvent.Opened.class).size());
            WebCallDeliveryEvent.Resolved resolved =
                webEventsOfType(webEvents, WebCallDeliveryEvent.Resolved.class).getFirst();
            assertEquals(Set.of(first.callId(), second.callId()), resolved.sourceCallIds());
            WebCallDeliveryEvent.OrderKey earliestOpen =
                webEventsOfType(webEvents, WebCallDeliveryEvent.Opened.class).stream()
                    .map(WebCallDeliveryEvent.Opened::orderKey).min(WebCallDeliveryEvent.OrderKey::compareTo)
                    .orElseThrow();
            assertEquals(earliestOpen, resolved.orderKey());
            assertEquals(1, recorded.size());
            assertSame(recorded.getFirst(), resolved.call(),
                "Recording and browser spooling must share the exact resolved immutable call");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void webLifecycleAbandonsSilentReservationAndReopensAtCurrentEventTime() throws Exception
    {
        List<WebCallDeliveryEvent> webEvents = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(false, false), null, null, null,
            DuplicateCallPriorityProvider.NONE, 20L, 60L, webEvents::add);

        try
        {
            AudioCallSnapshot call = snapshot(35, 1, 4400, 9001, "Test System", null,
                1_000L, 1_500L, 1, false);
            coordinator.receive(audioEvent(call, 160));
            awaitCondition(() -> webEventsOfType(webEvents, WebCallDeliveryEvent.Abandoned.class).size() == 1,
                "Expected the bounded silent-call reservation watchdog");
            WebCallDeliveryEvent.Abandoned abandoned =
                webEventsOfType(webEvents, WebCallDeliveryEvent.Abandoned.class).getFirst();
            assertEquals(WebCallDeliveryEvent.Abandoned.Reason.INACTIVITY, abandoned.reason());

            coordinator.receive(completionEvent(call));
            awaitCondition(() -> webEventsOfType(webEvents, WebCallDeliveryEvent.Resolved.class).size() == 1,
                "A late completion must reopen and then resolve a new reservation");
            List<WebCallDeliveryEvent.Opened> opened =
                webEventsOfType(webEvents, WebCallDeliveryEvent.Opened.class);
            assertEquals(2, opened.size());
            WebCallDeliveryEvent.OrderKey reopenedKey = opened.get(1).orderKey();
            WebCallDeliveryEvent.Resolved resolved =
                webEventsOfType(webEvents, WebCallDeliveryEvent.Resolved.class).getFirst();
            assertTrue(reopenedKey.compareTo(abandoned.orderKey()) > 0,
                "A reopened orphan must not insert behind an already advanced publication watermark");
            assertEquals(reopenedKey, resolved.orderKey());
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
            }, DuplicateCallPriorityProvider.NONE, 150L);

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
            coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, empty,
                System.currentTimeMillis(), null));
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
            coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, winner,
                System.currentTimeMillis(), winnerAudio));
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
            }, null, DuplicateCallPriorityProvider.NONE, 300L, 3_000L);

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
            assertFalse(streamingFanout.await(80, TimeUnit.MILLISECONDS),
                "The first completed member must remain pending while its peer progresses");

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
        List<CompletedAudioCall> webCalls = new CopyOnWriteArrayList<>();
        CountDownLatch streamingFanout = new CountDownLatch(1);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), null, call -> {
                streamed.add(call);
                streamingFanout.countDown();
            }, webCalls::add, DuplicateCallPriorityProvider.NONE, 120L);

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
    void detectionToggleReopensWebLifecycleForLiveMemberAfterResolvedElection() throws Exception
    {
        MutableCallManagementProvider preferences = new MutableCallManagementProvider(true, false, true);
        List<WebCallDeliveryEvent> webEvents = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(preferences, recorded::add, null, null,
            DuplicateCallPriorityProvider.NONE, 20L, 60L, webEvents::add);

        try
        {
            AudioCallSnapshot first = snapshot(81, 1, 1200, 9001, "Test System", null,
                1_000L, 2_000L, 1, false);
            AudioCallSnapshot lingering = snapshot(82, 2, 1200, 9002, "Test System", null,
                1_000L, 2_000L, 1, false);
            coordinator.receive(audioEvent(first, 160));
            coordinator.receive(audioEvent(lingering, 160));
            coordinator.receive(completionEvent(first));

            awaitCondition(() -> webEventsOfType(webEvents, WebCallDeliveryEvent.Resolved.class).size() == 1,
                "Expected the bounded duplicate election to resolve one browser call");
            assertEquals(2, webEventsOfType(webEvents, WebCallDeliveryEvent.Opened.class).size());

            preferences.setDetectionEnabled(false);
            coordinator.receive(audioEvent(lingering, 160));
            awaitCondition(() -> webEventsOfType(webEvents, WebCallDeliveryEvent.Opened.class).size() == 3,
                "The released live member must receive a fresh browser reservation");
            WebCallDeliveryEvent.OrderKey reopened =
                webEventsOfType(webEvents, WebCallDeliveryEvent.Opened.class).getLast().orderKey();

            coordinator.receive(completionEvent(lingering));
            awaitCondition(() -> webEventsOfType(webEvents, WebCallDeliveryEvent.Resolved.class).size() == 2,
                "The released live member must complete independently for browser playback");
            WebCallDeliveryEvent.Resolved resolved =
                webEventsOfType(webEvents, WebCallDeliveryEvent.Resolved.class).getLast();
            assertEquals(reopened, resolved.orderKey());
            assertEquals(Set.of(lingering.callId()), resolved.sourceCallIds());
            assertEquals(2, recorded.size(),
                "Browser lifecycle must stay aligned with recording after duplicate detection is disabled");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void disposeCancelsPendingStreamingSelection() throws Exception
    {
        CountDownLatch streamed = new CountDownLatch(1);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(
            new TestCallManagementProvider(true, false, true), null,
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

            assertFalse(streamed.await(40, TimeUnit.MILLISECONDS));
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
            snapshot.duplicate(), snapshot.recordingMetadata(), snapshot.voiceCallQuality());
        return new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, completed, System.currentTimeMillis(), null);
    }

    private static AudioCallSnapshot withVoiceQuality(AudioCallSnapshot snapshot, VoiceCallQuality quality)
    {
        return new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(), snapshot.aliasList(),
            snapshot.identifierCollection(), snapshot.broadcastChannels(), snapshot.startTimestamp(),
            snapshot.lastActivityTimestamp(), snapshot.burstCount(), snapshot.burstGeneration(),
            snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(), snapshot.burstActive(),
            snapshot.complete(), snapshot.encrypted(), snapshot.recordAudio(), snapshot.monitorPriority(),
            snapshot.duplicate(), snapshot.recordingMetadata(), quality);
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
            snapshot.complete(), snapshot.encrypted(), recordAudio, snapshot.monitorPriority(), snapshot.duplicate(),
            policyMetadata, snapshot.voiceCallQuality());
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
            snapshot.complete(), snapshot.encrypted(), snapshot.recordAudio(), snapshot.monitorPriority(),
            snapshot.duplicate(), recordingMetadata, snapshot.voiceCallQuality());
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

    private static <T extends WebCallDeliveryEvent> List<T> webEventsOfType(
        List<WebCallDeliveryEvent> events, Class<T> eventType)
    {
        return events.stream().filter(eventType::isInstance).map(eventType::cast).toList();
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
