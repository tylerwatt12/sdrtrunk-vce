/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        server.createContext(AliasAdminHttpController.SCAN_LISTS_PATH, controller::handle);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> catalogResponse = send(client,
                request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET());
            JsonNode catalogEnvelope = root(catalogResponse);
            assertTrue(catalogEnvelope.has("data"));
            assertTrue(catalogEnvelope.get("data").has("alias_lists"));
            JsonNode catalog = json(catalogResponse);
            long revision = catalog.get("revision").longValue();
            long defaultScanListId = catalog.at("/scan_lists/0/id").longValue();
            JsonNode createdList = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "name", "County P25", "family", "p25"))))));
            long aliasListId = createdList.get("alias_list_id").longValue();
            revision = createdList.get("revision").longValue();

            JsonNode createdScanList = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH).POST(HttpRequest.BodyPublishers.ofString(
                OBJECT_MAPPER.writeValueAsString(Map.of("revision", revision, "scan_list", Map.of(
                    "sort_order", 1, "name", "Cleveland", "description", "Cleveland calls",
                    "published", true, "default", false)))))));
            long clevelandScanListId = createdScanList.get("scan_list_id").longValue();
            revision = createdScanList.get("revision").longValue();

            JsonNode policyChanged = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + aliasListId + "/unmatched-talkgroups")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "recordable", true,
                    "broadcast_channels", java.util.List.of("Primary"),
                    "scan_list_ids", java.util.List.of(defaultScanListId, clevelandScanListId)))))));
            revision = policyChanged.get("revision").longValue();
            JsonNode policyCatalog = json(send(client,
                request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET()));
            assertEquals(aliasListId, policyCatalog.at("/alias_lists/0/alias_list_id").longValue());
            assertEquals("p25", policyCatalog.at("/alias_lists/0/family").textValue());
            assertFalse(policyCatalog.at("/alias_lists/0").has("id"));
            JsonNode policy = policyCatalog.at("/alias_lists/0/unmatched_talkgroup_policy");
            assertTrue(policy.get("recordable").booleanValue());
            assertEquals("Primary", policy.at("/broadcast_channels/0").textValue());
            assertEquals(2, policy.get("scan_list_ids").size());
            assertEquals(3, policy.size());
            assertTrue(aliasChanges.get() >= 2);

            JsonNode defaultPolicyChanged = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + aliasListId + "/unmatched-talkgroups")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "recordable", true,
                    "broadcast_channels", java.util.List.of("Primary"),
                    "scan_list_ids", java.util.List.of(defaultScanListId)))))));
            revision = defaultPolicyChanged.get("revision").longValue();
            policyCatalog = json(send(client,
                request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET()));
            assertEquals(defaultScanListId,
                policyCatalog.at("/alias_lists/0/unmatched_talkgroup_policy/scan_list_ids/0").longValue());

            JsonNode addedUnmatchedMembership = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH + "/" + clevelandScanListId + "/members")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "operation", "add", "alias_ids", java.util.List.of(),
                    "unmatched_alias_list_ids", java.util.List.of(aliasListId)))))));
            revision = addedUnmatchedMembership.get("revision").longValue();
            JsonNode aliasOwnersReplaced = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH + "/" + clevelandScanListId + "/members")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "operation", "replace", "alias_ids", java.util.List.of()))))));
            revision = aliasOwnersReplaced.get("revision").longValue();
            JsonNode clevelandDetail = json(send(client, request(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH + "/" + clevelandScanListId).GET()));
            assertEquals(aliasListId, clevelandDetail.at("/unmatched_alias_list_ids/0").longValue());
            assertEquals(1, clevelandDetail.get("unmatched_alias_list_ids_total").intValue());
            assertFalse(clevelandDetail.get("unmatched_alias_list_ids_truncated").booleanValue());
            JsonNode scanListCatalog = json(send(client,
                request(origin, AliasAdminHttpController.SCAN_LISTS_PATH).GET()));
            JsonNode clevelandSummary = java.util.stream.StreamSupport.stream(
                    scanListCatalog.get("scan_lists").spliterator(), false)
                .filter(row -> row.get("id").longValue() == clevelandScanListId).findFirst().orElseThrow();
            assertEquals(1, clevelandSummary.get("unmatched_alias_list_count").intValue());

            JsonNode p25Options = json(send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?alias_list_id=" + aliasListId).GET()));
            assertEquals(aliasListId, p25Options.at("/alias_list/alias_list_id").longValue());
            assertEquals(2, p25Options.at("/alias_list/unmatched_talkgroup_policy/scan_list_ids").size());
            assertFalse(p25Options.at("/alias_list").has("id"));
            assertEquals(200, send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?alias_list_id=" + encodedDecimal(aliasListId)).GET())
                .statusCode());
            assertEquals(400, send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?alias_list_id=+" + aliasListId).GET()).statusCode());
            assertEquals(400, send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?alias_list_id=" + aliasListId +
                    "&alias_list_id=" + aliasListId).GET()).statusCode());
            HttpResponse<String> rejectedCamelQuery = send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?aliasListId=" + aliasListId).GET());
            assertEquals(400, rejectedCamelQuery.statusCode());
            assertEquals("invalid_request", root(rejectedCamelQuery).at("/error/code").textValue());
            assertEquals("p25", matcher(p25Options, "talkgroup_range").get("protocol").textValue());
            assertEquals("phase_1", matcher(p25Options, "talkgroup_range").get("variant").textValue());
            assertEquals("p25", matcher(p25Options, "radio_range").get("protocol").textValue());
            assertTrue(java.util.stream.StreamSupport.stream(p25Options.get("matchers").spliterator(), false)
                .noneMatch(node -> "p25_fully_qualified_talkgroup".equals(node.get("type").textValue())));

            for(Map<String,Object> matcher: java.util.List.<Map<String,Object>>of(
                Map.of("type", "talkgroup_range", "protocol", "p25", "variant", "phase_1",
                    "minimum", 200, "maximum", 210),
                Map.of("type", "radio_range", "protocol", "p25", "variant", "phase_2",
                    "minimum", 300, "maximum", 310)))
            {
                Map<String,Object> candidate = new java.util.LinkedHashMap<>(alias(aliasListId,
                    "Matcher " + matcher.get("type"), false));
                candidate.put("matcher", matcher);
                JsonNode matcherCreated = json(send(client, jsonRequest(origin,
                    AliasAdminHttpController.ALIASES_PATH).POST(HttpRequest.BodyPublishers.ofString(
                    OBJECT_MAPPER.writeValueAsString(Map.of("revision", revision, "alias", candidate))))));
                revision = matcherCreated.get("revision").longValue();
            }

            int aliasesBeforeRejectedCreate = manager.getAliasModel().getAliases().size();
            Map<String,Object> rejectedMembershipCreate = new java.util.LinkedHashMap<>(
                alias(aliasListId, "Rejected Membership", false));
            rejectedMembershipCreate.put("matcher", Map.of("type", "talkgroup", "protocol", "p25",
                "variant", "phase_2", "value", 150));
            rejectedMembershipCreate.put("scan_list_ids", java.util.List.of(9_999_999L));
            assertEquals(404, send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(
                    Map.of("revision", revision, "alias", rejectedMembershipCreate))))).statusCode());
            assertEquals(aliasesBeforeRejectedCreate, manager.getAliasModel().getAliases().size());

            Map<String,Object> aliasPayload = alias(aliasListId, "Dispatch", false);
            aliasPayload.put("scan_list_ids", java.util.List.of(defaultScanListId, clevelandScanListId));
            Map<String,Object> create = Map.of("revision", revision, "alias", aliasPayload);
            JsonNode created = json(send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(create)))));
            long aliasId = created.get("alias_ids").get(0).longValue();
            assertEquals(1, created.get("alias_ids_total").intValue());
            assertFalse(created.get("alias_ids_truncated").booleanValue());
            revision = created.get("revision").longValue();
            JsonNode live = json(send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId).GET()));
            assertEquals("Dispatch", live.at("/alias/name").textValue());
            assertEquals("talkgroup", live.at("/alias/matcher/type").textValue());
            assertEquals("p25", live.at("/alias/matcher/protocol").textValue());
            assertEquals("phase_2", live.at("/alias/matcher/variant").textValue());
            assertEquals(2, live.at("/alias/scan_list_ids").size());
            assertEquals(200, send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + encodedDecimal(aliasId)).GET()).statusCode());
            assertEquals(400, send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/+" + aliasId).GET()).statusCode());

            Map<String,Object> camelAlias = new java.util.LinkedHashMap<>(alias(aliasListId,
                "Rejected Camel Case", false));
            camelAlias.put("aliasListId", camelAlias.remove("alias_list_id"));
            HttpResponse<String> rejectedCamelBody = send(client,
                jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH).POST(
                    HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(
                        Map.of("revision", revision, "alias", camelAlias)))));
            assertEquals(400, rejectedCamelBody.statusCode());
            assertEquals("invalid_request", root(rejectedCamelBody).at("/error/code").textValue());

            Map<String,Object> invalidAlias = new java.util.LinkedHashMap<>(alias(aliasListId, "Bad", false));
            invalidAlias.put("unexpected", true);
            Map<String,Object> invalid = Map.of("revision", revision, "alias", invalidAlias);
            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(invalid)))).statusCode());

            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIAS_LISTS_PATH)
                .POST(HttpRequest.BodyPublishers.ofString("{\"revision\":" + revision +
                    ",\"name\":\"Numeric Family\",\"family\":0}"))).statusCode());
            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIAS_LISTS_PATH)
                .POST(HttpRequest.BodyPublishers.ofString("{\"revision\":" + revision +
                    ",\"name\":\"Legacy Family\",\"family\":\"P25\"}"))).statusCode());
            Map<String,Object> decimalAlias = new java.util.LinkedHashMap<>(aliasPayload);
            decimalAlias.put("matcher", Map.of("type", "talkgroup", "protocol", "p25",
                "variant", "phase_1", "value", 101.5));
            String decimalMatcher = OBJECT_MAPPER.writeValueAsString(
                Map.of("revision", revision, "alias", decimalAlias));
            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(decimalMatcher))).statusCode());
            Map<String,Object> legacyProtocol = new java.util.LinkedHashMap<>(aliasPayload);
            legacyProtocol.put("matcher", Map.of("type", "talkgroup", "protocol", "APCO25",
                "variant", "phase_1", "value", 201));
            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(
                    Map.of("revision", revision, "alias", legacyProtocol))))).statusCode());
            Map<String,Object> missingP25Variant = new java.util.LinkedHashMap<>(aliasPayload);
            missingP25Variant.put("matcher", Map.of("type", "talkgroup", "protocol", "p25", "value", 201));
            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(
                    Map.of("revision", revision, "alias", missingP25Variant))))).statusCode());
            Map<String,Object> retiredMatcher = new java.util.LinkedHashMap<>(aliasPayload);
            retiredMatcher.put("matcher", Map.of("type", "P25_FULLY_QUALIFIED_TALKGROUP",
                "wacn", 0xBEE00, "system", 0x348, "value", 201));
            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(
                    Map.of("revision", revision, "alias", retiredMatcher))))).statusCode());

            Map<String,Object> updatedAlias = new java.util.LinkedHashMap<>(
                alias(aliasListId, "Dispatch Updated", true));
            updatedAlias.put("scan_list_ids", java.util.List.of(defaultScanListId, clevelandScanListId));
            Map<String,Object> update = Map.of("revision", revision, "alias", updatedAlias);
            JsonNode updated = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId)
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(update)))));
            revision = updated.get("revision").longValue();
            live = json(send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId).GET()));
            assertEquals("Dispatch Updated", live.at("/alias/name").textValue());
            assertTrue(live.at("/alias/recordable").booleanValue());
            assertEquals(2, live.at("/alias/scan_list_ids").size());

            Map<String,Object> bulk = new java.util.LinkedHashMap<>();
            bulk.put("revision", revision);
            bulk.put("alias_ids", java.util.List.of(aliasId));
            bulk.put("group_operation", "set");
            bulk.put("group", "Fire Dispatch");
            bulk.put("stream_operation", "add");
            bulk.put("broadcast_channels", java.util.List.of("Primary"));
            JsonNode bulkResult = json(send(client, jsonRequest(origin, AliasAdminHttpController.BULK_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(bulk)))));
            revision = bulkResult.get("revision").longValue();
            live = json(send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId).GET()));
            assertEquals("Fire Dispatch", live.at("/alias/group").textValue());
            assertEquals("Primary", live.at("/alias/broadcast_channels/0").textValue());

            bulk = new java.util.LinkedHashMap<>();
            bulk.put("revision", revision);
            bulk.put("alias_ids", java.util.List.of(aliasId));
            bulk.put("group_operation", "clear");
            bulk.put("stream_operation", "clear");
            bulkResult = json(send(client, jsonRequest(origin, AliasAdminHttpController.BULK_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(bulk)))));
            revision = bulkResult.get("revision").longValue();
            live = json(send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId).GET()));
            assertTrue(live.at("/alias/group").isNull());
            assertTrue(live.at("/alias/broadcast_channels").isEmpty());

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
                    "revision", deleted.get("revision").longValue(), "name", "County NBFM", "family", "nbfm"))))));
            long nbfmListId = nbfmList.get("alias_list_id").longValue();
            revision = nbfmList.get("revision").longValue();
            JsonNode options = json(send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?alias_list_id=" + nbfmListId).GET()));
            assertTrue(java.util.stream.StreamSupport.stream(options.get("dcs_codes").spliterator(), false)
                .noneMatch(node -> "unknown".equals(node.textValue())));

            Map<String,Object> amAlias = new java.util.LinkedHashMap<>(alias(nbfmListId, "Airband Tower", false));
            amAlias.put("matcher", Map.of("type", "talkgroup", "protocol", "am", "value", 1));
            amAlias.put("scan_list_ids", java.util.List.of(defaultScanListId, clevelandScanListId));
            JsonNode createdAm = json(send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(
                    Map.of("revision", revision, "alias", amAlias))))));
            long amAliasId = createdAm.at("/alias_ids/0").longValue();
            revision = createdAm.get("revision").longValue();
            JsonNode liveAm = json(send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + amAliasId).GET()));
            assertEquals("am", liveAm.at("/alias/matcher/protocol").textValue());
            assertFalse(liveAm.at("/alias/matcher").has("variant"));
            assertEquals(2, liveAm.at("/alias/scan_list_ids").size());
            assertTrue(java.util.stream.StreamSupport.stream(
                liveAm.at("/alias/scan_list_ids").spliterator(), false)
                .anyMatch(node -> node.longValue() == defaultScanListId));
            assertTrue(java.util.stream.StreamSupport.stream(
                liveAm.at("/alias/scan_list_ids").spliterator(), false)
                .anyMatch(node -> node.longValue() == clevelandScanListId));

            JsonNode nbfmPolicyChanged = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + nbfmListId + "/unmatched-talkgroups")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "recordable", false,
                    "broadcast_channels", java.util.List.of(),
                    "scan_list_ids", java.util.List.of(clevelandScanListId)))))));
            revision = nbfmPolicyChanged.get("revision").longValue();
            JsonNode nbfmOptions = json(send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?alias_list_id=" + nbfmListId).GET()));
            assertEquals(clevelandScanListId,
                nbfmOptions.at("/alias_list/unmatched_talkgroup_policy/scan_list_ids/0").longValue());

            JsonNode nbfmAddedFromScanList = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH + "/" + defaultScanListId + "/members")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "operation", "add", "alias_ids", java.util.List.of(),
                    "unmatched_alias_list_ids", java.util.List.of(nbfmListId)))))));
            revision = nbfmAddedFromScanList.get("revision").longValue();
            nbfmOptions = json(send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?alias_list_id=" + nbfmListId).GET()));
            assertEquals(2, nbfmOptions.at("/alias_list/unmatched_talkgroup_policy/scan_list_ids").size());
            assertTrue(java.util.stream.StreamSupport.stream(
                nbfmOptions.at("/alias_list/unmatched_talkgroup_policy/scan_list_ids").spliterator(), false)
                .anyMatch(node -> node.longValue() == defaultScanListId));
            assertTrue(java.util.stream.StreamSupport.stream(
                nbfmOptions.at("/alias_list/unmatched_talkgroup_policy/scan_list_ids").spliterator(), false)
                .anyMatch(node -> node.longValue() == clevelandScanListId));

            Map<String,Object> invalidDcs = new java.util.LinkedHashMap<>(alias(nbfmListId, "Invalid DCS", false));
            invalidDcs.put("matcher", Map.of("type", "dcs", "code", "unknown"));
            assertEquals(400, send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "alias", invalidDcs))))).statusCode());

            Map<String,Object> invalidTone = new java.util.LinkedHashMap<>(alias(nbfmListId, "Invalid Tone", false));
            invalidTone.put("matcher", Map.of("type", "tone_sequence", "tones",
                java.util.List.of(Map.of("tone", "dtmf_1", "duration", 0))));
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

    @Test
    void rejectsOversizedAliasListBeforeInvokingDeleteMutation() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("oversized-delete-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);
        manager.init();
        AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
        AtomicInteger impactCalls = new AtomicInteger();
        AtomicInteger deleteMutationCalls = new AtomicInteger();
        long aliasListId = 42L;
        long revision = 17L;
        AliasAdminHttpController.AliasListDeletion deletion = new AliasAdminHttpController.AliasListDeletion()
        {
            @Override
            public AliasAdministrationService.DeleteImpact impact(long requestedAliasListId, int maximumCount)
            {
                impactCalls.incrementAndGet();
                assertEquals(aliasListId, requestedAliasListId);
                return new AliasAdministrationService.DeleteImpact(revision, aliasListId, "Oversized",
                    maximumCount + 1, 0);
            }

            @Override
            public AliasAdministrationService.MutationResult delete(long requestedAliasListId,
                                                                     long requestedRevision, boolean confirmed)
            {
                deleteMutationCalls.incrementAndGet();
                throw new AssertionError("Oversized deletion must not reach the mutating service operation");
            }
        };
        AliasAdminHttpController controller = new AliasAdminHttpController(service, () -> {}, deletion);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(AliasAdminHttpController.ALIAS_LISTS_PATH, controller::handle);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> response = send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + aliasListId)
                .method("DELETE", HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "confirmed", true)))));

            assertEquals(413, response.statusCode());
            JsonNode error = root(response).get("error");
            assertEquals("alias_list_delete_too_large", error.get("code").textValue());
            assertEquals("alias_count", error.get("field").textValue());
            assertEquals(1, impactCalls.get());
            assertEquals(0, deleteMutationCalls.get());
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
        value.put("alias_list_id", aliasListId);
        value.put("name", name);
        value.put("description", null);
        value.put("group", "Operations");
        value.put("color", 0);
        value.put("icon_name", null);
        value.put("recordable", recordable);
        value.put("broadcast_channels", java.util.List.of());
        value.put("stream_as_talkgroup", null);
        value.put("matcher", Map.of("type", "talkgroup", "protocol", "p25", "variant", "phase_2",
            "value", 101));
        return value;
    }

    private static JsonNode matcher(JsonNode options, String type)
    {
        return java.util.stream.StreamSupport.stream(options.get("matchers").spliterator(), false)
            .filter(node -> type.equals(node.get("type").textValue())).findFirst()
            .orElseThrow(() -> new AssertionError("Missing matcher option " + type));
    }

    private static String encodedDecimal(long value)
    {
        return Long.toString(value).chars()
            .mapToObj(character -> "%" + Integer.toHexString(character))
            .collect(java.util.stream.Collectors.joining());
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
        return root(response).get("data");
    }

    private static JsonNode root(HttpResponse<String> response) throws Exception
    {
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
