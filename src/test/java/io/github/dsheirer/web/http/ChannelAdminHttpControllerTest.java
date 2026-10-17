/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.remote.P25RemoteBitstreamService;
import io.github.dsheirer.remote.P25RemotePhase;
import io.github.dsheirer.remote.RemoteP25BitstreamSource;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.config.SourceConfigRemote;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
            long aliasListId = aliases.createAliasList("County P25", AliasListFamily.P25).aliasListId();
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

            String create = """
                {"protocol_id":"p25-conventional","system":"County","site":"Central",
                 "name":"Dispatch","alias_list_id":%d,"source":{"frequencies_hz":[155010000]},
                 "settings":{"modulation":"CQPSK"},"frequency_map":[],"event_logs":[],
                 "recorders":[],"auxiliary_decoders":[]}
                """.formatted(aliasListId);
            HttpResponse<String> created = sendJson(client, origin.resolve(ChannelAdminHttpController.PATH),
                "POST", create);
            assertEquals(201, created.statusCode(), created.body());
            JsonNode createdData = MAPPER.readTree(created.body()).path("data");
            String channelId = createdData.path("configuration_ids").get(0).textValue();
            long revision = createdData.path("revision").longValue();

            HttpResponse<String> entry = client.send(HttpRequest.newBuilder(
                origin.resolve(ChannelAdminHttpController.PATH + "/" + channelId)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, entry.statusCode());
            JsonNode originalEntry = MAPPER.readTree(entry.body()).path("data");
            assertEquals(64, originalEntry.path("edit_revision").asText().length());
            assertEquals("CQPSK", originalEntry.path("channel")
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
                {"protocol_id":"p25-phase1","system":"County","site":"Remote",
                 "name":"Remote Control","alias_list_id":%d,
                 "source":{"frequencies_hz":[851012500],"source_type":"REMOTE",
                           "sender_id":"%s","feed_id":"%s"},
                 "settings":{},"frequency_map":[],"event_logs":[],
                 "recorders":[],"auxiliary_decoders":[]}
                """.formatted(aliasListId, senderId, feedId);
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
                {"protocol_id":"p25-phase1","system":"County","site":"Remote",
                 "name":"Duplicate Remote Control","alias_list_id":%d,
                 "source":%s,"settings":{},"frequency_map":[],"event_logs":[],
                 "recorders":[],"auxiliary_decoders":[]}
                """.formatted(aliasListId,
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

            String remoteEditRevision = channels.get(remoteId).editRevision();
            String[] changedSources = {
                "{\"frequencies_hz\":[851012500]}",
                remoteSource.replace(senderId, "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"),
                remoteSource.replace(feedId, "22222222-3333-4444-8555-666666666666"),
                remoteSource.replace("851012500", "852012500")
            };
            for(String changedSource: changedSources)
            {
                String changedSourceUpdate = """
                    {"edit_revision":"%s","protocol_id":"p25-phase1","system":"County","site":"Remote",
                     "name":"Changed Source","alias_list_id":%d,"source":%s,
                     "settings":{},"frequency_map":[],"event_logs":[],"recorders":[],
                     "auxiliary_decoders":[]}
                    """.formatted(remoteEditRevision, aliasListId, changedSource);
                HttpResponse<String> rejected = sendJson(client, remoteUri, "PUT", changedSourceUpdate);
                assertEquals(400, rejected.statusCode(), rejected.body());
                assertTrue(rejected.body().contains("Remote channel source cannot be changed"));
                assertEquals(remoteRevision, channels.currentRevision());
            }

            String remoteSettingsUpdate = """
                {"edit_revision":"%s","protocol_id":"p25-phase1","system":"County","site":"Remote",
                 "name":"Remote Control Updated","alias_list_id":%d,"source":%s,
                 "settings":{"traffic_channel_pool_size":4},"frequency_map":[],"event_logs":[],
                 "recorders":[],"auxiliary_decoders":[]}
                """.formatted(remoteEditRevision, aliasListId, remoteSource);
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

            ObjectNode independentUpdate = editRequest(originalEntry);
            independentUpdate.put("name", "Dispatch updated after remote edits");
            HttpResponse<String> independentSaved = sendJson(client,
                origin.resolve(ChannelAdminHttpController.PATH + "/" + channelId), "PUT", independentUpdate.toString());
            assertEquals(200, independentSaved.statusCode(), independentSaved.body());
            ObjectNode conflictingUpdate = editRequest(originalEntry);
            conflictingUpdate.put("name", "Older dispatch editor");
            HttpResponse<String> conflicting = sendJson(client,
                origin.resolve(ChannelAdminHttpController.PATH + "/" + channelId), "PUT", conflictingUpdate.toString());
            assertEquals(409, conflicting.statusCode(), conflicting.body());
            assertTrue(conflicting.body().contains("stale_channel"));
            assertEquals("Dispatch updated after remote edits", channels.get(channelId).channel().name());
            ObjectNode missingVersion = editRequest(originalEntry); missingVersion.remove("edit_revision");
            assertEquals(400, sendJson(client, origin.resolve(ChannelAdminHttpController.PATH + "/" + channelId),
                "PUT", missingVersion.toString()).statusCode());

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
    void originalSettingsRoundTripPreservesControlChannelLearningDuringAnUnrelatedNameEdit() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("channel-http-learning");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);
        HttpServer server = null;
        ExecutorService executor = Executors.newCachedThreadPool();
        try
        {
            manager.init();
            AliasAdministrationService aliases = AliasAdministrationServiceTestSupport.create(manager);
            long aliasListId = aliases.createAliasList("County P25", AliasListFamily.P25).aliasListId();
            ChannelAdministrationService channels = ChannelAdministrationServiceTestSupport.create(manager);
            ChannelAdminHttpController controller = new ChannelAdminHttpController(channels);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.setExecutor(executor);
            server.createContext(ChannelAdminHttpController.PATH, controller::handle);
            server.start();
            URI collection = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + ChannelAdminHttpController.PATH);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String create = """
                {"protocol_id":"p25-phase1","system":"County","site":"Central","name":"Control",
                 "alias_list_id":%d,"source":{"frequencies_hz":[851012500,852012500]},
                 "settings":{"learn_announced_control_channels":true},"frequency_map":[],"event_logs":[],
                 "recorders":[],"auxiliary_decoders":[]}
                """.formatted(aliasListId);
            HttpResponse<String> created = sendJson(client, collection, "POST", create);
            assertEquals(201, created.statusCode(), created.body());
            String id = MAPPER.readTree(created.body()).at("/data/configuration_ids/0").asText();
            URI item = URI.create(collection + "/" + id);
            JsonNode originalEntry = readEntry(client, item);
            Channel channel = manager.getChannelModel().getChannels().getFirst();
            ((SourceConfigTunerMultipleFrequency)channel.getSourceConfiguration()).addFrequency(853_012_500L);
            ((DecodeConfigP25)channel.getDecodeConfiguration()).addLearnedControlFrequency(853_012_500L);
            P25SiteIdentity identity = new P25SiteIdentity(0xBEE00, 0x123, 1, 2);
            channel.setP25SiteIdentity(identity);
            assertNotEquals(originalEntry.path("edit_revision").asText(), channels.get(id).editRevision());
            ObjectNode tampered = editRequest(originalEntry);
            ((ObjectNode)tampered.path("original")).put("name", "Forged original");
            assertEquals(400, sendJson(client, item, "PUT", tampered.toString()).statusCode());
            ObjectNode update = editRequest(originalEntry); update.put("name", "County control");
            HttpResponse<String> saved = sendJson(client, item, "PUT", update.toString());
            assertEquals(200, saved.statusCode(), saved.body());
            JsonNode current = readEntry(client, item).path("channel");
            assertEquals("County control", current.path("name").asText());
            assertEquals(3, current.at("/source/frequencies_hz").size());
            assertEquals(853_012_500L, current.at("/observed/learned_control_frequencies_hz/0").asLong());
            assertEquals(2, current.at("/observed/p25_site_identity/site").asInt());
            Channel persisted = new ConfigurationDatabaseStore(database).load().channels().getFirst();
            assertEquals(identity, persisted.getP25SiteIdentity());
            assertEquals(List.of(853_012_500L), ((DecodeConfigP25)persisted.getDecodeConfiguration()).getLearnedControlFrequencies());
        }
        finally
        {
            if(server != null) server.stop(0);
            executor.shutdownNow();
            try { manager.flushConfiguration(); }
            finally { MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
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
            long aliasListId = aliases.createAliasList("County P25", AliasListFamily.P25).aliasListId();
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
                {"protocol_id":"p25-phase1","system":"County","site":"Remote",
                 "name":"Remote Control","alias_list_id":%d,"source":%s,
                 "settings":{},"frequency_map":[],"event_logs":[],"recorders":[],
                 "auxiliary_decoders":[]}
                """.formatted(aliasListId, source);
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
            assertEquals(ChannelAdministrationService.ProcessingState.RUNNING,
                channels.get(channelId).processingState());
            URI channelUri = origin.resolve(ChannelAdminHttpController.PATH + "/" + channelId);
            JsonNode runningEntry = readEntry(client, channelUri);
            ObjectNode invalidUpdate = editRequest(runningEntry);
            invalidUpdate.put("name", "Changed Source");
            ((ObjectNode)invalidUpdate.path("source")).put("sender_id", "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff");
            HttpResponse<String> rejected = sendJson(client, channelUri, "PUT", invalidUpdate.toString());
            assertEquals(400, rejected.statusCode(), rejected.body());
            assertTrue(rejected.body().contains("Remote channel source cannot be changed"));
            assertEquals(runningEntry.path("edit_revision").asText(), channels.get(channelId).editRevision());
            assertSame(saved, manager.getChannelModel().getChannels().stream()
                .filter(channel -> channelId.equals(channel.getConfigurationId())).findFirst().orElseThrow());
            assertSame(originalChain,
                processing.getProcessingChainsByConfiguration(channelId, null).getFirst());
            assertEquals(0, stopNotifications.get());
            assertEquals(ChannelAdministrationService.ProcessingState.RUNNING,
                channels.get(channelId).processingState());
            assertEquals(senderId, ((SourceConfigRemote)saved.getSourceConfiguration()).getSenderId());

            ObjectNode validUpdate = editRequest(runningEntry);
            validUpdate.put("name", "Updated running remote channel");
            validUpdate.put("allow_restart", false);
            HttpResponse<String> confirmationRequired = sendJson(client, channelUri, "PUT", validUpdate.toString());
            assertEquals(409, confirmationRequired.statusCode(), confirmationRequired.body());
            assertTrue(confirmationRequired.body().contains("restart_required"));
            assertSame(originalChain, processing.getProcessingChainsByConfiguration(channelId, null).getFirst());
            assertEquals(0, stopNotifications.get());
            assertEquals("Remote Control", channels.get(channelId).channel().name());

            validUpdate.put("allow_restart", true);
            HttpResponse<String> updated = sendJson(client, channelUri, "PUT", validUpdate.toString());
            assertEquals(200, updated.statusCode(), updated.body());
            assertEquals("Updated running remote channel", channels.get(channelId).channel().name());
            assertEquals(ChannelAdministrationService.ProcessingState.RUNNING, channels.get(channelId).processingState());
            assertEquals(1, stopNotifications.get(), "consented save stops the original receiver once");
            assertNotSame(originalChain, processing.getProcessingChainsByConfiguration(channelId, null).getFirst());

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

    private static JsonNode readEntry(HttpClient client, URI uri) throws Exception
    {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body()).path("data");
    }

    private static ObjectNode editRequest(JsonNode entry)
    {
        ObjectNode request = ((ObjectNode)entry.path("channel")).deepCopy();
        request.remove(List.of("configuration_id", "observed"));
        request.put("edit_revision", entry.path("edit_revision").asText());
        request.set("original", entry.path("channel").deepCopy());
        return request;
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
