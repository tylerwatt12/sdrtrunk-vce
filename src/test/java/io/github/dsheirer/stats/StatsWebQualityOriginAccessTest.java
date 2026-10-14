/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.remote.RemoteOriginLookup;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.WebRequestSecurity;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebQualityOriginAccessTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String REMOTE = "remote-channel";
    private static final String LOCAL = "local-channel";
    private static final String UNKNOWN = "unknown-channel";

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void qualityUsesSavedRemoteMappingsWithoutExposingAdministrativeOriginFields() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        char[] password = "quality-origin-admin-password".toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(password);
        }
        finally
        {
            Arrays.fill(password, '\u0000');
        }

        accessService.setCapabilityTier(WebCapability.DASHBOARD_VIEW, AccessTier.PUBLIC);
        accessService.setCapabilityTier(WebCapability.RADIO_VIEW, AccessTier.ADMIN);
        WebRequestSecurity requestSecurity = new WebRequestSecurity(accessService,
            new WebAuthenticationService(accessService));
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        QualityDatabase statsDatabase = new QualityDatabase(database);
        AtomicReference<RemoteOriginLookup.OriginSnapshot> origins = new AtomicReference<>(
            new RemoteOriginLookup.OriginSnapshot(1L, Map.of(REMOTE, new RemoteOriginLookup.RemoteOrigin(
                "administrator-sender-id", "Administrator sender", "administrator-feed-id",
                "Administrator feed", null, null))));
        new StatsApiV1Controller(statsDatabase, Map::of, requestSecurity, null, Map::of, null,
            () -> 2500, origins::get).register(server);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> dashboard = get(client, origin, StatsApiV1.QUALITY);
            assertEquals(200, dashboard.statusCode(), dashboard.body());
            JsonNode rows = OBJECT_MAPPER.readTree(dashboard.body()).path("data");
            assertEquals(Map.of("remote", true), OBJECT_MAPPER.convertValue(
                rows.get(0).path("remote_origin"), Map.class));
            assertEquals(99.3, rows.get(0).path("decode_health_pct").doubleValue());
            assertTrue(rows.get(0).path("average_signal_dbfs").isNull());
            assertFalse(rows.get(1).has("remote_origin"));
            assertFalse(rows.get(2).has("remote_origin"));
            assertFalse(dashboard.body().contains("administrator-sender"));
            assertFalse(dashboard.body().contains("administrator-feed"));
            assertFalse(dashboard.body().contains("Administrator sender"));
            assertFalse(dashboard.body().contains("Administrator feed"));
            assertFalse(statsDatabase.mChannels.getFirst().containsKey("remote_origin"));
            assertEquals(401, get(client, origin, StatsApiV1.CHANNELS + "/" + REMOTE + "/quality").statusCode());

            accessService.setCapabilityTier(WebCapability.RADIO_VIEW, AccessTier.PUBLIC);
            HttpResponse<String> channel = get(client, origin, StatsApiV1.CHANNELS + "/" + REMOTE + "/quality");
            assertEquals(200, channel.statusCode(), channel.body());
            JsonNode selected = OBJECT_MAPPER.readTree(channel.body()).at("/data/0");
            assertEquals(rows.get(0).path("remote_origin"), selected.path("remote_origin"));
            assertEquals(99.3, selected.at("/series/0/decode_health_pct").doubleValue());

            origins.set(RemoteOriginLookup.OriginSnapshot.EMPTY);
            HttpResponse<String> unmapped = get(client, origin, StatsApiV1.QUALITY);
            assertEquals(200, unmapped.statusCode(), unmapped.body());
            assertFalse(OBJECT_MAPPER.readTree(unmapped.body()).at("/data/0").has("remote_origin"));
        }
        finally
        {
            server.stop(0);
            requestSecurity.close();
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI origin, String path) throws Exception
    {
        return client.send(HttpRequest.newBuilder(origin.resolve(path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static class QualityDatabase extends StatsWebDatabase
    {
        private final List<Map<String,Object>> mChannels = List.of(row(REMOTE), row(LOCAL), row(UNKNOWN));

        private QualityDatabase(Path database)
        {
            super(new UserPreferences(), database);
        }

        @Override
        Map<String,Object> qualityHistory(StatsRequest request)
        {
            return Map.of("channels", mChannels, "history_included", false);
        }

        @Override
        Map<String,Object> channelQuality(String configurationId, StatsRequest request)
        {
            return Map.of("channels", mChannels.stream().filter(row ->
                configurationId.equals(row.get("configuration_id"))).toList(), "history_included", true);
        }

        private static Map<String,Object> row(String configurationId)
        {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("configuration_id", configurationId);
            row.put("decode_health_pct", 99.3);
            row.put("signal_dbfs", null);
            row.put("average_signal_dbfs", null);
            row.put("series", List.of(Map.of("time_ms", 1000L, "decode_health_pct", 99.3)));
            return Collections.unmodifiableMap(row);
        }
    }
}
