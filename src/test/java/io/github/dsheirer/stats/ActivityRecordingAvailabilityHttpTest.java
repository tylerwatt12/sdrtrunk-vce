/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivityRecordingAvailabilityHttpTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE = CallRelationsServiceTest.A;
    private static final String CHANNEL = "00000000-0000-0000-0000-000000000071";
    private static final String QUERY = "?configuration_id=" + CHANNEL +
        "&from_ms=1&to_ms=5000&hide_grants=false&limit=20";
    @TempDir Path directory;

    @Test
    void availabilityRequiresRecordingsPermissionAndNeverExposesTheInternalCallReference() throws Exception
    {
        try(var fixture = new Fixture(directory))
        {
            fixture.access.setCapabilityTier(WebCapability.RECORDINGS_VIEW, AccessTier.ADMIN);
            var radioOnly = fixture.get(QUERY);
            assertEquals(200, radioOnly.statusCode(), radioOnly.body());
            assertEquals(2, data(radioOnly).size());
            assertFalse(data(radioOnly).get(0).path("has_recording").asBoolean());
            assertEquals(0, fixture.sourceReads, "Hidden recordings must not resolve private source references");
            assertEquals(0, fixture.catalogReads);
            assertPrivateReferencesAbsent(radioOnly);

            fixture.access.setCapabilityTier(WebCapability.RECORDINGS_VIEW, AccessTier.PUBLIC);
            var permitted = fixture.get(QUERY);
            assertEquals(200, permitted.statusCode(), permitted.body());
            assertEquals(1, fixture.sourceReads);
            assertEquals(1, fixture.catalogReads);
            assertTrue(row(permitted, 7L).path("has_recording").asBoolean(), permitted.body());
            assertTrue(row(permitted, 7L).path("duration_ms").isNull(), permitted.body());
            assertFalse(row(permitted, 8L).path("has_recording").asBoolean(), permitted.body());
            assertEquals(1500L, row(permitted, 8L).path("duration_ms").asLong(), permitted.body());
            assertPrivateReferencesAbsent(permitted);

            fixture.access.setCapabilityTier(WebCapability.RADIO_VIEW, AccessTier.ADMIN);
            assertEquals(401, fixture.get(QUERY).statusCode());
            assertEquals(1, fixture.sourceReads, "Unauthorized Activity never reaches either lookup");
            assertEquals(1, fixture.catalogReads);
        }
    }

    @Test
    void catalogFailureKeepsActivityReadableAndCompactProjectionSkipsRecordingLookups() throws Exception
    {
        try(var fixture = new Fixture(directory))
        {
            fixture.failCatalog = true;
            var ordinary = fixture.get(QUERY);
            assertEquals(200, ordinary.statusCode(), ordinary.body());
            assertEquals(2, data(ordinary).size());
            for(JsonNode row: data(ordinary)) assertFalse(row.path("has_recording").asBoolean());
            assertFalse(ordinary.body().contains("private-catalog-location"));
            assertPrivateReferencesAbsent(ordinary);
            assertEquals(1, fixture.sourceReads);
            assertEquals(1, fixture.catalogReads);

            var compact = fixture.get("?projection=p25_visualizer&from_ms=1&to_ms=5000&actions=CALL&after_id=0&limit=20");
            assertEquals(200,compact.statusCode(),compact.body());
            assertEquals(2,data(compact).size());
            for(JsonNode row:data(compact))
            {
                assertFalse(row.has("has_recording"));
                assertFalse(row.has("has_call_source"));
            }
            assertPrivateReferencesAbsent(compact);
            assertEquals(1, fixture.sourceReads,"compact history never looks up private call references");
            assertEquals(1, fixture.catalogReads,"compact history never reads the recording catalog");
        }
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception
    { return JSON.readTree(response.body()).path("data"); }

    private static JsonNode row(HttpResponse<String> response, long id) throws Exception
    {
        for(JsonNode row: data(response)) if(row.path("id").asLong() == id) return row;
        throw new AssertionError("Missing Activity " + id);
    }

    private static void assertPrivateReferencesAbsent(HttpResponse<String> response)
    {
        assertFalse(response.body().contains(SOURCE));
        assertFalse(response.body().contains("source_call_uuid"));
    }

    private static final class Fixture implements AutoCloseable
    {
        final WebAccessService access;
        final WebRequestSecurity security;
        final HttpServer server;
        final URI origin;
        final HttpClient client = HttpClient.newHttpClient();
        int sourceReads, catalogReads;
        boolean failCatalog;

        Fixture(Path directory) throws Exception
        {
            Path file = directory.resolve("activity.sqlite");
            SdrTrunkDatabaseStartup.createGlobalDatabase(file);
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement())
            {
                statement.executeUpdate("""
                    INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,
                        site_name,name,alias_list_id,decoder_type,primary_frequency_hz,config_json)
                    VALUES('%s','TRUNKED',71,'County system','North','County North',1,
                        'P25_PHASE1',851012500,'{}')
                    """.formatted(CHANNEL));
                statement.executeUpdate("""
                    INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,
                        p25_system_id,first_seen_ms,last_seen_ms)
                    VALUES(71,'p25:bee00:49f',1,0,0xBEE00,0x49F,1000,4000)
                    """);
                statement.executeUpdate("""
                    INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,
                        radio_system_id,radio_system_assigned_at_ms)
                    VALUES(71,'%s',1000,4000,71,1000)
                    """.formatted(CHANNEL));
                statement.executeUpdate("""
                    INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,
                        action_code,event_type_code,source_call_uuid,duration_ms)
                    VALUES(7,71,71,1000,4,7,'%s',NULL),
                          (8,71,71,2000,4,7,NULL,1500)
                    """.formatted(SOURCE));
            }
            access = new WebAccessService(file);
            access.provisionOrResetPrimaryAdmin("availability-fixture-password".toCharArray());
            access.setCapabilityTier(WebCapability.WEB_ACCESS, AccessTier.PUBLIC);
            access.setCapabilityTier(WebCapability.RADIO_VIEW, AccessTier.PUBLIC);
            access.setCapabilityTier(WebCapability.RECORDINGS_VIEW, AccessTier.PUBLIC);
            security = new WebRequestSecurity(access, new WebAuthenticationService(access));
            var availability = new ActivityRecordingAvailability(ids -> {
                sourceReads++;
                assertEquals(List.of(8L, 7L), ids);
                return Map.of(7L, SOURCE);
            }, (sources, limit) -> {
                catalogReads++;
                if(failCatalog) throw new SQLException("private-catalog-location " + SOURCE);
                assertEquals(List.of(SOURCE), sources);
                assertEquals(1, limit);
                return Map.of(SOURCE, List.of(1L));
            });
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            new StatsApiV1Controller(new StatsWebDatabase(new UserPreferences(), file), Map::of, security,
                null, Map::of, null, () -> 2500, RemoteOriginLookup.EMPTY, availability, List::of).register(server);
            server.start();
            origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        HttpResponse<String> get(String query) throws Exception
        {
            return client.send(HttpRequest.newBuilder(origin.resolve(StatsApiV1.ACTIVITY + query))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override public void close()
        { server.stop(0); security.close(); }
    }
}
