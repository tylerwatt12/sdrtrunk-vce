/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.ResolvedCallPolicy;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteGuidConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.DecoderLogicalChannelNameIdentifier;
import io.github.dsheirer.identifier.decoder.TrafficChannelIdentifier;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Rfss;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Site;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.scanlist.ScanListModel;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StatsWebCallServiceTest
{
    @Test
    void serializesSubscriptionAdmissionWithLifecycleChanges() throws Exception
    {
        assertTrue(Modifier.isSynchronized(StatsWebCallService.class.getDeclaredMethod("subscribe").getModifiers()));
        assertTrue(Modifier.isSynchronized(
            StatsWebCallService.class.getDeclaredMethod("subscribe", Set.class).getModifiers()));
    }

    @Test
    void callIdsRemainDistinctAcrossServiceRestarts() throws Exception
    {
        String firstId;
        String secondId;

        try(StatsWebCallService first = new StatsWebCallService();
            StatsWebCallService second = new StatsWebCallService())
        {
            first.start();
            second.start();

            try(StatsLiveEventHub.Subscription firstSubscription = first.subscribe();
                StatsLiveEventHub.Subscription secondSubscription = second.subscribe())
            {
                first.receive(call());
                second.receive(call());
                StatsLiveEventHub.LiveEvent firstEvent = firstSubscription.poll(5, TimeUnit.SECONDS);
                StatsLiveEventHub.LiveEvent secondEvent = secondSubscription.poll(5, TimeUnit.SECONDS);
                assertNotNull(firstEvent);
                assertNotNull(secondEvent);
                firstId = String.valueOf(metadata(firstEvent).get("call_id"));
                secondId = String.valueOf(metadata(secondEvent).get("call_id"));
            }
        }

        assertNotEquals(firstId, secondId);
    }

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
            assertEquals("Test Site", metadata.get("site"));
            assertEquals("00000000-0000-0000-0000-000000000021", metadata.get("site_guid"));
            assertEquals("00000000-0000-0000-0000-000000000737", metadata.get("channel_identity"));
            assertEquals("4400", metadata.get("target_id"));
            assertEquals("TALKGROUP", metadata.get("target_form"));
            assertEquals("9001", metadata.get("source_id"));
            assertEquals("RADIO", metadata.get("source_form"));
            assertEquals("CAR 9001", metadata.get("talker_alias"));
            assertEquals("APCO25", metadata.get("protocol"));
            assertEquals(854_187_500L, metadata.get("frequency_hz"));
            assertEquals("0-737", metadata.get("lcn"));
            assertEquals(String.valueOf(0xBEE00), metadata.get("wacn"));
            assertEquals(String.valueOf(0x4A7), metadata.get("system_id"));
            assertEquals(String.valueOf(0x4A1), metadata.get("nac"));
            assertEquals("1", metadata.get("rfss_id"));
            assertEquals("21", metadata.get("site_id"));
            assertEquals(100L, metadata.get("duration_ms"));
            assertEquals(98.0d, metadata.get("vc_quality_pct"));
            assertEquals(49L, metadata.get("vc_decoded_frames"));
            assertEquals(1L, metadata.get("vc_repeated_frames"));
            assertEquals(4L, metadata.get("vc_fec_errors"));

            String callId = String.valueOf(metadata.get("call_id"));
            URI audioUri = URI.create(String.valueOf(metadata.get("audio_url")));
            assertEquals(callId, StatsWebServerService.callAudioId(audioUri));

            StatsWebCallService.CachedCall cached = service.get(callId);
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
    void newSubscriptionStartsAtTheLiveEdgeWithoutCachedAnnouncements() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        service.start();

        try
        {
            try(StatsLiveEventHub.Subscription first = service.subscribe())
            {
                service.receive(call());
                StatsLiveEventHub.LiveEvent heard = first.poll(5, TimeUnit.SECONDS);
                assertNotNull(heard);
                assertNotNull(service.get(String.valueOf(metadata(heard).get("call_id"))));
            }

            try(StatsLiveEventHub.Subscription refreshed = service.subscribe())
            {
                assertNull(refreshed.poll(100, TimeUnit.MILLISECONDS),
                    "A fresh browser subscription must not receive calls from the audio cache");
                service.receive(call());
                StatsLiveEventHub.LiveEvent live = refreshed.poll(5, TimeUnit.SECONDS);
                assertNotNull(live);
                assertEquals("call", live.name());
            }
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void publishesLearnedP25SiteIdentityInsteadOfGenericCallIdentifiers() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        service.start();
        CompletedAudioCall template = call();
        CallLegSource source = new CallLegSource(DecoderType.P25_PHASE1, "configured-channel", "Traffic",
            "learned-site-guid", 1L, new P25SiteIdentity(0xABCDE, 0x348, 0x02, 0x17), true);
        AudioCallSnapshot snapshot = template.snapshot();
        AudioCallSnapshot learned = new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(),
            snapshot.aliasList(), snapshot.identifierCollection(), snapshot.broadcastChannels(),
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), snapshot.burstCount(),
            snapshot.burstGeneration(), snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(),
            snapshot.burstActive(), snapshot.complete(), snapshot.encryptionState(), snapshot.recordAudio(),
            snapshot.recordingMetadata(), snapshot.voiceCallQuality(), snapshot.callLegId(), source,
            snapshot.callEncryptionEvidence());

        try(StatsLiveEventHub.Subscription subscription = service.subscribe())
        {
            service.receive(new CompletedAudioCall(learned, template.audioBuffers()));
            StatsLiveEventHub.LiveEvent event = subscription.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            Map<String,Object> metadata = metadata(event);
            assertEquals(String.valueOf(0xABCDE), metadata.get("wacn"));
            assertEquals(String.valueOf(0x348), metadata.get("system_id"));
            assertEquals(String.valueOf(0x02), metadata.get("rfss_id"));
            assertEquals(String.valueOf(0x17), metadata.get("site_id"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void publishesOnlyCatalogOwnedCompletedCallNavigation() throws Exception
    {
        String configurationId = "00000000-0000-0000-0000-000000000737";
        String guid = "00000000-0000-0000-0000-000000000021";
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(() ->
            WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
                configurationId, guid, WebEntityRef.site(guid),
                WebEntityRef.system("p25:BEE00:4A7:alias-list:10"), 1, 0))));
        catalog.refreshNow();
        StatsWebCallService service = new StatsWebCallService(null, WebCallConfiguration.defaults(), catalog);
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribe())
        {
            service.receive(call());
            StatsLiveEventHub.LiveEvent event = subscription.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            Map<String,Object> metadata = metadata(event);
            assertEquals(Map.of("kind", "site", "key", guid), metadata.get("entity_ref"));
            assertEquals(Map.of("kind", "system", "key", "p25:BEE00:4A7:alias-list:10"),
                metadata.get("system_entity_ref"));
            assertEquals(Map.of("kind", "radio", "scope", "p25:BEE00:4A7:alias-list:10", "id", 9001),
                metadata.get("source_entity_ref"));
            assertEquals(Map.of("kind", "talkgroup", "scope", "p25:BEE00:4A7:alias-list:10", "id", 4400),
                metadata.get("target_entity_ref"));
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
    void boundsEveryCallMetadataString()
    {
        String oversized = "x".repeat(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS + 100);
        assertEquals(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS,
            StatsWebCallService.boundedText(oversized).length());
        String splitSurrogate = "x".repeat(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS - 1) +
            "\uD83D\uDE00tail";
        String boundedSurrogate = StatsWebCallService.boundedText(splitSurrogate);
        assertEquals(StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS - 1, boundedSurrogate.length());
        assertFalse(Character.isHighSurrogate(boundedSurrogate.charAt(boundedSurrogate.length() - 1)));

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
            String publishedSystem = String.valueOf(metadata.get("system"));
            assertTrue(publishedSystem.length() <= StatsWebCallService.MAXIMUM_METADATA_TEXT_CHARACTERS);
            assertEquals(system.substring(0, publishedSystem.length()), publishedSystem);
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void boundsConcurrentAudioResponsesAndReportsRejections()
    {
        StatsWebCallService service = new StatsWebCallService(new WebCallConfiguration(1, 16, 100, 512, 128));

        try
        {
            assertTrue(service.tryAcquireAudioResponse());
            assertFalse(service.tryAcquireAudioResponse());
            assertEquals(1, service.status().get("active_audio_responses"));
            assertEquals(1L, service.status().get("rejected_audio_responses"));

            service.releaseAudioResponse();
            assertEquals(0, service.status().get("active_audio_responses"));
            assertTrue(service.tryAcquireAudioResponse());
            service.releaseAudioResponse();
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void reportsALiveGapWhenPendingAudioCapacityIsFull() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService(
            scanListModel(Map.of(101L, Set.of(2L))), WebCallConfiguration.defaults());
        service.start();
        int maximumSamples = (StatsWebCallService.MAXIMUM_CALL_AUDIO_BYTES -
            StatsWebCallService.WAVE_HEADER_BYTES) / Short.BYTES;
        CompletedAudioCall template = call(Set.of(101L));
        CompletedAudioCall maximum = new CompletedAudioCall(template.snapshot(), audioBuffers(maximumSamples),
            template.resolvedPolicy());

        try(StatsLiveEventHub.Subscription subscription = service.subscribe(Set.of(2L)))
        {
            service.receive(maximum);
            service.receive(template);
            StatsLiveEventHub.LiveEvent gap = subscription.poll(5, TimeUnit.SECONDS);
            assertNotNull(gap);
            assertEquals("live_gap", gap.name());
            assertEquals(1, metadata(gap).get("dropped"));
            assertEquals("pending_audio_capacity", metadata(gap).get("reason"));
            assertEquals(List.of(2L), metadata(gap).get("scan_list_ids"));
            assertEquals(1L, service.status().get("dropped_pending_capacity"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void queuedEncodingFromAnEarlierRunCannotPublishAfterRestart() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        service.start();
        int maximumSamples = (StatsWebCallService.MAXIMUM_CALL_AUDIO_BYTES -
            StatsWebCallService.WAVE_HEADER_BYTES) / Short.BYTES;
        CompletedAudioCall template = call();
        CompletedAudioCall maximum = new CompletedAudioCall(template.snapshot(), audioBuffers(maximumSamples));
        StatsLiveEventHub.Subscription oldSubscription = service.subscribe();
        service.receive(maximum);
        service.stop();
        service.start();

        try(StatsLiveEventHub.Subscription restarted = service.subscribe())
        {
            assertNull(restarted.poll(1, TimeUnit.SECONDS));
            assertEquals(0, service.status().get("cached_calls"));
            assertEquals(0L, service.status().get("published_calls"));
        }
        finally
        {
            oldSubscription.close();
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
    void queuedEncodeFromPreviousRunCannotPopulateOrPublishRestartedRun() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribe())
        {
            encoderExecutor(service).execute(() -> {
                blockerStarted.countDown();

                try
                {
                    releaseBlocker.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
            service.receive(call());
            service.stop();
            service.start();
            service.receive(call());
            releaseBlocker.countDown();

            StatsLiveEventHub.LiveEvent event = subscription.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            @SuppressWarnings("unchecked")
            Map<String,Object> metadata = (Map<String,Object>)event.data();
            assertEquals(100L, metadata.get("duration_ms"));
            assertNotNull(service.get(String.valueOf(metadata.get("call_id"))));
            assertEquals(1, ((Number)service.status().get("cached_calls")).intValue());
            assertNull(subscription.poll(0, TimeUnit.MILLISECONDS));
        }
        finally
        {
            releaseBlocker.countDown();
            service.close();
        }
    }

    @Test
    void filtersAnnouncementsByEachSubscribersSelectedScanLists() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService(scanListModel(Map.of(
            101L, Set.of(2L), 102L, Set.of(3L))), WebCallConfiguration.defaults());
        service.start();

        try(StatsLiveEventHub.Subscription southwest = service.subscribe(Set.of(2L));
            StatsLiveEventHub.Subscription cleveland = service.subscribe(Set.of(3L)))
        {
            service.receive(call(Set.of(101L)));
            StatsLiveEventHub.LiveEvent southwestEvent = southwest.poll(5, TimeUnit.SECONDS);
            assertNotNull(southwestEvent);
            assertEquals(List.of(2L), metadata(southwestEvent).get("scan_list_ids"));
            assertNull(cleveland.poll(100, TimeUnit.MILLISECONDS));

            service.receive(call(Set.of(102L)));
            StatsLiveEventHub.LiveEvent clevelandEvent = cleveland.poll(5, TimeUnit.SECONDS);
            assertNotNull(clevelandEvent);
            assertEquals(List.of(3L), metadata(clevelandEvent).get("scan_list_ids"));
            assertNull(southwest.poll(100, TimeUnit.MILLISECONDS));
            assertEquals(2L, awaitStatus(service, "published_calls", 2L));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void publishesOneCallWithDeduplicatedMetadataForOverlappingSubscriptions() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService(
            scanListModel(Map.of(103L, Set.of(2L, 3L))), WebCallConfiguration.defaults());
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribe(Set.of(2L, 3L)))
        {
            service.receive(call(Set.of(103L)));
            StatsLiveEventHub.LiveEvent event = subscription.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals(List.of(2L, 3L), metadata(event).get("scan_list_ids"));
            assertNull(subscription.poll(100, TimeUnit.MILLISECONDS));
            assertEquals(1L, awaitStatus(service, "published_calls", 1L));
            assertEquals(1, ((Number)service.status().get("cached_calls")).intValue());
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void dropsCompletedCallsThatMatchNoPublishedScanList() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService(
            scanListModel(Map.of(101L, Set.of(2L))), WebCallConfiguration.defaults());
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribe(Set.of(2L)))
        {
            service.receive(call(Set.of(999L)));
            assertNull(subscription.poll(250, TimeUnit.MILLISECONDS));
            assertEquals(0, ((Number)service.status().get("cached_calls")).intValue());
            assertEquals(1L, ((Number)service.status().get("dropped_no_scan_list")).longValue());
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void skipsEncodingWhenNoCurrentSubscriberSelectsTheMatchedList() throws Exception
    {
        StatsWebCallService service = new StatsWebCallService(
            scanListModel(Map.of(101L, Set.of(2L))), WebCallConfiguration.defaults());
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribe(Set.of(3L)))
        {
            service.receive(call(Set.of(101L)));
            assertNull(subscription.poll(250, TimeUnit.MILLISECONDS));
            assertEquals(0, ((Number)service.status().get("cached_calls")).intValue());
            assertEquals(0L, ((Number)service.status().get("published_calls")).longValue());
            assertEquals(1L,
                ((Number)service.status().get("dropped_no_matching_listeners")).longValue());
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void publishesAliasesFrozenWhenTheCallSnapshotWasCaptured() throws Exception
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
        destination.setDescription("Renamed destination description");
        destination.setGroup("Renamed destination group");
        source.setName("Renamed unit");
        source.setDescription("Renamed source description");
        source.setGroup("Renamed source group");

        StatsWebCallService service = new StatsWebCallService(
            scanListModel(Map.of(101L, Set.of(2L), 102L, Set.of(2L))), WebCallConfiguration.defaults());
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribe(Set.of(2L)))
        {
            service.receive(completed);
            StatsLiveEventHub.LiveEvent event = subscription.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals("Dispatch", metadata(event).get("target_alias"));
            assertEquals("County dispatch channel", metadata(event).get("target_description"));
            assertEquals("Dispatch", metadata(event).get("target_group"));
            assertEquals("Unit 9001", metadata(event).get("source_alias"));
            assertEquals("Patrol unit", metadata(event).get("source_description"));
            assertEquals("Police radios", metadata(event).get("source_group"));
        }
        finally
        {
            service.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> metadata(StatsLiveEventHub.LiveEvent event)
    {
        return (Map<String,Object>)event.data();
    }

    private static long awaitStatus(StatsWebCallService service, String key, long expected)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long actual;

        do
        {
            actual = ((Number)service.status().get(key)).longValue();
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
        ResolvedCallPolicy.MatchContext context = new ResolvedCallPolicy.MatchContext(null, 10, "County",
            "Test System", List.of(), matchedAliasIds, AliasList.TalkgroupMatchStatus.NOT_APPLICABLE,
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
        identifiers.add(SiteConfigurationIdentifier.create("Test Site"));
        identifiers.add(SiteGuidConfigurationIdentifier.create("00000000-0000-0000-0000-000000000021"));
        identifiers.add(ChannelConfigurationIdentifier.create("00000000-0000-0000-0000-000000000737"));
        identifiers.add(FrequencyConfigurationIdentifier.create(854_187_500L));
        identifiers.add(APCO25RadioIdentifier.createFrom(9001));
        identifiers.add(P25TalkerAliasIdentifier.create("CAR 9001"));
        identifiers.add(APCO25Wacn.create(0xBEE00));
        identifiers.add(APCO25System.create(0x4A7));
        identifiers.add(APCO25Nac.create(0x4A1));
        identifiers.add(APCO25Rfss.create(1));
        identifiers.add(APCO25Site.create(21));

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

        AudioCallId callId = new AudioCallId(1, 2, 0);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            new IdentifierCollection(identifiers), Set.of(), 1_000L, 1_100L, 1, 1L, 1_000L, 1_100L,
            false, true, CallEncryptionState.CLEAR, true, null,
            new VoiceCallQuality(49, 1, 0, 0, 4, 6_850), CallLegId.from(callId), CallLegSource.UNKNOWN, null);
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

    private static ThreadPoolExecutor encoderExecutor(StatsWebCallService service) throws Exception
    {
        var field = StatsWebCallService.class.getDeclaredField("mEncoderExecutor");
        field.setAccessible(true);
        return (ThreadPoolExecutor)field.get(service);
    }
}
