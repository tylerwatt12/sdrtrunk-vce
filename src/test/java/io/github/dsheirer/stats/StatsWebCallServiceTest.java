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
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
        List<Identifier> identifiers = new ArrayList<>();
        identifiers.add(SystemConfigurationIdentifier.create("Test System"));
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
}
