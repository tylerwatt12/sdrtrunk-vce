/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteGuidConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.DecoderLogicalChannelNameIdentifier;
import io.github.dsheirer.identifier.decoder.TrafficChannelIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNFullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Rfss;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Site;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.scanlist.ScanListModel;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StatsWebCallServiceTest
{
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
            assertEquals("00000000-0000-0000-0000-000000000021", metadata.get("site_guid"));
            assertEquals("00000000-0000-0000-0000-000000000737", metadata.get("channel_identity"));
            assertEquals("4400", metadata.get("target_id"));
            assertEquals("TALKGROUP", metadata.get("target_form"));
            assertEquals("9001", metadata.get("source_id"));
            assertEquals("RADIO", metadata.get("source_form"));
            assertEquals("CAR 9001", metadata.get("talker_alias"));
            assertEquals("APCO25", metadata.get("protocol"));
            assertEquals("apco25:test system:talkgroup:4400", metadata.get("conversation_key"));
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
    void fullyQualifiedP25AndNxdnDestinationsDoNotShareConversationKeys() throws Exception
    {
        try(StatsWebCallService service = started(new StatsWebCallService());
            FeedClient client = listen(service, Set.of()))
        {
            String firstP25 = conversationKey(client, service,
                APCO25FullyQualifiedTalkgroupIdentifier.createTo(4400, 0xBEE00, 0x4A7, 12345));
            String secondP25 = conversationKey(client, service,
                APCO25FullyQualifiedTalkgroupIdentifier.createTo(4400, 0xABCDE, 0x123, 12345));
            assertNotEquals(firstP25, secondP25);
            assertTrue(firstP25.contains("apco25:fq:781824:1191:12345"));

            String firstNxdn = conversationKey(client, service,
                NXDNFullyQualifiedTalkgroupIdentifier.createTo(11, 1200));
            String secondNxdn = conversationKey(client, service,
                NXDNFullyQualifiedTalkgroupIdentifier.createTo(12, 1200));
            assertNotEquals(firstNxdn, secondNxdn);
            assertTrue(firstNxdn.contains("nxdn:fq:11:1200"));
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
        String guid = "00000000-0000-0000-0000-000000000021";
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(() ->
            WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
                configurationId, guid, WebEntityRef.site(guid),
                WebEntityRef.system("p25:BEE00:4A7:alias-list:10"), 1, 0))));
        catalog.refreshNow();

        try(StatsWebCallService service = started(new StatsWebCallService(null, catalog));
            FeedClient client = listen(service, Set.of()))
        {
            service.receive(call());
            Map<String,Object> metadata = client.awaitCall();
            assertEquals(Map.of("kind", "site", "key", guid), metadata.get("entity_ref"));
            assertEquals(Map.of("kind", "system", "key", "p25:BEE00:4A7:alias-list:10"),
                metadata.get("system_entity_ref"));
            assertEquals(Map.of("kind", "radio", "scope", "p25:BEE00:4A7:alias-list:10", "id", 9001),
                metadata.get("source_entity_ref"));
            assertEquals(Map.of("kind", "talkgroup", "scope", "p25:BEE00:4A7:alias-list:10", "id", 4400),
                metadata.get("target_entity_ref"));
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

    private static String conversationKey(FeedClient client, StatsWebCallService service, Identifier<?> target)
        throws InterruptedException
    {
        service.receive(call(target));
        Map<String,Object> metadata = client.awaitCall();
        assertNotNull(metadata);
        return String.valueOf(metadata.get("conversation_key"));
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
        return new CompletedAudioCall(replaced, template.audioBuffers(), template.resolvedPolicy());
    }

    private static CompletedAudioCall call()
    {
        List<Identifier> identifiers = new ArrayList<>();
        identifiers.add(SystemConfigurationIdentifier.create("Test System"));
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
        identifiers.add(APCO25Talkgroup.create(4400));
        identifiers.add(DecoderLogicalChannelNameIdentifier.create("0-737", Protocol.APCO25));
        identifiers.add(TrafficChannelIdentifier.create());
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
