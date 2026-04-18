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
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.audio.playback.ManagedPlayableAudioCall;
import io.github.dsheirer.preference.duplicate.TestCallManagementProvider;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCallCoordinatorTest
{
    @Test
    void playbackHandoffAndDuplicateSuppressionUpdateSharedPlaybackCall() throws Exception
    {
        List<ManagedPlayableAudioCall> playbackCalls = new CopyOnWriteArrayList<>();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(true, true),
            playbackCalls::add, null, null);

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

            awaitCondition(() -> playbackCalls.size() == 2, "Expected playback handoff for both active calls");
            awaitCondition(() -> playbackCalls.stream().anyMatch(ManagedPlayableAudioCall::isDuplicate),
                "Expected duplicate suppression to mark one playback call as duplicate");

            ManagedPlayableAudioCall first = playbackCalls.get(0);
            ManagedPlayableAudioCall second = playbackCalls.get(1);

            assertEquals(1, first.getAudioBufferCount());
            assertEquals(1, second.getAudioBufferCount());
            assertArrayEquals(audio1, first.getAudioBuffer(0));
            assertArrayEquals(audio2, second.getAudioBuffer(0));
            assertTrue(first.isDuplicate() || second.isDuplicate(),
                "One active playback call should be marked duplicate");
        }
        finally
        {
            coordinator.dispose();
        }
    }

    @Test
    void completionFansOutImmutableCallToRecordingAndStreaming() throws Exception
    {
        List<CompletedAudioCall> recorded = new CopyOnWriteArrayList<>();
        List<CompletedAudioCall> streamed = new CopyOnWriteArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(2);
        AudioCallCoordinator coordinator = new AudioCallCoordinator(new TestCallManagementProvider(false, false),
            null, call -> {
                recorded.add(call);
                completionLatch.countDown();
            }, call -> {
                streamed.add(call);
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

            CompletedAudioCall recordedCall = recorded.getFirst();
            CompletedAudioCall streamedCall = streamed.getFirst();

            assertTrue(recordedCall.snapshot().complete());
            assertFalse(recordedCall.snapshot().duplicate());
            assertEquals(1, recordedCall.audioBuffers().size());
            assertArrayEquals(audio, recordedCall.audioBuffers().getFirst());
            assertSame(recordedCall, streamedCall);
        }
        finally
        {
            coordinator.dispose();
        }
    }

    private static AudioCallSnapshot snapshot(long producerId, long sequence, int talkgroup, boolean complete,
                                              boolean duplicate)
    {
        AudioCallId callId = new AudioCallId(producerId, sequence, 0);
        List<Identifier> identifiers = List.of(
            SystemConfigurationIdentifier.create("Test System"),
            APCO25Talkgroup.create(talkgroup),
            APCO25RadioIdentifier.createFrom(9001));

        return new AudioCallSnapshot(callId, null, null, new IdentifierCollection(identifiers), Set.of(),
            1_000L, 1_500L, 1, 1L, 1_100L, 1_400L, false, complete, false, true, 50, duplicate);
    }

    private static void awaitCondition(BooleanSupplier condition, String message) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);

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
}
