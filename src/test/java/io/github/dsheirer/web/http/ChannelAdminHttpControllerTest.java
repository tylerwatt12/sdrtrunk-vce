/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.remote.P25RemoteBitstreamService;
import io.github.dsheirer.remote.P25RemotePhase;
import io.github.dsheirer.remote.RemoteP25BitstreamSource;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.config.SourceConfigRemote;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelAdminHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void servesProtocolCatalogAndStrictPersistentChannelCommands() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("channel-http-data");
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);
        HttpServer server = null;
        ExecutorService executor = Executors.newCachedThreadPool();
        try
        {
            manager.init();
            AliasAdministrationService aliases = AliasAdministrationServiceTestSupport.create(manager);
            long aliasListId = aliases.createAliasList("County P25", AliasListFamily.P25,
                aliases.currentRevision()).aliasListId();
            ChannelAdministrationService channels = ChannelAdministrationServiceTestSupport.create(manager);
            ChannelAdminHttpController controller = new ChannelAdminHttpController(channels, () -> true);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.setExecutor(executor);
            server.createContext(ChannelAdminHttpController.PATH, controller::handle);
            server.createContext(ChannelAdminHttpController.READ_PATH, controller::handleCatalog);
            server.start();
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

            HttpResponse<String> protocols = client.send(HttpRequest.newBuilder(
                origin.resolve(ChannelAdminHttpController.PROTOCOLS_PATH)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, protocols.statusCode());
            assertTrue(protocols.body().contains("\"value\":\"CQPSK\",\"label\":\"CQPSK\""));
            assertTrue(MAPPER.readTree(protocols.body()).path("data")
                .path("voice_decryption_module_loaded").booleanValue());

            long revision = channels.currentRevision();
            String create = """
                {"revision":%d,"protocol_id":"p25-conventional","system":"County","site":"Central",
                 "name":"Dispatch","alias_list_id":%d,"source":{"frequencies_hz":[155010000]},
                 "settings":{"modulation":"CQPSK"},"frequency_map":[],"event_logs":[],
                 "recorders":[],"auxiliary_decoders":[]}
                """.formatted(revision, aliasListId);
            HttpResponse<String> created = sendJson(client, origin.resolve(ChannelAdminHttpController.PATH),
                "POST", create);
            assertEquals(201, created.statusCode(), created.body());
            JsonNode createdData = MAPPER.readTree(created.body()).path("data");
            String channelId = createdData.path("configuration_ids").get(0).textValue();
            revision = createdData.path("revision").longValue();

            HttpResponse<String> entry = client.send(HttpRequest.newBuilder(
                origin.resolve(ChannelAdminHttpController.PATH + "/" + channelId)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, entry.statusCode());
            assertEquals("CQPSK", MAPPER.readTree(entry.body()).path("data").path("channel")
                .path("settings").path("modulation").textValue());

            HttpResponse<String> readOnlyCatalog = client.send(HttpRequest.newBuilder(
                origin.resolve(ChannelAdminHttpController.READ_PATH)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, readOnlyCatalog.statusCode());
            assertEquals("Dispatch", MAPPER.readTree(readOnlyCatalog.body()).path("data").path("channels")
                .get(0).path("name").textValue());
            assertTrue(MAPPER.readTree(readOnlyCatalog.body()).path("data").path("channels")
                .get(0).path("editable").booleanValue());

            HttpResponse<String> stoppedPreview = sendJson(client,
                origin.resolve(ChannelAdminHttpController.PATH + "/" + channelId + "/squelch-preview"), "POST",
                "{\"action\":\"APPLY\",\"noise_open\":0.1,\"noise_close\":0.19," +
                    "\"hysteresis_open\":4,\"hysteresis_close\":6}");
            assertEquals(409, stoppedPreview.statusCode(), stoppedPreview.body());
            assertTrue(stoppedPreview.body().contains("not currently running"));

            HttpResponse<String> move = sendJson(client, origin.resolve(ChannelAdminHttpController.PATH + "/" +
                channelId + "/auto-start/move"), "POST",
                "{\"revision\":" + revision + ",\"direction\":\"EARLIER\"}");
            assertEquals(200, move.statusCode(), move.body());
            assertEquals(1, channels.get(channelId).autoStartOrder());

            revision = MAPPER.readTree(move.body()).path("data").path("revision").longValue();
            HttpResponse<String> autoStartDisabled = sendJson(client,
                origin.resolve(ChannelAdminHttpController.ACTIONS_PATH), "POST",
                "{\"revision\":" + revision + ",\"action\":\"DISABLE_AUTO_START\"," +
                    "\"configuration_ids\":[\"" + channelId + "\"]}");
            assertEquals(200, autoStartDisabled.statusCode(), autoStartDisabled.body());
            assertEquals(1, MAPPER.readTree(autoStartDisabled.body()).path("data").path("affected").asInt());
            assertNull(channels.get(channelId).autoStartOrder());

            revision = MAPPER.readTree(autoStartDisabled.body()).path("data").path("revision").longValue();
            HttpResponse<String> autoStartEnabled = sendJson(client,
                origin.resolve(ChannelAdminHttpController.ACTIONS_PATH), "POST",
                "{\"revision\":" + revision + ",\"action\":\"ENABLE_AUTO_START\"," +
                    "\"configuration_ids\":[\"" + channelId + "\"]}");
            assertEquals(200, autoStartEnabled.statusCode(), autoStartEnabled.body());
            assertEquals(1, channels.get(channelId).autoStartOrder());

            HttpResponse<String> unknown = sendJson(client, origin.resolve(ChannelAdminHttpController.PATH),
                "POST", create.substring(0, create.lastIndexOf('}')) + ",\"unexpected\":true}");
            assertEquals(400, unknown.statusCode());
            assertTrue(unknown.body().contains("invalid_request"));

            String senderId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
            String feedId = "11111111-2222-4333-8444-555555555555";
            String remoteCreate = """
                {"revision":%d,"protocol_id":"p25-phase1","system":"County","site":"Remote",
                 "name":"Remote Control","alias_list_id":%d,
                 "source":{"frequencies_hz":[851012500],"source_type":"REMOTE",
                           "sender_id":"%s","feed_id":"%s"},
                 "settings":{},"frequency_map":[],"event_logs":[],
                 "recorders":[],"auxiliary_decoders":[]}
                """.formatted(channels.currentRevision(), aliasListId, senderId, feedId);
            HttpResponse<String> remoteCreated = sendJson(client, origin.resolve(ChannelAdminHttpController.PATH),
                "POST", remoteCreate);
            assertEquals(201, remoteCreated.statusCode(), remoteCreated.body());
            JsonNode remoteCreatedData = MAPPER.readTree(remoteCreated.body()).path("data");
            String remoteId = remoteCreatedData.path("configuration_ids").get(0).textValue();
            long remoteRevision = remoteCreatedData.path("revision").longValue();
            URI remoteUri = origin.resolve(ChannelAdminHttpController.PATH + "/" + remoteId);
            String remoteSource = """
                {"frequencies_hz":[851012500],"source_type":"REMOTE",
                 "sender_id":"%s","feed_id":"%s"}
                """.formatted(senderId, feedId);
            String duplicateRemoteCreate = """
                {"revision":%d,"protocol_id":"p25-phase1","system":"County","site":"Remote",
                 "name":"Duplicate Remote Control","alias_list_id":%d,
                 "source":%s,"settings":{},"frequency_map":[],"event_logs":[],
                 "recorders":[],"auxiliary_decoders":[]}
                """.formatted(remoteRevision, aliasListId,
                    remoteSource.replace("851012500", "852012500"));
            HttpResponse<String> duplicateCreated = sendJson(client,
                origin.resolve(ChannelAdminHttpController.PATH), "POST", duplicateRemoteCreate);
            assertEquals(400, duplicateCreated.statusCode(), duplicateCreated.body());
            assertTrue(duplicateCreated.body().contains("already configured for this sender and feed"));
            assertEquals(remoteRevision, channels.currentRevision());

            HttpResponse<String> duplicateCloned = sendJson(client,
                origin.resolve(ChannelAdminHttpController.ACTIONS_PATH), "POST",
                "{\"revision\":" + remoteRevision + ",\"action\":\"CLONE\",\"configuration_ids\":[\"" +
                    remoteId + "\"]}");
            assertEquals(400, duplicateCloned.statusCode(), duplicateCloned.body());
            assertTrue(duplicateCloned.body().contains("already configured for this sender and feed"));
            assertEquals(remoteRevision, channels.currentRevision());

            String[] changedSources = {
                "{\"frequencies_hz\":[851012500]}",
                remoteSource.replace(senderId, "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"),
                remoteSource.replace(feedId, "22222222-3333-4444-8555-666666666666"),
                remoteSource.replace("851012500", "852012500")
            };
            for(String changedSource: changedSources)
            {
                String changedSourceUpdate = """
                    {"revision":%d,"protocol_id":"p25-phase1","system":"County","site":"Remote",
                     "name":"Changed Source","alias_list_id":%d,"source":%s,
                     "settings":{},"frequency_map":[],"event_logs":[],"recorders":[],
                     "auxiliary_decoders":[]}
                    """.formatted(remoteRevision, aliasListId, changedSource);
                HttpResponse<String> rejected = sendJson(client, remoteUri, "PUT", changedSourceUpdate);
                assertEquals(400, rejected.statusCode(), rejected.body());
                assertTrue(rejected.body().contains("Remote channel source cannot be changed"));
                assertEquals(remoteRevision, channels.currentRevision());
            }

            String remoteSettingsUpdate = """
                {"revision":%d,"protocol_id":"p25-phase1","system":"County","site":"Remote",
                 "name":"Remote Control Updated","alias_list_id":%d,"source":%s,
                 "settings":{"traffic_channel_pool_size":4},"frequency_map":[],"event_logs":[],
                 "recorders":[],"auxiliary_decoders":[]}
                """.formatted(remoteRevision, aliasListId, remoteSource);
            HttpResponse<String> remoteUpdated = sendJson(client, remoteUri, "PUT", remoteSettingsUpdate);
            assertEquals(200, remoteUpdated.statusCode(), remoteUpdated.body());
            HttpResponse<String> remoteEntry = client.send(HttpRequest.newBuilder(remoteUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, remoteEntry.statusCode(), remoteEntry.body());
            JsonNode savedRemote = MAPPER.readTree(remoteEntry.body()).path("data").path("channel");
            assertEquals("Remote Control Updated", savedRemote.path("name").textValue());
            assertEquals(4, savedRemote.path("settings").path("traffic_channel_pool_size").intValue());
            assertEquals("REMOTE", savedRemote.path("source").path("source_type").textValue());
            assertEquals(senderId, savedRemote.path("source").path("sender_id").textValue());
            assertEquals(feedId, savedRemote.path("source").path("feed_id").textValue());
            assertEquals(851_012_500L, savedRemote.path("source").path("frequencies_hz").get(0).longValue());

            HttpResponse<String> catalogWithRemote = client.send(HttpRequest.newBuilder(
                origin.resolve(ChannelAdminHttpController.READ_PATH)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, catalogWithRemote.statusCode());
            JsonNode remoteRows = MAPPER.readTree(catalogWithRemote.body()).path("data").path("channels");
            assertEquals(2, remoteRows.size());
            assertFalse(remoteRows.get(0).path("remote_origin").path("remote").asBoolean());
            assertTrue(remoteRows.get(1).path("remote_origin").path("remote").asBoolean());
            assertFalse(catalogWithRemote.body().contains(senderId));
            assertFalse(catalogWithRemote.body().contains(feedId));
        }
        finally
        {
            if(server != null) server.stop(0);
            executor.shutdownNow();
            try
            {
                manager.flushConfiguration();
            }
            finally
            {
                MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
            }
        }
    }

    @Test
    void rejectedRemoteSourceEditDoesNotStopRunningChannel() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("running-remote-channel-http-data");
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        TestUserPreferences preferences = new TestUserPreferences(dataRoot);
        AliasModel aliasModel = new AliasModel();
        ConfigurationManager manager = new ConfigurationManager(preferences, null, aliasModel,
            new EventLogManager(aliasModel, preferences), null);
        ChannelProcessingManager processing = manager.getChannelProcessingManager();
        AtomicInteger stopNotifications = new AtomicInteger();
        HttpServer server = null;
        ExecutorService executor = Executors.newCachedThreadPool();
        try
        {
            manager.init();
            AliasAdministrationService aliases = AliasAdministrationServiceTestSupport.create(manager);
            long aliasListId = aliases.createAliasList("County P25", AliasListFamily.P25,
                aliases.currentRevision()).aliasListId();
            ChannelAdministrationService channels = ChannelAdministrationServiceTestSupport.create(manager);
            processing.setP25RemoteBitstreamService(new P25RemoteBitstreamService()
            {
                @Override
                public Source acquireSource(Channel channel, String threadName)
                {
                    SourceConfigRemote remote = (SourceConfigRemote)channel.getSourceConfiguration();
                    return new RemoteP25BitstreamSource(remote.getFrequency(), P25RemotePhase.PHASE_1, threadName);
                }

                @Override
                public void channelStarted(Channel channel, ChannelStartProcessingRequest request,
                                           ProcessingChain chain)
                {
                }

                @Override
                public void channelStopped(Channel channel, ProcessingChain chain)
                {
                    stopNotifications.incrementAndGet();
                }
            });
            ChannelAdminHttpController controller = new ChannelAdminHttpController(channels, () -> true);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.setExecutor(executor);
            server.createContext(ChannelAdminHttpController.PATH, controller::handle);
            server.start();
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

            String senderId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
            String feedId = "11111111-2222-4333-8444-555555555555";
            String source = """
                {"frequencies_hz":[851012500],"source_type":"REMOTE",
                 "sender_id":"%s","feed_id":"%s"}
                """.formatted(senderId, feedId);
            String create = """
                {"revision":%d,"protocol_id":"p25-phase1","system":"County","site":"Remote",
                 "name":"Remote Control","alias_list_id":%d,"source":%s,
                 "settings":{},"frequency_map":[],"event_logs":[],"recorders":[],
                 "auxiliary_decoders":[]}
                """.formatted(channels.currentRevision(), aliasListId, source);
            HttpResponse<String> created = sendJson(client, origin.resolve(ChannelAdminHttpController.PATH),
                "POST", create);
            assertEquals(201, created.statusCode(), created.body());
            JsonNode createdData = MAPPER.readTree(created.body()).path("data");
            String channelId = createdData.path("configuration_ids").get(0).textValue();
            Channel saved = manager.getChannelModel().getChannels().stream()
                .filter(channel -> channelId.equals(channel.getConfigurationId())).findFirst().orElseThrow();
            processing.start(saved);
            ProcessingChain originalChain =
                processing.getProcessingChainsByConfiguration(channelId, null).getFirst();
            long revision = channels.currentRevision();
            assertEquals(ChannelAdministrationService.ProcessingState.RUNNING,
                channels.get(channelId).processingState());

            String invalidUpdateTemplate = """
                {"revision":%d,"protocol_id":"p25-phase1","system":"County","site":"Remote",
                 "name":"Changed Source","alias_list_id":%d,"source":%s,
                 "settings":{},"frequency_map":[],"event_logs":[],"recorders":[],
                 "auxiliary_decoders":[]}
                """;
            HttpResponse<String> rejected = null;
            for(int attempt = 0; attempt < 3; attempt++)
            {
                revision = channels.currentRevision();
                String invalidUpdate = invalidUpdateTemplate.formatted(revision, aliasListId,
                    source.replace(senderId, "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"));
                rejected = sendJson(client, origin.resolve(ChannelAdminHttpController.PATH + "/" + channelId),
                    "PUT", invalidUpdate);
                // Processing-start notifications may finish after the initial revision was read.
                if(rejected.statusCode() != 409) break;
            }
            assertEquals(400, rejected.statusCode(), rejected.body());
            assertTrue(rejected.body().contains("Remote channel source cannot be changed"));
            assertEquals(revision, channels.currentRevision());
            assertSame(saved, manager.getChannelModel().getChannels().stream()
                .filter(channel -> channelId.equals(channel.getConfigurationId())).findFirst().orElseThrow());
            assertSame(originalChain,
                processing.getProcessingChainsByConfiguration(channelId, null).getFirst());
            assertEquals(0, stopNotifications.get());
            assertEquals(ChannelAdministrationService.ProcessingState.RUNNING,
                channels.get(channelId).processingState());
            assertEquals(senderId, ((SourceConfigRemote)saved.getSourceConfiguration()).getSenderId());
        }
        finally
        {
            if(server != null) server.stop(0);
            executor.shutdownNow();
            try
            {
                processing.close();
                manager.flushConfiguration();
            }
            finally
            {
                MyEventBus.getGlobalEventBus().unregister(processing);
            }
        }
    }

    private static HttpResponse<String> sendJson(HttpClient client, URI uri, String method, String body)
        throws Exception
    {
        return client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class TestUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(Path dataRoot)
        {
            mDirectoryPreference = new DirectoryPreference(preferenceType -> {})
            {
                @Override
                public Path getDirectoryApplicationRoot()
                {
                    return dataRoot;
                }
            };
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }
}
