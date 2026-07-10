/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
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
            assertEquals("9001", metadata.get("source_id"));
            assertEquals(854_187_500L, metadata.get("frequency_hz"));
            assertEquals(100L, metadata.get("duration_ms"));

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

    private static CompletedAudioCall call()
    {
        List<Identifier> identifiers = List.of(SystemConfigurationIdentifier.create("Test System"),
            FrequencyConfigurationIdentifier.create(854_187_500L), APCO25Talkgroup.create(4400),
            APCO25RadioIdentifier.createFrom(9001));
        AudioCallSnapshot snapshot = new AudioCallSnapshot(new AudioCallId(1, 2, 0), null, null,
            new IdentifierCollection(identifiers), Set.of(), 1_000L, 1_100L, 1, 1L, 1_000L, 1_100L,
            false, true, false, true, 50, false);
        float[] audio = new float[800];

        for(int index = 0; index < audio.length; index++)
        {
            audio[index] = (float)(Math.sin(2.0 * Math.PI * 440.0 * index / 8000.0) * 0.2);
        }

        return new CompletedAudioCall(snapshot, List.of(audio));
    }
}
