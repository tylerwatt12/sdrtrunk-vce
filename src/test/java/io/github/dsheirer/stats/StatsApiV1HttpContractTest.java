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
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path assets = mTemporaryDirectory.resolve("assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("index.html"), "<!doctype html><title>v1 test</title>");
        mPreviousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        TestApplicationPreference applicationPreference = new TestApplicationPreference();
        TestUserPreferences preferences = new TestUserPreferences(applicationPreference,
            new TestDirectoryPreference(dataRoot));
        mService = new StatsWebServerService(preferences);
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
        assertEquals(StatsApiV1.LIVE_CHANNEL_ACTIVITY,
            server.at("/live_channels/channel_activity").textValue());

        HttpResponse<String> dashboardResponse = get(StatsApiV1.DASHBOARD);
        assertEquals(200, dashboardResponse.statusCode(), dashboardResponse.body());
        JsonNode dashboard = OBJECT_MAPPER.readTree(dashboardResponse.body()).get("data");
        assertTrue(dashboard.has("source_activity_24h"), dashboardResponse.body());
        assertFalse(dashboard.has("source_activity24h"), dashboardResponse.body());

        HttpResponse<String> systemsResponse = get(StatsApiV1.SYSTEMS + "?limit=1");
        assertEquals(200, systemsResponse.statusCode(), systemsResponse.body());
        JsonNode systems = OBJECT_MAPPER.readTree(systemsResponse.body());
        assertTrue(systems.get("data").isArray(), systemsResponse.body());
        assertEquals(1, systems.at("/meta/limit").intValue(), systemsResponse.body());
        assertEquals(0, systems.at("/meta/offset").intValue(), systemsResponse.body());
        assertTrue(systems.at("/meta/has_more").isBoolean(), systemsResponse.body());
        assertFalse(systems.at("/meta").has("hasMore"), systemsResponse.body());

        HttpResponse<String> aliasesResponse = get(StatsApiV1.ALIAS_LISTS + "?limit=1");
        assertEquals(200, aliasesResponse.statusCode(), aliasesResponse.body());
        JsonNode aliases = OBJECT_MAPPER.readTree(aliasesResponse.body());
        assertTrue(aliases.get("data").isArray(), aliasesResponse.body());
        assertEquals(List.of("p25", "dmr", "nxdn", "nbfm"),
            OBJECT_MAPPER.convertValue(aliases.at("/meta/families"), List.class));
        assertEquals(List.of("dcs", "esn", "radio", "radio_range", "talkgroup", "talkgroup_range",
                "tone_sequence", "unit_status", "user_status"),
            OBJECT_MAPPER.convertValue(aliases.at("/meta/matcher_types"), List.class));
    }

    @Test
    void invalidQueriesAndMethodsUseTheStructuredErrorContract() throws Exception
    {
        HttpResponse<String> unknown = get(StatsApiV1.STATUS + "?surprise=true");
        assertStructuredError(unknown, 400, "unknown_parameter", "surprise");

        HttpResponse<String> unbounded = get(StatsApiV1.SYSTEMS + "?limit=501");
        assertStructuredError(unbounded, 400, "invalid_parameter", "limit");

        HttpResponse<String> removedPatchSpelling = get(StatsApiV1.ACTIVITY +
            "?talkgroup_id=1&kind=patch");
        assertStructuredError(removedPatchSpelling, 400, "invalid_parameter", "kind");

        HttpResponse<String> doubleEncodedPath = get(StatsApiV1.SYSTEMS + "/p25%253Atest");
        assertStructuredError(doubleEncodedPath, 400, "invalid_path", null);

        HttpResponse<String> missingCursor = get(StatsApiV1.ACTIVITY +
            "?before_id=999&talkgroup_id=1&kind=patch_group&radio_id=2&scope=p25:test" +
            "&guid=test-guid&context=test-context&hide_grants=true&limit=1");
        assertEquals(200, missingCursor.statusCode(), missingCursor.body());
        JsonNode emptyPage = OBJECT_MAPPER.readTree(missingCursor.body());
        assertEquals(0, emptyPage.get("data").size(), missingCursor.body());
        assertEquals(1, emptyPage.at("/meta/limit").intValue(), missingCursor.body());

        assertEquals(400, get(StatsApiV1.ALIASES + "?family=P25").statusCode());
        assertEquals(400, get(StatsApiV1.ALIASES + "?matcher=TALKGROUP").statusCode());

        HttpResponse<String> wrongMethod = send(HttpRequest.newBuilder(mOrigin.resolve(StatsApiV1.STATUS))
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build());
        assertStructuredError(wrongMethod, 405, "method_not_allowed", null);
        assertEquals("GET", wrongMethod.headers().firstValue("Allow").orElseThrow());
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
            "/api/system-directory",
            "/api/alias-list/observed-talkgroups?list=1",
            "/api/export.csv?dataset=system-talkgroups",
            "/api/tuner-diagnostics/targets",
            "/live/systems",
            "/live/events",
            "/live/web-calls",
            "/api/web-player/calls/1/audio",
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
            Map.entry("SYSTEMS", "/api/v1/systems"),
            Map.entry("SITES", "/api/v1/sites"),
            Map.entry("ACTIVITY", "/api/v1/activity"),
            Map.entry("CONVENTIONAL_CONTEXTS", "/api/v1/conventional-contexts"),
            Map.entry("EXPORTS", "/api/v1/exports"),
            Map.entry("TUNER_DIAGNOSTICS", "/api/v1/diagnostics/tuners"),
            Map.entry("LIVE_CHANNEL_ACTIVITY", "/api/v1/live/channel-activity"),
            Map.entry("LIVE_DECODE_EVENTS", "/api/v1/live/decode-events"),
            Map.entry("LIVE_DECODE_MESSAGES", "/api/v1/live/decode-messages"),
            Map.entry("LIVE_CHANNEL_DIAGNOSTICS", "/api/v1/live/channel-diagnostics"),
            Map.entry("LIVE_TUNER_DIAGNOSTICS", "/api/v1/live/tuner-diagnostics"),
            Map.entry("LIVE_SITES", "/api/v1/live/sites"),
            Map.entry("LIVE_CALLS", "/api/v1/live/calls"),
            Map.entry("LIVE_ACTIVITY", "/api/v1/live/activity"),
            Map.entry("CALLS", "/api/v1/calls")
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
    void liveSubscribersDoNotStarveOrdinaryApiRequests() throws Exception
    {
        List<HttpResponse<InputStream>> streams = new ArrayList<>();

        try
        {
            for(int index = 0; index < 12; index++)
            {
                HttpRequest request = HttpRequest.newBuilder(mOrigin.resolve(StatsApiV1.LIVE_CHANNEL_ACTIVITY))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
                HttpResponse<InputStream> stream = mClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, stream.statusCode());
                assertTrue(stream.headers().firstValue("Content-Type").orElse("")
                    .startsWith("text/event-stream"));
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
