/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.audio.tone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.protocol.Protocol;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class P25PagingToneServiceTest
{
    private static final P25PagingToneDetector.DetectedTone TONE =
        new P25PagingToneDetector.DetectedTone(800, 54, 200, 0.98, "decoded_audio", "p25_pcm_tone_v1");

    @Test
    void preservesCallAndSampleClockProvenanceOnlyOnWorker()
    {
        AtomicInteger analyses = new AtomicInteger();
        P25PagingToneService service = new P25PagingToneService(100, 64000, 8, buffers -> {
            analyses.incrementAndGet();
            assertEquals(40, buffers.size());
            return List.of(TONE);
        });
        var registration = service.register();
        List<IMessage> messages = new ArrayList<>();
        registration.setMessageListener(messages::add);
        offerCall(registration, 7, 40);
        assertEquals(0, analyses.get());
        assertTrue(messages.isEmpty());
        service.drain();
        assertEquals(1, analyses.get());
        DecodedPagingToneMessage message = (DecodedPagingToneMessage)messages.getFirst();
        assertEquals(new AudioCallId(1, 7, 0), message.callId());
        assertEquals(1000, message.callStartTimestamp());
        assertEquals(1020, message.firstVoiceFrameTimestamp());
        assertEquals(1800, message.completedTimestamp());
        assertEquals(1054, message.getTimestamp());
        assertEquals(Protocol.APCO25, message.getProtocol());
        assertEquals(0, message.getTimeslot());
        assertEquals(List.of(TONE), message.toneIdentifier().getValue());
        assertEquals(0, service.retainedBytes());
    }

    @Test
    void fullQueueInvalidatesEvidenceAndNextCallRecovers()
    {
        P25PagingToneService service = new P25PagingToneService(2, 64000, 8, buffers -> List.of(TONE));
        var registration = service.register();
        List<IMessage> messages = new ArrayList<>();
        registration.setMessageListener(messages::add);
        assertTimeoutPreemptively(Duration.ofMillis(250), () -> offerCall(registration, 1, 40));
        assertTrue(service.queuedEvents() <= 2);
        assertTrue(service.droppedEvents() > 0);
        service.drain();
        assertTrue(messages.isEmpty());
        assertEquals(0, service.retainedBytes());

        for(AudioCallEvent event: callEvents(2, 40))
        {
            registration.receive(event);
            service.drain();
        }

        assertEquals(1, messages.size());
        assertEquals(2, ((DecodedPagingToneMessage)messages.getFirst()).callId().sequence());
        assertEquals(0, service.retainedBytes());
    }

    @Test
    void retainedPcmAndActiveCallLimitsRejectIncompleteEvidence()
    {
        P25PagingToneService service = new P25PagingToneService(100, 1280, 1, buffers -> List.of(TONE));
        var first = service.register();
        var second = service.register();
        List<IMessage> messages = new ArrayList<>();
        first.setMessageListener(messages::add);
        second.setMessageListener(messages::add);
        first.receive(callEvents(1, 40).getFirst());
        service.drain();
        second.receive(callEvents(2, 40).getFirst());
        service.drain();
        assertTrue(service.droppedEvents() > 0);
        offerCall(first, 3, 40);
        assertTrue(service.retainedBytes() <= 1280);
        service.drain();
        service.drain();
        assertTrue(messages.isEmpty());
        assertEquals(0, service.retainedBytes());
    }

    @Test
    void completedContextSurvivesResetWhileUnfinishedContextDoesNot()
    {
        List<Integer> sizes = new ArrayList<>();
        P25PagingToneService service = new P25PagingToneService(200, 128000, 8, buffers -> {
            sizes.add(buffers.size());
            return List.of(TONE);
        });
        var registration = service.register();
        List<IMessage> messages = new ArrayList<>();
        registration.setMessageListener(messages::add);
        offerCall(registration, 1, 40);
        registration.resetUnfinishedCall();
        List<AudioCallEvent> unfinished = callEvents(2, 20);
        unfinished.subList(0, unfinished.size() - 1).forEach(registration::receive);
        registration.resetUnfinishedCall();
        offerCall(registration, 3, 40);
        service.drain();
        assertEquals(List.of(40, 40), sizes);
        assertEquals(List.of(1L, 3L), messages.stream()
            .map(value -> ((DecodedPagingToneMessage)value).callId().sequence()).toList());
        assertEquals(0, service.retainedBytes());
    }

    @Test
    void listenerReplacementAndDisposeSuppressQueuedOldMessages()
    {
        P25PagingToneService service = new P25PagingToneService(200, 128000, 8, buffers -> List.of(TONE));
        var registration = service.register();
        List<IMessage> oldMessages = new ArrayList<>();
        List<IMessage> newMessages = new ArrayList<>();
        registration.setMessageListener(oldMessages::add);
        offerCall(registration, 1, 40);
        registration.setMessageListener(newMessages::add);
        offerCall(registration, 2, 40);
        service.drain();
        assertTrue(oldMessages.isEmpty());
        assertEquals(2, ((DecodedPagingToneMessage)newMessages.getFirst()).callId().sequence());
        offerCall(registration, 3, 40);
        registration.close();
        service.drain();
        assertEquals(1, newMessages.size());
        assertEquals(0, service.retainedBytes());
    }

    @Test
    void slowConsumerNeverBlocksOffersDisposeOrOtherModuleContext() throws Exception
    {
        P25PagingToneService service = new P25PagingToneService(100, 64000, 8, buffers -> List.of(TONE));
        var slow = service.register();
        var other = service.register();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<IMessage> otherMessages = new ArrayList<>();
        slow.setMessageListener(message -> {
            entered.countDown();
            try
            {
                if(!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Test listener timed out");
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        });
        other.setMessageListener(otherMessages::add);
        offerCall(slow, 1, 40);
        var executor = Executors.newSingleThreadExecutor();

        try
        {
            var drain = executor.submit(service::drain);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(250), () -> {
                offerCall(other, 2, 40);
                slow.setMessageListener(message -> {});
                slow.close();
                service.drain(); //a contended drain is also non-blocking
            });
            assertFalse(drain.isDone());
            release.countDown();
            drain.get(2, TimeUnit.SECONDS);
            service.drain();
            assertEquals(1, otherMessages.size());
            assertEquals(2, ((DecodedPagingToneMessage)otherMessages.getFirst()).callId().sequence());
            assertEquals(0, service.retainedBytes());
        }
        finally
        {
            release.countDown();
            executor.shutdownNow();
            slow.close();
            other.close();
        }
    }

    @Test
    void timestampDiscontinuityAndNonfiniteFramesInvalidateRatherThanConcatenate()
    {
        P25PagingToneService service = new P25PagingToneService(200, 128000, 8, buffers -> List.of(TONE));
        var registration = service.register();
        List<IMessage> messages = new ArrayList<>();
        registration.setMessageListener(messages::add);
        List<AudioCallEvent> gap = callEvents(1, 40);
        AudioCallEvent frame = gap.get(10);
        gap.set(10, new AudioCallEvent(frame.eventType(), frame.snapshot(), frame.audioFrame(), false, 1,
            frame.voiceFrameTimestamp() + 20));
        gap.forEach(registration::receive);
        service.drain();
        List<AudioCallEvent> nonfinite = callEvents(2, 40);
        frame = nonfinite.get(10);
        float[] audio = frame.audioFrame();
        audio[0] = Float.NaN;
        nonfinite.set(10, new AudioCallEvent(frame.eventType(), frame.snapshot(), audio, false, 1,
            frame.voiceFrameTimestamp()));
        nonfinite.forEach(registration::receive);
        service.drain();
        service.drain();
        assertTrue(messages.isEmpty());
        assertTrue(service.droppedEvents() >= 2);
        assertEquals(0, service.retainedBytes());
    }

    @Test
    void productionDetectorAndWorkerMessageUseRawFiniteAudio()
    {
        P25PagingToneService service = new P25PagingToneService(100, 64000, 8, new P25PagingToneDetector()::detect);
        var registration = service.register();
        List<IMessage> messages = new ArrayList<>();
        registration.setMessageListener(messages::add);
        offerCall(registration, 5, 40);
        service.drain();
        var message = (DecodedPagingToneMessage)messages.getFirst();
        assertEquals(800.0, message.toneIdentifier().getValue().getFirst().frequencyHz(), 1.0);
        assertNotEquals("ToneIdentifier", message.toneIdentifier().getClass().getSimpleName());
    }

    private static void offerCall(P25PagingToneService.Registration registration, long sequence, int frames)
    {
        callEvents(sequence, frames).forEach(registration::receive);
    }

    private static List<AudioCallEvent> callEvents(long sequence, int frames)
    {
        AudioCallId id = new AudioCallId(1, sequence, 0);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(id, null, AliasList.empty("tone test"),
            new MutableIdentifierCollection(), Set.of(), 1000, 1000 + frames * 20L, 1, 1, 1000,
            1000 + frames * 20L, false, true, CallEncryptionState.CLEAR, false, null, VoiceCallQuality.EMPTY,
            CallLegId.from(id), CallLegSource.UNKNOWN, null);
        List<AudioCallEvent> events = new ArrayList<>();
        events.add(new AudioCallEvent(AudioCallEventType.CALL_CREATED, snapshot, null, false, 0, 0));
        for(int frame = 0; frame < frames; frame++)
        {
            float[] audio = new float[160];
            for(int sample = 0; sample < audio.length; sample++)
                audio[sample] = (float)(0.1 * Math.sin(2 * Math.PI * 800 * (frame * 160 + sample) / 8000));
            events.add(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, snapshot, audio, false, frame + 1,
                1020 + frame * 20L));
        }
        events.add(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, snapshot, null, false, 0, 0));
        return events;
    }
}
