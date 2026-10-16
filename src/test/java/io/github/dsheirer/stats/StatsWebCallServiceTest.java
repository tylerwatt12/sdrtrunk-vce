/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.ResolvedCallPolicy;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.audio.output.JmbeAudioOutput;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.audio.tone.ToneSummaryPayload;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentityResolutionEvidence;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.RadioResolveConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.DecoderLogicalChannelNameIdentifier;
import io.github.dsheirer.identifier.decoder.TrafficChannelIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.nbfm.NBFMTalkgroup;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Rfss;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Site;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.scanlist.ScanListModel;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebCallServiceTest
{
    @Test
    void suppliedAudioFormatIsUsedWithoutChangingNativeCallOrCachedOutput() throws Exception
    {
        var rate = new java.util.concurrent.atomic.AtomicInteger(8000);
        java.util.function.Function<CompletedAudioCall,JmbeAudioOutput.RenderedAudio> renderer = source -> {
            int outputRate = rate.get();
            float[] output = new float[Math.toIntExact(source.getDuration() * outputRate / 1000)];
            java.util.Arrays.fill(output, .1f);
            return new JmbeAudioOutput.RenderedAudio(List.of(output), outputRate);
        };
        try(StatsWebCallService service = started(new StatsWebCallService(null, null, renderer, ignored -> null));
            FeedClient client = listen(service, Set.of()))
        {
            CompletedAudioCall source = call();
            float[] before = source.audioBuffers().getFirst().clone();
            service.receive(source);
            var plain = client.awaitCall();
            byte[] original = service.get(String.valueOf(plain.get("call_id"))).wave().clone();
            assertEquals(8000, plain.get("audio_sample_rate"));
            assertEquals(8000, ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(24));
            for(int outputRate : new int[] {16000, 24000})
            {
                rate.set(outputRate);
                service.receive(source);
                var supplied = client.awaitCall();
                assertEquals(100L, supplied.get("duration_ms"));
                assertEquals(outputRate, supplied.get("audio_sample_rate"));
                byte[] wave = service.get(String.valueOf(supplied.get("call_id"))).wave();
                assertEquals(44 + outputRate / 10 * 2, wave.length);
                assertEquals(outputRate, ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN).getInt(24));
                assertFalse(supplied.containsKey("voice_model"));
                assertFalse(supplied.containsKey("voice_enhancement"));
                assertFalse(supplied.containsKey("continuous_agc"));
            }
            assertArrayEquals(original, service.get(String.valueOf(plain.get("call_id"))).wave());
            assertArrayEquals(before, source.audioBuffers().getFirst());
            assertEquals(100L, source.getDuration());
        }
    }

    @Test
    void outputBeyondBrowserCapacityRetainsThePlayableNativeCall() throws Exception
    {
        CompletedAudioCall source = new CompletedAudioCall(call().snapshot(), audioBuffers(8000 * 90));
        try(StatsWebCallService service = started(new StatsWebCallService(null, null,
                ignored -> new JmbeAudioOutput.RenderedAudio(List.of(new float[96000 * 90]), 96000),
                ignored -> null)); FeedClient client = listen(service, Set.of()))
        {
            service.receive(source);
            var metadata = client.awaitCall();
            assertEquals(90000L, metadata.get("duration_ms"));
            assertEquals(8000, metadata.get("audio_sample_rate"));
            assertEquals(1L, service.observerStatus().get("audio_output_failures"));
            assertEquals(44 + 8000 * 90 * 2, service.get(String.valueOf(metadata.get("call_id"))).wave().length);
        }
    }

    @Test
    void failedAudioOutputPreservesNativePlayback() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService(null, null,
                ignored -> { throw new IllegalStateException("Output failed"); }, ignored -> null));
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(call());
            var metadata = client.awaitCall();
            assertEquals(8000, metadata.get("audio_sample_rate"));
            assertEquals(1L, service.observerStatus().get("audio_output_failures"));
            assertEquals(8000, ByteBuffer.wrap(service.get(String.valueOf(metadata.get("call_id"))).wave())
                .order(ByteOrder.LITTLE_ENDIAN).getInt(24));
        }
    }

    @Test
    void sharedToneDetailsDoNotRequireEnhancementOrBrowserAnalysis(@TempDir Path directory) throws Exception
    {
        CompletedAudioCall source = pagingCall();
        CompletedToneSequence sequence = inferredTones(source.snapshot());
        try(StatsWebCallService service = started(new StatsWebCallService(null, null, null,
                snapshot -> sequence.matches(snapshot) ? sequence : null)); FeedClient client = listen(service, Set.of()))
        {
            service.receive(source);
            var metadata = client.awaitCall();
            assertEquals(ToneSummaryPayload.summary(sequence), metadata.get("paging_tone_summary"));
            assertFalse(metadata.containsKey("detected_paging_tones"), "Shared outcome replaces duplicate PCM analysis");
        }
    }

    @Test
    void unavailableSharedResultPreservesAudibleCallAndCanAppearIndependently() throws Exception
    {
        var enabled = new java.util.concurrent.atomic.AtomicBoolean(false);
        CompletedAudioCall source = pagingCall();
        CompletedToneSequence sequence = inferredTones(source.snapshot());
        try(StatsWebCallService service = started(new StatsWebCallService(null, null, null,
                snapshot -> enabled.get() && sequence.matches(snapshot) ? sequence : null));
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(source);
            var off = client.awaitCall();
            assertFalse(off.containsKey("paging_tone_summary"));
            byte[] plain = service.get(String.valueOf(off.get("call_id"))).wave();
            enabled.set(true);
            service.receive(source);
            var on = client.awaitCall();
            assertTrue(on.containsKey("paging_tone_summary"));
            assertArrayEquals(plain, service.get(String.valueOf(on.get("call_id"))).wave());
        }
    }

    @Test
    void invalidatedSharedOutcomeDoesNotPublishOldDetections() throws Exception
    {
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        CompletedAudioCall source = pagingCall();
        CompletedToneSequence sequence = inferredTones(source.snapshot());
        try(StatsWebCallService service = started(new StatsWebCallService(null, null, null,
                snapshot -> checks.incrementAndGet() == 1 ? sequence : null));
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(source);
            assertFalse(client.awaitCall().containsKey("paging_tone_summary"));
            assertEquals(2, checks.get());
        }
    }

    @Test
    void lateToneOutcomeUpdatesMetadataOnceWithoutChangingAudioOrPublishingAnotherCall() throws Exception
    {
        var outcome = new AtomicReference<CompletedToneSequence>();
        CompletedAudioCall source = pagingCall();
        CompletedToneSequence sequence = inferredTones(source.snapshot());
        try(StatsWebCallService service = started(new StatsWebCallService(null, null, null, snapshot -> outcome.get()));
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(source);
            var original = client.awaitCall();
            String id = String.valueOf(original.get("call_id"));
            byte[] wave = service.get(id).wave();
            outcome.set(sequence);
            service.receiveToneSequence(sequence);
            var update = client.awaitCall();
            assertEquals(id, update.get("call_id"));
            assertEquals(true, update.get("audio_metadata_updated"));
            assertEquals(ToneSummaryPayload.summary(sequence), update.get("paging_tone_summary"));
            assertArrayEquals(wave, service.get(id).wave());
            assertEquals(1L, service.observerStatus().get("published_calls"));
            service.receiveToneSequence(sequence);
            long feedGeneration = service.tryAcquireFeed();
            try
            {
                var duplicate = service.feed(Set.of(), Long.parseLong(client.cursor()), 50, TimeUnit.MILLISECONDS, feedGeneration);
                assertTrue(duplicate.calls().isEmpty());
            }
            finally { service.releaseFeed(feedGeneration); }
        }
    }

    @Test
    void sharedToneLookupFailureCannotSuppressAudio() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService(null, null, null,
                snapshot -> { throw new IllegalStateException("Unavailable optional observer"); }));
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(call());
            var metadata = client.awaitCall();
            assertFalse(metadata.containsKey("paging_tone_summary"));
            assertNotNull(service.get(String.valueOf(metadata.get("call_id"))));
            assertEquals(1L, service.observerStatus().get("tone_metadata_failures"));
        }
    }

    @Test
    void switchingPcmDetectionOffWhilePublicationIsBlockedCannotAttachStaleTones() throws Exception
    {
        CompletedAudioCall source = distinctPagingCall(1);
        CompletedToneSequence sequence = inferredTones(source.nativeAudioSnapshot());
        var outcome = new AtomicReference<>(sequence);
        try(StatsWebCallService service = started(new StatsWebCallService(null, null, null,
                snapshot -> outcome.get())); FeedClient client = listen(service, Set.of()))
        {
            synchronized(service)
            {
                service.receive(source);
                assertTrue(awaitThreadState(encoderThread(service), Thread.State.BLOCKED, 5, TimeUnit.SECONDS));
                //The shared observer invalidates PCM lookup identity immediately on Off.
                outcome.set(null);
            }
            var off = client.awaitCall();
            assertNotNull(off);
            assertFalse(off.containsKey("paging_tone_summary"));
            byte[] wave = service.get(String.valueOf(off.get("call_id"))).wave();
            outcome.set(sequence);
            service.receive(source);
            var on = client.awaitCall();
            assertEquals(ToneSummaryPayload.summary(sequence), on.get("paging_tone_summary"));
            assertArrayEquals(wave, service.get(String.valueOf(on.get("call_id"))).wave());
        }
    }

    @Test
    void mixedOriginalAndToneUpdateEntriesPageInCursorOrderWithoutHistoricalReplayForNewClient() throws Exception
    {
        Map<CallLegId,CompletedToneSequence> outcomes = new java.util.concurrent.ConcurrentHashMap<>();
        try(StatsWebCallService service = started(new StatsWebCallService(null, null, null,
                snapshot -> outcomes.get(snapshot.callLegId())));
            FeedClient slow = listen(service, Set.of()); FeedClient observer = listen(service, Set.of()))
        {
            List<String> ids = new ArrayList<>();
            List<Boolean> updates = new ArrayList<>();
            for(int index = 0; index < 70; index++)
            {
                CompletedAudioCall source = distinctPagingCall(index + 10);
                service.receive(source);
                var original = observer.awaitCall();
                assertNotNull(original);
                String id = String.valueOf(original.get("call_id"));
                ids.add(id); updates.add(false);
                if(index % 2 == 0)
                {
                    CompletedToneSequence sequence = inferredTones(source.nativeAudioSnapshot());
                    outcomes.put(sequence.callLegId(), sequence);
                    service.receiveToneSequence(sequence);
                    var update = observer.awaitCall();
                    assertEquals(id, update.get("call_id"));
                    assertEquals(true, update.get("audio_metadata_updated"));
                    ids.add(id); updates.add(true);
                }
            }
            var first = slow.page(0, TimeUnit.NANOSECONDS);
            var second = slow.page(0, TimeUnit.NANOSECONDS);
            assertFalse(first.reset()); assertFalse(second.reset());
            assertEquals(64, first.calls().size()); assertEquals("64", first.cursor());
            assertEquals(41, second.calls().size()); assertEquals("105", second.cursor());
            List<Map<String,Object>> merged = new ArrayList<>(first.calls()); merged.addAll(second.calls());
            for(int index = 0; index < ids.size(); index++)
            {
                assertEquals(ids.get(index), merged.get(index).get("call_id"));
                assertEquals(updates.get(index).booleanValue(), Boolean.TRUE.equals(merged.get(index).get("audio_metadata_updated")));
            }
            assertEquals(70L, service.observerStatus().get("published_calls"));
            try(FeedClient fresh = listen(service, Set.of()))
            {
                assertTrue(fresh.page(0, TimeUnit.NANOSECONDS).calls().isEmpty(), "A new listener starts at the live edge");
                service.receive(distinctPagingCall(1000));
                var next = fresh.awaitCall();
                assertNotNull(next); assertFalse(next.containsKey("audio_metadata_updated"));
                assertFalse(ids.contains(String.valueOf(next.get("call_id"))));
            }
        }
    }

    @Test
    void toneUpdateRingOverflowAndAudioEvictionPreserveOriginalAudioOrderAndBounds() throws Exception
    {
        Map<CallLegId,CompletedToneSequence> outcomes = new java.util.concurrent.ConcurrentHashMap<>();
        try(StatsWebCallService service = started(new StatsWebCallService(null, null, null,
                snapshot -> outcomes.get(snapshot.callLegId()))); FeedClient observer = listen(service, Set.of()))
        {
            List<String> ids = new ArrayList<>();
            CompletedToneSequence firstSequence = null;
            for(int index = 0; index < 257; index++)
            {
                CompletedAudioCall source = distinctPagingCall(index + 2000);
                service.receive(source);
                var original = observer.awaitCall();
                String id = String.valueOf(original.get("call_id")); ids.add(id);
                long cursor = service.get(id).cursor(); byte[] wave = service.get(id).wave();
                CompletedToneSequence sequence = inferredTones(source.nativeAudioSnapshot());
                if(index == 0) firstSequence = sequence;
                outcomes.put(sequence.callLegId(), sequence); service.receiveToneSequence(sequence);
                var update = observer.awaitCall();
                assertEquals(id, update.get("call_id"));
                assertEquals(cursor, service.get(id).cursor(), "Metadata cannot replace the audio cursor");
                assertTrue(wave == service.get(id).wave(), "Metadata cannot copy or replace the cached WAV");
            }
            assertEquals(256, toneUpdateCount(service));
            assertEquals(1L, service.observerStatus().get("dropped_tone_metadata"), "Only optional late metadata was trimmed");
            for(int index = 0; index < 257; index++)
            {
                service.receive(distinctPagingCall(index + 3000));
                var original = observer.awaitCall();
                assertNotNull(original); assertFalse(original.containsKey("audio_metadata_updated"));
                ids.add(String.valueOf(original.get("call_id")));
            }
            assertEquals(514L, service.observerStatus().get("published_calls"));
            assertNull(service.get(ids.get(0))); assertNull(service.get(ids.get(1)));
            assertEquals(255, toneUpdateCount(service), "Eviction also removes that audio call's late entry");
            long previous = 0L;
            for(int index = 2; index < ids.size(); index++)
            {
                var retained = service.get(ids.get(index)); assertNotNull(retained);
                assertTrue(retained.cursor() > previous); previous = retained.cursor();
            }
            var remaining = service.feed(Set.of(), 3L, 0, TimeUnit.NANOSECONDS, observer.generation());
            assertFalse(remaining.reset(), "Missing optional metadata must not force retained audio out of order");
            assertTrue(remaining.calls().stream().noneMatch(value -> ids.get(0).equals(value.get("call_id")) || ids.get(1).equals(value.get("call_id"))));
            service.receiveToneSequence(firstSequence);
            assertTrue(observer.page(50, TimeUnit.MILLISECONDS).calls().isEmpty(), "An evicted call cannot reappear as an update");
        }
    }

    @Test
    void fullToneIngressNeverWaitsForBlockedConsumerAndRestartRejectsOldStampedUpdates() throws Exception
    {
        CompletedAudioCall oldCall = distinctPagingCall(4000);
        CompletedToneSequence sequence = inferredTones(oldCall.nativeAudioSnapshot());
        try(StatsWebCallService service = started(new StatsWebCallService(null, null, null,
                snapshot -> sequence.matches(snapshot) ? sequence : null)); FeedClient oldClient = listen(service, Set.of()))
        {
            service.receive(oldCall);
            String oldId = String.valueOf(oldClient.awaitCall().get("call_id"));
            CountDownLatch offered = new CountDownLatch(1);
            AtomicLong offerElapsed = new AtomicLong();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread producer = null;
            try
            {
                synchronized(service)
                {
                    service.receiveToneSequence(sequence);
                    assertTrue(awaitThreadState(encoderThread(service), Thread.State.BLOCKED, 5, TimeUnit.SECONDS));
                    producer = new Thread(() -> {
                        long started = System.nanoTime();
                        try { for(int index = 0; index < 129; index++) service.receiveToneSequence(sequence); }
                        catch(Throwable error) { failure.set(error); }
                        finally { offerElapsed.set(System.nanoTime() - started); offered.countDown(); }
                    }, "tone ingress test producer");
                    producer.start();
                    assertTrue(offered.await(500, TimeUnit.MILLISECONDS), "Producer cannot wait for a blocked cache consumer");
                    assertNull(failure.get());
                    assertTrue(offerElapsed.get() < TimeUnit.MILLISECONDS.toNanos(500));
                    assertEquals(128, service.observerStatus().get("tone_metadata_queue_depth"));
                    assertEquals(1L, service.observerStatus().get("dropped_tone_metadata"));
                    service.stop(); service.start();
                }
            }
            finally { if(producer != null) producer.join(1000); }
            assertNull(service.get(oldId));
            assertTrue(oldClient.page(0, TimeUnit.NANOSECONDS).reset());
            try(FeedClient restarted = listen(service, Set.of()))
            {
                service.receive(distinctPagingCall(4001));
                var fresh = restarted.awaitCall(); assertNotNull(fresh);
                assertFalse(fresh.containsKey("paging_tone_summary"));
                assertFalse(fresh.containsKey("audio_metadata_updated"));
                assertEquals(0L, awaitStatus(service, "tone_metadata_queue_depth", 0L));
                assertEquals(0, toneUpdateCount(service));
            }
        }
    }

    @Test
    void expandedLogicalTimesStillMatchTheNativeAudioWinnerForInitialAndLateTones() throws Exception
    {
        CompletedAudioCall source = distinctPagingCall(5000);
        AudioCallSnapshot nativeSnapshot = source.nativeAudioSnapshot();
        AudioCallSnapshot logical = new AudioCallSnapshot(nativeSnapshot.callId(), nativeSnapshot.linkedCallId(), nativeSnapshot.aliasList(),
            nativeSnapshot.identifierCollection(), nativeSnapshot.broadcastChannels(), nativeSnapshot.startTimestamp() - 500L,
            nativeSnapshot.lastActivityTimestamp() + 500L, nativeSnapshot.burstCount(), nativeSnapshot.burstGeneration(),
            nativeSnapshot.lastBurstStartTimestamp(), nativeSnapshot.lastBurstEndTimestamp(), false, true,
            nativeSnapshot.encryptionState(), nativeSnapshot.recordAudio(), nativeSnapshot.recordingMetadata(), nativeSnapshot.voiceCallQuality(),
            nativeSnapshot.callLegId(), nativeSnapshot.callLegSource(), nativeSnapshot.callEncryptionEvidence());
        CompletedAudioCall expanded = new CompletedAudioCall(source.logicalCallId(), logical, source.audioBuffers(), source.resolvedPolicy(),
            source.callLegSummaries(), source.recordingIdentifierCollection(), nativeSnapshot, null);
        CompletedToneSequence sequence = inferredTones(nativeSnapshot);
        assertFalse(sequence.matches(logical));
        var outcome = new AtomicReference<>(sequence);
        try(StatsWebCallService service = started(new StatsWebCallService(null, null, null,
                snapshot -> sequence.matches(snapshot) ? outcome.get() : null)); FeedClient client = listen(service, Set.of()))
        {
            service.receive(expanded);
            var initial = client.awaitCall();
            assertEquals(logical.startTimestamp(), initial.get("started_at_ms"));
            assertEquals(ToneSummaryPayload.summary(sequence), initial.get("paging_tone_summary"));
            byte[] wave = service.get(String.valueOf(initial.get("call_id"))).wave();
            outcome.set(null); service.receive(expanded);
            var original = client.awaitCall(); assertFalse(original.containsKey("paging_tone_summary"));
            outcome.set(sequence); service.receiveToneSequence(sequence);
            var late = client.awaitCall();
            assertEquals(original.get("call_id"), late.get("call_id"));
            assertEquals(true, late.get("audio_metadata_updated"));
            assertEquals(ToneSummaryPayload.summary(sequence), late.get("paging_tone_summary"));
            assertArrayEquals(wave, service.get(String.valueOf(late.get("call_id"))).wave());
        }
    }

    private static int toneUpdateCount(StatsWebCallService service) throws Exception
    {
        var field = StatsWebCallService.class.getDeclaredField("mToneMetadataUpdates"); field.setAccessible(true);
        synchronized(service) { return ((java.util.Deque<?>)field.get(service)).size(); }
    }

    private static CompletedAudioCall distinctPagingCall(int index)
    {
        CompletedAudioCall template = pagingCall(); AudioCallSnapshot original = template.snapshot();
        AudioCallId id = new AudioCallId(9876, index + 1L, 0);
        long start = 10_000L + index * 1_000L, end = start + 1_000L;
        AudioCallSnapshot snapshot = new AudioCallSnapshot(id, null, original.aliasList(), original.identifierCollection(), original.broadcastChannels(),
            start, end, original.burstCount(), original.burstGeneration(), start, end, false, true,
            original.encryptionState(), original.recordAudio(), original.recordingMetadata(), original.voiceCallQuality(),
            CallLegId.from(id), original.callLegSource(), original.callEncryptionEvidence());
        return new CompletedAudioCall(snapshot, template.audioBuffers());
    }

    private static CompletedAudioCall pagingCall()
    {
        AudioCallSnapshot original = call().snapshot();
        long end = original.startTimestamp() + 1_000L;
        AudioCallSnapshot snapshot = new AudioCallSnapshot(original.callId(), original.linkedCallId(), original.aliasList(),
            original.identifierCollection(), original.broadcastChannels(), original.startTimestamp(), end,
            original.burstCount(), original.burstGeneration(), original.lastBurstStartTimestamp(), end, false, true,
            original.encryptionState(), original.recordAudio(), original.recordingMetadata(), original.voiceCallQuality(),
            original.callLegId(), original.callLegSource(), original.callEncryptionEvidence());
        float[] audio = new float[8_000];
        for(int i = 0; i < audio.length; i++) audio[i] = (float)(.04 * Math.sin(2 * Math.PI * 1_000 * i / 8_000));
        return new CompletedAudioCall(snapshot, List.of(audio));
    }

    private static CompletedToneSequence inferredTones(AudioCallSnapshot snapshot)
    {
        return new CompletedToneSequence(snapshot, snapshot.startTimestamp(), CompletedToneSequence.Source.DECODED_AUDIO,
            CompletedToneSequence.Method.P25_PCM_TONE_V1,
            List.of(new CompletedToneSequence.Element(List.of(1_000_000), null, 0L, 250L, .9)));
    }

    @Test
    void encoderWorkerFollowsServiceOwnership() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        Thread worker = encoderThread(service);
        assertEquals(Thread.State.NEW, worker.getState(), "Construction alone must not start a permanent web worker");
        service.start();
        assertTrue(worker.isAlive());
        service.close();
        assertFalse(worker.isAlive());
        assertThrows(IllegalStateException.class, service::start);
    }

    @Test
    void publishesCompletedCallToTheSharedFeedAndServesOneMonoWave() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(call());
            Map<String,Object> metadata = client.awaitCall();
            assertNotNull(metadata);
            assertEquals("Test System", metadata.get("system"));
            assertEquals("Test Site", metadata.get("site"));
            assertEquals("00000000-0000-0000-0000-000000000737", metadata.get("configuration_id"));
            assertFalse(metadata.containsKey("site_guid"));
            assertFalse(metadata.containsKey("channel_identity"));
            assertEquals("4400", metadata.get("target_id"));
            assertEquals("TALKGROUP", metadata.get("target_form"));
            assertEquals("9001", metadata.get("source_id"));
            assertEquals("RADIO", metadata.get("source_form"));
            assertFalse(metadata.containsKey("source_canonical_identity"));
            assertFalse(metadata.containsKey("source_observed_working_id"),
                "an ordinary local P25 radio is not proven to use a roaming Working ID");
            assertEquals("CAR 9001", metadata.get("talker_alias"));
            assertEquals("APCO25", metadata.get("protocol"));
            assertEquals("p25:bee00:4a7", metadata.get("radio_system_key"));
            assertEquals(Map.of("key", "system:p25:bee00:4a7:v1-g-bee00-4a7-4400", "kind", "talkgroup",
                "radio_system_key", "p25:bee00:4a7", "label", "Talkgroup 4400"),
                metadata.get("playback_target"));
            assertEquals(854_187_500L, metadata.get("frequency_hz"));
            assertEquals("0-737", metadata.get("lcn"));
            assertEquals(String.valueOf(0xBEE00), metadata.get("wacn"));
            assertEquals(String.valueOf(0x4A7), metadata.get("system_id"));
            assertEquals(String.valueOf(0x4A1), metadata.get("nac"));
            assertEquals("1", metadata.get("rfss_id"));
            assertEquals("21", metadata.get("site_id"));
            assertEquals(100L, metadata.get("duration_ms"));
            assertEquals(98.0d, metadata.get("vc_quality_pct"));

            String callId = String.valueOf(metadata.get("call_id"));
            URI audioUri = URI.create(String.valueOf(metadata.get("audio_url")));
            assertEquals(callId, StatsWebServerService.callAudioId(audioUri));
            StatsWebCallService.CachedCall cached = service.get(callId);
            assertNotNull(cached);
            assertEquals("RIFF", new String(cached.wave(), 0, 4, StandardCharsets.US_ASCII));
            assertEquals("WAVE", new String(cached.wave(), 8, 4, StandardCharsets.US_ASCII));
            assertEquals(44 + 800 * Short.BYTES, cached.wave().length);
        }
    }

    @Test
    void publishesCanonicalP25SourceAndDestinationSeparatelyFromWorkingIds() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(canonicalRadioCall());
            Map<String,Object> metadata = client.awaitCall();
            assertNotNull(metadata);
            assertEquals(Map.of("wacn", 0xBEE00, "system_id", 0x348,
                "subscriber_id", 9_601_699), metadata.get("source_canonical_identity"));
            assertEquals(130_001, metadata.get("source_observed_working_id"));
            assertEquals(Map.of("wacn", 0x92498, "system_id", 0x926,
                "subscriber_id", 34_006), metadata.get("target_canonical_identity"));
            assertEquals(130_002, metadata.get("target_observed_working_id"));
            assertEquals("130001", metadata.get("source_id"));
            assertEquals("130002", metadata.get("target_id"));
            assertEquals("92498.926.34006 (Working ID 130002)",
                ((Map<?,?>)metadata.get("playback_target")).get("label"));
        }
    }

    @Test
    void actualCapturedGroupCallsRetainTypedFactsAndExplicitLocalProofAcrossServingContexts() throws Exception
    {
        P25SiteIdentity[] sites = {new P25SiteIdentity(0xBEE00, 0x4A7, 1, 21),
            new P25SiteIdentity(0xABCDE, 0x123, 1, 1), null,
            new P25SiteIdentity(0xABCDE, 0x123, 1, 1)};
        String[] servingKeys = {"p25:bee00:4a7", "p25:abcde:123", null, null};
        Integer[] localProofs = {3101, 501, null, null};
        int[] rawValues = {3101, 501, 0, 501};
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            for(int receiver = 0; receiver < sites.length; receiver++)
            {
                for(int kind = 0; kind < rawValues.length; kind++)
                {
                    Identifier<?> target = localProofs[kind] != null ?
                        APCO25FullyQualifiedTalkgroupIdentifier.createWithLocalAddress(rawValues[kind], 0xABCDE,
                            0x123, 501, Role.TO, IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT,
                            APCO25Talkgroup.create(rawValues[kind])) :
                        APCO25FullyQualifiedTalkgroupIdentifier.createTo(rawValues[kind], 0xABCDE, 0x123, 501);
                    var call = capturedP25Call(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                        130_001, 0xBEE00, 0x348, 9_601_699), target, sites[receiver], receiver == 3);
                    assertEquals(Integer.toString(rawValues[kind]), call.snapshot().recordingMetadata().destinationValue());
                    service.receive(call);
                    Map<String,Object> metadata = client.awaitCall();
                    assertNotNull(metadata);
                    assertEquals(Integer.toString(rawValues[kind]), metadata.get("target_id"));
                    assertEquals(Map.of("wacn", 0xABCDE, "system_id", 0x123, "talkgroup_id", 501),
                        metadata.get("target_canonical_group_identity"));
                    assertTrue(metadata.containsKey("target_observed_local_id"));
                    assertEquals(localProofs[kind], metadata.get("target_observed_local_id"));
                    assertFalse(metadata.containsKey("target_canonical_identity"));
                    assertFalse(metadata.containsKey("target_observed_working_id"));
                    assertEquals(130_001, metadata.get("source_observed_local_id"));
                    assertEquals(130_001, metadata.get("source_observed_working_id"));
                    assertEquals(servingKeys[receiver], metadata.get("serving_system_key"));
                    assertTrue(metadata.containsKey("serving_system_key"));
                    assertEquals(metadata, service.get(String.valueOf(metadata.get("call_id"))).metadata(),
                        "Cached call endpoint and live feed carry the same proven facts");
                    if(receiver == 2)
                    {
                        assertFalse(metadata.containsKey("playback_target"), "Unknown routing is not invented");
                    }
                    else
                    {
                        String identityLabel = receiver == 0 ? "ABCDE.123.501" : "501";
                        if(kind == 0) identityLabel += " (Local TG ID 3101)";
                        assertEquals("Talkgroup " + identityLabel,
                            ((Map<?,?>)metadata.get("playback_target")).get("label"));
                    }
                }
            }
        }
    }

    @Test
    void actualFromGroupFactsDoNotBroadenRadioAliasMatchingOrBorrowTheDestinationIdentity() throws Exception
    {
        var call = capturedP25Call(APCO25FullyQualifiedTalkgroupIdentifier.createFromWithLocalAddress(
            91, 0xABCDF, 0x124, 701), APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(
            3101, 0xABCDE, 0x123, 501), new P25SiteIdentity(0xBEE00, 0x4A7, 1, 21), false);
        assertNull(call.snapshot().recordingMetadata().sourceValue(), "Source alias capture remains radio-only");
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(call);
            var metadata = client.awaitCall();
            assertEquals("91", metadata.get("source_id"));
            assertEquals("TALKGROUP", metadata.get("source_form"));
            assertEquals(Map.of("wacn", 0xABCDF, "system_id", 0x124, "talkgroup_id", 701),
                metadata.get("source_canonical_group_identity"));
            assertEquals(91, metadata.get("source_observed_local_id"));
            assertEquals(Map.of("wacn", 0xABCDE, "system_id", 0x123, "talkgroup_id", 501),
                metadata.get("target_canonical_group_identity"));
            assertEquals(3101, metadata.get("target_observed_local_id"));
            assertFalse(metadata.containsKey("source_canonical_identity"));
            assertFalse(metadata.containsKey("source_observed_working_id"));
            assertFalse(metadata.containsKey("source_alias"));
        }
    }

    @Test
    void actualPatchCallCarriesBoundedMemberFactsWithoutInventingLocalAddressesOrServingContext() throws Exception
    {
        PatchGroup patch = new PatchGroup(APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(
            3101, 0xABCDE, 0x123, 501));
        patch.addPatchedTalkgroup(APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(
            91, 0xABCDF, 0x124, 701));
        patch.addPatchedTalkgroup(APCO25FullyQualifiedTalkgroupIdentifier.createTo(0, 0xABCDF, 0x124, 702));
        for(int local = 93; local < 125; local++) patch.addPatchedTalkgroup(APCO25Talkgroup.create(local));
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(
            220_078, 0xABCDE, 0x321, 777));
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createTo(0, 0xABCDF, 0x322, 999));
        var call = capturedP25Call(APCO25RadioIdentifier.createFrom(9001), APCO25PatchGroup.create(patch), null, false);
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(call);
            var metadata = client.awaitCall();
            assertEquals("3101", metadata.get("target_id"));
            assertEquals("PATCH_GROUP", metadata.get("target_form"));
            assertEquals(3101, metadata.get("patch_group_id"));
            assertEquals(Map.of("wacn", 0xABCDE, "system_id", 0x123, "talkgroup_id", 501),
                metadata.get("target_canonical_group_identity"));
            assertEquals(3101, metadata.get("target_observed_local_id"));
            assertEquals(36, metadata.get("patch_member_count"));
            assertEquals(true, metadata.get("patch_members_truncated"));
            List<?> members = (List<?>)metadata.get("patch_members");
            assertEquals(34, members.size(), "Both member kinds retain their independent bound");
            Map<?,?> group = (Map<?,?>)members.getFirst();
            assertEquals("talkgroup", group.get("kind"));
            assertEquals(91, group.get("id"));
            assertEquals(91, group.get("observed_local_id"));
            assertEquals(Map.of("wacn", 0xABCDF, "system_id", 0x124, "talkgroup_id", 701),
                group.get("canonical_group_identity"));
            Map<?,?> homeOnly = (Map<?,?>)members.get(1);
            assertEquals(0, homeOnly.get("id"));
            assertTrue(homeOnly.containsKey("observed_local_id"));
            assertNull(homeOnly.get("observed_local_id"));
            assertEquals(Map.of("wacn", 0xABCDF, "system_id", 0x124, "talkgroup_id", 702),
                homeOnly.get("canonical_group_identity"));
            Map<?,?> radio = (Map<?,?>)members.get(32);
            assertEquals("radio", radio.get("kind"));
            assertEquals(220_078, radio.get("id"));
            assertEquals(220_078, radio.get("observed_working_id"));
            assertEquals(Map.of("wacn", 0xABCDE, "system_id", 0x321, "subscriber_id", 777),
                radio.get("canonical_identity"));
            Map<?,?> homeOnlyRadio = (Map<?,?>)members.get(33);
            assertEquals(0, homeOnlyRadio.get("id"));
            assertFalse(homeOnlyRadio.containsKey("observed_working_id"));
            assertEquals(Map.of("wacn", 0xABCDF, "system_id", 0x322, "subscriber_id", 999),
                homeOnlyRadio.get("canonical_identity"));
            for(Object member : members)
            {
                assertTrue(((Map<?,?>)member).containsKey("serving_system_key"));
                assertNull(((Map<?,?>)member).get("serving_system_key"));
            }
        }
    }

    @Test
    void playbackLabelsUseCapturedWinnerServingFactsWithoutChangingRoutingOrCanonicalIdentities() throws Exception
    {
        CompletedAudioCall template = canonicalRadioCall();
        CallLegSource originalSource = template.snapshot().callLegSource();
        P25SiteIdentity[] sites = {new P25SiteIdentity(0x92498, 0x926, 1, 1),
            new P25SiteIdentity(0xBEE00, 0x4A7, 1, 21), null,
            new P25SiteIdentity(0x92498, 0x926, 1, 1)};
        String[] servingKeys = {"p25:92498:926", "p25:bee00:4a7", null, null};
        String[] labels = {"34006 (Working ID 130002)", "92498.926.34006 (Working ID 130002)",
            "34006 (Working ID 130002)", "34006 (Working ID 130002)"};
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            for(int index = 0; index < sites.length; index++)
            {
                P25SiteIdentity site = sites[index];
                CallLegSource source = new CallLegSource(originalSource.decoderType(),
                    originalSource.channelConfigurationId(), originalSource.channelName(), originalSource.radioResolveId(),
                    originalSource.aliasListId(), site, originalSource.identityDomain(), originalSource.channelKind(), true);
                CompletedAudioCall snapshotCall = withSource(template, source);
                IdentifierCollection observed = site != null ? new IdentifierCollection(List.of(
                    APCO25Wacn.create(index == 3 ? 0xBEE00 : site.wacn()),
                    APCO25System.create(index == 3 ? 0x4A7 : site.system()))) : new IdentifierCollection();
                CompletedAudioCall call = new CompletedAudioCall(snapshotCall.logicalCallId(), snapshotCall.snapshot(),
                    snapshotCall.audioBuffers(), snapshotCall.resolvedPolicy(), snapshotCall.callLegSummaries(), observed);

                service.receive(call);
                Map<String,Object> metadata = client.awaitCall();

                assertNotNull(metadata);
                assertTrue(metadata.containsKey("serving_system_key"), "Captured unknown context must remain explicit");
                assertEquals(servingKeys[index], metadata.get("serving_system_key"));
                Map<?,?> playback = (Map<?,?>)metadata.get("playback_target");
                assertEquals(labels[index], playback.get("label"));
                assertEquals(metadata.get("radio_system_key"), playback.get("radio_system_key"));
                assertNotNull(playback.get("key"), "Display must not remove the canonical playback target");
                assertEquals(Map.of("wacn", 0x92498, "system_id", 0x926, "subscriber_id", 34_006),
                    metadata.get("target_canonical_identity"));
                assertEquals(130_002, metadata.get("target_observed_working_id"));
            }
        }
    }

    @Test
    void labelsAnalogPlaybackBySavedChannelInsteadOfSyntheticTalkgroup() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(conventionalCall("Fire Dispatch"));
            Map<String,Object> metadata = client.awaitCall();
            assertNotNull(metadata);
            assertEquals("1", metadata.get("target_id"),
                "The configured analog routing number remains available as raw metadata");
            assertEquals(Map.of(
                "key", "channel:00000000-0000-0000-0000-000000000737",
                "kind", "channel",
                "label", "Fire Dispatch"), metadata.get("playback_target"));
        }
    }

    @Test
    void aMissingCursorStartsAtTheLiveEdgeWithoutHistory() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient first = listen(service, Set.of()))
        {
            service.receive(call());
            assertNotNull(first.awaitCall());

            try(FeedClient refreshed = listen(service, Set.of()))
            {
                assertTrue(refreshed.page(0, TimeUnit.NANOSECONDS).calls().isEmpty());
                service.receive(call());
                assertNotNull(refreshed.awaitCall());
            }
        }
    }

    @Test
    void keepsTheFeedActiveAcrossTheRequestGap() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService()))
        {
            String cursor;

            try(FeedClient first = listen(service, Set.of()))
            {
                cursor = first.cursor();
            }

            service.receive(call());

            try(FeedClient resumed = resume(service, Set.of(), cursor))
            {
                assertNotNull(resumed.awaitCall(),
                    "A completed call between consecutive polls must not fall through the transport gap");
            }
        }
    }

    @Test
    void aPublishedCallWakesTheBoundedLongPoll() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient ignored = listen(service, Set.of()))
        {
            AtomicReference<StatsWebCallService.FeedResult> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            Thread waiter = new Thread(() -> {
                started.countDown();
                try
                {
                    result.set(service.feed(Set.of(), 0L, 5, TimeUnit.SECONDS, ignored.generation()));
                }
                catch(Throwable throwable)
                {
                    failure.set(throwable);
                }
            }, "web-call-feed-test");
            waiter.start();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            service.receive(call());
            waiter.join(2_000L);
            assertFalse(waiter.isAlive(), "A new shared call should wake the long poll immediately");
            assertNull(failure.get());
            assertNotNull(result.get());
            assertEquals(1, result.get().calls().size());
        }
    }

    @Test
    void doesNoBrowserWorkWithoutAnActiveOrRecentlyActiveFeed() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService()))
        {
            service.receive(call());
            Thread.sleep(100);
            assertEquals(0L, service.observerStatus().get("published_calls"));
        }
    }

    @Test
    void filtersOneSharedCallBySelectedScanListsAndDeduplicatesOverlap() throws Exception
    {
        ScanListModel model = scanListModel(Map.of(101L, Set.of(2L, 3L), 102L, Set.of(3L)));

        try(StatsWebCallService service = started(new StatsWebCallService(model));
            FeedClient southwest = listen(service, Set.of(2L));
            FeedClient cleveland = listen(service, Set.of(3L)))
        {
            service.receive(call(Set.of(101L)));
            Map<String,Object> southwestCall = southwest.awaitCall();
            Map<String,Object> clevelandCall = cleveland.awaitCall();
            assertNotNull(southwestCall);
            assertNotNull(clevelandCall);
            assertEquals(southwestCall.get("call_id"), clevelandCall.get("call_id"));
            assertEquals(List.of(2L, 3L), southwestCall.get("scan_list_ids"));
            assertTrue(southwest.page(50, TimeUnit.MILLISECONDS).calls().isEmpty());

            service.receive(call(Set.of(102L)));
            assertNotNull(cleveland.awaitCall());
            assertTrue(southwest.page(250, TimeUnit.MILLISECONDS).calls().isEmpty());
            assertEquals(2L, awaitStatus(service, "published_calls", 2L));
        }
    }

    @Test
    void aFutureCursorGetsOneSimpleLiveEdgeReset() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient ignored = listen(service, Set.of()))
        {
            StatsWebCallService.FeedResult result = service.feed(Set.of(), 100L, 0, TimeUnit.NANOSECONDS,
                ignored.generation());
            assertTrue(result.reset());
            assertEquals("0", result.cursor());
            assertTrue(result.calls().isEmpty());
        }
    }

    @Test
    void returnsAscendingCallsInFixedBatches() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            for(int count = 1; count <= StatsWebCallService.MAXIMUM_FEED_CALLS + 6; count++)
            {
                service.receive(call());
                assertEquals(count, awaitStatus(service, "published_calls", count));
            }

            StatsWebCallService.FeedResult first = client.page(0, TimeUnit.NANOSECONDS);
            assertEquals(StatsWebCallService.MAXIMUM_FEED_CALLS, first.calls().size());
            assertFalse(first.reset());
            StatsWebCallService.FeedResult second = client.page(0, TimeUnit.NANOSECONDS);
            assertEquals(6, second.calls().size());
            assertFalse(second.reset());

            List<String> ids = new ArrayList<>();
            first.calls().forEach(call -> ids.add(String.valueOf(call.get("call_id"))));
            second.calls().forEach(call -> ids.add(String.valueOf(call.get("call_id"))));
            assertEquals(ids.size(), ids.stream().distinct().count());
        }
    }

    @Test
    void anExpiredCursorResetsToTheCurrentLiveEdgeWithoutReconstruction() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient ignored = listen(service, Set.of()))
        {
            for(int count = 1; count <= StatsWebCallService.MAXIMUM_CACHED_CALLS + 1; count++)
            {
                service.receive(call());
                assertEquals(count, awaitStatus(service, "published_calls", count));
            }

            StatsWebCallService.FeedResult result = service.feed(Set.of(), 0L, 0, TimeUnit.NANOSECONDS,
                ignored.generation());
            assertTrue(result.reset());
            assertEquals(Long.toString(StatsWebCallService.MAXIMUM_CACHED_CALLS + 1L), result.cursor());
            assertTrue(result.calls().isEmpty());
        }
    }

    @Test
    void boundedIngressNeverRunsEncodingOnTheCallingThreadAndSignalsTheSharedLoss() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient firstClient = listen(service, Set.of());
            FeedClient secondClient = listen(service, Set.of()))
        {
            Thread worker = encoderThread(service);
            assertTrue(worker.isAlive(), "Lifecycle construction must start the single encoder worker");
            assertEquals(Thread.MIN_PRIORITY + 1, worker.getPriority());

            synchronized(service)
            {
                service.receive(call());
                assertTrue(awaitThreadState(worker, Thread.State.BLOCKED, 5, TimeUnit.SECONDS),
                    "The first call should reach publication while the test holds the service monitor");

                long started = System.nanoTime();
                service.receive(call());
                service.receive(call());
                service.receive(call());
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(elapsedMs < 100, "The coordinator-facing offer must not wait for the encoder");
                assertEquals(1L, service.observerStatus().get("dropped_encoder_capacity"));
            }

            assertEquals(3L, awaitStatus(service, "published_calls", 3L));
            StatsWebCallService.FeedResult first = firstClient.page(0, TimeUnit.NANOSECONDS);
            StatsWebCallService.FeedResult second = secondClient.page(0, TimeUnit.NANOSECONDS);
            assertTrue(first.reset(), "Every cursor that crosses shared encoder loss must be notified");
            assertTrue(second.reset(), "The loss marker must remain visible to other connected cursors");
            assertEquals(3, first.calls().size(), "A loss notice must not discard later valid calls");
            assertEquals(first.calls(), second.calls());
        }
    }

    @Test
    void encoderFailureSignalsTheNextSuccessfullyPublishedCall() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(callWithFailingIdentifiers());
            assertEquals(1L, awaitStatus(service, "encoder_failures", 1L));
            service.receive(call());
            assertEquals(1L, awaitStatus(service, "published_calls", 1L));
            StatsWebCallService.FeedResult result = client.page(0, TimeUnit.NANOSECONDS);
            assertTrue(result.reset());
            assertEquals(1, result.calls().size());
        }
    }

    @Test
    void fullyQualifiedP25DestinationsDoNotSharePlaybackTargets() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            String firstP25 = playbackTargetKey(client, service,
                APCO25FullyQualifiedTalkgroupIdentifier.createTo(4400, 0xBEE00, 0x4A7, 12345));
            String secondP25 = playbackTargetKey(client, service,
                APCO25FullyQualifiedTalkgroupIdentifier.createTo(4400, 0xABCDE, 0x123, 12345));
            assertNotEquals(firstP25, secondP25);
            assertTrue(firstP25.contains("v1-g-bee00-4a7-12345"));
        }
    }

    @Test
    void validatesAudioSizeOnTheWorkerAndBoundsEachWave()
    {
        int maximumSamples = (StatsWebCallService.MAXIMUM_CALL_AUDIO_BYTES -
            StatsWebCallService.WAVE_HEADER_BYTES) / Short.BYTES;
        List<float[]> maximum = audioBuffers(maximumSamples);
        List<float[]> oversized = new ArrayList<>(maximum);
        oversized.add(new float[1]);
        assertEquals(StatsWebCallService.MAXIMUM_CALL_AUDIO_BYTES,
            StatsWebCallService.checkedWaveLength(maximum));
        assertEquals(-1, StatsWebCallService.checkedWaveLength(oversized));
        assertNull(StatsWebCallService.wave(new CompletedAudioCall(call().snapshot(), oversized)));
    }

    @Test
    void boundsConcurrentAudioResponsesWithAFixedLimit()
    {
        try(StatsWebCallService service = new StatsWebCallService())
        {
            for(int count = 0; count < StatsWebCallService.MAXIMUM_ACTIVE_FEEDS; count++)
            {
                assertTrue(service.tryAcquireAudioResponse());
            }
            assertFalse(service.tryAcquireAudioResponse());
            assertEquals(1L, service.observerStatus().get("rejected_audio_responses"));

            for(int count = 0; count < StatsWebCallService.MAXIMUM_ACTIVE_FEEDS; count++)
            {
                service.releaseAudioResponse();
            }
            assertTrue(service.tryAcquireAudioResponse());
            service.releaseAudioResponse();
        }
    }

    @Test
    void boundsConcurrentFeedRequestsWithAFixedLimit()
    {
        try(StatsWebCallService service = started(new StatsWebCallService()))
        {
            List<Long> generations = new ArrayList<>();

            for(int count = 0; count < StatsWebCallService.MAXIMUM_ACTIVE_FEEDS; count++)
            {
                long generation = service.tryAcquireFeed();
                assertNotEquals(StatsWebCallService.NO_FEED_GENERATION, generation);
                generations.add(generation);
            }
            assertEquals(StatsWebCallService.NO_FEED_GENERATION, service.tryAcquireFeed());
            assertEquals(1L, service.observerStatus().get("rejected_feeds"));

            for(long generation: generations)
            {
                service.releaseFeed(generation);
            }
            assertEquals(0, service.observerStatus().get("active_feeds"));
        }
    }

    @Test
    void anInactivePeriodProducesOneResetForAnOlderCursor() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService()))
        {
            service.receive(call());
            assertEquals(0L, service.observerStatus().get("published_calls"));

            long generation = service.tryAcquireFeed();
            assertNotEquals(StatsWebCallService.NO_FEED_GENERATION, generation);

            try
            {
                StatsWebCallService.FeedResult result = service.feed(Set.of(), 0L, 0, TimeUnit.NANOSECONDS,
                    generation);
                assertTrue(result.reset());
                assertEquals("1", result.cursor());
                assertTrue(result.calls().isEmpty());
            }
            finally
            {
                service.releaseFeed(generation);
            }
        }
    }

    @Test
    void stopAndRestartInvalidatesAnOlderCursor() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService()))
        {
            long oldGeneration = service.tryAcquireFeed();
            assertNotEquals(StatsWebCallService.NO_FEED_GENERATION, oldGeneration);
            StatsWebCallService.FeedResult edge = service.feed(Set.of(), null, 0, TimeUnit.NANOSECONDS,
                oldGeneration);

            service.stop();
            service.start();
            long newGeneration = service.tryAcquireFeed();
            assertNotEquals(StatsWebCallService.NO_FEED_GENERATION, newGeneration);

            try
            {
                StatsWebCallService.FeedResult result = service.feed(Set.of(), Long.parseLong(edge.cursor()), 0,
                    TimeUnit.NANOSECONDS, newGeneration);
                assertTrue(result.reset());
                assertEquals("1", result.cursor());
                assertTrue(result.calls().isEmpty());
            }
            finally
            {
                service.releaseFeed(newGeneration);
            }
        }
    }

    @Test
    void staleGenerationReleaseCannotDecrementANewRun() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService()))
        {
            long staleGeneration = service.tryAcquireFeed();
            assertNotEquals(StatsWebCallService.NO_FEED_GENERATION, staleGeneration);
            service.stop();
            service.start();
            long currentGeneration = service.tryAcquireFeed();
            assertNotEquals(StatsWebCallService.NO_FEED_GENERATION, currentGeneration);

            service.releaseFeed(staleGeneration);
            assertEquals(1, service.observerStatus().get("active_feeds"));
            service.releaseFeed(currentGeneration);
            assertEquals(0, service.observerStatus().get("active_feeds"));
        }
    }

    @Test
    void maintenanceReleasesTheIdleRingAndPreservesTheDiscardedCursorBoundary() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService()))
        {
            String callId;
            String publishedCursor;

            try(FeedClient client = listen(service, Set.of()))
            {
                service.receive(call());
                Map<String,Object> metadata = client.awaitCall();
                assertNotNull(metadata);
                callId = String.valueOf(metadata.get("call_id"));
                publishedCursor = client.cursor();
            }

            feedActiveUntil(service).set(System.nanoTime() - 1L);
            service.maintain();
            assertNull(service.get(callId), "Idle maintenance must release retained WAV audio");

            long generation = service.tryAcquireFeed();
            assertNotEquals(StatsWebCallService.NO_FEED_GENERATION, generation);

            try
            {
                StatsWebCallService.FeedResult stale = service.feed(Set.of(), 0L, 0, TimeUnit.NANOSECONDS,
                    generation);
                assertTrue(stale.reset(), "A cursor before discarded data must reset");
                assertEquals(publishedCursor, stale.cursor());
                assertTrue(stale.calls().isEmpty());

                StatsWebCallService.FeedResult boundary = service.feed(Set.of(), Long.parseLong(publishedCursor), 0,
                    TimeUnit.NANOSECONDS, generation);
                assertFalse(boundary.reset(), "A cursor that already acknowledged the discarded call remains valid");
                assertEquals(publishedCursor, boundary.cursor());
                assertTrue(boundary.calls().isEmpty());

                StatsWebCallService.FeedResult fresh = service.feed(Set.of(), null, 0, TimeUnit.NANOSECONDS,
                    generation);
                assertFalse(fresh.reset(), "A new listener starts cleanly at the current live edge");
                assertEquals(publishedCursor, fresh.cursor());
                assertTrue(fresh.calls().isEmpty());
            }
            finally
            {
                service.releaseFeed(generation);
            }
        }
    }

    @Test
    void callIdsRemainDistinctAcrossServiceInstances() throws Exception
    {
        String firstId;
        String secondId;

        try(StatsWebCallService first = started(new StatsWebCallService());
            StatsWebCallService second = started(new StatsWebCallService());
            FeedClient firstFeed = listen(first, Set.of());
            FeedClient secondFeed = listen(second, Set.of()))
        {
            first.receive(call());
            second.receive(call());
            firstId = String.valueOf(firstFeed.awaitCall().get("call_id"));
            secondId = String.valueOf(secondFeed.awaitCall().get("call_id"));
        }

        assertNotEquals(firstId, secondId);
    }

    @Test
    void publishesLearnedP25SiteIdentity() throws Exception
    {
        CompletedAudioCall template = call();
        CallLegSource source = new CallLegSource(DecoderType.P25_PHASE1,
            "00000000-0000-0000-0000-000000000737", "Traffic",
            "radioresolve-id", 1L, new P25SiteIdentity(0xABCDE, 0x348, 0x02, 0x17),
            io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain.STANDARD,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED, true);
        AudioCallSnapshot snapshot = template.snapshot();
        AudioCallSnapshot learned = new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(),
            snapshot.aliasList(), snapshot.identifierCollection(), snapshot.broadcastChannels(),
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), snapshot.burstCount(),
            snapshot.burstGeneration(), snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(),
            snapshot.burstActive(), snapshot.complete(), snapshot.encryptionState(), snapshot.recordAudio(),
            snapshot.recordingMetadata(), snapshot.voiceCallQuality(), snapshot.callLegId(), source,
            snapshot.callEncryptionEvidence());

        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(new CompletedAudioCall(learned, template.audioBuffers()));
            Map<String,Object> metadata = client.awaitCall();
            assertEquals(String.valueOf(0xABCDE), metadata.get("wacn"));
            assertEquals(String.valueOf(0x348), metadata.get("system_id"));
            assertEquals(String.valueOf(0x02), metadata.get("rfss_id"));
            assertEquals(String.valueOf(0x17), metadata.get("site_id"));
        }
    }

    @Test
    void publishesOnlyCatalogOwnedNavigationReferences() throws Exception
    {
        String configurationId = "00000000-0000-0000-0000-000000000737";
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(() ->
            WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
                configurationId, WebEntityRef.channel(configurationId),
                WebEntityRef.radioSystem("p25:bee00:4a7"), 1, 0, 0xBEE00, 0x4A7))));
        catalog.refreshNow();

        try(StatsWebCallService service = started(new StatsWebCallService(null, catalog));
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(call());
            Map<String,Object> metadata = client.awaitCall();
            assertEquals(Map.of("kind", "channel", "key", configurationId), metadata.get("entity_ref"));
            assertEquals(Map.of("kind", "radio_system", "key", "p25:bee00:4a7"),
                metadata.get("radio_system_entity_ref"));
            assertEquals(Map.of("kind", "radio", "radio_system_key", "p25:bee00:4a7",
                "identity_key", "v1-r-x-x-9001"),
                metadata.get("source_entity_ref"));
            assertEquals(Map.of("kind", "talkgroup", "radio_system_key", "p25:bee00:4a7",
                "identity_key", "v1-g-bee00-4a7-4400"),
                metadata.get("target_entity_ref"));
        }
    }

    @Test
    void omitsScopedNavigationWhenTheCatalogHasADifferentCurrentSystem() throws Exception
    {
        String configurationId = "00000000-0000-0000-0000-000000000737";
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(() ->
            WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
                configurationId, WebEntityRef.channel(configurationId),
                WebEntityRef.radioSystem("p25:bee00:4a8"), 1, 0, 0xBEE00, 0x4A8))));
        catalog.refreshNow();

        try(StatsWebCallService service = started(new StatsWebCallService(null, catalog));
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(call());
            Map<String,Object> metadata = client.awaitCall();
            assertEquals("p25:bee00:4a7", metadata.get("radio_system_key"));
            assertEquals(Map.of("kind", "channel", "key", configurationId), metadata.get("entity_ref"));
            assertFalse(metadata.containsKey("radio_system_entity_ref"));
            assertFalse(metadata.containsKey("source_entity_ref"));
            assertFalse(metadata.containsKey("target_entity_ref"));
        }
    }

    @Test
    void fullyQualifiedPrivateRadioUsesItsCanonicalHomeIdentityForNavigation() throws Exception
    {
        String configurationId = "00000000-0000-0000-0000-000000000737";
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(() ->
            WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
                configurationId, WebEntityRef.channel(configurationId),
                WebEntityRef.radioSystem("p25:bee00:4a7"), 1, 0, 0xBEE00, 0x4A7))));
        catalog.refreshNow();

        try(StatsWebCallService service = started(new StatsWebCallService(null, catalog));
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(call(APCO25FullyQualifiedRadioIdentifier.createTo(
                404, 0xABCDE, 0x123, 1234567)));
            Map<String,Object> metadata = client.awaitCall();
            assertEquals(Map.of("kind", "radio", "radio_system_key", "p25:bee00:4a7",
                "identity_key", "v1-r-abcde-123-1234567"), metadata.get("target_entity_ref"));
        }
    }

    @Test
    void callLegSourceConfigurationIdOwnsLiveCallNavigation() throws Exception
    {
        String authoritativeId = "00000000-0000-0000-0000-000000000738";
        CompletedAudioCall template = call();
        AudioCallSnapshot snapshot = template.snapshot();
        CallLegSource authoritativeSource = new CallLegSource(DecoderType.P25_PHASE1,
            authoritativeId, "Test Site", null, 0,
            new P25SiteIdentity(0xBEE00, 0x4A7, 1, 21),
            io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain.STANDARD,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED, true);
        AudioCallSnapshot replaced = new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(),
            snapshot.aliasList(), snapshot.identifierCollection(), snapshot.broadcastChannels(),
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), snapshot.burstCount(),
            snapshot.burstGeneration(), snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(),
            snapshot.burstActive(), snapshot.complete(), snapshot.encryptionState(), snapshot.recordAudio(),
            snapshot.recordingMetadata(), snapshot.voiceCallQuality(), snapshot.callLegId(), authoritativeSource,
            snapshot.callEncryptionEvidence());
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(() ->
            WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
                authoritativeId, WebEntityRef.channel(authoritativeId),
                WebEntityRef.radioSystem("p25:bee00:4a7"), 1, 0, 0xBEE00, 0x4A7))));
        catalog.refreshNow();

        try(StatsWebCallService service = started(new StatsWebCallService(null, catalog));
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(new CompletedAudioCall(replaced, template.audioBuffers()));
            Map<String,Object> metadata = client.awaitCall();
            assertEquals(authoritativeId, metadata.get("configuration_id"));
            assertEquals(Map.of("kind", "channel", "key", authoritativeId), metadata.get("entity_ref"));
        }
    }

    @Test
    void callLegSourceOwnsProtocolAndDecoderWhenTargetMetadataDisagrees() throws Exception
    {
        CompletedAudioCall p25WithDmrTarget = call(new DMRTalkgroup(4400));
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(p25WithDmrTarget);
            Map<String,Object> metadata = client.awaitCall();
            assertEquals("APCO25", metadata.get("protocol"));
            assertEquals("P25_PHASE1", metadata.get("decoder"));
        }

        CompletedAudioCall template = call(APCO25Talkgroup.create(4400));
        CallLegSource dmrSource = new CallLegSource(DecoderType.DMR,
            "00000000-0000-0000-0000-000000000737", "DMR conventional", null, 0, null,
            io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain.STANDARD,
            ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL, false);
        CompletedAudioCall conventionalDmr = withSource(template, dmrSource);
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(conventionalDmr);
            Map<String,Object> metadata = client.awaitCall();
            assertEquals("DMR", metadata.get("protocol"));
            assertEquals("DMR", metadata.get("decoder"));
        }

        CompletedAudioCall analogTemplate = conventionalCall("Source only");
        IdentifierCollection withoutTarget = new IdentifierCollection(
            analogTemplate.snapshot().identifierCollection().getIdentifiers().stream()
                .filter(identifier -> identifier != analogTemplate.snapshot().identifierCollection().getToIdentifier())
                .toList());
        CompletedAudioCall sourceOnlyAnalog = withIdentifiers(analogTemplate, withoutTarget, null);
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(sourceOnlyAnalog);
            Map<String,Object> metadata = client.awaitCall();
            assertEquals("NBFM", metadata.get("protocol"));
            assertEquals("NBFM", metadata.get("decoder"));
        }
    }

    @Test
    void publishesAliasTextFrozenAtSnapshotTime() throws Exception
    {
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        definition.setId(10);
        Alias destination = alias(101, "Dispatch", new Talkgroup(Protocol.APCO25, 4400), definition);
        Alias source = alias(102, "Unit 9001", new Radio(Protocol.APCO25, 9001), definition);
        destination.setDescription("County dispatch channel");
        destination.setGroup("Dispatch");
        source.setDescription("Patrol unit");
        source.setGroup("Police radios");
        AliasList aliasList = new AliasList(definition);
        aliasList.addAliases(List.of(destination, source));
        CompletedAudioCall completed = withAliasList(call(), aliasList);
        destination.setName("Renamed dispatch");
        source.setName("Renamed unit");

        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(completed);
            Map<String,Object> metadata = client.awaitCall();
            assertEquals("Dispatch", metadata.get("target_alias"));
            assertEquals("County dispatch channel", metadata.get("target_description"));
            assertEquals("Unit 9001", metadata.get("source_alias"));
            assertEquals("Patrol unit", metadata.get("source_description"));
        }
    }

    @Test
    void boundsEveryMetadataStringWithoutSplittingUnicode()
    {
        String oversized = "x".repeat(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS + 100);
        assertEquals(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS,
            StatsWebCallService.boundedText(oversized).length());
        String splitSurrogate = "x".repeat(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS - 1) +
            "\uD83D\uDE00tail";
        String bounded = StatsWebCallService.boundedText(splitSurrogate);
        assertFalse(Character.isHighSurrogate(bounded.charAt(bounded.length() - 1)));
    }

    private static StatsWebCallService started(StatsWebCallService service)
    {
        service.start();
        return service;
    }

    private static FeedClient listen(StatsWebCallService service, Set<Long> selected) throws Exception
    {
        long generation = service.tryAcquireFeed();
        assertNotEquals(StatsWebCallService.NO_FEED_GENERATION, generation);
        StatsWebCallService.FeedResult edge = service.feed(selected, null, 0, TimeUnit.NANOSECONDS, generation);
        return new FeedClient(service, selected, edge.cursor(), generation);
    }

    private static FeedClient resume(StatsWebCallService service, Set<Long> selected, String cursor)
    {
        long generation = service.tryAcquireFeed();
        assertNotEquals(StatsWebCallService.NO_FEED_GENERATION, generation);
        return new FeedClient(service, selected, cursor, generation);
    }

    private static final class FeedClient implements AutoCloseable
    {
        private final StatsWebCallService mService;
        private final Set<Long> mSelected;
        private final long mGeneration;
        private String mCursor;

        private FeedClient(StatsWebCallService service, Set<Long> selected, String cursor, long generation)
        {
            mService = service;
            mSelected = selected;
            mCursor = cursor;
            mGeneration = generation;
        }

        private String cursor()
        {
            return mCursor;
        }

        private long generation()
        {
            return mGeneration;
        }

        private StatsWebCallService.FeedResult page(long wait, TimeUnit unit) throws InterruptedException
        {
            StatsWebCallService.FeedResult result = mService.feed(mSelected, Long.parseLong(mCursor), wait, unit,
                mGeneration);
            mCursor = result.cursor();
            return result;
        }

        private Map<String,Object> awaitCall() throws InterruptedException
        {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

            while(System.nanoTime() < deadline)
            {
                StatsWebCallService.FeedResult result = page(250, TimeUnit.MILLISECONDS);
                assertFalse(result.reset());

                if(!result.calls().isEmpty())
                {
                    return result.calls().getFirst();
                }
            }

            return null;
        }

        @Override
        public void close()
        {
            mService.releaseFeed(mGeneration);
        }
    }

    private static long awaitStatus(StatsWebCallService service, String key, long expected)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long actual;

        do
        {
            actual = ((Number)service.observerStatus().get(key)).longValue();
            if(actual == expected)
            {
                return actual;
            }
            Thread.sleep(10);
        }
        while(System.nanoTime() < deadline);

        return actual;
    }

    private static ScanListModel scanListModel(Map<Long,Set<Long>> aliasMemberships)
    {
        ScanListModel model = new ScanListModel();
        model.replaceConfiguration(new ScanListConfiguration(List.of(
            new ScanList(1, 0, "Default", null, true, true),
            new ScanList(2, 1, "SouthWest", null, true, false),
            new ScanList(3, 2, "Cleveland", null, true, false)), aliasMemberships, Map.of()));
        return model;
    }

    private static CompletedAudioCall call(Set<Long> matchedAliasIds)
    {
        CompletedAudioCall template = call();
        ResolvedCallPolicy.MatchContext context = new ResolvedCallPolicy.MatchContext(null, 10, null,
            List.of(), matchedAliasIds, AliasList.TalkgroupMatchStatus.NOT_APPLICABLE,
            false, false, Set.of());
        return new CompletedAudioCall(template.snapshot(), template.audioBuffers(),
            new ResolvedCallPolicy(false, false, Set.of(), List.of(context)));
    }

    private static Alias alias(long id, String name, io.github.dsheirer.alias.id.AliasID matcher,
                               AliasListDefinition definition)
    {
        Alias alias = new Alias(name);
        alias.setId(id);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(matcher);
        return alias;
    }

    private static CompletedAudioCall withAliasList(CompletedAudioCall template, AliasList aliasList)
    {
        AudioCallSnapshot snapshot = template.snapshot();
        AudioCallSnapshot captured = new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(), aliasList,
            snapshot.identifierCollection(), snapshot.broadcastChannels(), snapshot.startTimestamp(),
            snapshot.lastActivityTimestamp(), snapshot.burstCount(), snapshot.burstGeneration(),
            snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(), snapshot.burstActive(),
            snapshot.complete(), snapshot.encryptionState(), snapshot.recordAudio(), null,
            snapshot.voiceCallQuality(), snapshot.callLegId(), snapshot.callLegSource(),
            snapshot.callEncryptionEvidence());
        return new CompletedAudioCall(captured, template.audioBuffers());
    }

    private static String playbackTargetKey(FeedClient client, StatsWebCallService service, Identifier<?> target)
        throws InterruptedException
    {
        service.receive(call(target));
        Map<String,Object> metadata = client.awaitCall();
        assertNotNull(metadata);
        return String.valueOf(((Map<?,?>)metadata.get("playback_target")).get("key"));
    }

    private static CompletedAudioCall call(Identifier<?> target)
    {
        CompletedAudioCall template = call();
        List<Identifier> identifiers = new ArrayList<>(template.snapshot().identifierCollection().getIdentifiers());
        identifiers.remove(template.snapshot().identifierCollection().getToIdentifier());
        identifiers.add(target);
        IdentifierCollection collection = new IdentifierCollection(identifiers);
        return withIdentifiers(template, collection,
            AudioCallRecordingMetadata.captureAtSnapshot(null, collection));
    }

    private static CompletedAudioCall canonicalRadioCall()
    {
        CompletedAudioCall template = call();
        List<Identifier> identifiers = new ArrayList<>(template.snapshot().identifierCollection().getIdentifiers());
        identifiers.removeIf(identifier -> identifier.getIdentifierClass() ==
            io.github.dsheirer.identifier.IdentifierClass.USER &&
            (identifier.getForm() == io.github.dsheirer.identifier.Form.RADIO ||
                identifier.getForm() == io.github.dsheirer.identifier.Form.TALKGROUP));
        identifiers.add(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            130_001, 0xBEE00, 0x348, 9_601_699));
        identifiers.add(APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(
            130_002, 0x92498, 0x926, 34_006));
        IdentifierCollection collection = new IdentifierCollection(identifiers);
        return withIdentifiers(template, collection, AudioCallRecordingMetadata.captureAtSnapshot(null, collection));
    }

    private static CompletedAudioCall capturedP25Call(Identifier<?> source, Identifier<?> destination,
                                                      P25SiteIdentity servingSite, boolean conflict)
    {
        CompletedAudioCall template = call();
        List<Identifier> identifiers = new ArrayList<>(template.snapshot().identifierCollection().getIdentifiers());
        identifiers.removeIf(identifier -> identifier.getIdentifierClass() == IdentifierClass.USER &&
            (identifier.getRole() == Role.FROM || identifier.getRole() == Role.TO) ||
            identifier.getIdentifierClass() == IdentifierClass.NETWORK &&
                (identifier.getForm() == Form.WACN || identifier.getForm() == Form.SYSTEM));
        identifiers.add(source);
        identifiers.add(destination);
        if(servingSite != null)
        {
            identifiers.add(APCO25Wacn.create(conflict ? 0xBEE00 : servingSite.wacn()));
            identifiers.add(APCO25System.create(conflict ? 0x4A7 : servingSite.system()));
        }
        IdentifierCollection collection = new IdentifierCollection(identifiers);
        AudioCallRecordingMetadata metadata = AudioCallRecordingMetadata.captureAtSnapshot(null, collection);
        CompletedAudioCall replaced = withIdentifiers(template, collection, metadata);
        CallLegSource original = replaced.snapshot().callLegSource();
        replaced = withSource(replaced, new CallLegSource(original.decoderType(), original.channelConfigurationId(),
            original.channelName(), original.radioResolveId(), original.aliasListId(), servingSite,
            original.identityDomain(), original.channelKind(), original.trafficChannel()));
        return new CompletedAudioCall(replaced.snapshot(), replaced.audioBuffers());
    }

    private static CompletedAudioCall callWithFailingIdentifiers()
    {
        CompletedAudioCall template = call();
        IdentifierCollection identifiers = new IdentifierCollection(
            template.snapshot().identifierCollection().getIdentifiers())
        {
            @Override
            public Identifier getToIdentifier()
            {
                throw new IllegalStateException("deliberate encoder projection failure");
            }
        };
        return withIdentifiers(template, identifiers, null);
    }

    private static CompletedAudioCall withIdentifiers(CompletedAudioCall template, IdentifierCollection identifiers,
                                                       AudioCallRecordingMetadata recordingMetadata)
    {
        AudioCallSnapshot snapshot = template.snapshot();
        AudioCallSnapshot replaced = new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(),
            snapshot.aliasList(), identifiers, snapshot.broadcastChannels(), snapshot.startTimestamp(),
            snapshot.lastActivityTimestamp(), snapshot.burstCount(), snapshot.burstGeneration(),
            snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(), snapshot.burstActive(),
            snapshot.complete(), snapshot.encryptionState(), snapshot.recordAudio(), recordingMetadata,
            snapshot.voiceCallQuality(), snapshot.callLegId(), snapshot.callLegSource(),
            snapshot.callEncryptionEvidence());
        return new CompletedAudioCall(template.logicalCallId(), replaced, template.audioBuffers(),
            template.resolvedPolicy(), template.callLegSummaries());
    }

    private static CompletedAudioCall withSource(CompletedAudioCall template, CallLegSource source)
    {
        AudioCallSnapshot snapshot = template.snapshot();
        AudioCallSnapshot replaced = new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(),
            snapshot.aliasList(), snapshot.identifierCollection(), snapshot.broadcastChannels(),
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), snapshot.burstCount(),
            snapshot.burstGeneration(), snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(),
            snapshot.burstActive(), snapshot.complete(), snapshot.encryptionState(), snapshot.recordAudio(),
            snapshot.recordingMetadata(), snapshot.voiceCallQuality(), snapshot.callLegId(), source,
            snapshot.callEncryptionEvidence());
        return new CompletedAudioCall(template.logicalCallId(), replaced, template.audioBuffers(),
            template.resolvedPolicy(), template.callLegSummaries());
    }

    private static CompletedAudioCall call()
    {
        List<Identifier> identifiers = new ArrayList<>();
        identifiers.add(SystemConfigurationIdentifier.create("Test System"));
        identifiers.add(SiteConfigurationIdentifier.create("Test Site"));
        identifiers.add(RadioResolveConfigurationIdentifier.create("00000000-0000-0000-0000-000000000021"));
        identifiers.add(ChannelConfigurationIdentifier.create("00000000-0000-0000-0000-000000000737"));
        identifiers.add(FrequencyConfigurationIdentifier.create(854_187_500L));
        identifiers.add(APCO25RadioIdentifier.createFrom(9001));
        identifiers.add(P25TalkerAliasIdentifier.create("CAR 9001"));
        identifiers.add(APCO25Wacn.create(0xBEE00));
        identifiers.add(APCO25System.create(0x4A7));
        identifiers.add(APCO25Nac.create(0x4A1));
        identifiers.add(APCO25Rfss.create(1));
        identifiers.add(APCO25Site.create(21));
        identifiers.add(APCO25Talkgroup.create(4400));
        identifiers.add(DecoderLogicalChannelNameIdentifier.create("0-737", Protocol.APCO25));
        identifiers.add(TrafficChannelIdentifier.create());
        AudioCallId callId = new AudioCallId(1, 2, 0);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            new IdentifierCollection(identifiers), Set.of(), 1_000L, 1_100L, 1, 1L, 1_000L, 1_100L,
            false, true, CallEncryptionState.CLEAR, true, null,
            new VoiceCallQuality(49, 1, 0, 0, 4, 6_850), CallLegId.from(callId),
            new CallLegSource(DecoderType.P25_PHASE1, "00000000-0000-0000-0000-000000000737", "Test Site",
                "00000000-0000-0000-0000-000000000021", 0,
                new P25SiteIdentity(0xBEE00, 0x4A7, 1, 21),
                io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain.STANDARD,
                ChannelConfigurationPolicy.ChannelKind.TRUNKED, true), null);
        float[] audio = new float[800];

        for(int index = 0; index < audio.length; index++)
        {
            audio[index] = (float)(Math.sin(2.0 * Math.PI * 440.0 * index / 8000.0) * 0.2);
        }

        return new CompletedAudioCall(snapshot, List.of(audio));
    }

    private static CompletedAudioCall conventionalCall(String channelName)
    {
        CompletedAudioCall template = call();
        AudioCallSnapshot original = template.snapshot();
        List<Identifier> identifiers = new ArrayList<>(original.identifierCollection().getIdentifiers());
        identifiers.removeIf(identifier -> identifier.getForm() == io.github.dsheirer.identifier.Form.TALKGROUP ||
            identifier.getForm() == io.github.dsheirer.identifier.Form.TRAFFIC_CHANNEL ||
            identifier.getForm() == io.github.dsheirer.identifier.Form.CHANNEL);
        identifiers.add(new NBFMTalkgroup(1));
        identifiers.add(ChannelNameConfigurationIdentifier.create(channelName));
        IdentifierCollection collection = new IdentifierCollection(identifiers);
        CallLegSource source = new CallLegSource(DecoderType.NBFM,
            "00000000-0000-0000-0000-000000000737", channelName, null, 10, null,
            io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain.STANDARD,
            ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL, false);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(original.callId(), original.linkedCallId(),
            original.aliasList(), collection, original.broadcastChannels(), original.startTimestamp(),
            original.lastActivityTimestamp(), original.burstCount(), original.burstGeneration(),
            original.lastBurstStartTimestamp(), original.lastBurstEndTimestamp(), original.burstActive(),
            original.complete(), original.encryptionState(), original.recordAudio(),
            AudioCallRecordingMetadata.captureAtSnapshot(null, collection), original.voiceCallQuality(),
            original.callLegId(), source, original.callEncryptionEvidence());
        return new CompletedAudioCall(snapshot, template.audioBuffers(), template.resolvedPolicy());
    }

    private static List<float[]> audioBuffers(int sampleCount)
    {
        int bufferLength = 8192;
        float[] fullBuffer = new float[bufferLength];
        List<float[]> buffers = new ArrayList<>((sampleCount + bufferLength - 1) / bufferLength);
        int remaining = sampleCount;

        while(remaining >= bufferLength)
        {
            buffers.add(fullBuffer);
            remaining -= bufferLength;
        }

        if(remaining > 0)
        {
            buffers.add(new float[remaining]);
        }

        return buffers;
    }

    private static Thread encoderThread(StatsWebCallService service) throws Exception
    {
        var field = StatsWebCallService.class.getDeclaredField("mEncoderThread");
        field.setAccessible(true);
        return (Thread)field.get(service);
    }

    private static boolean awaitThreadState(Thread thread, Thread.State state, long timeout, TimeUnit unit)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while(System.nanoTime() < deadline)
        {
            if(thread.getState() == state)
            {
                return true;
            }

            Thread.sleep(1);
        }
        return thread.getState() == state;
    }

    private static AtomicLong feedActiveUntil(StatsWebCallService service) throws Exception
    {
        var field = StatsWebCallService.class.getDeclaredField("mFeedActiveUntilNanos");
        field.setAccessible(true);
        return (AtomicLong)field.get(service);
    }


}
