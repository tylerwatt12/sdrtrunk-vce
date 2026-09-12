/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
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
            ChannelAdminHttpController controller = new ChannelAdminHttpController(channels);
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

            HttpResponse<String> move = sendJson(client, origin.resolve(ChannelAdminHttpController.PATH + "/" +
                channelId + "/auto-start/move"), "POST",
                "{\"revision\":" + revision + ",\"direction\":\"EARLIER\"}");
            assertEquals(200, move.statusCode(), move.body());
            assertEquals(1, channels.get(channelId).autoStartOrder());

            HttpResponse<String> unknown = sendJson(client, origin.resolve(ChannelAdminHttpController.PATH),
                "POST", create.substring(0, create.lastIndexOf('}')) + ",\"unexpected\":true}");
            assertEquals(400, unknown.statusCode());
            assertTrue(unknown.body().contains("invalid_request"));
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
