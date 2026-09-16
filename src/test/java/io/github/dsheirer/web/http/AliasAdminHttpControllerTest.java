/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasAdminHttpControllerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String EXPORT_TOKEN = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void transferPreparationFailureReturnsAnExportError() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("failed-export-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);
        manager.init();
        AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
        long listId = service.createAliasList("Transfer", AliasListFamily.P25).aliasListId();
        AliasAdminHttpController controller = new AliasAdminHttpController(service, () -> {}, aliasId ->
        {
            throw new IOException("injected preparation failure");
        });
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(AliasAdminHttpController.ALIAS_LISTS_PATH, controller::handle);
        server.start();

        try(HttpClient client = HttpClient.newHttpClient())
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpResponse<String> invalidToken = send(client, request(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + listId +
                    "/transfer?export_token=not-a-browser-nonce").GET());
            assertEquals(400, invalidToken.statusCode());
            assertEquals("DENY", invalidToken.headers().firstValue("X-Frame-Options").orElseThrow());

            HttpResponse<String> response = send(client, request(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + listId +
                    "/transfer?export_token=" + EXPORT_TOKEN).GET());
            assertEquals(503, response.statusCode());
            JsonNode error = root(response).get("error");
            assertEquals("export_failed", error.get("code").textValue());
            assertEquals("Alias CSV export could not be prepared", error.get("message").textValue());
            assertEquals("SAMEORIGIN", response.headers().firstValue("X-Frame-Options").orElseThrow());
            assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow()
                .contains("frame-ancestors 'self'"));
            assertFalse(response.headers().firstValue("Set-Cookie").isPresent(),
                "A failed preparation must not announce that the download started");
        }
        finally
        {
            server.stop(0);
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void interruptedTransferDeclaresTheCompleteLengthAndRemovesItsPreparedSpool() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("disconnect-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);
        manager.init();
        AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
        long listId = service.createAliasList("Transfer", AliasListFamily.P25).aliasListId();
        Path spool = mTemporaryFolder.resolve("complete-export.csv");
        byte[] complete = ("\uFEFF" + String.join(",", io.github.dsheirer.alias.AliasTransferCsv.HEADERS) +
            "\r\n2,Transfer,Dispatch,,,0,,TALKGROUP,APCO25,1,,,,,false,[],[],\r\n")
            .getBytes(StandardCharsets.UTF_8);
        Files.write(spool, complete);
        AtomicBoolean cleaned = new AtomicBoolean();
        AliasAdminHttpController controller = new AliasAdminHttpController(service, () -> {}, aliasId ->
            new AliasAdminHttpController.AliasListExport(spool, "complete.csv", 1, complete.length, () ->
            {
                cleaned.set(true);
                Files.deleteIfExists(spool);
            }));
        DisconnectingExchange exchange = new DisconnectingExchange(
            AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + listId +
                "/transfer?export_token=" + EXPORT_TOKEN, 32);

        try
        {
            controller.handle(exchange);
            assertEquals(200, exchange.getResponseCode());
            assertEquals(complete.length, exchange.responseLength(),
                "Content-Length must describe the validated complete file, not bytes sent before disconnect");
            assertTrue(exchange.bytesAccepted() < complete.length);
            assertTrue(cleaned.get());
            assertFalse(Files.exists(spool));
            assertEquals("SAMEORIGIN", exchange.getResponseHeaders().getFirst("X-Frame-Options"));
            assertTrue(exchange.getResponseHeaders().getFirst("Content-Security-Policy")
                .contains("frame-ancestors 'self'"));
            String readyCookie = exchange.getResponseHeaders().getFirst("Set-Cookie");
            assertTrue(readyCookie.contains(WebRequestSecurity.ALIAS_EXPORT_READY_COOKIE_PREFIX + EXPORT_TOKEN + "=1"));
            assertTrue(readyCookie.contains("Max-Age=30"));
            assertTrue(readyCookie.contains("SameSite=Strict"));
            assertFalse(readyCookie.contains("HttpOnly"), "The page must be able to read the ready marker");
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void transferRequiresMatchingPreviewAndExplicitReplaceConfirmation() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("transfer-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);
        manager.init();
        AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
        long listId = service.createAliasList("Transfer", AliasListFamily.P25).aliasListId();
        AliasAdminHttpController controller = new AliasAdminHttpController(service);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(AliasAdminHttpController.ALIAS_LISTS_PATH, controller::handle);
        server.start();
        try(HttpClient client = HttpClient.newHttpClient())
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            String path = AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + listId + "/transfer";
            Map<String,Object> body = new java.util.LinkedHashMap<>();
            body.put("format", "RADIOREFERENCE"); body.put("mode", "REPLACE"); body.put("action", "preview");
            body.put("csv", "Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category\r\n123,7b,Dispatch,D,Fire dispatch,Fire Dispatch,Fire\r\n");
            JsonNode preview = json(send(client, jsonRequest(origin, path).POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))));
            assertEquals(1, preview.at("/counts/added").intValue());
            assertEquals(0, service.transferSnapshot(listId).aliases().size());
            body.put("action", "apply"); body.put("revision", preview.get("revision").longValue());
            body.put("digest", preview.get("digest").textValue());
            assertEquals(400, send(client, jsonRequest(origin, path).POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))).statusCode());
            body.put("confirm_replace", true);
            body.put("digest", "changed");
            assertEquals(409, send(client, jsonRequest(origin, path).POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))).statusCode());
            body.put("digest", preview.get("digest").textValue());
            assertEquals(200, send(client, jsonRequest(origin, path).POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))).statusCode());
            assertEquals(1, service.transferSnapshot(listId).aliases().size());
            assertEquals(409, send(client, jsonRequest(origin, path).POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))).statusCode());
            HttpResponse<String> exported = send(client, request(origin, path).GET());
            assertEquals(200, exported.statusCode());
            assertTrue(exported.headers().firstValue("Content-Type").orElseThrow().startsWith("text/csv"));
            assertTrue(exported.headers().firstValue("Content-Disposition").orElseThrow().contains("attachment"));
            body.put("format", "VCE"); body.put("mode", "UPDATE_ADD"); body.put("action", "preview");
            body.put("csv", exported.body());
            preview = json(send(client, jsonRequest(origin, path).POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))));
            assertEquals(1, preview.at("/counts/unchanged").intValue());
            assertEquals("Transfer", preview.get("source_list").textValue());
            assertEquals("Transfer", preview.get("destination_list").textValue());
            assertTrue(exported.body().startsWith("\uFEFFformat_version,alias_list,name,"));
        }
        finally
        {
            server.stop(0);
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

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

        try(HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
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
            JsonNode initialCustomCatalog = json(send(client,
                request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET()));
            JsonNode initialCustomPolicy = aliasList(initialCustomCatalog, aliasListId)
                .get("unmatched_talkgroup_policy");
            assertFalse(initialCustomPolicy.get("recordable").booleanValue());
            assertTrue(initialCustomPolicy.get("broadcast_configuration_ids").isEmpty());
            assertTrue(initialCustomPolicy.get("scan_list_ids").isEmpty());
            assertTrue(aliasList(initialCustomCatalog, aliasListId).has("unknown_alias_behavior"));
            assertTrue(aliasList(initialCustomCatalog, aliasListId).has("new_alias_behavior"));

            JsonNode createdScanList = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH).POST(HttpRequest.BodyPublishers.ofString(
                OBJECT_MAPPER.writeValueAsString(Map.of("revision", revision, "scan_list", Map.of(
                    "sort_order", 1, "name", "Cleveland", "description", "Cleveland calls",
                    "published", true, "default", false)))))));
            long clevelandScanListId = createdScanList.get("scan_list_id").longValue();
            revision = createdScanList.get("revision").longValue();

            JsonNode defaultsChanged = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + aliasListId + "/defaults")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision,
                    "unknown_alias_behavior", Map.of("recordable", false,
                        "broadcast_configuration_ids", java.util.List.of(),
                        "scan_list_ids", java.util.List.of(defaultScanListId)),
                    "new_alias_behavior", Map.of("recordable", true,
                        "broadcast_configuration_ids", java.util.List.of(primary.getConfigurationId()),
                        "scan_list_ids", java.util.List.of(clevelandScanListId))))))));
            revision = defaultsChanged.get("revision").longValue();
            JsonNode independentDefaults = aliasList(json(send(client,
                request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET())), aliasListId);
            assertFalse(independentDefaults.at("/unknown_alias_behavior/recordable").booleanValue());
            assertEquals(defaultScanListId,
                independentDefaults.at("/unknown_alias_behavior/scan_list_ids/0").longValue());
            assertTrue(independentDefaults.at("/new_alias_behavior/recordable").booleanValue());
            assertEquals(clevelandScanListId,
                independentDefaults.at("/new_alias_behavior/scan_list_ids/0").longValue());

            JsonNode policyChanged = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + aliasListId + "/unmatched-talkgroups")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "recordable", true,
                    "broadcast_configuration_ids", java.util.List.of(primary.getConfigurationId()),
                    "scan_list_ids", java.util.List.of(defaultScanListId, clevelandScanListId)))))));
            revision = policyChanged.get("revision").longValue();
            JsonNode policyCatalog = json(send(client,
                request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET()));
            JsonNode countyAliasList = aliasList(policyCatalog, aliasListId);
            assertEquals(aliasListId, countyAliasList.get("alias_list_id").longValue());
            assertEquals("p25", countyAliasList.get("family").textValue());
            assertFalse(countyAliasList.has("id"));
            JsonNode policy = countyAliasList.get("unmatched_talkgroup_policy");
            assertTrue(policy.get("recordable").booleanValue());
            assertEquals(primary.getConfigurationId(),
                policy.at("/broadcast_configuration_ids/0").textValue());
            assertEquals(2, policy.get("scan_list_ids").size());
            assertEquals(3, policy.size());
            assertTrue(aliasChanges.get() >= 2);

            JsonNode defaultPolicyChanged = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + aliasListId + "/unmatched-talkgroups")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "recordable", true,
                    "broadcast_configuration_ids", java.util.List.of(primary.getConfigurationId()),
                    "scan_list_ids", java.util.List.of(defaultScanListId)))))));
            revision = defaultPolicyChanged.get("revision").longValue();
            policyCatalog = json(send(client,
                request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET()));
            assertEquals(defaultScanListId,
                aliasList(policyCatalog, aliasListId).at("/unmatched_talkgroup_policy/scan_list_ids/0").longValue());

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
            assertEquals(400, send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?alias_list_id=" + aliasListId +
                    "&include_group_names=FALSE").GET()).statusCode());
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

            Map<String,Object> duplicatePayload = new java.util.LinkedHashMap<>(
                alias(aliasListId, "Dispatch Duplicate", false));
            JsonNode duplicateCreated = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIASES_PATH).POST(HttpRequest.BodyPublishers.ofString(
                OBJECT_MAPPER.writeValueAsString(Map.of("revision", revision, "alias", duplicatePayload))))));
            long duplicateId = duplicateCreated.at("/alias_ids/0").longValue();
            revision = duplicateCreated.get("revision").longValue();
            JsonNode conflicts = json(send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId + "/conflicts").GET()));
            assertEquals(aliasId, conflicts.at("/alias/alias_id").longValue());
            assertEquals(aliasListId, conflicts.at("/alias/alias_list_id").longValue());
            assertEquals("talkgroup", conflicts.at("/alias/matcher/type").textValue());
            assertEquals(1, conflicts.get("conflicts_total").intValue());
            assertFalse(conflicts.get("conflicts_truncated").booleanValue());
            assertEquals(duplicateId, conflicts.at("/conflicts/0/alias_id").longValue());
            assertEquals("Dispatch Duplicate", conflicts.at("/conflicts/0/name").textValue());
            JsonNode duplicateDeleted = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + duplicateId)
                .method("DELETE", HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(
                    Map.of("revision", revision))))));
            revision = duplicateDeleted.get("revision").longValue();

            JsonNode scopeAdded = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH + "/" + clevelandScanListId + "/members")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "operation", "add",
                    "alias_scope", Map.of("alias_list_id", aliasListId)))))));
            assertEquals(0, scopeAdded.get("affected").intValue(),
                "Every newly created Alias inherits the configured Cleveland membership");
            revision = scopeAdded.get("revision").longValue();
            clevelandDetail = json(send(client, request(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH + "/" + clevelandScanListId).GET()));
            assertEquals(3, clevelandDetail.get("alias_ids_total").intValue());

            String removeAllPayload = OBJECT_MAPPER.writeValueAsString(Map.of(
                "revision", revision, "operation", "remove", "alias_scope", Map.of()));
            JsonNode allMembersRemoved = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH + "/" + clevelandScanListId + "/members")
                .PUT(HttpRequest.BodyPublishers.ofString(removeAllPayload))));
            assertEquals(3, allMembersRemoved.get("affected").intValue());
            revision = allMembersRemoved.get("revision").longValue();
            clevelandDetail = json(send(client, request(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH + "/" + clevelandScanListId).GET()));
            assertEquals(0, clevelandDetail.get("alias_ids_total").intValue());
            assertEquals(1, clevelandDetail.get("unmatched_alias_list_ids_total").intValue(),
                "Removing every Alias member must preserve unmatched-talkgroup routing");

            JsonNode countedCatalog = json(send(client,
                request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET()));
            JsonNode countedList = aliasList(countedCatalog, aliasListId);
            assertEquals(3, countedList.get("alias_count").intValue());
            assertEquals(0, countedList.get("assigned_channel_count").intValue());
            JsonNode leanCatalog = json(send(client, request(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "?include_counts=false").GET()));
            JsonNode leanList = aliasList(leanCatalog, aliasListId);
            assertFalse(leanList.has("alias_count"));
            assertFalse(leanList.has("assigned_channel_count"));
            JsonNode defaultGroupOptions = json(send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?alias_list_id=" + aliasListId).GET()));
            assertTrue(java.util.stream.StreamSupport.stream(defaultGroupOptions.get("group_names").spliterator(),
                false).anyMatch(group -> "Operations".equals(group.textValue())));
            JsonNode leanGroupOptions = json(send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?alias_list_id=" + aliasListId +
                    "&include_group_names=false").GET()));
            assertTrue(leanGroupOptions.get("group_names").isEmpty());
            assertEquals(400, send(client, request(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "?include_counts=FALSE").GET()).statusCode());

            assertEquals(400, send(client, jsonRequest(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH + "/" + clevelandScanListId + "/members")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "operation", "add", "alias_ids", java.util.List.of(aliasId),
                    "alias_scope", Map.of()))))).statusCode());

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
            bulk.put("broadcast_configuration_ids", java.util.List.of(primary.getConfigurationId()));
            JsonNode bulkResult = json(send(client, jsonRequest(origin, AliasAdminHttpController.BULK_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(bulk)))));
            revision = bulkResult.get("revision").longValue();
            live = json(send(client, request(origin,
                AliasAdminHttpController.ALIASES_PATH + "/" + aliasId).GET()));
            assertEquals("Fire Dispatch", live.at("/alias/group").textValue());
            assertEquals(primary.getConfigurationId(),
                live.at("/alias/broadcast_configuration_ids/0").textValue());

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
            assertTrue(live.at("/alias/broadcast_configuration_ids").isEmpty());

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
                    "broadcast_configuration_ids", java.util.List.of(),
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

            BroadcastifyCallConfiguration overflowStream = null;
            for(int index = 0; index <= 500; index++)
            {
                BroadcastifyCallConfiguration configuration =
                    new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
                configuration.setName("Stream " + String.format("%03d", index));
                manager.getBroadcastModel().addBroadcastConfiguration(configuration);
                if(index == 500) overflowStream = configuration;
            }
            assertNotNull(overflowStream);
            JsonNode overflowPolicy = json(send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + nbfmListId + "/unmatched-talkgroups")
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "recordable", false,
                    "broadcast_configuration_ids", java.util.List.of(overflowStream.getConfigurationId()),
                    "scan_list_ids", java.util.List.of(clevelandScanListId)))))));
            revision = overflowPolicy.get("revision").longValue();
            JsonNode boundedOptions = json(send(client, request(origin,
                AliasAdminHttpController.OPTIONS_PATH + "?alias_list_id=" + nbfmListId).GET()));
            assertEquals(500, boundedOptions.get("streams").size());
            assertEquals(502, boundedOptions.get("streams_total").intValue());
            assertTrue(boundedOptions.get("streams_truncated").booleanValue());
            assertTrue(java.util.stream.StreamSupport.stream(boundedOptions.get("streams").spliterator(), false)
                .anyMatch(stream -> "Stream 500".equals(stream.get("name").textValue())),
                "a selected-list default beyond the normal option cap must remain named");
            assertTrue(boundedOptions.at("/streams/0").has("configuration_id"));
            assertTrue(boundedOptions.at("/streams/0").has("name"));
            assertEquals(boundedOptions.get("icon_names").size(),
                boundedOptions.get("icon_names_total").intValue());
            assertFalse(boundedOptions.get("icon_names_truncated").booleanValue());

            Map<String,Object> transferDefaults = new java.util.LinkedHashMap<>();
            transferDefaults.put("recordable", null);
            transferDefaults.put("scan_lists", null);
            transferDefaults.put("streams", java.util.List.of("Stream 500"));
            Map<String,Object> transfer = new java.util.LinkedHashMap<>();
            transfer.put("format", "RADIOREFERENCE");
            transfer.put("mode", "UPDATE_ADD");
            transfer.put("action", "preview");
            transfer.put("defaults", transferDefaults);
            transfer.put("csv", "Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category\r\n" +
                "54321,d431,Overflow,D,Overflow stream,Interop,Interop\r\n");
            String transferPath = AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + aliasListId + "/transfer";
            JsonNode transferPreview = json(send(client, jsonRequest(origin, transferPath)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(transfer)))));
            assertEquals(0, transferPreview.at("/counts/error").intValue());
            transfer.put("action", "apply");
            transfer.put("revision", transferPreview.get("revision").longValue());
            transfer.put("digest", transferPreview.get("digest").textValue());
            assertEquals(200, send(client, jsonRequest(origin, transferPath)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(transfer)))).statusCode());
            Alias overflowAlias = service.transferSnapshot(aliasListId).aliases().stream()
                .map(AliasAdministrationService.AliasEntry::alias)
                .filter(alias -> "Overflow".equals(alias.getName())).findFirst().orElseThrow();
            assertEquals(overflowStream.getConfigurationId(),
                overflowAlias.getBroadcastChannels().iterator().next().getConfigurationId());
        }
        finally
        {
            server.stop(0);
            closeConfigurationManager(manager);
        }
    }

    @Test
    void acceptsTenThousandExplicitAliasIdsAndRejectsAnyLargerCollection() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("large-bulk-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);
        manager.init();
        AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
        AliasAdminHttpController controller = new AliasAdminHttpController(service);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(AliasAdminHttpController.ALIASES_PATH, controller::handle);
        server.createContext(AliasAdminHttpController.SCAN_LISTS_PATH, controller::handle);
        server.start();

        try(HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            List<Long> maximumIds = new ArrayList<>(AliasAdministrationService.MAX_BULK_ALIASES);
            for(int index = 0; index < AliasAdministrationService.MAX_BULK_ALIASES; index++)
            {
                maximumIds.add(Long.MAX_VALUE - index);
            }

            String maximumBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                "revision", service.currentRevision(), "alias_ids", maximumIds, "recordable", true));
            assertTrue(maximumBody.getBytes(StandardCharsets.UTF_8).length > 16 * 1024,
                "The accepted request must exercise the enlarged HTTP body boundary");
            HttpResponse<String> acceptedBulk = send(client, jsonRequest(origin, AliasAdminHttpController.BULK_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(maximumBody)));
            assertEquals(404, acceptedBulk.statusCode(), acceptedBulk.body());
            assertEquals("not_found", root(acceptedBulk).at("/error/code").textValue(),
                "The complete 10,000-ID request must reach the atomic service command");

            long scanListId = service.scanListCatalog().scanLists().getFirst().scanList().getId();
            String maximumMembershipBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                "revision", service.currentRevision(), "operation", "add", "alias_ids", maximumIds));
            HttpResponse<String> acceptedMembership = send(client, jsonRequest(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH + "/" + scanListId + "/members")
                .PUT(HttpRequest.BodyPublishers.ofString(maximumMembershipBody)));
            assertEquals(404, acceptedMembership.statusCode(), acceptedMembership.body());
            assertEquals("not_found", root(acceptedMembership).at("/error/code").textValue(),
                "Scan-list membership must use the same explicit Alias-ID ceiling");

            maximumIds.add(Long.MAX_VALUE - AliasAdministrationService.MAX_BULK_ALIASES);
            String oversizedBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                "revision", service.currentRevision(), "alias_ids", maximumIds, "recordable", true));
            HttpResponse<String> rejected = send(client, jsonRequest(origin, AliasAdminHttpController.BULK_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(oversizedBody)));
            assertEquals(400, rejected.statusCode(), rejected.body());
            assertEquals("invalid_request", root(rejected).at("/error/code").textValue());
        }
        finally
        {
            server.stop(0);
            closeConfigurationManager(manager);
        }
    }

    @Test
    void confirmedAliasListDeletionAllowsLargeListsAndBoundsTheResponse() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("oversized-delete-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);
        manager.init();
        List<AliasListDefinition> legacyDefinitions = new ArrayList<>();
        for(int index = 1; index <= 501; index++)
        {
            AliasListDefinition definition = new AliasListDefinition("Legacy " + index, AliasListFamily.P25);
            definition.setId(index);
            legacyDefinitions.add(definition);
        }
        manager.getAliasModel().replaceCommittedConfiguration(legacyDefinitions, List.of());
        AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
        AtomicInteger impactCalls = new AtomicInteger();
        AtomicInteger deleteMutationCalls = new AtomicInteger();
        long aliasListId = 42L;
        long revision = 17L;
        AliasAdminHttpController.AliasListDeletion deletion = new AliasAdminHttpController.AliasListDeletion()
        {
            @Override
            public AliasAdministrationService.DeleteImpact impact(long requestedAliasListId)
            {
                impactCalls.incrementAndGet();
                assertEquals(aliasListId, requestedAliasListId);
                return new AliasAdministrationService.DeleteImpact(revision, aliasListId, "Large", 501, 0);
            }

            @Override
            public AliasAdministrationService.MutationResult delete(long requestedAliasListId,
                                                                     long requestedRevision, boolean confirmed)
            {
                deleteMutationCalls.incrementAndGet();
                assertEquals(aliasListId, requestedAliasListId);
                assertEquals(revision, requestedRevision);
                assertTrue(confirmed);
                return new AliasAdministrationService.MutationResult(revision + 1, aliasListId,
                    java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList(), 501);
            }
        };
        AliasAdminHttpController controller = new AliasAdminHttpController(service, () -> {}, deletion);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(AliasAdminHttpController.ALIAS_LISTS_PATH, controller::handle);
        server.start();

        try(HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode completeCatalog = json(send(client,
                request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET()));
            assertEquals(501, completeCatalog.get("alias_lists").size(),
                "Existing imported catalogs must remain editable beyond the web creation limit");
            HttpResponse<String> response = send(client, jsonRequest(origin,
                AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + aliasListId)
                .method("DELETE", HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "revision", revision, "confirmed", true)))));

            assertEquals(200, response.statusCode());
            JsonNode deleted = json(response);
            assertEquals(501, deleted.get("affected").intValue());
            assertEquals(500, deleted.get("alias_ids").size());
            assertEquals(501, deleted.get("alias_ids_total").intValue());
            assertTrue(deleted.get("alias_ids_truncated").booleanValue());
            assertEquals(1, impactCalls.get());
            assertEquals(1, deleteMutationCalls.get());
        }
        finally
        {
            server.stop(0);
            closeConfigurationManager(manager);
        }
    }

    private static void closeConfigurationManager(ConfigurationManager manager)
    {
        var channelProcessingManager = manager.getChannelProcessingManager();

        try
        {
            manager.flushConfiguration();
        }
        finally
        {
            try
            {
                MyEventBus.getGlobalEventBus().unregister(channelProcessingManager);
            }
            finally
            {
                channelProcessingManager.close();
            }
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
        value.put("broadcast_configuration_ids", java.util.List.of());
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

    private static JsonNode aliasList(JsonNode catalog, long aliasListId)
    {
        return java.util.stream.StreamSupport.stream(catalog.get("alias_lists").spliterator(), false)
            .filter(node -> node.get("alias_list_id").longValue() == aliasListId).findFirst()
            .orElseThrow(() -> new AssertionError("Missing Alias List " + aliasListId));
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

    private static final class DisconnectingExchange extends HttpExchange
    {
        private final Headers mRequestHeaders = new Headers();
        private final Headers mResponseHeaders = new Headers();
        private final Map<String,Object> mAttributes = new HashMap<>();
        private final URI mUri;
        private final int mDisconnectAfter;
        private int mBytesAccepted;
        private int mResponseCode = -1;
        private long mResponseLength = -1;

        private DisconnectingExchange(String path, int disconnectAfter)
        {
            mUri = URI.create(path);
            mDisconnectAfter = disconnectAfter;
        }

        private int bytesAccepted()
        {
            return mBytesAccepted;
        }

        private long responseLength()
        {
            return mResponseLength;
        }

        @Override
        public Headers getRequestHeaders()
        {
            return mRequestHeaders;
        }

        @Override
        public Headers getResponseHeaders()
        {
            return mResponseHeaders;
        }

        @Override
        public URI getRequestURI()
        {
            return mUri;
        }

        @Override
        public String getRequestMethod()
        {
            return "GET";
        }

        @Override
        public HttpContext getHttpContext()
        {
            return null;
        }

        @Override
        public void close()
        {
        }

        @Override
        public InputStream getRequestBody()
        {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getResponseBody()
        {
            return new OutputStream()
            {
                @Override
                public void write(int value) throws IOException
                {
                    if(mBytesAccepted >= mDisconnectAfter)
                    {
                        throw new IOException("simulated browser disconnect");
                    }

                    mBytesAccepted++;
                }

                @Override
                public void write(byte[] values, int offset, int length) throws IOException
                {
                    int accepted = Math.min(length, Math.max(0, mDisconnectAfter - mBytesAccepted));
                    mBytesAccepted += accepted;

                    if(accepted < length)
                    {
                        throw new IOException("simulated browser disconnect");
                    }
                }
            };
        }

        @Override
        public void sendResponseHeaders(int responseCode, long responseLength)
        {
            mResponseCode = responseCode;
            mResponseLength = responseLength;
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
        }

        @Override
        public int getResponseCode()
        {
            return mResponseCode;
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080);
        }

        @Override
        public String getProtocol()
        {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name)
        {
            return mAttributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value)
        {
            mAttributes.put(name, value);
        }

        @Override
        public void setStreams(InputStream input, OutputStream output)
        {
        }

        @Override
        public HttpPrincipal getPrincipal()
        {
            return null;
        }
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
