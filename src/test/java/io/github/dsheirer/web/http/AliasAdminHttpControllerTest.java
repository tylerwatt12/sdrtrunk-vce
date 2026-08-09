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
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasAdminHttpControllerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void rejectsUnknownJsonAndCreatesUpdatesAndDeletesAnAlias() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);
        manager.init();
        BroadcastifyCallConfiguration primary = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
        primary.setName("Primary");
        manager.getBroadcastModel().addBroadcastConfiguration(primary);
        AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
        AtomicInteger aliasChanges = new AtomicInteger();
        AliasAdminHttpController controller = new AliasAdminHttpController(service, aliasChanges::incrementAndGet);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(AliasAdminHttpController.ALIAS_LISTS_PATH, controller::handle);
        server.createContext(AliasAdminHttpController.ALIASES_PATH, controller::handle);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            JsonNode catalog = json(send(client, request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET()));
            long revision = catalog.get("revision").longValue();
            JsonNode createdList = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "name", "County P25", "family", "P25"))))));
            long aliasListId = createdList.get("aliasListId").longValue();
            revision = createdList.get("revision").longValue();

            JsonNode policyChanged = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + aliasListId + "/unmatched-talkgroups")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "listenEnabled", false, "recordable", true,
                    "broadcastChannels", java.util.List.of("Primary")))))));
            revision = policyChanged.get("revision").longValue();
            JsonNode policyCatalog = json(send(client,
                request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET()));
            JsonNode policy = policyCatalog.at("/aliasLists/0/unmatchedTalkgroupPolicy");
            assertEquals(-1, policy.get("playbackPriority").intValue());
            assertTrue(policy.get("recordEnabled").booleanValue());
            assertEquals("Primary", policy.at("/streamDestinationNames/0").textValue());
            assertTrue(aliasChanges.get() >= 2);

            JsonNode defaultPolicyChanged = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + aliasListId + "/unmatched-talkgroups")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "listenEnabled", true, "priority", 100, "recordable", true,
                    "broadcastChannels", java.util.List.of("Primary")))))));
            revision = defaultPolicyChanged.get("revision").longValue();
            policyCatalog = json(send(client,
                request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET()));
            assertEquals(100,
                policyCatalog.at("/aliasLists/0/unmatchedTalkgroupPolicy/playbackPriority").intValue());

            JsonNode p25Options = json(send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?aliasListId=" + aliasListId).GET()));
            assertEquals("APCO25", matcher(p25Options, "TALKGROUP_RANGE").get("protocol").textValue());
            assertEquals("APCO25", matcher(p25Options, "RADIO_ID_RANGE").get("protocol").textValue());
            assertTrue(java.util.stream.StreamSupport.stream(p25Options.get("matchers").spliterator(), false)
                .noneMatch(node -> "P25_FULLY_QUALIFIED_TALKGROUP".equals(node.get("type").textValue())));

            for(Map<String,Object> matcher: java.util.List.<Map<String,Object>>of(
                Map.of("type", "TALKGROUP_RANGE", "protocol", "APCO25", "minimum", 200, "maximum", 210),
                Map.of("type", "RADIO_ID_RANGE", "protocol", "APCO25", "minimum", 300, "maximum", 310)))
            {
                Map<String,Object> candidate = new java.util.LinkedHashMap<>(alias(aliasListId,
                    "Matcher " + matcher.get("type"), false));
                candidate.put("matcher", matcher);
                JsonNode matcherCreated = json(send(client, jsonRequest(origin,
                    AliasAdminHttpController.ALIASES_PATH).POST(HttpRequest.BodyPublishers.ofString(
                    OBJECT_MAPPER.writeValueAsString(Map.of("revision", revision, "alias", candidate))))));
                revision = matcherCreated.get("revision").longValue();
            }

            Map<String,Object> aliasPayload = alias(aliasListId, "Dispatch", false);
            Map<String,Object> create = Map.of("revision", revision, "alias", aliasPayload);
            JsonNode created = json(send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(create)))));
            long aliasId = created.get("aliasIds").get(0).longValue();
            revision = created.get("revision").longValue();
            JsonNode live = json(send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId).GET()));
            assertEquals("Dispatch", live.at("/alias/name").textValue());
            assertEquals("TALKGROUP", live.at("/alias/matcher/type").textValue());
            assertTrue(live.at("/alias/listenEnabled").booleanValue());

            Map<String,Object> invalidAlias = new java.util.LinkedHashMap<>(alias(aliasListId, "Bad", false));
            invalidAlias.put("unexpected", true);
            Map<String,Object> invalid = Map.of("revision", revision, "alias", invalidAlias);
            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(invalid)))).statusCode());

            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIAS_LISTS_PATH)
                .POST(HttpRequest.BodyPublishers.ofString("{\"revision\":" + revision +
                    ",\"name\":\"Numeric Family\",\"family\":0}"))).statusCode());
            Map<String,Object> decimalAlias = new java.util.LinkedHashMap<>(aliasPayload);
            decimalAlias.put("matcher", Map.of("type", "TALKGROUP", "protocol", "APCO25", "value", 101.5));
            String decimalMatcher = OBJECT_MAPPER.writeValueAsString(
                Map.of("revision", revision, "alias", decimalAlias));
            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(decimalMatcher))).statusCode());
            Map<String,Object> retiredMatcher = new java.util.LinkedHashMap<>(aliasPayload);
            retiredMatcher.put("matcher", Map.of("type", "P25_FULLY_QUALIFIED_TALKGROUP",
                "wacn", 0xBEE00, "system", 0x348, "value", 201));
            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(
                    Map.of("revision", revision, "alias", retiredMatcher))))).statusCode());

            Map<String,Object> update = Map.of("revision", revision,
                "alias", alias(aliasListId, "Dispatch Updated", true));
            JsonNode updated = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId)
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(update)))));
            revision = updated.get("revision").longValue();
            live = json(send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId).GET()));
            assertEquals("Dispatch Updated", live.at("/alias/name").textValue());
            assertTrue(live.at("/alias/recordable").booleanValue());

            Map<String,Object> bulk = new java.util.LinkedHashMap<>();
            bulk.put("revision", revision);
            bulk.put("aliasIds", java.util.List.of(aliasId));
            bulk.put("groupOperation", "SET");
            bulk.put("group", "Fire Dispatch");
            bulk.put("streamOperation", "ADD");
            bulk.put("broadcastChannels", java.util.List.of("Primary"));
            JsonNode bulkResult = json(send(client, jsonRequest(origin, AliasAdminHttpController.BULK_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(bulk)))));
            revision = bulkResult.get("revision").longValue();
            live = json(send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId).GET()));
            assertEquals("Fire Dispatch", live.at("/alias/group").textValue());
            assertEquals("Primary", live.at("/alias/broadcastChannels/0").textValue());

            bulk = new java.util.LinkedHashMap<>();
            bulk.put("revision", revision);
            bulk.put("aliasIds", java.util.List.of(aliasId));
            bulk.put("groupOperation", "CLEAR");
            bulk.put("streamOperation", "CLEAR");
            bulkResult = json(send(client, jsonRequest(origin, AliasAdminHttpController.BULK_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(bulk)))));
            revision = bulkResult.get("revision").longValue();
            live = json(send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId).GET()));
            assertTrue(live.at("/alias/group").isNull());
            assertTrue(live.at("/alias/broadcastChannels").isEmpty());

            JsonNode deleted = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId)
                .method("DELETE", HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(
                    Map.of("revision", revision))))));
            assertEquals(1, deleted.get("affected").intValue());
            assertEquals(404, send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId).GET()).statusCode());

            JsonNode nbfmList = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", deleted.get("revision").longValue(), "name", "County NBFM", "family", "NBFM"))))));
            long nbfmListId = nbfmList.get("aliasListId").longValue();
            revision = nbfmList.get("revision").longValue();
            JsonNode options = json(send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?aliasListId=" + nbfmListId).GET()));
            assertTrue(java.util.stream.StreamSupport.stream(options.get("dcsCodes").spliterator(), false)
                .noneMatch(node -> "UNKNOWN".equals(node.textValue())));
            assertEquals(400, send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + nbfmListId + "/unmatched-talkgroups")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "listenEnabled", true, "recordable", false,
                    "broadcastChannels", java.util.List.of()))))).statusCode());

            Map<String,Object> invalidDcs = new java.util.LinkedHashMap<>(alias(nbfmListId, "Invalid DCS", false));
            invalidDcs.put("matcher", Map.of("type", "DCS", "code", "UNKNOWN"));
            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "alias", invalidDcs))))).statusCode());

            Map<String,Object> invalidTone = new java.util.LinkedHashMap<>(alias(nbfmListId, "Invalid Tone", false));
            invalidTone.put("matcher", Map.of("type", "TONES", "tones",
                java.util.List.of(Map.of("tone", "DTMF_1", "duration", 0))));
            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "alias", invalidTone))))).statusCode());
        }
        finally
        {
            server.stop(0);
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    private static Map<String,Object> alias(long aliasListId, String name, boolean recordable)
    {
        Map<String,Object> value = new java.util.LinkedHashMap<>();
        value.put("aliasListId", aliasListId);
        value.put("name", name);
        value.put("description", null);
        value.put("group", "Operations");
        value.put("color", 0);
        value.put("iconName", null);
        value.put("listenEnabled", true);
        value.put("priority", null);
        value.put("recordable", recordable);
        value.put("broadcastChannels", java.util.List.of());
        value.put("streamAsTalkgroup", null);
        value.put("matcher", Map.of("type", "TALKGROUP", "protocol", "APCO25", "value", 101));
        return value;
    }

    private static JsonNode matcher(JsonNode options, String type)
    {
        return java.util.stream.StreamSupport.stream(options.get("matchers").spliterator(), false)
            .filter(node -> type.equals(node.get("type").textValue())).findFirst()
            .orElseThrow(() -> new AssertionError("Missing matcher option " + type));
    }

    private static HttpRequest.Builder jsonRequest(URI origin, String path)
    {
        return request(origin, path).header("Content-Type", "application/json");
    }

    private static HttpRequest.Builder request(URI origin, String path)
    {
        return HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(30));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception
    {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception
    {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static final class TestUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(Path dataRoot)
        {
            mDirectoryPreference = new DirectoryPreference(_ -> {})
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
