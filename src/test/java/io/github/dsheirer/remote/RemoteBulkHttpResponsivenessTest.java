/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.remote;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.configuration.ChannelConfigurationSnapshot;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.*;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.source.config.SourceConfigRemote;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.web.http.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No transport or receiver is started: only real local HTTP commands and private persisted settings are used. */
class RemoteBulkHttpResponsivenessTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void maximumExportSelectionSavesWithActivityWriterAndConfigurationOwnerHeld(@TempDir Path data) throws Exception
    {
        try(Fixture fixture = new Fixture(data))
        {
            List<Channel> channels = new ArrayList<>();
            for(int index = 0; index < RemoteWireMessages.MAXIMUM_FEEDS; index++) channels.add(fixture.channel("Export " + index, null));
            fixture.replace(channels);
            long revision = fixture.remote.snapshot().revision();
            try(var history = SdrTrunkDatabase.openHistory(fixture.manager.getDatabasePath());
                OwnerGate gate = new OwnerGate(fixture.manager))
            {
                history.createStatement().execute("BEGIN IMMEDIATE");
                try
                {
                    Map<String,Object> request = Map.of("revision", revision, "enabled", false,
                        "destination_host", "", "destination_port", 35300, "sender_id", UUID.randomUUID().toString(),
                        "exported_channel_configuration_ids", channels.stream().map(Channel::getConfigurationId).toList());
                    var saved = fixture.send("PUT", RemoteLinksHttpController.PATH + "/sender-connection", request);
                    assertEquals(200, saved.statusCode(), saved.body());
                    assertEquals(RemoteWireMessages.MAXIMUM_FEEDS, JSON.readTree(saved.body()).at("/data/sender_connection/exported_channel_configuration_ids").size());
                    fixture.assertReads(RemoteWireMessages.MAXIMUM_FEEDS);
                    assertEquals(RemoteWireMessages.MAXIMUM_FEEDS, new RemoteLinkSettingsStore(fixture.manager.getDatabasePath()).load()
                        .outbound().exportedChannelIds().size());
                    assertFalse(saved.body().contains("secret\""));
                }
                finally { history.createStatement().execute("ROLLBACK"); }
            }
        }
    }

    @Test
    void waitingFeedEditDoesNotHoldRemoteStatusMonitorOrExposeUnsavedChannel(@TempDir Path data) throws Exception
    {
        try(Fixture fixture = new Fixture(data))
        {
            String sender = fixture.remote.createSender(fixture.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Saved sender")).senderId();
            Channel channel = fixture.channel("Saved remote channel", sender);
            fixture.replace(List.of(channel));
            String feed = ((SourceConfigRemote)channel.getSourceConfiguration()).getFeedId();
            long before = fixture.remote.snapshot().revision();
            try(OwnerGate gate = new OwnerGate(fixture.manager))
            {
                var edit = fixture.sendAsync("PUT", RemoteLinksHttpController.PATH + "/senders/" + sender +
                    "/feeds/" + feed, Map.of("revision", before, "display_name", "Updated remote channel",
                        "alias_list_id", fixture.p25List, "enabled", false));
                // updateFeed persists its command revision before asking the held owner to update the channel.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while(new RemoteLinkSettingsStore(fixture.manager.getDatabasePath()).load().revision() == before &&
                    System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(before + 1, new RemoteLinkSettingsStore(fixture.manager.getDatabasePath()).load().revision());
                assertFalse(edit.isDone());
                fixture.assertReads(1);
                var status = fixture.send("GET", RemoteLinksHttpController.PATH, null);
                assertTrue(status.body().contains("Saved remote channel"), status.body());
                assertFalse(status.body().contains("Updated remote channel"), status.body());
                gate.release();
                var result = edit.get(5, TimeUnit.SECONDS);
                assertEquals(200, result.statusCode(), result.body());
                assertEquals("Updated remote channel", fixture.manager.getChannelModel().getChannels().getFirst().getName());
            }
        }
    }

    @Test
    void senderDeletionRemovesEverySavedFeedWithoutActivityAndKeepsOtherSender(@TempDir Path data) throws Exception
    {
        try(Fixture fixture = new Fixture(data))
        {
            String removed = fixture.remote.createSender(fixture.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Remove sender")).senderId();
            String kept = fixture.remote.createSender(fixture.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Keep sender")).senderId();
            List<Channel> channels = new ArrayList<>();
            for(int index = 0; index < 128; index++) channels.add(fixture.channel("Removed feed " + index, removed));
            channels.add(fixture.channel("Retained feed", kept));
            fixture.replace(channels);
            try(var history = SdrTrunkDatabase.openHistory(fixture.manager.getDatabasePath());
                OwnerGate gate = new OwnerGate(fixture.manager))
            {
                history.createStatement().execute("BEGIN IMMEDIATE");
                try
                {
                    var deleted = fixture.sendAsync("DELETE", RemoteLinksHttpController.PATH + "/senders/" + removed,
                        Map.of("revision", fixture.remote.snapshot().revision()));
                    fixture.assertReads(129);
                    gate.release();
                    var result = deleted.get(8, TimeUnit.SECONDS);
                    assertEquals(200, result.statusCode(), result.body());
                    assertEquals(1, fixture.manager.getChannelModel().getChannels().size());
                    assertEquals("Retained feed", fixture.manager.getChannelModel().getChannels().getFirst().getName());
                    assertEquals(List.of(kept), new RemoteLinkSettingsStore(fixture.manager.getDatabasePath()).load()
                        .trustedSenders().stream().map(RemoteLinkSettingsStore.TrustedSender::senderId).toList());
                }
                finally { history.createStatement().execute("ROLLBACK"); }
            }
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final ConfigurationManager manager;
        final RemoteConnectivityService remote;
        final long p25List;
        final HttpServer server;
        final ExecutorService workers = Executors.newFixedThreadPool(6);
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final URI origin;
        Fixture(Path data) throws Exception
        {
            SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(data));
            DirectoryPreference directory = new DirectoryPreference(ignored -> {})
            { @Override public Path getDirectoryApplicationRoot() { return data; } };
            UserPreferences preferences = new UserPreferences()
            { @Override public DirectoryPreference getDirectoryPreference() { return directory; } };
            manager = new ConfigurationManager(preferences, null, new AliasModel(), null);
            manager.init();
            p25List = manager.getAliasModel().aliasListDefinitions().stream()
                .filter(list -> list.getFamily() == AliasListFamily.P25).findFirst().orElseThrow().getId();
            var channels = ChannelAdministrationServiceTestSupport.create(manager);
            remote = new RemoteConnectivityService(manager, channels);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext(RemoteLinksHttpController.PATH, new RemoteLinksHttpController(remote)::handle);
            var channelController = new ChannelAdminHttpController(channels);
            server.createContext(ChannelAdminHttpController.READ_PATH, channelController::handleCatalog);
            server.createContext(ChannelAdminHttpController.PATH, channelController::handle);
            server.start();
            origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }
        Channel channel(String name, String sender)
        {
            Channel channel = new Channel(name);
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            channel.setAliasListDefinition(manager.getAliasModel().getAliasListDefinition(p25List));
            if(sender == null)
            {
                SourceConfigTuner source = new SourceConfigTuner(); source.setFrequency(851_012_500L);
                channel.setSourceConfiguration(source);
            }
            else
            {
                SourceConfigRemote source = new SourceConfigRemote(); source.setSenderId(sender);
                source.setFeedId(UUID.randomUUID().toString()); source.setFrequency(851_012_500L);
                channel.setSourceConfiguration(source);
            }
            return channel;
        }
        void replace(List<Channel> channels)
        {
            manager.commitAndPublishChannelConfiguration(new ChannelConfigurationSnapshot(channels),
                channels.stream().map(Channel::getConfigurationId).collect(java.util.stream.Collectors.toSet()), false);
        }
        HttpResponse<String> send(String method, String path, Object body) throws Exception
        { return sendAsync(method, path, body).get(3, TimeUnit.SECONDS); }
        CompletableFuture<HttpResponse<String>> sendAsync(String method, String path, Object body) throws Exception
        {
            var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(12));
            if(body != null) request.header("Content-Type", "application/json");
            return client.sendAsync(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        void assertReads(int count) throws Exception
        {
            for(String path: List.of(RemoteLinksHttpController.PATH, ChannelAdminHttpController.READ_PATH,
                ChannelAdminHttpController.PATH + "/options"))
            {
                var response = send("GET", path, null);
                assertEquals(200, response.statusCode(), response.body());
                if(path.equals(ChannelAdminHttpController.READ_PATH))
                    assertEquals(count, JSON.readTree(response.body()).at("/data/channels").size());
            }
        }
        @Override public void close()
        {
            server.stop(0); workers.shutdownNow(); remote.close();
            manager.quiesceAdministration(); manager.getChannelProcessingManager().close();
            manager.getBroadcastModel().close(); manager.close(); manager.getRadioReferenceDirectoryService().close();
            client.close();
        }
    }
    private static final class OwnerGate implements AutoCloseable
    {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CompletableFuture<Void> held;
        OwnerGate(ConfigurationManager manager) throws Exception
        {
            CountDownLatch started = new CountDownLatch(1);
            held = manager.submitConfigurationTask(() -> {
                started.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
        }
        void release() { release.countDown(); }
        @Override public void close() throws Exception { release(); held.get(3, TimeUnit.SECONDS); }
    }
}
