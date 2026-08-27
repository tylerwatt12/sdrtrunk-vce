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
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.scanlist.ScanListModel;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HTTP-boundary regression coverage for the canonical v1 API and its hard removal of legacy routes.
 */
class StatsApiV1HttpContractTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    private StatsWebServerService mService;
    private HttpClient mClient;
    private URI mOrigin;
    private String mPreviousAssetOverride;

    @BeforeEach
    void startServer() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        seedSystemDirectory(database);
        Path assets = mTemporaryDirectory.resolve("assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("index.html"), "<!doctype html>" +
            "<meta name=\"sdrtrunk-web-revision\" content=\"test-revision\"><title>v1 test</title>");
        mPreviousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        TestApplicationPreference applicationPreference = new TestApplicationPreference();
        TestUserPreferences preferences = new TestUserPreferences(applicationPreference,
            new TestDirectoryPreference(dataRoot));
        ScanListModel scanListModel = new ScanListModel();
        scanListModel.replaceConfiguration(new ScanListConfiguration(List.of(
            new ScanList(1, 0, "Default", null, true, true)), Map.of(), Map.of()));
        mService = new StatsWebServerService(preferences, null, null, null, null, null, scanListModel);
        WebServerRuntimeState state = mService.getRuntimeState();
        assertTrue(state.running(), state.statusMessage());
        mOrigin = URI.create("http://127.0.0.1:" + state.port());
        mClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stopServer()
    {
        if(mService != null)
        {
            mService.close();
        }

        if(mPreviousAssetOverride == null)
        {
            System.clearProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        }
        else
        {
            System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, mPreviousAssetOverride);
        }
    }

    @Test
    void canonicalObjectAndCollectionRoutesUseTheSharedSnakeCaseEnvelope() throws Exception
    {
        HttpResponse<String> statusResponse = get(StatsApiV1.STATUS);
        assertEquals(200, statusResponse.statusCode(), statusResponse.body());
        assertEquals("application/json; charset=utf-8",
            statusResponse.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", statusResponse.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode status = OBJECT_MAPPER.readTree(statusResponse.body());
        assertEquals(1, status.size(), statusResponse.body());
        assertTrue(status.has("data"), statusResponse.body());
        JsonNode server = status.at("/data/server");
        assertTrue(server.isObject(), statusResponse.body());
        assertTrue(server.has("access_mode"), statusResponse.body());
        assertFalse(server.has("accessMode"), statusResponse.body());
        assertEquals(StatsApiV1.LIVE_MULTIPLEX,
            server.at("/live_transport/stream").textValue());
        assertEquals(StatsApiV1.LIVE_MULTIPLEX_CONTROL,
            server.at("/live_transport/control").textValue());

        JsonNode database = status.at("/data/database");
        assertTrue(database.isObject(), statusResponse.body());
        assertTrue(database.path("database_exists").isBoolean(), statusResponse.body());
        assertTrue(database.path("database_bytes").isIntegralNumber(), statusResponse.body());
        assertTrue(database.path("wal_bytes").isIntegralNumber(), statusResponse.body());
        assertTrue(database.path("shm_bytes").isIntegralNumber(), statusResponse.body());
        assertTrue(database.path("stats_logging_enabled").isBoolean(), statusResponse.body());
        assertTrue(database.path("detailed_history_enabled").isBoolean(), statusResponse.body());
        assertTrue(database.path("detailed_history_available").isBoolean(), statusResponse.body());
        assertTrue(database.path("last_detailed_history_ms").isIntegralNumber(), statusResponse.body());
        assertFalse(database.has("databaseBytes"), statusResponse.body());

        JsonNode statsLogging = status.at("/data/stats_logging");
        assertTrue(statsLogging.isObject(), statusResponse.body());
        assertTrue(statsLogging.path("summary_configured").isBoolean(), statusResponse.body());
        assertTrue(statsLogging.path("detailed_history_configured").isBoolean(), statusResponse.body());
        assertTrue(statsLogging.path("summary_active").isBoolean(), statusResponse.body());
        assertTrue(statsLogging.path("detailed_history_active").isBoolean(), statusResponse.body());
        assertTrue(statsLogging.path("retention_days").isIntegralNumber(), statusResponse.body());
        assertTrue(statsLogging.path("state").isTextual(), statusResponse.body());
        assertFalse(status.at("/data").has("statsLogging"), statusResponse.body());

        HttpResponse<String> dashboardResponse = get(StatsApiV1.DASHBOARD);
        assertEquals(200, dashboardResponse.statusCode(), dashboardResponse.body());
        JsonNode dashboard = OBJECT_MAPPER.readTree(dashboardResponse.body()).get("data");
        assertTrue(dashboard.has("source_activity_24h"), dashboardResponse.body());
        assertFalse(dashboard.has("source_activity24h"), dashboardResponse.body());

        HttpResponse<String> actionsResponse = get(StatsApiV1.ACTIVITY_ACTIONS + "?range=24h");
        assertEquals(200, actionsResponse.statusCode(), actionsResponse.body());
        JsonNode actions = OBJECT_MAPPER.readTree(actionsResponse.body());
        assertEquals(2, actions.size(), actionsResponse.body());
        assertTrue(actions.path("data").isArray(), actionsResponse.body());
        assertEquals(4, actions.path("meta").size(), actionsResponse.body());
        assertEquals("24h", actions.at("/meta/range").textValue(), actionsResponse.body());
        assertTrue(actions.at("/meta/from_ms").isIntegralNumber(), actionsResponse.body());
        assertTrue(actions.at("/meta/to_ms").isIntegralNumber(), actionsResponse.body());
        assertTrue(actions.at("/meta/total_observation_count").isIntegralNumber(), actionsResponse.body());
        assertFalse(actions.path("meta").has("group_by"), actionsResponse.body());

        for(JsonNode row: actions.path("data"))
        {
            assertEquals(2, row.size(), actionsResponse.body());
            assertTrue(row.path("action").isTextual(), actionsResponse.body());
            assertTrue(row.path("observation_count").isIntegralNumber(), actionsResponse.body());
            assertFalse("continue".equals(row.path("action").textValue()), actionsResponse.body());
            assertFalse(row.has("detail_supported"), actionsResponse.body());
        }

        HttpResponse<String> radiosResponse = get(StatsApiV1.ACTIVITY_RADIOS +
            "?range=24h&action=GRANT&limit=1&offset=0");
        assertEquals(200, radiosResponse.statusCode(), radiosResponse.body());
        JsonNode radios = OBJECT_MAPPER.readTree(radiosResponse.body());
        assertEquals(2, radios.size(), radiosResponse.body());
        assertTrue(radios.path("data").isArray(), radiosResponse.body());
        assertEquals(13, radios.path("meta").size(), radiosResponse.body());
        assertEquals("24h", radios.at("/meta/range").textValue(), radiosResponse.body());
        assertEquals("grant", radios.at("/meta/action").textValue(), radiosResponse.body());
        assertTrue(radios.at("/meta/from_ms").isIntegralNumber(), radiosResponse.body());
        assertTrue(radios.at("/meta/to_ms").isIntegralNumber(), radiosResponse.body());
        assertTrue(radios.at("/meta/action_observation_count").isIntegralNumber(), radiosResponse.body());
        assertTrue(radios.at("/meta/retained_observation_count").isIntegralNumber(), radiosResponse.body());
        assertTrue(radios.at("/meta/identified_observation_count").isIntegralNumber(), radiosResponse.body());
        assertTrue(radios.at("/meta/unknown_source_observation_count").isIntegralNumber(), radiosResponse.body());
        assertTrue(radios.at("/meta/total_count").isIntegralNumber(), radiosResponse.body());
        assertEquals(1, radios.at("/meta/limit").intValue(), radiosResponse.body());
        assertEquals(0, radios.at("/meta/offset").intValue(), radiosResponse.body());
        assertTrue(radios.at("/meta/has_more").isBoolean(), radiosResponse.body());
        assertTrue(radios.path("meta").has("next_offset"), radiosResponse.body());

        HttpResponse<String> systemsResponse = get(StatsApiV1.SYSTEMS + "?limit=1");
        assertEquals(200, systemsResponse.statusCode(), systemsResponse.body());
        JsonNode systems = OBJECT_MAPPER.readTree(systemsResponse.body());
        assertTrue(systems.get("data").isArray(), systemsResponse.body());
        assertEquals(1, systems.at("/meta/limit").intValue(), systemsResponse.body());
        assertEquals(0, systems.at("/meta/offset").intValue(), systemsResponse.body());
        assertTrue(systems.at("/meta/has_more").isBoolean(), systemsResponse.body());
        assertFalse(systems.at("/meta").has("hasMore"), systemsResponse.body());
        assertFalse(systems.at("/data/0").has("site_preview"), systemsResponse.body());
        assertEquals(71, systems.at("/data/0/alias_list_id").intValue(), systemsResponse.body());
        assertEquals("HTTP Aliases", systems.at("/data/0/alias_list_name").textValue(),
            systemsResponse.body());
        assertEquals("linked_system", systems.at("/data/0/scope_kind").textValue(),
            systemsResponse.body());

        HttpResponse<String> systemPreviewResponse = get(StatsApiV1.SYSTEMS +
            "?include_site_preview=true&limit=25");
        assertEquals(200, systemPreviewResponse.statusCode(), systemPreviewResponse.body());
        JsonNode systemPreview = OBJECT_MAPPER.readTree(systemPreviewResponse.body());
        assertTrue(systemPreview.get("data").isArray(), systemPreviewResponse.body());
        assertEquals(StatsWebDatabase.MAXIMUM_SYSTEM_DIRECTORY_SITE_PREVIEW,
            systemPreview.at("/meta/site_preview_limit_per_system").intValue(),
            systemPreviewResponse.body());
        assertTrue(systemPreview.at("/data/0/site_preview").isArray(), systemPreviewResponse.body());
        assertEquals(1, systemPreview.at("/data/0/site_preview").size(), systemPreviewResponse.body());
        assertFalse(systemPreview.at("/data/0/site_preview_truncated").booleanValue(),
            systemPreviewResponse.body());
        JsonNode previewSite = systemPreview.at("/data/0/site_preview/0");
        assertEquals("p25", previewSite.path("protocol").textValue(), systemPreviewResponse.body());
        assertEquals("trunked", previewSite.path("site_kind").textValue(), systemPreviewResponse.body());
        assertEquals(2, previewSite.path("site_id").intValue(), systemPreviewResponse.body());
        assertEquals(71, previewSite.path("alias_list_id").intValue(), systemPreviewResponse.body());
        assertFalse(previewSite.has("scope_id"), systemPreviewResponse.body());
        assertFalse(previewSite.has("protocol_code"), systemPreviewResponse.body());

        HttpResponse<String> aliasesResponse = get(StatsApiV1.ALIAS_LISTS + "?limit=1");
        assertStructuredError(aliasesResponse, 401, "authentication_required", null);
    }

    @Test
    void staticIndexPublishesItsWebClientRevisionWithoutExpandingTheApi() throws Exception
    {
        HttpResponse<String> response = send(HttpRequest.newBuilder(mOrigin)
            .timeout(Duration.ofSeconds(10))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build());
        assertEquals(200, response.statusCode());
        assertEquals("test-revision", response.headers().firstValue("X-Sdrtrunk-Web-Revision").orElseThrow());
        assertTrue(response.body().isEmpty());
    }

    @Test
    void invalidQueriesAndMethodsUseTheStructuredErrorContract() throws Exception
    {
        HttpResponse<String> unknown = get(StatsApiV1.STATUS + "?surprise=true");
        assertStructuredError(unknown, 400, "unknown_parameter", "surprise");

        HttpResponse<String> unbounded = get(StatsApiV1.SYSTEMS + "?limit=501");
        assertStructuredError(unbounded, 400, "invalid_parameter", "limit");

        HttpResponse<String> invalidPreview = get(StatsApiV1.SYSTEMS + "?include_site_preview=maybe");
        assertStructuredError(invalidPreview, 400, "invalid_parameter", "include_site_preview");

        HttpResponse<String> oversizedPreviewPage = get(StatsApiV1.SYSTEMS +
            "?include_site_preview=true&limit=26");
        assertStructuredError(oversizedPreviewPage, 400, "invalid_parameter", "limit");

        HttpResponse<String> invalidAffiliated = get(StatsApiV1.SYSTEMS +
            "/p25%3A00001%3A047/radios?affiliated=maybe");
        assertStructuredError(invalidAffiliated, 400, "invalid_parameter", "affiliated");

        HttpResponse<String> kindWithoutTalkgroup = get(StatsApiV1.SYSTEMS +
            "/p25%3A00001%3A047/relationships?radio_id=1&kind=patch_group");
        assertStructuredError(kindWithoutTalkgroup, 400, "invalid_parameter", "kind");

        HttpResponse<String> removedPatchSpelling = get(StatsApiV1.ACTIVITY +
            "?talkgroup_id=1&kind=patch");
        assertStructuredError(removedPatchSpelling, 400, "invalid_parameter", "kind");

        HttpResponse<String> missingRadioAction = get(StatsApiV1.ACTIVITY_RADIOS + "?range=24h");
        assertStructuredError(missingRadioAction, 400, "invalid_parameter", "action");

        HttpResponse<String> invalidRadioAction = get(StatsApiV1.ACTIVITY_RADIOS +
            "?range=24h&action=not-real");
        assertStructuredError(invalidRadioAction, 400, "invalid_parameter", "action");

        HttpResponse<String> continueRadioAction = get(StatsApiV1.ACTIVITY_RADIOS +
            "?range=24h&action=CONTINUE");
        assertStructuredError(continueRadioAction, 400, "invalid_parameter", "action");

        HttpResponse<String> legacyActionsGroupBy = get(StatsApiV1.ACTIVITY_ACTIONS +
            "?range=24h&group_by=action");
        assertStructuredError(legacyActionsGroupBy, 400, "unknown_parameter", "group_by");

        HttpResponse<String> legacyActionsLimit = get(StatsApiV1.ACTIVITY_ACTIONS +
            "?range=24h&limit=1");
        assertStructuredError(legacyActionsLimit, 400, "unknown_parameter", "limit");

        HttpResponse<String> legacyRadiosGroupBy = get(StatsApiV1.ACTIVITY_RADIOS +
            "?range=24h&action=GRANT&group_by=radio");
        assertStructuredError(legacyRadiosGroupBy, 400, "unknown_parameter", "group_by");

        HttpResponse<String> doubleEncodedPath = get(StatsApiV1.SYSTEMS + "/p25%253Atest");
        assertStructuredError(doubleEncodedPath, 400, "invalid_path", null);

        HttpResponse<String> missingCursor = get(StatsApiV1.ACTIVITY +
            "?before_id=999&talkgroup_id=1&kind=patch_group&radio_id=2&scope=p25:test" +
            "&guid=test-guid&context=test-context&hide_grants=true&limit=1");
        assertEquals(200, missingCursor.statusCode(), missingCursor.body());
        JsonNode emptyPage = OBJECT_MAPPER.readTree(missingCursor.body());
        assertEquals(0, emptyPage.get("data").size(), missingCursor.body());
        assertEquals(1, emptyPage.at("/meta/limit").intValue(), missingCursor.body());

        assertEquals(401, get(StatsApiV1.ALIASES + "?family=P25").statusCode());
        assertEquals(401, get(StatsApiV1.ALIASES + "?matcher=TALKGROUP").statusCode());

        HttpResponse<String> wrongMethod = send(HttpRequest.newBuilder(mOrigin.resolve(StatsApiV1.STATUS))
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build());
        assertStructuredError(wrongMethod, 405, "method_not_allowed", null);
        assertEquals("GET", wrongMethod.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void conventionalCsvExportsAcceptOnlyCanonicalConfigurationIdentity() throws Exception
    {
        String configurationId = "00000000-0000-0000-0000-000000000072";
        HttpResponse<String> canonical = get(StatsApiV1.EXPORTS +
            "/conventional-talkgroups.csv?configuration_id=" + configurationId);
        assertEquals(200, canonical.statusCode(), canonical.body());
        assertTrue(canonical.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));

        HttpResponse<String> removedContext = get(StatsApiV1.EXPORTS +
            "/conventional-talkgroups.csv?context=retired-context");
        assertStructuredError(removedContext, 400, "unknown_parameter", "context");
    }

    @Test
    void systemGroupIdentityCollectionAcceptsItsPathScope() throws Exception
    {
        HttpResponse<String> response = get(StatsApiV1.SYSTEMS +
            "/p25%3A00001%3A047/group-identities?limit=1");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode page = OBJECT_MAPPER.readTree(response.body());
        assertTrue(page.get("data").isArray(), response.body());
        assertEquals(1, page.at("/meta/limit").intValue(), response.body());
    }

    @Test
    void legacyReadLiveExportAndAudioRoutesAreNotCompatibilityEndpoints() throws Exception
    {
        for(String path: List.of(
            "/api/status",
            "/api/talkgroup?scope=p25%3ABEE00%3A348&id=51900",
            "/api/system-directory",
            "/api/alias-list/observed-talkgroups?list=1",
            "/api/export.csv?dataset=system-talkgroups",
            "/api/tuner-diagnostics/targets",
            "/live/systems",
            "/live/events",
            "/live/web-calls",
            "/api/v1/live/channel-activity",
            "/api/v1/live/decode-events?configuration_id=00000000-0000-0000-0000-000000000001",
            "/api/v1/live/decode-messages?configuration_id=00000000-0000-0000-0000-000000000001",
            "/api/v1/live/channel-diagnostics?configuration_id=00000000-0000-0000-0000-000000000001",
            "/api/v1/live/tuner-diagnostics?target_id=retired",
            "/api/v1/live/sites",
            "/api/v1/live/calls",
            "/api/v1/live/activity",
            "/api/v1/activity-analytics?range=24h&group_by=action",
            "/api/web-player/calls/1/audio",
            "/api/v1/systems/p25%3Atest/affiliations",
            "/api/v1/not-a-resource"))
        {
            HttpResponse<String> response = get(path);
            assertStructuredError(response, 404, "not_found", null);
        }
    }

    @Test
    void routeManifestDefinesOneUniqueFirstVersionSurface() throws Exception
    {
        Map<String,String> expected = Map.ofEntries(
            Map.entry("ROOT", "/api/v1"),
            Map.entry("STATUS", "/api/v1/status"),
            Map.entry("DASHBOARD", "/api/v1/dashboard"),
            Map.entry("QUALITY", "/api/v1/quality"),
            Map.entry("ALIAS_LISTS", "/api/v1/alias-lists"),
            Map.entry("ALIASES", "/api/v1/aliases"),
            Map.entry("SCAN_LISTS", "/api/v1/scan-lists"),
            Map.entry("SYSTEMS", "/api/v1/systems"),
            Map.entry("SITES", "/api/v1/sites"),
            Map.entry("ACTIVITY", "/api/v1/activity"),
            Map.entry("ACTIVITY_ACTIONS", "/api/v1/activity/actions"),
            Map.entry("ACTIVITY_RADIOS", "/api/v1/activity/radios"),
            Map.entry("CONVENTIONAL_CHANNELS", "/api/v1/conventional-channels"),
            Map.entry("EXPORTS", "/api/v1/exports"),
            Map.entry("TUNER_DIAGNOSTICS", "/api/v1/diagnostics/tuners"),
            Map.entry("RECEIVER_HEALTH", "/api/v1/receiver-health"),
            Map.entry("LIVE_MULTIPLEX", "/api/v1/live/multiplex"),
            Map.entry("LIVE_MULTIPLEX_CONTROL", "/api/v1/live/multiplex/control"),
            Map.entry("CALLS", "/api/v1/calls"),
            Map.entry("RADIO_REFERENCE", "/api/v1/admin/radioreference")
        );
        Map<String,String> actual = new LinkedHashMap<>();

        for(Field field: StatsApiV1.class.getDeclaredFields())
        {
            if(field.getType() == String.class && Modifier.isPublic(field.getModifiers()) &&
                Modifier.isStatic(field.getModifiers()))
            {
                actual.put(field.getName(), (String)field.get(null));
            }
        }

        assertEquals(expected, actual);
        assertEquals(actual.size(), actual.values().stream().distinct().count());
        assertTrue(actual.entrySet().stream()
            .filter(entry -> !"ROOT".equals(entry.getKey()))
            .allMatch(entry -> entry.getValue().startsWith(StatsApiV1.ROOT + "/")));
    }

    @Test
    void multiplexedBrowserDocumentsDoNotStarveOrdinaryApiRequests() throws Exception
    {
        List<HttpResponse<InputStream>> streams = new ArrayList<>();

        try
        {
            for(int index = 0; index < 4; index++)
            {
                String clientId = "00000000-0000-0000-0000-" + String.format("%012d", index + 1);
                HttpRequest request = HttpRequest.newBuilder(mOrigin.resolve(
                        StatsApiV1.LIVE_MULTIPLEX + "?client_id=" + clientId))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
                HttpResponse<InputStream> stream = mClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, stream.statusCode());
                assertTrue(stream.headers().firstValue("Content-Type").orElse("")
                    .startsWith("application/vnd.sdrtrunk.live+binary"));
                streams.add(stream);
            }

            HttpResponse<String> status = get(StatsApiV1.STATUS);
            assertEquals(200, status.statusCode(), status.body());
            assertTrue(OBJECT_MAPPER.readTree(status.body()).has("data"));
        }
        finally
        {
            for(HttpResponse<InputStream> stream: streams)
            {
                stream.body().close();
            }
        }
    }

    @Test
    void oneMultiplexConnectionCarriesMultipleLogicalSubscriptions() throws Exception
    {
        String clientId = "00000000-0000-0000-0000-000000000123";
        HttpRequest streamRequest = HttpRequest.newBuilder(mOrigin.resolve(
                StatsApiV1.LIVE_MULTIPLEX + "?client_id=" + clientId))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        HttpResponse<InputStream> stream = mClient.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());

        try(InputStream input = stream.body())
        {
            MultiplexFrame ready = readMultiplexFrame(input);
            assertEquals(0, ready.topic());
            assertEquals("ready", ready.json().path("event").textValue());
            assertEquals(clientId, ready.json().at("/data/client_id").textValue());

            String body = OBJECT_MAPPER.writeValueAsString(Map.of(
                "client_id", clientId,
                "revision", 1,
                "subscriptions", Map.of("channel_activity", Map.of(), "calls", Map.of())));
            HttpRequest control = HttpRequest.newBuilder(mOrigin.resolve(StatsApiV1.LIVE_MULTIPLEX_CONTROL))
                .timeout(Duration.ofSeconds(10))
                .header("Origin", mOrigin.toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> accepted = send(control);
            assertEquals(200, accepted.statusCode(), accepted.body());
            assertEquals(1, OBJECT_MAPPER.readTree(accepted.body()).at("/data/revision").intValue());

            boolean activity = false;
            boolean calls = false;

            for(int count = 0; count < 4 && (!activity || !calls); count++)
            {
                MultiplexFrame frame = readMultiplexFrame(input);
                activity |= frame.topic() == 1 && "snapshot".equals(frame.json().path("event").textValue());
                calls |= frame.topic() == 2 && "snapshot".equals(frame.json().path("event").textValue());
            }

            assertTrue(activity, "Channel activity did not arrive on the multiplex connection");
            assertTrue(calls, "Call snapshot did not arrive on the multiplex connection");
        }
    }

    @Test
    void rejectsHistoricalActivityAsAMultiplexSubscription() throws Exception
    {
        String body = OBJECT_MAPPER.writeValueAsString(Map.of(
            "client_id", "00000000-0000-0000-0000-000000000124",
            "revision", 1,
            "subscriptions", Map.of("activity", Map.of())));
        HttpRequest control = HttpRequest.newBuilder(mOrigin.resolve(StatsApiV1.LIVE_MULTIPLEX_CONTROL))
            .timeout(Duration.ofSeconds(10))
            .header("Origin", mOrigin.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        assertStructuredError(send(control), 400, "invalid_request", null);
    }

    private static MultiplexFrame readMultiplexFrame(InputStream input) throws Exception
    {
        byte[] headerBytes = input.readNBytes(16);
        assertEquals(16, headerBytes.length, "Multiplex frame header was truncated");
        ByteBuffer header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN);
        assertEquals(0x534C4D58, header.getInt());
        assertEquals(1, Byte.toUnsignedInt(header.get()));
        int kind = Byte.toUnsignedInt(header.get());
        int topic = Short.toUnsignedInt(header.getShort());
        int length = header.getInt();
        header.getInt();
        byte[] payload = input.readNBytes(length);
        assertEquals(length, payload.length, "Multiplex frame payload was truncated");
        JsonNode json = kind == 1 ? OBJECT_MAPPER.readTree(new String(payload, StandardCharsets.UTF_8)) : null;
        return new MultiplexFrame(kind, topic, json);
    }

    private record MultiplexFrame(int kind, int topic, JsonNode json)
    {
    }

    private static void seedSystemDirectory(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list (id, name, family) " +
                "VALUES (71, 'HTTP Aliases', 'P25')");
            statement.executeUpdate("INSERT INTO p25_system VALUES (1, 1, 71, 1000, 2000)");
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    alias_list_name, radres_guid, decoder_type, primary_frequency_hz, config_json
                ) VALUES ('00000000-0000-0000-0000-000000000071', 'TRUNKED', 71,
                    'HTTP System', 'HTTP Site', 'HTTP Site', 'HTTP Aliases',
                    '00000000-0000-0000-0000-000000000071', 'P25_PHASE1', 851012500, '{}')
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    decoder_type, primary_frequency_hz, config_json
                ) VALUES ('00000000-0000-0000-0000-000000000072', 'CONVENTIONAL', 72,
                    'HTTP Conventional', 'HTTP County', 'HTTP Fire', 'NBFM', 154310000, '{}')
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name, decoder,
                    first_seen_ms, last_seen_ms, system_key, rfss, site, current_control_hz
                ) VALUES (1, 'GUID:00000000-0000-0000-0000-000000000071',
                    '00000000-0000-0000-0000-000000000071', 1, 1,
                    'HTTP Site', 'HTTP Aliases', 'P25-1',
                    1000, 2000, 1, 1, 2, 851012500)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope (
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    alias_list_id, p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES (1, 'p25:00001:047', 1, 1, 0, 71, 1, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context (scope_id, context_id, first_seen_ms, last_seen_ms)
                VALUES (1, 1, 1000, 2000)
                """);
        }
    }

    private HttpResponse<String> get(String path) throws Exception
    {
        return send(HttpRequest.newBuilder(mOrigin.resolve(path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception
    {
        return mClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertStructuredError(HttpResponse<String> response, int status, String code, String field)
        throws Exception
    {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals("application/json; charset=utf-8",
            response.headers().firstValue("Content-Type").orElseThrow());
        JsonNode body = OBJECT_MAPPER.readTree(response.body());
        assertEquals(1, body.size(), response.body());
        assertEquals(code, body.at("/error/code").textValue(), response.body());
        assertEquals(status, body.at("/error/status").intValue(), response.body());
        assertTrue(body.at("/error/message").isTextual(), response.body());

        if(field != null)
        {
            assertEquals(field, body.at("/error/field").textValue(), response.body());
        }
        else
        {
            assertFalse(body.at("/error").has("field"), response.body());
        }
    }

    private static final class TestUserPreferences extends UserPreferences
    {
        private final ApplicationPreference mApplicationPreference;
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(ApplicationPreference applicationPreference,
                                    DirectoryPreference directoryPreference)
        {
            mApplicationPreference = applicationPreference;
            mDirectoryPreference = directoryPreference;
        }

        @Override
        public ApplicationPreference getApplicationPreference()
        {
            return mApplicationPreference;
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }

    private static final class TestApplicationPreference extends ApplicationPreference
    {
        private TestApplicationPreference()
        {
            super(preferenceType -> {});
        }

        @Override
        public boolean isStatsWebServerEnabled()
        {
            return true;
        }

        @Override
        public int getStatsWebServerPort()
        {
            return 0;
        }

        @Override
        public boolean isStatsWebServerAnyIpEnabled()
        {
            return false;
        }

        @Override
        public boolean isStatsWebServerHttpsEnabled()
        {
            return false;
        }
    }

    private static final class TestDirectoryPreference extends DirectoryPreference
    {
        private final Path mDataRoot;

        private TestDirectoryPreference(Path dataRoot)
        {
            super(preferenceType -> {});
            mDataRoot = dataRoot;
        }

        @Override
        public Path getDirectoryApplicationRoot()
        {
            return mDataRoot;
        }
    }
}
