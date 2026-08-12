/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.DecoderLogicalChannelNameIdentifier;
import io.github.dsheirer.identifier.decoder.TrafficChannelIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StatsWebCallServiceTest
{
    @Test
    void publishesCompletedCallAndServesMonoWave() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribe())
        {
            service.receive(call());
            StatsLiveEventHub.LiveEvent event = subscription.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals("call", event.name());

            @SuppressWarnings("unchecked")
            Map<String,Object> metadata = (Map<String,Object>)event.data();
            assertEquals("Test System", metadata.get("system"));
            assertEquals("4400", metadata.get("target_id"));
            assertEquals("TALKGROUP", metadata.get("target_form"));
            assertEquals("9001", metadata.get("source_id"));
            assertEquals("RADIO", metadata.get("source_form"));
            assertEquals(854_187_500L, metadata.get("frequency_hz"));
            assertEquals(100L, metadata.get("duration_ms"));
            assertEquals(98.0d, metadata.get("vc_quality_pct"));
            assertEquals(49L, metadata.get("vc_decoded_frames"));
            assertEquals(1L, metadata.get("vc_repeated_frames"));
            assertEquals(4L, metadata.get("vc_fec_errors"));
            assertEquals(List.of(metadata), service.snapshot());

            StatsWebCallService.CachedCall cached = service.get(String.valueOf(metadata.get("call_id")));
            assertNotNull(cached);
            assertEquals("RIFF", new String(cached.wave(), 0, 4, StandardCharsets.US_ASCII));
            assertEquals("WAVE", new String(cached.wave(), 8, 4, StandardCharsets.US_ASCII));
            assertEquals(44 + 800 * Short.BYTES, cached.wave().length);
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void checksThePerCallWaveLimitBeforeEncoding()
    {
        int maximumSamples = (StatsWebCallService.MAXIMUM_CALL_AUDIO_BYTES -
            StatsWebCallService.WAVE_HEADER_BYTES) / Short.BYTES;
        List<float[]> maximum = audioBuffers(maximumSamples);
        List<float[]> oversized = new ArrayList<>(maximum);
        oversized.add(new float[1]);

        assertEquals(16 * 1024 * 1024, StatsWebCallService.MAXIMUM_CALL_AUDIO_BYTES);
        assertEquals(StatsWebCallService.MAXIMUM_CALL_AUDIO_BYTES,
            StatsWebCallService.MAXIMUM_PENDING_AUDIO_BYTES);
        assertEquals(StatsWebCallService.MAXIMUM_CALL_AUDIO_BYTES,
            StatsWebCallService.checkedWaveLength(maximum));
        assertEquals(-1, StatsWebCallService.checkedWaveLength(oversized));

        CompletedAudioCall template = call();
        assertNull(StatsWebCallService.wave(new CompletedAudioCall(template.snapshot(), oversized)));
    }

    @Test
    void boundsEveryCallMetadataStringAndTheMaximumRecoverySnapshot() throws Exception
    {
        String oversized = "x".repeat(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS + 100);
        assertEquals(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS,
            StatsWebCallService.boundedText(oversized).length());
        String splitSurrogate = "x".repeat(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS - 1) +
            "\uD83D\uDE00tail";
        String boundedSurrogate = StatsWebCallService.boundedText(splitSurrogate);
        assertEquals(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS - 1, boundedSurrogate.length());
        assertFalse(Character.isHighSurrogate(boundedSurrogate.charAt(boundedSurrogate.length() - 1)));

        //Control characters exercise JSON's largest common escaping expansion for each externally derived text field.
        String maximallyEscaped = "\u0001".repeat(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS);
        Map<String,Object> maximumCall = new LinkedHashMap<>();
        maximumCall.put("call_id", "z".repeat(16));
        maximumCall.put("audio_url", "/api/v1/calls/" + "z".repeat(16) + "/audio");

        for(String field: List.of("system", "channel", "decoder", "source_id", "source_alias", "target_id",
            "target_alias"))
        {
            maximumCall.put(field, maximallyEscaped);
        }

        maximumCall.put("source_form", "RADIO");
        maximumCall.put("target_form", "TALKGROUP");
        maximumCall.put("completed_at_ms", Long.MAX_VALUE);
        maximumCall.put("duration_ms", Long.MAX_VALUE);
        maximumCall.put("frequency_hz", Long.MAX_VALUE);
        maximumCall.put("timeslot", Integer.MAX_VALUE);
        maximumCall.put("encrypted", true);
        maximumCall.put("vc_quality_pct", 100.0d);
        maximumCall.put("vc_decoded_frames", Long.MAX_VALUE);
        maximumCall.put("vc_repeated_frames", Long.MAX_VALUE);
        maximumCall.put("vc_concealed_frames", Long.MAX_VALUE);
        maximumCall.put("vc_missing_frames", Long.MAX_VALUE);
        maximumCall.put("vc_fec_errors", Long.MAX_VALUE);
        maximumCall.put("vc_fec_protected_bits", Long.MAX_VALUE);
        List<Map<String,Object>> calls = new ArrayList<>(StatsWebCallService.MAXIMUM_SNAPSHOT_CALLS);

        for(int index = 0; index < StatsWebCallService.MAXIMUM_SNAPSHOT_CALLS; index++)
        {
            calls.add(maximumCall);
        }

        byte[] encoded = ApiHttpResponse.encodePayload(Map.of("event", "snapshot", "data", Map.of("calls", calls)));
        assertTrue(encoded.length <= StatsWebCallService.MAXIMUM_SNAPSHOT_JSON_BYTES,
            () -> "Maximum call recovery snapshot encoded " + encoded.length + " bytes");
    }

    @Test
    void truncatesPublishedConfigurationMetadata() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        service.start();
        String system = "system-".repeat(100);

        try(StatsLiveEventHub.Subscription subscription = service.subscribe())
        {
            service.receive(call(true, true, false, system));
            StatsLiveEventHub.LiveEvent event = subscription.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            @SuppressWarnings("unchecked")
            Map<String,Object> metadata = (Map<String,Object>)event.data();
            assertEquals(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS,
                String.valueOf(metadata.get("system")).length());
            assertEquals(system.substring(0, StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS),
                metadata.get("system"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void doesNotPublishAudioAboveThePerCallResponseLimit() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        service.start();
        int maximumSamples = (StatsWebCallService.MAXIMUM_CALL_AUDIO_BYTES -
            StatsWebCallService.WAVE_HEADER_BYTES) / Short.BYTES;
        List<float[]> oversized = audioBuffers(maximumSamples);
        oversized.add(new float[1]);
        CompletedAudioCall template = call();

        try(StatsLiveEventHub.Subscription subscription = service.subscribe())
        {
            service.receive(new CompletedAudioCall(template.snapshot(), oversized));
            assertNull(subscription.poll(1, TimeUnit.SECONDS));
            assertEquals(0, ((Number)service.status().get("cached_calls")).intValue());
            assertEquals(16 * 1024 * 1024,
                ((Number)service.status().get("maximum_call_audio_bytes")).intValue());
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void doesNotCacheWhenNoBrowserIsListening() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        service.start();

        try
        {
            service.receive(call());
            Thread.sleep(100);
            assertEquals(0, ((Number)service.status().get("cached_calls")).intValue());
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void doesNotPublishUnresolvedTrafficFragment() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribe())
        {
            service.receive(call(false, true));
            assertNull(subscription.poll(250, TimeUnit.MILLISECONDS));
            assertEquals(0, ((Number)service.status().get("cached_calls")).intValue());
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void publishesTargetlessStandardCall() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribe())
        {
            service.receive(call(false, false));
            StatsLiveEventHub.LiveEvent event = subscription.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            @SuppressWarnings("unchecked")
            Map<String,Object> metadata = (Map<String,Object>)event.data();
            assertFalse(metadata.containsKey("target_id"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void publishesTargetlessStandardP25CallWithLogicalChannelName() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribe())
        {
            service.receive(call(false, false, true));
            assertNotNull(subscription.poll(5, TimeUnit.SECONDS));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void blockedEncodeFromPreviousRunCannotPopulateOrPublishRestartedRun() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        CountDownLatch encodeStarted = new CountDownLatch(1);
        CountDownLatch releaseEncode = new CountDownLatch(1);
        CompletedAudioCall template = call();
        CompletedAudioCall blocked = new CompletedAudioCall(template.snapshot(),
            new BlockingAudioBuffers(new float[400], encodeStarted, releaseEncode));
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribe())
        {
            service.receive(blocked);
            assertTrue(encodeStarted.await(5, TimeUnit.SECONDS));
            service.stop();
            service.start();
            service.receive(call());
            releaseEncode.countDown();

            StatsLiveEventHub.LiveEvent event = subscription.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            @SuppressWarnings("unchecked")
            Map<String,Object> metadata = (Map<String,Object>)event.data();
            assertEquals(100L, metadata.get("duration_ms"));
            assertEquals(List.of(metadata), service.snapshot());
            assertEquals(1, ((Number)service.status().get("cached_calls")).intValue());
            assertNull(subscription.poll(0, TimeUnit.MILLISECONDS));
        }
        finally
        {
            releaseEncode.countDown();
            service.close();
        }
    }

    private static CompletedAudioCall call()
    {
        return call(true, true);
    }

    private static CompletedAudioCall call(boolean includeTarget, boolean traffic)
    {
        return call(includeTarget, traffic, false);
    }

    private static CompletedAudioCall call(boolean includeTarget, boolean traffic, boolean logicalChannel)
    {
        return call(includeTarget, traffic, logicalChannel, "Test System");
    }

    private static CompletedAudioCall call(boolean includeTarget, boolean traffic, boolean logicalChannel,
                                           String systemName)
    {
        List<Identifier> identifiers = new ArrayList<>();
        identifiers.add(SystemConfigurationIdentifier.create(systemName));
        identifiers.add(FrequencyConfigurationIdentifier.create(854_187_500L));
        identifiers.add(APCO25RadioIdentifier.createFrom(9001));

        if(includeTarget)
        {
            identifiers.add(APCO25Talkgroup.create(4400));
        }

        if(traffic)
        {
            identifiers.add(DecoderLogicalChannelNameIdentifier.create("0-737", Protocol.APCO25));
            identifiers.add(TrafficChannelIdentifier.create());
        }
        else
        {
            identifiers.add(ChannelNameConfigurationIdentifier.create(logicalChannel ? "P25 Conventional" : "DMR Direct"));
            identifiers.add(DecoderTypeConfigurationIdentifier.create(
                logicalChannel ? DecoderType.P25_PHASE1 : DecoderType.DMR));
        }

        if(logicalChannel)
        {
            identifiers.add(DecoderLogicalChannelNameIdentifier.create("0-737", Protocol.APCO25));
        }

        AudioCallSnapshot snapshot = new AudioCallSnapshot(new AudioCallId(1, 2, 0), null, null,
            new IdentifierCollection(identifiers), Set.of(), 1_000L, 1_100L, 1, 1L, 1_000L, 1_100L,
            false, true, false, true, 50, false, null,
            new VoiceCallQuality(49, 1, 0, 0, 4, 6_850));
        float[] audio = new float[800];

        for(int index = 0; index < audio.length; index++)
        {
            audio[index] = (float)(Math.sin(2.0 * Math.PI * 440.0 * index / 8000.0) * 0.2);
        }

        return new CompletedAudioCall(snapshot, List.of(audio));
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

    private static class BlockingAudioBuffers extends AbstractList<float[]>
    {
        private final float[] mAudio;
        private final CountDownLatch mEncodeStarted;
        private final CountDownLatch mReleaseEncode;
        private final AtomicInteger mIteratorCount = new AtomicInteger();

        private BlockingAudioBuffers(float[] audio, CountDownLatch encodeStarted, CountDownLatch releaseEncode)
        {
            mAudio = audio;
            mEncodeStarted = encodeStarted;
            mReleaseEncode = releaseEncode;
        }

        @Override
        public float[] get(int index)
        {
            if(index != 0)
            {
                throw new IndexOutOfBoundsException(index);
            }

            return mAudio;
        }

        @Override
        public int size()
        {
            return 1;
        }

        @Override
        public Iterator<float[]> iterator()
        {
            if(mIteratorCount.incrementAndGet() == 2)
            {
                mEncodeStarted.countDown();

                try
                {
                    mReleaseEncode.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }

            return List.of(mAudio).iterator();
        }
    }
}
