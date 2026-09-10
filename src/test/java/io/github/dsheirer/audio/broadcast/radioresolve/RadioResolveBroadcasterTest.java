/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast.radioresolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteReceiverContext;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.SourceType;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadioResolveBroadcasterTest
{
    @Test
    void modesIndependentlyControlCallsAndMetadata()
    {
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_AND_METADATA);
        assertTrue(configuration.isCallUploadEnabled());
        assertTrue(configuration.isSiteMetadataEnabled());
        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        assertTrue(configuration.isCallUploadEnabled());
        assertFalse(configuration.isSiteMetadataEnabled());
        configuration.setMode(RadioResolveConfiguration.Mode.METADATA_ONLY);
        assertFalse(configuration.isCallUploadEnabled());
        assertTrue(configuration.isSiteMetadataEnabled());
    }

    @Test
    void completedCallMovesIntoDurableSpoolAndSurvivesRecreation(@TempDir Path directory) throws Exception
    {
        Path audio = directory.resolve("incoming.mp3");
        Files.write(audio, new byte[] {0x49, 0x44, 0x33, 0x01});
        Path spool = directory.resolve("portable-data/radioresolve-spool/config-id");
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        AudioRecording recording = RadioResolveTestFixtures.recording(audio, System.currentTimeMillis() - 2_500L);
        recording.addPendingReplay();
        RadioResolveBroadcaster first = new RadioResolveBroadcaster(configuration, null, null, null, spool);
        first.receive(recording);

        assertEquals(1, first.getAudioQueueSize());
        assertFalse(recording.hasPendingReplays(), "the durable spool owns retry after receive returns");
        first.stop();
        first.dispose();

        RadioResolveBroadcaster recreated = new RadioResolveBroadcaster(configuration, null, null, null, spool);
        assertEquals(1, recreated.getAudioQueueSize());
        try(var paths = Files.list(spool))
        {
            assertEquals(2, paths.count(), "audio and its manifest must survive recreation");
        }
        recreated.dispose();
    }

    @Test
    void metadataOnlyDoesNotSpoolCalls(@TempDir Path directory) throws Exception
    {
        Path audio = directory.resolve("call.mp3");
        Files.write(audio, new byte[] {0x49, 0x44, 0x33});
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.METADATA_ONLY);
        RadioResolveBroadcaster broadcaster = new RadioResolveBroadcaster(configuration, null, null, null,
            directory.resolve("spool"));
        AudioRecording recording = RadioResolveTestFixtures.recording(audio, System.currentTimeMillis() - 2_500L);
        recording.addPendingReplay();
        broadcaster.receive(recording);

        assertEquals(0, broadcaster.getAudioQueueSize());
        assertFalse(recording.hasPendingReplays());
        broadcaster.dispose();
    }

    @Test
    void uploadUsesBoundedV3BatchMultipartContract(@TempDir Path directory) throws Exception
    {
        Path audio = directory.resolve("call.mp3");
        Path secondAudio = directory.resolve("second.mp3");
        Files.write(audio, new byte[] {0x49, 0x44, 0x33});
        Files.write(secondAudio, new byte[] {0x49, 0x44, 0x34});
        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("spool-request"));
        spool.open();
        RadioResolveSpool.Entry entry = spool.enqueue(audio,
            RadioResolveTestFixtures.readyEnvelope(audio), RadioResolveCallEnvelope.HoldContext.EMPTY,
            RadioResolveTestFixtures.END).entry();
        RadioResolveSpool.Entry secondEntry = spool.enqueue(secondAudio,
            RadioResolveTestFixtures.readyEnvelope(secondAudio, RadioResolveTestFixtures.END - 1_000L,
                "00000000-0000-4000-8000-000000000002"), RadioResolveCallEnvelope.HoldContext.EMPTY,
            RadioResolveTestFixtures.END + 1L).entry();
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setHost("https://calls.example.com/");
        configuration.setApiKey("test-key");
        HttpRequest request = RadioResolveBroadcaster.createUploadRequest(configuration, List.of(entry, secondEntry));

        assertEquals("https://calls.example.com/api/node/v3/upload-calls", request.uri().toString());
        assertEquals(Optional.of(Duration.ofSeconds(30)), request.timeout());
        assertTrue(request.headers().firstValue("Content-Type").orElseThrow()
            .startsWith("multipart/form-data; boundary="));
        assertNotNull(request.bodyPublisher().orElse(null));
        assertTrue(request.bodyPublisher().orElseThrow().contentLength() <=
            RadioResolveBroadcaster.MAXIMUM_UPLOAD_BYTES);
        String multipart = new String(collectBody(request), StandardCharsets.ISO_8859_1);
        assertTrue(multipart.contains("name=\"batch\""));
        assertTrue(multipart.contains("\"audio_part\":\"audio-0\""));
        assertTrue(multipart.contains("\"audio_part\":\"audio-1\""));
        assertTrue(multipart.contains("name=\"audio-0\""));
        assertTrue(multipart.indexOf("name=\"audio-0\"") < multipart.indexOf("name=\"audio-1\""));
    }

    @Test
    void connectionTestRequiresTheServerEpochField()
    {
        assertEquals(1_700_000_000_000L, RadioResolveBroadcaster.serverEpochMilliseconds(
            "{\"ok\":true,\"serverEpochMilliseconds\":1700000000000}"));
        assertNull(RadioResolveBroadcaster.serverEpochMilliseconds("{\"ok\":true}"));
        assertNull(RadioResolveBroadcaster.serverEpochMilliseconds(
            "{\"serverEpochMilliseconds\":0}"));
        assertNull(RadioResolveBroadcaster.serverEpochMilliseconds("not-json"));
    }

    @Test
    void eachUploadUsesANewMultipartBoundary()
    {
        String first = new RadioResolveBuilder().getBoundary();
        String second = new RadioResolveBuilder().getBoundary();

        assertNotEquals(first, second);
        assertTrue(first.matches("sdrtrunk-vce-[0-9a-f-]{36}"));
        assertTrue(second.matches("sdrtrunk-vce-[0-9a-f-]{36}"));
    }

    @Test
    void onlyContractTerminalStatusesDiscardTheDurableCall()
    {
        for(int status : List.of(400, 409, 410, 413, 415, 422))
        {
            assertTrue(RadioResolveBroadcaster.isPermanentCallRejectionStatus(status));
        }

        for(int status : List.of(404, 405, 408, 425, 429, 500, 501, 502, 503, 504))
        {
            assertFalse(RadioResolveBroadcaster.isPermanentCallRejectionStatus(status),
                "unexpected or temporary HTTP " + status + " must retain the spooled call for retry");
        }
    }

    @Test
    void successfulV3RequestRestoresConnectivityLatchWhenClockProofIsCurrent(@TempDir Path directory)
    {
        AtomicLong epochMilliseconds = new AtomicLong(1_700_000_000_000L);
        AtomicLong monotonicNanoseconds = new AtomicLong(TimeUnit.SECONDS.toNanos(10L));
        RadioResolveClockSynchronizer clock = new RadioResolveClockSynchronizer(epochMilliseconds::get,
            monotonicNanoseconds::get);
        RadioResolveClockSynchronizer.RequestTiming request = clock.beginRequest();
        epochMilliseconds.addAndGet(4L);
        monotonicNanoseconds.addAndGet(TimeUnit.MILLISECONDS.toNanos(4L));
        assertTrue(clock.completeRequest(request, 1_700_000_000_002L));
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        RadioResolveBroadcaster broadcaster = new RadioResolveBroadcaster(configuration, null, null, null,
            directory.resolve("spool-connected"), clock);
        broadcaster.setBroadcastState(io.github.dsheirer.audio.broadcast.BroadcastState.ERROR);

        broadcaster.recordConnectionSuccess();

        assertTrue(broadcaster.connected(),
            "metadata or call success must release uploads after an earlier connection-test failure");
        assertEquals(io.github.dsheirer.audio.broadcast.BroadcastState.CONNECTED,
            broadcaster.getBroadcastState());
        broadcaster.dispose();
    }

    @Test
    void metadataRequiresConsistentIdentityOnTheObservedControlFrequency()
    {
        P25NetworkConfigurationSnapshot valid = completeSiteSnapshot();
        assertTrue(RadioResolveBroadcaster.hasVerifiedSiteProof(siteEvent(17L, 3L, valid, 10L)));

        P25NetworkConfigurationSnapshot mismatchedIdentity = new P25NetworkConfigurationSnapshot(valid.decoder(),
            valid.network(), new P25NetworkConfigurationSnapshot.CurrentSite(
                RadioResolveTestFixtures.SYSTEM + 1, 0x348, RadioResolveTestFixtures.RFSS,
                RadioResolveTestFixtures.SITE, 5, true), valid.channels(), valid.neighborSites(),
            valid.frequencyBands(), valid.patchGroups(), valid.talkerAliases(), valid.siteStatus(),
            valid.foreignSystemBands());
        assertFalse(RadioResolveBroadcaster.hasVerifiedSiteProof(
            siteEvent(17L, 3L, mismatchedIdentity, 11L)));
        assertFalse(RadioResolveBroadcaster.hasVerifiedSiteProof(
            siteEvent(17L, 3L, valid, 12L, RadioResolveTestFixtures.FREQUENCY + 12_500L)));
    }

    @Test
    void metadataUsesNativeFactsAndDeclaresMergeVersusCompleteSemantics()
    {
        Channel channel = new Channel("Control");
        channel.setRadioResolveId("legacy-v2-guid");
        JsonObject payload = RadioResolveBroadcaster.createSiteMetadataPayload(new SiteMetadataEvent(channel,
            completeSiteSnapshot(), 1_700_000_010_000L, RadioResolveTestFixtures.FREQUENCY),
            1_700_000_010_000L);

        assertEquals(3, payload.get("schema_version").getAsInt());
        assertFalse(payload.has("node"));
        assertFalse(payload.has("receiver_id"));
        assertFalse(payload.has("radres_guid"));
        assertEquals("p25", payload.getAsJsonObject("system").get("protocol").getAsString());
        assertEquals(RadioResolveTestFixtures.SYSTEM,
            payload.getAsJsonObject("system").get("system_id").getAsInt());
        assertEquals("merge", payload.getAsJsonObject("observation_modes").get("bandplans").getAsString());
        assertEquals("complete_site_scope",
            payload.getAsJsonObject("observation_modes").get("active_patches").getAsString());
        assertEquals(1_700_000_009_000L, payload.get("active_patches_observed_at_ms").getAsLong());
        assertEquals(1_700_000_001_000L,
            payload.getAsJsonArray("channels").get(0).getAsJsonObject().get("observed_at_ms").getAsLong());
        assertEquals(1, payload.getAsJsonArray("active_patches").size());
        assertEquals(1, payload.getAsJsonArray("talker_aliases").size());
    }

    @Test
    void representativeMetadataFixtureMatchesWireContract() throws Exception
    {
        JsonObject actual = RadioResolveBroadcaster.createSiteMetadataPayload(new SiteMetadataEvent(
            new Channel("Control"), completeSiteSnapshot(), 1_700_000_010_000L,
            RadioResolveTestFixtures.FREQUENCY), 1_700_000_010_000L);

        try(InputStreamReader reader = new InputStreamReader(java.util.Objects.requireNonNull(getClass()
            .getResourceAsStream("/io/github/dsheirer/audio/broadcast/radioresolve/v3-site-metadata.json")),
            StandardCharsets.UTF_8))
        {
            assertEquals(JsonParser.parseReader(reader), actual);
        }
    }

    @Test
    void oneServerClockOffsetMovesEveryMetadataObservationTime()
    {
        long offset = 275L;
        JsonObject payload = RadioResolveBroadcaster.createSiteMetadataPayload(new SiteMetadataEvent(
            new Channel("Control"), completeSiteSnapshot(), 1_700_000_010_000L,
            RadioResolveTestFixtures.FREQUENCY), 1_700_000_010_000L, offset);

        assertEquals(1_700_000_010_275L, payload.get("observed_at_ms").getAsLong());
        assertEquals(1_700_000_001_275L,
            payload.getAsJsonArray("channels").get(0).getAsJsonObject().get("observed_at_ms").getAsLong());
        assertEquals(1_700_000_002_275L,
            payload.getAsJsonArray("neighbors").get(0).getAsJsonObject().get("observed_at_ms").getAsLong());
        assertEquals(1_700_000_003_275L,
            payload.getAsJsonArray("bandplans").get(0).getAsJsonObject().get("observed_at_ms").getAsLong());
        assertEquals(1_700_000_004_275L,
            payload.getAsJsonArray("foreign_bandplans").get(0).getAsJsonObject()
                .get("observed_at_ms").getAsLong());
        assertEquals(1_700_000_005_275L,
            payload.getAsJsonArray("talker_aliases").get(0).getAsJsonObject().get("observed_at_ms").getAsLong());
        assertEquals(1_700_000_009_275L, payload.get("active_patches_observed_at_ms").getAsLong());
    }

    @Test
    void emptyMergeCollectionsAreOmittedButEmptyPatchListIsAuthoritative()
    {
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(RadioResolveTestFixtures.WACN,
                RadioResolveTestFixtures.SYSTEM, 0x348, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(RadioResolveTestFixtures.SYSTEM, 0x348,
                RadioResolveTestFixtures.RFSS, RadioResolveTestFixtures.SITE, null, true),
            List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of(), 10L);
        JsonObject payload = RadioResolveBroadcaster.createSiteMetadataPayload(new SiteMetadataEvent(
            new Channel("Control"), snapshot, 10L, RadioResolveTestFixtures.FREQUENCY), 10L);

        assertFalse(payload.has("channels"));
        assertFalse(payload.has("neighbors"));
        assertFalse(payload.has("bandplans"));
        assertFalse(payload.has("talker_aliases"));
        assertTrue(payload.has("active_patches"));
        assertEquals(10L, payload.get("active_patches_observed_at_ms").getAsLong());
        assertTrue(payload.getAsJsonArray("active_patches").isEmpty());
    }

    @Test
    void patchStateIsOmittedUntilTheStabilizerDeclaresACompleteSnapshot()
    {
        P25NetworkConfigurationSnapshot source = completeSiteSnapshot();
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot(source.decoder(),
            source.network(), source.currentSite(), source.channels(), source.neighborSites(),
            source.frequencyBands(), source.patchGroups(), source.talkerAliases(), source.siteStatus(),
            source.foreignSystemBands());
        JsonObject payload = RadioResolveBroadcaster.createSiteMetadataPayload(new SiteMetadataEvent(
            new Channel("Control"), snapshot, 1_700_000_010_000L, RadioResolveTestFixtures.FREQUENCY),
            1_700_000_010_000L);

        assertFalse(payload.has("active_patches"));
        assertFalse(payload.has("active_patches_observed_at_ms"));
        assertFalse(payload.getAsJsonObject("observation_modes").has("active_patches"));
    }

    @Test
    void cachedMergeFactsKeepTheirOwnObservationTimesAndExpiredAliasesAreOmitted()
    {
        P25NetworkConfigurationSnapshot source = completeSiteSnapshot();
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot(source.decoder(),
            source.network(), source.currentSite(), source.channels(), source.neighborSites(),
            source.frequencyBands(), source.patchGroups(),
            List.of(new P25NetworkConfigurationSnapshot.TalkerAlias(700_001, "stale",
                1_700_000_010_000L - TimeUnit.HOURS.toMillis(27))), source.siteStatus(),
            source.foreignSystemBands(), source.activePatchesObservedAtMs());
        JsonObject payload = RadioResolveBroadcaster.createSiteMetadataPayload(new SiteMetadataEvent(
            new Channel("Control"), snapshot, 1_700_000_010_000L, RadioResolveTestFixtures.FREQUENCY),
            1_700_000_010_000L);

        assertEquals(1_700_000_001_000L,
            payload.getAsJsonArray("channels").get(0).getAsJsonObject().get("observed_at_ms").getAsLong());
        assertFalse(payload.has("talker_aliases"),
            "an old true last-seen time is omitted rather than falsely re-stamped and rejected server-side");
    }

    @Test
    void sameSystemNeighborWithoutExplicitSystemIdUsesVerifiedServingSystem()
    {
        P25NetworkConfigurationSnapshot source = completeSiteSnapshot();
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot(source.decoder(),
            source.network(), source.currentSite(), source.channels(),
            List.of(new P25NetworkConfigurationSnapshot.NeighborSite(null, 0x349, 3, 2, 6,
                "0-500", 854_131_250L, 809_131_250L, "active", 1_700_000_006_000L)),
            source.frequencyBands(),
            source.patchGroups(), source.talkerAliases(), source.siteStatus(), source.foreignSystemBands());

        JsonObject payload = RadioResolveBroadcaster.createSiteMetadataPayload(
            siteEvent(17L, 3L, snapshot, 1_700_000_010_000L), 1_700_000_010_000L);

        assertEquals(RadioResolveTestFixtures.SYSTEM,
            payload.getAsJsonArray("neighbors").get(0).getAsJsonObject().get("system_id").getAsInt());
    }

    @Test
    void blockedMetadataConsumerCoalescesWithoutRunningProjectionOnProducer(@TempDir Path directory)
        throws Exception
    {
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        BlockingMetadataBroadcaster broadcaster = new BlockingMetadataBroadcaster(configuration,
            directory.resolve("spool"));
        broadcaster.start();
        String producerThread = Thread.currentThread().getName();
        SiteMetadataEvent first = new SiteMetadataEvent(null, null, completeSiteSnapshot(), 1L);
        SiteMetadataEvent stale = new SiteMetadataEvent(null, null, completeSiteSnapshot(), 2L);
        SiteMetadataEvent latest = new SiteMetadataEvent(null, null, completeSiteSnapshot(), 3L);

        broadcaster.receiveSiteMetadata(first);
        assertTrue(broadcaster.consumerEntered.await(2, TimeUnit.SECONDS));
        broadcaster.receiveSiteMetadata(stale);
        broadcaster.receiveSiteMetadata(latest);

        assertEquals(List.of(1L), broadcaster.processedObservedAt,
            "a full handoff must never use caller-runs");
        assertEquals(1L, broadcaster.coalescedSiteMetadataCount());
        assertFalse(producerThread.equals(broadcaster.consumerThread));
        broadcaster.releaseConsumer.countDown();
        assertTrue(broadcaster.latestProcessed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(1L, 3L), broadcaster.processedObservedAt);
        broadcaster.stop();
        broadcaster.dispose();
    }

    @Test
    void blockedMetadataConsumerRetainsLatestSnapshotForDifferentReceivers(@TempDir Path directory)
        throws Exception
    {
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        BlockingMetadataBroadcaster broadcaster = new BlockingMetadataBroadcaster(configuration,
            directory.resolve("spool"));
        broadcaster.start();
        broadcaster.receiveSiteMetadata(siteEvent("receiver-a", 1L));
        assertTrue(broadcaster.consumerEntered.await(2, TimeUnit.SECONDS));
        broadcaster.receiveSiteMetadata(siteEvent("receiver-a", 2L));
        broadcaster.receiveSiteMetadata(siteEvent("receiver-b", 3L));
        broadcaster.releaseConsumer.countDown();

        assertTrue(broadcaster.subsequentProcessed.await(2, TimeUnit.SECONDS));
        assertTrue(broadcaster.processedObservedAt.containsAll(List.of(2L, 3L)),
            "one busy receiver must not overwrite another receiver's latest observation");
        broadcaster.stop();
        broadcaster.dispose();
    }

    @Test
    void connectionAndCallPollingRunOnDedicatedLowPriorityWorker(@TempDir Path directory) throws Exception
    {
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        WorkerThreadBroadcaster broadcaster = new WorkerThreadBroadcaster(configuration,
            directory.resolve("spool-worker"));

        try
        {
            broadcaster.start();
            assertTrue(broadcaster.pollEntered.await(2, TimeUnit.SECONDS));
            assertNotNull(broadcaster.pollThread);
            assertTrue(broadcaster.pollThread.getName().startsWith("radioresolve-v3-calls-"));
            assertFalse(broadcaster.pollThread.getName().startsWith("sdrtrunk scheduled"));
            assertEquals(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1),
                broadcaster.pollThread.getPriority());
        }
        finally
        {
            broadcaster.stop();
            broadcaster.dispose();
        }
    }

    @Test
    void blockedBatchNetworkWorkerCannotBlockCompletedCallProducer(@TempDir Path directory) throws Exception
    {
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        BlockingConnectionBroadcaster broadcaster = new BlockingConnectionBroadcaster(configuration,
            directory.resolve("spool-producer-isolation"));
        Path audio = directory.resolve("producer-call.mp3");
        Files.write(audio, new byte[] {0x49, 0x44, 0x33});
        AudioRecording recording = RadioResolveTestFixtures.recording(audio, System.currentTimeMillis() - 2_500L);
        recording.addPendingReplay();
        var producer = Executors.newSingleThreadExecutor();

        try
        {
            broadcaster.start();
            assertTrue(broadcaster.connectionEntered.await(2, TimeUnit.SECONDS));
            producer.submit(() -> broadcaster.receive(recording)).get(1, TimeUnit.SECONDS);
            assertEquals(1, broadcaster.getAudioQueueSize());
            assertFalse(recording.hasPendingReplays());
        }
        finally
        {
            broadcaster.releaseConnection.countDown();
            producer.shutdownNow();
            broadcaster.stop();
            broadcaster.dispose();
        }
    }

    @Test
    void exactVerifiedGenerationPlacesCallAndOverridesPersistedStaleIdentity(@TempDir Path directory)
    {
        RadioResolveBroadcaster broadcaster = broadcaster(directory.resolve("spool-a"));
        RadioResolveCallEnvelope.BuildResult build = RadioResolveTestFixtures.build(Path.of("call.mp3"));
        RadioResolveCallEnvelope.HoldContext hold = build.holdContext()
            .withEvidenceSession(broadcaster.evidenceSessionId());
        assertFalse(build.envelope().isReady(), "persisted channel identity alone is never placement proof");

        broadcaster.rememberVerifiedSite(siteEvent(17L, 3L, completeSiteSnapshot(),
            System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)));
        RadioResolveCallEnvelope placed = broadcaster.applyVerifiedPlacements(build.envelope(), hold);

        assertTrue(placed.isReady());
        assertEquals(RadioResolveTestFixtures.placement(), placed.reception().placement());
        broadcaster.dispose();
    }

    @Test
    void retuneVerificationCannotReleaseHeldCallFromPriorTuningGeneration(@TempDir Path directory)
    {
        RadioResolveBroadcaster broadcaster = broadcaster(directory.resolve("spool-b"));
        RadioResolveCallEnvelope.BuildResult build = RadioResolveTestFixtures.build(Path.of("call.mp3"));
        RadioResolveCallEnvelope.HoldContext hold = build.holdContext()
            .withEvidenceSession(broadcaster.evidenceSessionId());
        P25NetworkConfigurationSnapshot differentSite = siteSnapshot(0xABCDE, 0x321, 5, 6);

        broadcaster.rememberVerifiedSite(siteEvent(17L, 4L, differentSite, System.currentTimeMillis()));

        assertFalse(broadcaster.applyVerifiedPlacements(build.envelope(), hold).isReady());
        broadcaster.dispose();
    }

    @Test
    void channelRestartInvalidatesRetainedVerifiedSiteProof(@TempDir Path directory)
    {
        RadioResolveBroadcaster broadcaster = broadcaster(directory.resolve("spool-restart"));
        RadioResolveCallEnvelope.BuildResult build = RadioResolveTestFixtures.build(Path.of("call.mp3"));
        RadioResolveCallEnvelope.HoldContext hold = build.holdContext()
            .withEvidenceSession(broadcaster.evidenceSessionId());
        MutableGenerationChannel channel = new MutableGenerationChannel(17L, 3L);
        SiteReceiverContext context = SiteReceiverContext.capture(channel, Protocol.APCO25_PHASE2,
            RadioResolveTestFixtures.FREQUENCY);
        broadcaster.rememberVerifiedSite(new SiteMetadataEvent(channel, context, completeSiteSnapshot(),
            System.currentTimeMillis()));

        assertTrue(broadcaster.applyVerifiedPlacements(build.envelope(), hold).isReady());

        channel.deactivate();
        assertTrue(broadcaster.applyVerifiedPlacements(build.envelope(), hold).isReady(),
            "terminal call handoff may finish after its processing mapping closes");

        channel.reactivate(18L, 1L);

        assertFalse(broadcaster.applyVerifiedPlacements(build.envelope(), hold).isReady(),
            "a late call from the prior processing chain must not use retained site authority");
        broadcaster.dispose();
    }

    @Test
    void restartSessionPreventsNumericGenerationCollision(@TempDir Path directory)
    {
        RadioResolveCallEnvelope.BuildResult build = RadioResolveTestFixtures.build(Path.of("call.mp3"));
        RadioResolveBroadcaster oldProcess = broadcaster(directory.resolve("spool-c"));
        RadioResolveCallEnvelope.HoldContext oldHold = build.holdContext()
            .withEvidenceSession(oldProcess.evidenceSessionId());
        oldProcess.dispose();
        RadioResolveBroadcaster restarted = broadcaster(directory.resolve("spool-c"));
        restarted.rememberVerifiedSite(siteEvent(17L, 3L, completeSiteSnapshot(),
            System.currentTimeMillis()));

        assertFalse(restarted.applyVerifiedPlacements(build.envelope(), oldHold).isReady());
        restarted.dispose();
    }

    @Test
    void freshCallsBypassRestartedBacklogInChronologicalOrderThenBacklogDrains(@TempDir Path directory)
        throws Exception
    {
        assertEquals(TimeUnit.SECONDS.toMillis(180),
            RadioResolveBroadcaster.LIVE_UPLOAD_PRIORITY_WINDOW_MILLISECONDS);
        long now = System.currentTimeMillis();
        Path spoolDirectory = directory.resolve("spool-fresh-priority");
        Path audio = directory.resolve("call.mp3");
        Files.write(audio, new byte[] {0x49, 0x44, 0x33});
        RadioResolveSpool beforeRestart = new RadioResolveSpool(spoolDirectory);
        beforeRestart.open();
        RadioResolveCallEnvelope oldestBacklog = RadioResolveTestFixtures.readyEnvelope(audio,
            now - TimeUnit.HOURS.toMillis(3), "00000000-0000-4000-8000-000000000021");
        RadioResolveCallEnvelope newestBacklog = RadioResolveTestFixtures.readyEnvelope(audio,
            now - TimeUnit.HOURS.toMillis(2), "00000000-0000-4000-8000-000000000022");
        RadioResolveCallEnvelope newestFresh = RadioResolveTestFixtures.readyEnvelope(audio,
            now - TimeUnit.SECONDS.toMillis(10), "00000000-0000-4000-8000-000000000024");
        RadioResolveCallEnvelope oldestFresh = RadioResolveTestFixtures.readyEnvelope(audio,
            now - TimeUnit.SECONDS.toMillis(20), "00000000-0000-4000-8000-000000000023");
        beforeRestart.enqueue(audio, oldestBacklog, RadioResolveCallEnvelope.HoldContext.EMPTY, now);
        beforeRestart.enqueue(audio, newestBacklog, RadioResolveCallEnvelope.HoldContext.EMPTY, now + 1L);
        beforeRestart.enqueue(audio, newestFresh, RadioResolveCallEnvelope.HoldContext.EMPTY, now + 2L);
        beforeRestart.enqueue(audio, oldestFresh, RadioResolveCallEnvelope.HoldContext.EMPTY, now + 3L);

        RadioResolveBroadcaster restarted = broadcaster(spoolDirectory);
        RadioResolveSpool afterRestart = new RadioResolveSpool(spoolDirectory);
        afterRestart.open();
        List<String> expectedOrder = List.of(oldestFresh.submissionId(), newestFresh.submissionId(),
            oldestBacklog.submissionId(), newestBacklog.submissionId());

        for(String submissionId : expectedOrder)
        {
            RadioResolveSpool.Entry candidate = firstUploadCandidate(restarted, now + 4L);
            assertNotNull(candidate);
            assertEquals(submissionId, candidate.manifest().envelope().submissionId());
            afterRestart.remove(candidate);
        }

        assertNull(firstUploadCandidate(restarted, now + 4L));
        assertEquals(0, afterRestart.size());
        restarted.dispose();
    }

    @Test
    void batchKeepsFreshCallOrderThenFillsWithOldestBacklog(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path spoolDirectory = directory.resolve("spool-batch-order");
        Path audio = directory.resolve("batch-order.mp3");
        Files.write(audio, new byte[] {0x49, 0x44, 0x33});
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory);
        spool.open();
        RadioResolveCallEnvelope oldestBacklog = RadioResolveTestFixtures.readyEnvelope(audio,
            now - TimeUnit.HOURS.toMillis(3), "00000000-0000-4000-8000-000000000041");
        RadioResolveCallEnvelope newerBacklog = RadioResolveTestFixtures.readyEnvelope(audio,
            now - TimeUnit.HOURS.toMillis(2), "00000000-0000-4000-8000-000000000042");
        RadioResolveCallEnvelope newerFresh = RadioResolveTestFixtures.readyEnvelope(audio,
            now - TimeUnit.SECONDS.toMillis(10), "00000000-0000-4000-8000-000000000044");
        RadioResolveCallEnvelope oldestFresh = RadioResolveTestFixtures.readyEnvelope(audio,
            now - TimeUnit.SECONDS.toMillis(20), "00000000-0000-4000-8000-000000000043");
        spool.enqueue(audio, oldestBacklog, RadioResolveCallEnvelope.HoldContext.EMPTY, now);
        spool.enqueue(audio, newerBacklog, RadioResolveCallEnvelope.HoldContext.EMPTY, now + 1L);
        spool.enqueue(audio, newerFresh, RadioResolveCallEnvelope.HoldContext.EMPTY, now + 2L);
        spool.enqueue(audio, oldestFresh, RadioResolveCallEnvelope.HoldContext.EMPTY, now + 3L);
        RadioResolveBroadcaster broadcaster = broadcaster(spoolDirectory);

        List<String> actual = broadcaster.nextUploadBatch(now + 4L).stream()
            .map(entry -> entry.manifest().envelope().submissionId()).toList();

        assertEquals(List.of(oldestFresh.submissionId(), newerFresh.submissionId(),
            oldestBacklog.submissionId(), newerBacklog.submissionId()), actual);
        broadcaster.dispose();
    }

    @Test
    void batchSelectionHonorsCountAndByteBounds(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path spoolDirectory = directory.resolve("spool-batch-bounds");
        Path audio = directory.resolve("batch-bounds.mp3");
        Files.write(audio, new byte[256]);
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory);
        spool.open();

        for(int index = 1; index <= 5; index++)
        {
            spool.enqueue(audio, RadioResolveTestFixtures.readyEnvelope(audio,
                now - TimeUnit.HOURS.toMillis(2) + index,
                String.format("00000000-0000-4000-8000-%012d", index)),
                RadioResolveCallEnvelope.HoldContext.EMPTY, now + index);
        }

        RadioResolveBroadcaster broadcaster = broadcaster(spoolDirectory);
        List<RadioResolveSpool.Entry> countBounded = broadcaster.nextUploadBatch(now + 10L, 3,
            Long.MAX_VALUE);
        assertEquals(3, countBounded.size());
        long firstTwoBytes = countBounded.get(0).sizeBytes() + countBounded.get(1).sizeBytes();
        List<RadioResolveSpool.Entry> byteBounded = broadcaster.nextUploadBatch(now + 10L, 8,
            firstTwoBytes - 1L);

        assertEquals(1, byteBounded.size());
        assertEquals(countBounded.getFirst().manifest().envelope().submissionId(),
            byteBounded.getFirst().manifest().envelope().submissionId());
        broadcaster.dispose();
    }

    @Test
    void audioAbovePerCallLimitIsTerminallyRemovedBeforeBatchUpload(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path audio = directory.resolve("oversized.mp3");

        try(FileChannel channel = FileChannel.open(audio, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE))
        {
            channel.position(RadioResolveBroadcaster.MAXIMUM_CALL_AUDIO_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }

        Path spoolDirectory = directory.resolve("spool-oversized-call");
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory);
        spool.open();
        RadioResolveSpool.Entry entry = spool.enqueue(audio,
            RadioResolveTestFixtures.readyEnvelope(audio, now - 3_000L,
                "00000000-0000-4000-8000-000000000061"),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry();
        RadioResolveBroadcaster broadcaster = broadcaster(spoolDirectory);

        assertNull(broadcaster.createBoundedUploadRequest(List.of(entry)));
        assertEquals(0, spool.size());
        assertEquals(1, broadcaster.getAudioErrorCount());
        assertTrue(Files.isRegularFile(audio), "the broadcaster removes only its durable spool copy");
        broadcaster.dispose();
    }

    @Test
    void partialBatchAcknowledgementDeletesOnlyCertainOutcomes(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path spoolDirectory = directory.resolve("spool-batch-results");
        Path audio = directory.resolve("batch-results.mp3");
        Files.write(audio, new byte[] {0x49, 0x44, 0x33});
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory);
        spool.open();
        List<RadioResolveSpool.Entry> entries = new java.util.ArrayList<>();

        for(int index = 1; index <= 4; index++)
        {
            entries.add(spool.enqueue(audio, RadioResolveTestFixtures.readyEnvelope(audio, now - 3_000L + index,
                String.format("00000000-0000-4000-8000-%012d", index)),
                RadioResolveCallEnvelope.HoldContext.EMPTY, now + index).entry());
        }

        String body = "{\"data\":{\"results\":[" +
            resultJson(0, entries.get(0), 201) + "," +
            resultJson(1, entries.get(1), 422) + "," +
            resultJson(2, entries.get(2), 503) + "]}}";
        RadioResolveBroadcaster broadcaster = broadcaster(spoolDirectory);
        broadcaster.applyBatchUploadResponse(entries, 200, body);
        List<RadioResolveSpool.Entry> remaining = spool.entries();

        assertEquals(2, remaining.size());
        assertEquals(entries.get(2).manifest().envelope().submissionId(),
            remaining.get(0).manifest().envelope().submissionId());
        assertEquals(entries.get(3).manifest().envelope().submissionId(),
            remaining.get(1).manifest().envelope().submissionId());
        assertEquals(1, remaining.get(0).manifest().attemptCount(), "transient result retries");
        assertEquals(1, remaining.get(1).manifest().attemptCount(), "missing result retries");
        broadcaster.dispose();
    }

    @Test
    void malformedOrMismatchedBatchResponseKeepsEveryCallAcrossRestart(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path spoolDirectory = directory.resolve("spool-batch-uncertain");
        Path audio = directory.resolve("batch-uncertain.mp3");
        Files.write(audio, new byte[] {0x49, 0x44, 0x33});
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory);
        spool.open();
        RadioResolveSpool.Entry first = spool.enqueue(audio,
            RadioResolveTestFixtures.readyEnvelope(audio, now - 3_000L,
                "00000000-0000-4000-8000-000000000051"),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry();
        RadioResolveSpool.Entry second = spool.enqueue(audio,
            RadioResolveTestFixtures.readyEnvelope(audio, now - 2_000L,
                "00000000-0000-4000-8000-000000000052"),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now + 1L).entry();
        String mismatched = "{\"data\":{\"results\":[{" +
            "\"index\":0,\"submission_id\":\"00000000-0000-4000-8000-000000000099\"," +
            "\"http_status\":201}]}}";
        RadioResolveBroadcaster broadcaster = broadcaster(spoolDirectory);
        broadcaster.applyBatchUploadResponse(List.of(first, second), 200, mismatched);
        broadcaster.dispose();

        RadioResolveSpool reopened = new RadioResolveSpool(spoolDirectory);
        reopened.open();
        assertEquals(2, reopened.size());
        assertTrue(reopened.entries().stream().allMatch(entry -> entry.manifest().attemptCount() == 1));
        assertTrue(reopened.entries().stream().allMatch(entry -> Files.isRegularFile(entry.audioPath())));
    }

    @Test
    void clockAdjustedRetryStaysAheadOfRawNewerCall(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path spoolDirectory = directory.resolve("spool-clock-adjusted-order");
        Path audio = directory.resolve("call.mp3");
        Files.write(audio, new byte[] {0x49, 0x44, 0x33});
        RadioResolveBroadcaster broadcaster = broadcaster(spoolDirectory);
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory);
        spool.open();
        RadioResolveCallEnvelope older = RadioResolveTestFixtures.readyEnvelope(audio,
            now - TimeUnit.SECONDS.toMillis(20), "00000000-0000-4000-8000-000000000031");
        RadioResolveSpool.Entry queuedOlder = spool.enqueue(audio, older,
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry();
        RadioResolveSpool.Entry adjustedOlder = spool.applyServerClockOffset(queuedOlder,
            TimeUnit.SECONDS.toMillis(15));
        long retryReadyAt = System.currentTimeMillis() + 1_000L;
        spool.retry(adjustedOlder, retryReadyAt);
        RadioResolveCallEnvelope newer = RadioResolveTestFixtures.readyEnvelope(audio,
            now - TimeUnit.SECONDS.toMillis(10), "00000000-0000-4000-8000-000000000032");
        spool.enqueue(audio, newer, RadioResolveCallEnvelope.HoldContext.EMPTY, now + 1L);

        RadioResolveSpool.Entry candidate = firstUploadCandidate(broadcaster, retryReadyAt + 1L);

        assertNotNull(candidate);
        assertEquals(older.submissionId(), candidate.manifest().envelope().submissionId(),
            "server clock normalization must not let later receiver-local speech leapfrog a retry");
        broadcaster.dispose();
    }

    @Test
    void unresolvedOlderCallBlocksNewerReadyCallUntilItsPlacementDeadline(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path spoolDirectory = directory.resolve("spool-mixed");
        Path audio = directory.resolve("call.mp3");
        Files.write(audio, new byte[] {0x49, 0x44, 0x33});
        RadioResolveBroadcaster broadcaster = broadcaster(spoolDirectory);
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory);
        spool.open();
        RadioResolveCallEnvelope.BuildResult held = RadioResolveTestFixtures.build(audio, now - 4_000L,
            "00000000-0000-4000-8000-000000000001");
        spool.enqueue(audio, held.envelope(),
            held.holdContext().withEvidenceSession(broadcaster.evidenceSessionId()), now);
        RadioResolveCallEnvelope ready = RadioResolveTestFixtures.readyEnvelope(audio, now - 3_000L,
            "00000000-0000-4000-8000-000000000002");
        spool.enqueue(audio, ready, RadioResolveCallEnvelope.HoldContext.EMPTY, now + 1L);

        RadioResolveSpool.Entry candidate = firstUploadCandidate(broadcaster, now + 2L);

        assertNull(candidate, "a newer call must not leapfrog an unresolved older call in Live playback order");
        assertEquals(2, spool.size(), "the unresolved call remains held until its own deadline");

        candidate = firstUploadCandidate(broadcaster,
            now + RadioResolveBroadcaster.METADATA_HOLD_MILLISECONDS + 1L);
        assertNotNull(candidate);
        assertEquals(ready.submissionId(), candidate.manifest().envelope().submissionId());
        assertEquals(1, spool.size(), "the unresolved call is dropped before the ordered queue can advance");
        broadcaster.dispose();
    }

    @Test
    void verifiedOlderHeldCallAdvancesBeforeNewerReadyCall(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path spoolDirectory = directory.resolve("spool-ordered-resolution");
        Path audio = directory.resolve("call.mp3");
        Files.write(audio, new byte[] {0x49, 0x44, 0x33});
        RadioResolveBroadcaster broadcaster = broadcaster(spoolDirectory);
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory);
        spool.open();
        RadioResolveCallEnvelope.BuildResult held = RadioResolveTestFixtures.build(audio, now - 4_000L,
            "00000000-0000-4000-8000-000000000011");
        spool.enqueue(audio, held.envelope(),
            held.holdContext().withEvidenceSession(broadcaster.evidenceSessionId()), now);
        RadioResolveCallEnvelope ready = RadioResolveTestFixtures.readyEnvelope(audio, now - 3_000L,
            "00000000-0000-4000-8000-000000000012");
        spool.enqueue(audio, ready, RadioResolveCallEnvelope.HoldContext.EMPTY, now + 1L);
        broadcaster.rememberVerifiedSite(siteEvent(17L, 3L, completeSiteSnapshot(), now + 2L));

        RadioResolveSpool.Entry candidate = firstUploadCandidate(broadcaster, now + 3L);

        assertNotNull(candidate);
        assertEquals(held.envelope().submissionId(), candidate.manifest().envelope().submissionId());
        assertTrue(candidate.manifest().envelope().isReady());
        assertEquals(2, spool.size());
        broadcaster.dispose();
    }

    @Test
    void readyCallWaitsDurablyPastPlacementDeadlineUntilClockProofReturns(@TempDir Path directory)
        throws Exception
    {
        long now = 1_700_000_000_000L;
        AtomicLong epochMilliseconds = new AtomicLong(now);
        AtomicLong monotonicNanoseconds = new AtomicLong(TimeUnit.SECONDS.toNanos(10L));
        RadioResolveClockSynchronizer clock = new RadioResolveClockSynchronizer(epochMilliseconds::get,
            monotonicNanoseconds::get);
        Path spoolDirectory = directory.resolve("spool-clock-held");
        Path audio = directory.resolve("call.mp3");
        Files.write(audio, new byte[] {0x49, 0x44, 0x33});
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        RadioResolveBroadcaster broadcaster = new RadioResolveBroadcaster(configuration, null, null, null,
            spoolDirectory, clock);
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory);
        spool.open();
        RadioResolveCallEnvelope envelope = RadioResolveTestFixtures.readyEnvelope(audio, now - 3_000L,
            "00000000-0000-4000-8000-000000000003");
        spool.enqueue(audio, envelope, RadioResolveCallEnvelope.HoldContext.EMPTY, now);

        RadioResolveSpool.Entry candidate = firstUploadCandidate(broadcaster,
            now + RadioResolveBroadcaster.METADATA_HOLD_MILLISECONDS + 1L);
        assertNotNull(candidate, "clock uncertainty is not a placement failure");
        assertNull(prepareFirst(broadcaster, candidate));
        assertEquals(1, spool.size(), "unsynchronized audio remains owned by the 24-hour durable spool");

        RadioResolveClockSynchronizer.RequestTiming request = clock.beginRequest();
        epochMilliseconds.addAndGet(10L);
        monotonicNanoseconds.addAndGet(TimeUnit.MILLISECONDS.toNanos(10L));
        assertTrue(clock.completeRequest(request, now + 130L));
        RadioResolveSpool.Entry prepared = prepareFirst(broadcaster, candidate);

        assertNotNull(prepared);
        assertEquals(125L, prepared.manifest().appliedServerClockOffsetMs());
        assertEquals(envelope.completedAtMs() + 125L, prepared.manifest().envelope().completedAtMs());
        broadcaster.dispose();
    }

    private static String resultJson(int index, RadioResolveSpool.Entry entry, int httpStatus)
    {
        return "{\"index\":" + index + ",\"submission_id\":\"" +
            entry.manifest().envelope().submissionId() + "\",\"http_status\":" + httpStatus + "}";
    }

    private static RadioResolveSpool.Entry firstUploadCandidate(RadioResolveBroadcaster broadcaster, long now)
        throws Exception
    {
        List<RadioResolveSpool.Entry> batch = broadcaster.nextUploadBatch(now, 1,
            RadioResolveBroadcaster.MAXIMUM_UPLOAD_BYTES);
        return batch.isEmpty() ? null : batch.getFirst();
    }

    private static RadioResolveSpool.Entry prepareFirst(RadioResolveBroadcaster broadcaster,
                                                         RadioResolveSpool.Entry entry) throws Exception
    {
        List<RadioResolveSpool.Entry> prepared = broadcaster.prepareUploadEntries(List.of(entry));
        return prepared.isEmpty() ? null : prepared.getFirst();
    }

    private static byte[] collectBody(HttpRequest request) throws Exception
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<byte[]> completed = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>()
        {
            @Override
            public void onSubscribe(Flow.Subscription subscription)
            {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item)
            {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable)
            {
                completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete()
            {
                completed.complete(output.toByteArray());
            }
        });
        return completed.get(2, TimeUnit.SECONDS);
    }

    private static RadioResolveBroadcaster broadcaster(Path spool)
    {
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        return new RadioResolveBroadcaster(configuration, null, null, null, spool);
    }

    private static SiteMetadataEvent siteEvent(long incarnation, long tuningGeneration,
                                               P25NetworkConfigurationSnapshot snapshot, long observedAt)
    {
        return siteEvent(incarnation, tuningGeneration, snapshot, observedAt,
            RadioResolveTestFixtures.FREQUENCY);
    }

    private static SiteMetadataEvent siteEvent(long incarnation, long tuningGeneration,
                                               P25NetworkConfigurationSnapshot snapshot, long observedAt,
                                               long sourceFrequency)
    {
        SiteReceiverContext context = new SiteReceiverContext(RadioResolveTestFixtures.CHANNEL_ID, 7,
            incarnation, tuningGeneration, Channel.ChannelType.STANDARD, DecoderType.P25_PHASE2,
            Protocol.APCO25_PHASE2, SiteReceiverContext.ReceiverMode.TRUNKED,
            TrunkedIdentityDomain.STANDARD, SourceType.TUNER, RadioResolveTestFixtures.FREQUENCY, null,
            sourceFrequency, null, "Control", "System", "Site", 1L, "Aliases");
        return new SiteMetadataEvent(null, context, snapshot, observedAt);
    }

    private static SiteMetadataEvent siteEvent(String configurationId, long observedAt)
    {
        SiteReceiverContext context = new SiteReceiverContext(configurationId, 7, 17L, 3L,
            Channel.ChannelType.STANDARD, DecoderType.P25_PHASE2, Protocol.APCO25_PHASE2,
            SiteReceiverContext.ReceiverMode.TRUNKED, TrunkedIdentityDomain.STANDARD, SourceType.TUNER,
            RadioResolveTestFixtures.FREQUENCY, null, RadioResolveTestFixtures.FREQUENCY, null,
            "Control", "System", "Site", 1L, "Aliases");
        return new SiteMetadataEvent(null, context, completeSiteSnapshot(), observedAt);
    }

    private static P25NetworkConfigurationSnapshot siteSnapshot(int wacn, int system, int rfss, int site)
    {
        return new P25NetworkConfigurationSnapshot("P25_PHASE_2",
            new P25NetworkConfigurationSnapshot.Network(wacn, system, 0x123, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(system, 0x123, rfss, site, null, true),
            List.of(new P25NetworkConfigurationSnapshot.Channel("current_control", "1-1",
                RadioResolveTestFixtures.FREQUENCY, 809_087_500L, true, 2)), List.of(),
            List.of(new P25NetworkConfigurationSnapshot.FrequencyBand(1, true, 851_000_000L, 12_500,
                12_500L, -45_000_000L, 2)), List.of(), List.of());
    }

    static P25NetworkConfigurationSnapshot completeSiteSnapshot()
    {
        return new P25NetworkConfigurationSnapshot("P25_PHASE_2",
            new P25NetworkConfigurationSnapshot.Network(RadioResolveTestFixtures.WACN,
                RadioResolveTestFixtures.SYSTEM, 0x348, 5),
            new P25NetworkConfigurationSnapshot.CurrentSite(RadioResolveTestFixtures.SYSTEM, 0x348,
                RadioResolveTestFixtures.RFSS, RadioResolveTestFixtures.SITE, 5, true),
            List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", "0-493",
                RadioResolveTestFixtures.FREQUENCY, 809_087_500L, false, 1, "WXYZ",
                1_700_000_001_000L)),
            List.of(new P25NetworkConfigurationSnapshot.NeighborSite(RadioResolveTestFixtures.SYSTEM, 0x349,
                3, 2, 6, "0-500", 854_131_250L, 809_131_250L, "active",
                1_700_000_002_000L)),
            List.of(new P25NetworkConfigurationSnapshot.FrequencyBand(0, false, 851_006_250L, 12_500,
                6_250L, -45_000_000L, 1, 1_700_000_003_000L)),
            List.of(new P25NetworkConfigurationSnapshot.PatchGroup(4_500, 7, List.of(4_400, 4_401),
                List.of(700_001))),
            List.of(new P25NetworkConfigurationSnapshot.TalkerAlias(700_001, "OTA UNIT 7",
                1_700_000_005_000L)),
            new P25NetworkConfigurationSnapshot.SiteStatus(1_700_000_000_000L, 3, true, "allowed", 30,
                true, 0, true),
            List.of(new P25NetworkConfigurationSnapshot.ForeignSystemBand(0xABCDE, 0x321, 2, 4,
                762_006_250L, 6_250L, 30_000_000L, 1_700_000_004_000L)),
            1_700_000_009_000L);
    }

    private static class BlockingMetadataBroadcaster extends RadioResolveBroadcaster
    {
        private final CountDownLatch consumerEntered = new CountDownLatch(1);
        private final CountDownLatch releaseConsumer = new CountDownLatch(1);
        private final CountDownLatch latestProcessed = new CountDownLatch(1);
        private final CountDownLatch subsequentProcessed = new CountDownLatch(2);
        private final List<Long> processedObservedAt = new CopyOnWriteArrayList<>();
        private volatile String consumerThread;

        private BlockingMetadataBroadcaster(RadioResolveConfiguration configuration, Path spool)
        {
            super(configuration, null, null, null, spool);
        }

        @Override
        void processSiteMetadata(SiteMetadataEvent event)
        {
            consumerThread = Thread.currentThread().getName();
            processedObservedAt.add(event.observedAtEpochMilliseconds());

            if(event.observedAtEpochMilliseconds() == 1L)
            {
                consumerEntered.countDown();
                try
                {
                    releaseConsumer.await(2, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
            else if(event.observedAtEpochMilliseconds() == 3L)
            {
                latestProcessed.countDown();
            }

            if(event.observedAtEpochMilliseconds() != 1L)
            {
                subsequentProcessed.countDown();
            }
        }
    }

    private static class BlockingConnectionBroadcaster extends RadioResolveBroadcaster
    {
        private final CountDownLatch connectionEntered = new CountDownLatch(1);
        private final CountDownLatch releaseConnection = new CountDownLatch(1);

        private BlockingConnectionBroadcaster(RadioResolveConfiguration configuration, Path spool)
        {
            super(configuration, null, null, null, spool);
        }

        @Override
        boolean connected()
        {
            connectionEntered.countDown();

            try
            {
                releaseConnection.await(2, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            return false;
        }
    }

    private static class WorkerThreadBroadcaster extends RadioResolveBroadcaster
    {
        private final CountDownLatch pollEntered = new CountDownLatch(1);
        private volatile Thread pollThread;

        private WorkerThreadBroadcaster(RadioResolveConfiguration configuration, Path spool)
        {
            super(configuration, null, null, null, spool);
        }

        @Override
        boolean connected()
        {
            pollThread = Thread.currentThread();
            pollEntered.countDown();
            return false;
        }
    }

    private static class MutableGenerationChannel extends Channel
    {
        private long incarnation;
        private long tuningGeneration;
        private boolean active = true;

        private MutableGenerationChannel(long incarnation, long tuningGeneration)
        {
            super("Control", ChannelType.STANDARD);
            this.incarnation = incarnation;
            this.tuningGeneration = tuningGeneration;
            setConfigurationId(RadioResolveTestFixtures.CHANNEL_ID);
            setDecodeConfiguration(new DecodeConfigP25Phase2());
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(RadioResolveTestFixtures.FREQUENCY);
            setSourceConfiguration(source);
        }

        private void reactivate(long newIncarnation, long newTuningGeneration)
        {
            incarnation = newIncarnation;
            tuningGeneration = newTuningGeneration;
            active = true;
        }

        private void deactivate()
        {
            active = false;
        }

        @Override
        public long getProcessingIncarnation()
        {
            return incarnation;
        }

        @Override
        public long getSiteEvidenceTuningGeneration()
        {
            return tuningGeneration;
        }

        @Override
        public boolean matchesProcessingIncarnation(long expected, boolean requireActive)
        {
            return expected == incarnation && (!requireActive || active);
        }
    }
}
