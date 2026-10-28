/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.broadcast.broadcastify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastDeliveryEvidence;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BroadcastifyDuplicateRejectedTest
{
    @TempDir
    Path directory;

    @Test
    void skippedLegacyAndSiteCallsCountOnlyDuplicatesAndReleaseOnlyTheirOwnReplay() throws Exception
    {
        for(boolean siteBound: List.of(false, true))
        {
            try(Fixture fixture = new Fixture(siteBound, 1))
            {
                TrackedRecording recording = fixture.recording(directory.resolve("skipped.mp3"));
                recording.addPendingReplay();
                recording.addPendingReplay();
                CountDownLatch completed = new CountDownLatch(1);
                AtomicInteger eventCount = new AtomicInteger();
                AtomicInteger releasesWhenNotified = new AtomicInteger(-1);
                AtomicInteger duplicatesWhenNotified = new AtomicInteger(-1);
                fixture.broadcaster.setListener(event -> {
                    if(event.getEvent() == BroadcastEvent.Event.BROADCASTER_DUPLICATE_REJECTED_COUNT_CHANGE)
                    {
                        releasesWhenNotified.set(recording.releases.get());
                        duplicatesWhenNotified.set(fixture.broadcaster.getDuplicateRejectedAudioCount());
                        eventCount.incrementAndGet();
                        completed.countDown();
                    }
                });

                assertTrue(fixture.broadcaster.accepts(recording));
                fixture.broadcaster.receive(recording);
                fixture.broadcaster.new AudioRecordingProcessor().run();
                fixture.releaseResponses();
                assertTrue(completed.await(5, TimeUnit.SECONDS));
                fixture.broadcaster.new AudioRecordingProcessor().run();

                assertEquals(1, fixture.requests.get(), "A skipped call must not be retried");
                assertEquals(0, fixture.puts.get());
                assertEquals(1, eventCount.get());
                assertEquals(1, releasesWhenNotified.get());
                assertEquals(1, duplicatesWhenNotified.get());
                assertEquals(1, recording.releases.get());
                assertTrue(recording.hasPendingReplays(), "Another destination still owns its pending replay");
                assertUnchangedDeliveryCounters(fixture.broadcaster);
                assertEquals(0, fixture.broadcaster.getAudioQueueSize());

                BroadcastifyCallBroadcaster replacement = new BroadcastifyCallBroadcaster(fixture.configuration,
                    null, null, fixture.aliases);
                assertEquals(0, replacement.getDuplicateRejectedAudioCount(),
                    "A replacement sender starts a new counter lifetime");
            }
        }
    }

    @Test
    void duplicateNotificationFailureCannotLeakThePendingReplay() throws Exception
    {
        try(Fixture fixture = new Fixture(false, 1))
        {
            TrackedRecording recording = fixture.recording(directory.resolve("observer-failure.mp3"));
            recording.addPendingReplay();
            CountDownLatch notified = new CountDownLatch(1);
            AtomicInteger releasesWhenNotified = new AtomicInteger(-1);
            fixture.broadcaster.setListener(event -> {
                if(event.getEvent() == BroadcastEvent.Event.BROADCASTER_DUPLICATE_REJECTED_COUNT_CHANGE)
                {
                    releasesWhenNotified.set(recording.releases.get());
                    notified.countDown();
                    throw new IllegalStateException("Synthetic observer failure");
                }
            });

            fixture.broadcaster.receive(recording);
            fixture.broadcaster.new AudioRecordingProcessor().run();
            fixture.releaseResponses();
            assertTrue(notified.await(5, TimeUnit.SECONDS));

            assertEquals(1, releasesWhenNotified.get());
            assertEquals(1, recording.releases.get());
            assertFalse(recording.hasPendingReplays());
            assertEquals(1, fixture.broadcaster.getDuplicateRejectedAudioCount());
            assertEquals(1, fixture.requests.get());
            assertEquals(0, fixture.puts.get());
            assertUnchangedDeliveryCounters(fixture.broadcaster);
        }
    }

    @Test
    void overlappingSkipResponsesRetainEveryCountEventAndReplayRelease() throws Exception
    {
        int count = 16;
        try(Fixture fixture = new Fixture(false, count))
        {
            CountDownLatch completed = new CountDownLatch(count);
            AtomicInteger events = new AtomicInteger();
            fixture.broadcaster.setListener(event -> {
                if(event.getEvent() == BroadcastEvent.Event.BROADCASTER_DUPLICATE_REJECTED_COUNT_CHANGE)
                {
                    events.incrementAndGet();
                    completed.countDown();
                }
            });
            List<TrackedRecording> recordings = new ArrayList<>();
            for(int index = 0; index < count; index++)
            {
                TrackedRecording recording = fixture.recording(directory.resolve("concurrent-" + index + ".mp3"));
                recording.addPendingReplay();
                recordings.add(recording);
                fixture.broadcaster.receive(recording);
            }
            fixture.broadcaster.new AudioRecordingProcessor().run();
            fixture.releaseResponses();
            assertTrue(completed.await(5, TimeUnit.SECONDS));

            assertEquals(count, fixture.broadcaster.getDuplicateRejectedAudioCount());
            assertEquals(count, events.get());
            assertEquals(count, fixture.requests.get());
            assertEquals(0, fixture.puts.get());
            for(TrackedRecording recording: recordings)
            {
                assertEquals(1, recording.releases.get());
                assertFalse(recording.hasPendingReplays());
            }
            assertUnchangedDeliveryCounters(fixture.broadcaster);
        }
    }

    private static void assertUnchangedDeliveryCounters(BroadcastifyCallBroadcaster broadcaster)
    {
        assertEquals(0, broadcaster.getStreamedAudioCount());
        assertEquals(0, broadcaster.getAudioErrorCount());
        assertEquals(0, broadcaster.getAgedOffAudioCount());
        assertEquals(BroadcastState.CONNECTED, broadcaster.getBroadcastState());
        assertNull(broadcaster.getLastBadBroadcastState());
    }

    private static final class TrackedRecording extends AudioRecording
    {
        private final AtomicInteger releases = new AtomicInteger();

        private TrackedRecording(Path path, BroadcastDeliveryEvidence evidence)
        {
            super(path, List.of(), new IdentifierCollection(List.of(NXDNRadioIdentifier.createFrom(91),
                NXDNTalkgroupIdentifier.createTo(101))), System.currentTimeMillis(), 1_000L, evidence);
        }

        @Override
        public void removePendingReplay()
        {
            releases.incrementAndGet();
            super.removePendingReplay();
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        private final HttpServer server;
        private final ExecutorService serverExecutor;
        private final CountDownLatch received;
        private final CountDownLatch respond = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger puts = new AtomicInteger();
        private final AliasModel aliases = new AliasModel();
        private final BroadcastifyCallConfiguration configuration;
        private final BroadcastifyCallBroadcaster broadcaster;
        private final BroadcastDeliveryEvidence evidence;

        private Fixture(boolean siteBound, int count) throws IOException
        {
            received = new CountDownLatch(count);
            serverExecutor = Executors.newFixedThreadPool(count);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverExecutor);
            server.createContext("/calls", exchange -> {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                received.countDown();
                try
                {
                    if(!respond.await(5, TimeUnit.SECONDS)) throw new IOException("Synthetic response gate timed out");
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                reply(exchange, "1 SKIPPED duplicate call");
            });
            server.createContext("/file", exchange -> {
                puts.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                reply(exchange, "ok");
            });
            server.start();
            if(siteBound)
            {
                AliasListDefinition list = new AliasListDefinition("Synthetic Calls site", AliasListFamily.NXDN);
                list.setId(7);
                aliases.replaceCommittedConfiguration(List.of(list), List.of());
                BroadcastifyCallSiteConfiguration selected = new BroadcastifyCallSiteConfiguration();
                selected.setAliasListId(list.getId());
                selected.setChannelConfigurationId(UUID.randomUUID().toString());
                configuration = selected;
                evidence = new BroadcastDeliveryEvidence(List.of(new BroadcastDeliveryEvidence.Observation(
                    selected.getChannelConfigurationId(), list.getId(), ChannelConfigurationPolicy.ChannelKind.TRUNKED,
                    Set.of(selected.getConfigurationId()))));
            }
            else
            {
                configuration = new BroadcastifyCallConfiguration();
                evidence = BroadcastDeliveryEvidence.EMPTY;
            }
            configuration.setHost("http://127.0.0.1:" + server.getAddress().getPort() + "/calls");
            configuration.setApiKey("synthetic-api-key");
            configuration.setSystemID(1);
            broadcaster = new BroadcastifyCallBroadcaster(configuration, null, null, aliases);
            broadcaster.setBroadcastState(BroadcastState.CONNECTED);
        }

        private TrackedRecording recording(Path path)
        {
            return new TrackedRecording(path, evidence);
        }

        private void releaseResponses() throws InterruptedException
        {
            assertTrue(received.await(5, TimeUnit.SECONDS), "All requests must overlap before their responses release");
            respond.countDown();
        }

        private static void reply(HttpExchange exchange, String text) throws IOException
        {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try(var output = exchange.getResponseBody()) { output.write(bytes); }
        }

        @Override
        public void close()
        {
            respond.countDown();
            broadcaster.dispose();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }
}
