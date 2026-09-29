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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivityMaintenance;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.activity.RetainedSiteKey;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.http.WebSessionHttpController;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedStatisticsAdminHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSTEM = "p25:bee00:49f";
    private static final String OTHER_SYSTEM = "p25:bee00:4a0";
    private static final String SITE_A = "00000000-0000-0000-0000-000000000071";
    private static final String SITE_B = "00000000-0000-0000-0000-000000000072";
    private static final String OTHER_SITE = "00000000-0000-0000-0000-000000000073";
    private static final String DMR_CHANNEL = "00000000-0000-0000-0000-000000000074";
    private static final String PASSWORD = "test-admin-password";

    @TempDir
    Path mDirectory;

    private Path mDatabase;
    private RetainedStatisticsCatalog mCatalog;
    private HttpServer mServer;
    private ExecutorService mExecutor;
    private WebRequestSecurity mSecurity;
    private HttpClient mClient;
    private URI mOrigin;
    private AtomicReference<StatsDatabaseMaintenanceRequest> mDispatched;
    private AtomicInteger mDispatchCount;

    @BeforeEach
    void setUp() throws Exception
    {
        mDatabase = mDirectory.resolve("statistics.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabase);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
            ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
            seed(statement);
        }
        mCatalog = new RetainedStatisticsCatalog(mDatabase);
        WebAccessService access = new WebAccessService(mDatabase);
        char[] password = PASSWORD.toCharArray();
        try
        {
            access.provisionOrResetPrimaryAdmin(password);
        }
        finally
        {
            Arrays.fill(password, '\0');
        }
        WebAuthenticationService authentication = new WebAuthenticationService(access);
        mSecurity = new WebRequestSecurity(access, authentication);
        mServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        mExecutor = Executors.newCachedThreadPool();
        mServer.setExecutor(mExecutor);
        new WebSessionHttpController(access, authentication, mSecurity).register(mServer);
        mDispatched = new AtomicReference<>();
        mDispatchCount = new AtomicInteger();
        RetainedStatisticsAdminHttpController controller = new RetainedStatisticsAdminHttpController(mCatalog,
            request -> {
                mDispatchCount.incrementAndGet();
                mDispatched.set(request);
            });
        mServer.createContext(RetainedStatisticsAdminHttpController.PATH,
            mSecurity.protectApi(WebCapability.ADMIN_SETTINGS, controller::handle));
        mServer.start();
        mOrigin = URI.create("http://127.0.0.1:" + mServer.getAddress().getPort());
        mClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown()
    {
        if(mServer != null) mServer.stop(0);
        if(mSecurity != null) mSecurity.close();
        if(mExecutor != null) mExecutor.shutdownNow();
    }

    @Test
    void catalogRequiresSourceAndSiteAndPagesExactOwnedTargets() throws Exception
    {
        RetainedStatisticsCatalog.Page sources = mCatalog.sources("radio_system", null, 10, 0);
        assertEquals(2, sources.totalCount());
        assertTrue(sources.rows().stream().anyMatch(row -> "Shared P25".equals(row.get("label"))));
        assertFalse(sources.rows().getFirst().containsKey("radios"));

        RetainedStatisticsCatalog.Page sites = mCatalog.sites("radio_system", SYSTEM, null, 10, 0);
        assertEquals(2, sites.totalCount());
        assertEquals(RetainedSiteKey.p25(1, 1, 71, 71, 1000),
            sites.rows().getFirst().get("site_key"));
        assertEquals(SITE_A, sites.rows().getFirst().get("configuration_id"));

        assertThrows(StatsApiException.class, () -> mCatalog.results(
            "radio_system", SYSTEM, "frequencies", null, null, 1, 0));
        RetainedStatisticsCatalog.Page first = mCatalog.results(
            "radio_system", SYSTEM, "frequencies", SITE_A, null, 1, 0);
        assertEquals(2, first.totalCount());
        assertTrue(first.hasMore());
        assertEquals(1, first.nextOffset());
        assertEquals(851012500L, ((Number)first.rows().getFirst().get("frequency_hz")).longValue());
        JsonNode target = MAPPER.valueToTree(first.rows().getFirst().get("target"));
        assertEquals("frequency", target.get("kind").textValue());
        assertEquals(SITE_A, target.get("site_configuration_id").textValue());
        assertEquals(RetainedSiteKey.p25(1, 1, 71, 71, 1000),
            target.get("expected_site_key").textValue());

        RetainedStatisticsCatalog.Page second = mCatalog.results(
            "radio_system", SYSTEM, "frequencies", SITE_A, null, 1, 1);
        assertEquals(851025000L, ((Number)second.rows().getFirst().get("frequency_hz")).longValue());
        assertFalse(second.hasMore());
        assertEquals(0, mCatalog.results("radio_system", SYSTEM, "frequencies", SITE_A,
            "853.012500", 10, 0).totalCount(), "other site's frequency must not appear");

        RetainedStatisticsCatalog.Page radios = mCatalog.results(
            "radio_system", SYSTEM, "radios", null, null, 10, 0);
        assertEquals(2, radios.totalCount());
        assertEquals("v1-r-bee00-49f-202", radios.rows().getFirst().get("identity_key"));
        RetainedStatisticsCatalog.Page firstRadio = mCatalog.results("radio_system", SYSTEM,
            "radios", null, null, 1, 0);
        RetainedStatisticsCatalog.Page nextRadio = mCatalog.results("radio_system", SYSTEM,
            "radios", null, null, 1, 1);
        assertTrue(firstRadio.hasMore());
        assertFalse(firstRadio.rows().getFirst().get("identity_key").equals(
            nextRadio.rows().getFirst().get("identity_key")),
            "same native IDs in two home scopes must page deterministically");
        assertEquals(1, mCatalog.results("radio_system", SYSTEM, "talkgroups", null,
            null, 10, 0).totalCount());
        RetainedStatisticsCatalog.Page systemSites = mCatalog.results("radio_system", SYSTEM, "sites", null,
            null, 10, 0);
        assertEquals(3, systemSites.totalCount());
        assertTrue(systemSites.rows().stream().anyMatch(row -> "learned_site".equals(
            ((Map<?,?>)row.get("target")).get("kind"))));
        assertTrue(systemSites.rows().stream().anyMatch(row -> "saved_site".equals(
            ((Map<?,?>)row.get("target")).get("kind"))));
        assertEquals(2, mCatalog.results("radio_system", SYSTEM, "channels", null,
            null, 10, 0).totalCount());
        assertEquals(1, mCatalog.results("radio_system", SYSTEM, "systems", null,
            null, 10, 0).totalCount());
        assertEquals(0, mCatalog.results("radio_system", OTHER_SYSTEM, "radios", null,
            "202", 10, 0).totalCount());

        RetainedStatisticsCatalog.Page conventionalSites = mCatalog.sites("saved_channel", DMR_CHANNEL,
            null, 10, 0);
        assertEquals(1, conventionalSites.totalCount());
        assertEquals(RetainedSiteKey.conventional(74, 1000),
            conventionalSites.rows().getFirst().get("site_key"));
        RetainedStatisticsCatalog.Page conventionalFrequencies = mCatalog.results("saved_channel",
            DMR_CHANNEL, "frequencies", DMR_CHANNEL, null, 10, 0);
        assertEquals(4, conventionalFrequencies.totalCount());
        assertEquals(3, ((Number)conventionalFrequencies.rows().getFirst()
            .get("observation_count")).intValue(), "shared summary carries the exact call count");
        RetainedStatisticsCatalog.Page dmrOnlyFrequency = mCatalog.results("saved_channel", DMR_CHANNEL,
            "frequencies", DMR_CHANNEL, "460.025", 10, 0);
        assertEquals(1, dmrOnlyFrequency.totalCount(), "a DMR-only summary must expose its frequency");
        assertNull(dmrOnlyFrequency.rows().getFirst().get("observation_count"),
            "identity counts can overlap and must not be shown as exact physical observations");
        assertEquals(1, mCatalog.results("saved_channel", DMR_CHANNEL, "frequencies", DMR_CHANNEL,
            "460.050", 10, 0).totalCount(), "an hourly bucket must expose its frequency");
        RetainedStatisticsCatalog.Page conventionalRadios = mCatalog.results("saved_channel", DMR_CHANNEL,
            "radios", null, null, 10, 0);
        assertEquals(2, conventionalRadios.totalCount());
        assertEquals("conventional_radio", ((Map<?,?>)conventionalRadios.rows().getFirst()
            .get("target")).get("kind"));
        assertEquals(2, mCatalog.results("saved_channel", DMR_CHANNEL, "talkgroups", null,
            null, 10, 0).totalCount());
        assertThrows(StatsApiException.class, () -> mCatalog.results("saved_channel", SITE_A,
            "radios", null, null, 10, 0));
    }

    @Test
    void adminRouteRequiresCsrfAndRetriesOneJobWithoutDeletingFromHttpThread() throws Exception
    {
        String base = RetainedStatisticsAdminHttpController.PATH;
        assertEquals(401, send(request(base + "/sources?kind=radio_system").GET()).statusCode());
        Session admin = login();
        HttpResponse<String> sourceResponse = send(request(base + "/sources?kind=radio_system&limit=1")
            .header("Cookie", admin.cookie()).GET());
        assertEquals(200, sourceResponse.statusCode(), sourceResponse.body());
        assertEquals(1, json(sourceResponse).at("/data").size());
        assertEquals(2, json(sourceResponse).at("/meta/total_count").intValue());
        assertEquals(400, send(request(base + "/results?source_kind=radio_system&source_key=" + SYSTEM +
            "&data_type=frequencies").header("Cookie", admin.cookie()).GET()).statusCode());
        HttpResponse<String> results = send(request(base + "/results?source_kind=radio_system&source_key=" +
            SYSTEM + "&data_type=frequencies&site_configuration_id=" + SITE_A + "&limit=1")
            .header("Cookie", admin.cookie()).GET());
        assertEquals(200, results.statusCode(), results.body());
        assertEquals(2, json(results).at("/meta/total_count").intValue());

        String requestId = UUID.randomUUID().toString();
        String body = MAPPER.writeValueAsString(Map.of("request_id", requestId, "target",
            Map.of("kind", "frequency", "site_configuration_id", SITE_A,
                "expected_site_key", RetainedSiteKey.p25(1, 1, 71, 71, 1000),
                "frequency_hz", 851012500)));
        assertEquals(403, send(request(base + "/deletions")
            .header("Origin", mOrigin.toString()).header("Cookie", admin.cookie())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))).statusCode());
        assertEquals(403, send(request(base + "/deletions")
            .header("Origin", "http://example.invalid").header("Cookie", admin.cookie())
            .header("X-CSRF-Token", admin.csrf()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))).statusCode());

        HttpResponse<String> accepted = send(mutation(base + "/deletions", admin)
            .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(202, accepted.statusCode(), accepted.body());
        assertEquals("running", json(accepted).at("/data/state").textValue());
        assertEquals(requestId, json(accepted).at("/data/job_id").textValue());
        assertEquals(1, mDispatchCount.get());
        assertNotNull(mDispatched.get());
        assertEquals(202, send(mutation(base + "/deletions", admin)
            .POST(HttpRequest.BodyPublishers.ofString(body))).statusCode());
        assertEquals(1, mDispatchCount.get(), "an identical retry must not create a second deletion");
        String different = body.replace("851012500", "851025000");
        assertEquals(409, send(mutation(base + "/deletions", admin)
            .POST(HttpRequest.BodyPublishers.ofString(different))).statusCode());
        String anotherId = body.replace(requestId, UUID.randomUUID().toString());
        assertEquals(429, send(mutation(base + "/deletions", admin)
            .POST(HttpRequest.BodyPublishers.ofString(anotherId))).statusCode(),
            "only one maintenance deletion should be pending at a time");
        String missingSiteKey = MAPPER.writeValueAsString(Map.of("request_id", UUID.randomUUID().toString(),
            "target", Map.of("kind", "frequency", "site_configuration_id", SITE_A,
                "frequency_hz", 851012500)));
        assertEquals(400, send(mutation(base + "/deletions", admin)
            .POST(HttpRequest.BodyPublishers.ofString(missingSiteKey))).statusCode());

        mDispatched.get().result().complete(new ReceiverActivityMaintenance.Result(
            ReceiverActivityMaintenance.Operation.DELETE_RETAINED_STATS, 4, null, 0, 0, 0, 0,
            ReceiverActivityMaintenance.DeletionOutcome.DELETED));
        HttpResponse<String> finished = send(request(base + "/deletions/" + requestId)
            .header("Cookie", admin.cookie()).GET());
        assertEquals(200, finished.statusCode(), finished.body());
        assertEquals("succeeded", json(finished).at("/data/state").textValue());
        assertEquals("deleted", json(finished).at("/data/outcome").textValue());
        assertEquals(4, json(finished).at("/data/rows_deleted").intValue());
        HttpResponse<String> secondAccepted = send(mutation(base + "/deletions", admin)
            .POST(HttpRequest.BodyPublishers.ofString(anotherId)));
        assertEquals(202, secondAccepted.statusCode(), secondAccepted.body());
        mDispatched.get().result().complete(new ReceiverActivityMaintenance.Result(
            ReceiverActivityMaintenance.Operation.DELETE_RETAINED_STATS, 0, null, 0, 0, 0, 0,
            ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE));
        String secondId = json(secondAccepted).at("/data/job_id").textValue();
        assertEquals("stale_site", json(send(request(base + "/deletions/" + secondId)
            .header("Cookie", admin.cookie()).GET())).at("/data/outcome").textValue());
        assertEquals(2, mCatalog.results("radio_system", SYSTEM, "frequencies", SITE_A,
            null, 10, 0).totalCount(), "the HTTP test dispatcher must not change the database");
    }

    @Test
    void completedJobHistoryStaysBoundedDuringLongCleanupSession() throws Exception
    {
        Session admin = login();
        String lastId = null;
        for(int index = 0; index < 70; index++)
        {
            lastId = UUID.randomUUID().toString();
            String body = MAPPER.writeValueAsString(Map.of("request_id", lastId,
                "target", Map.of("kind", "channel", "configuration_id", SITE_A)));
            HttpResponse<String> accepted = send(mutation(RetainedStatisticsAdminHttpController.PATH +
                "/deletions", admin).POST(HttpRequest.BodyPublishers.ofString(body)));
            assertEquals(202, accepted.statusCode(), accepted.body());
            mDispatched.get().result().complete(new ReceiverActivityMaintenance.Result(
                ReceiverActivityMaintenance.Operation.DELETE_RETAINED_STATS, 0, null, 0, 0, 0, 0,
                ReceiverActivityMaintenance.DeletionOutcome.NOT_FOUND));
        }
        assertEquals(70, mDispatchCount.get());
        HttpResponse<String> latest = send(request(RetainedStatisticsAdminHttpController.PATH +
            "/deletions/" + lastId).header("Cookie", admin.cookie()).GET());
        assertEquals(200, latest.statusCode(), latest.body());
        assertEquals("not_found", json(latest).at("/data/outcome").textValue());
    }

    private Session login() throws Exception
    {
        String body = MAPPER.writeValueAsString(java.util.Map.of("username", "admin", "password", PASSWORD));
        HttpResponse<String> response = send(request("/api/v1/auth/login")
            .header("Origin", mOrigin.toString()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(200, response.statusCode(), response.body());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        return new Session(cookie, json(response).at("/data/csrf_token").textValue());
    }

    private HttpRequest.Builder mutation(String path, Session session)
    {
        return request(path).header("Origin", mOrigin.toString()).header("Cookie", session.cookie())
            .header("X-CSRF-Token", session.csrf()).header("Content-Type", "application/json");
    }

    private HttpRequest.Builder request(String path)
    {
        return HttpRequest.newBuilder(mOrigin.resolve(path)).timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception
    {
        return mClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception
    {
        return MAPPER.readTree(response.body());
    }

    private static void seed(Statement statement) throws Exception
    {
        statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES " +
            "(71,'Test P25','P25'),(74,'Test DMR','DMR')");
        statement.executeUpdate("""
            INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,name,
                alias_list_id,decoder_type,primary_frequency_hz,config_json) VALUES
              ('%1$s','TRUNKED',71,'Shared P25','North','North Control',71,'P25_PHASE1',851012500,'{}'),
              ('%2$s','TRUNKED',72,'Shared P25','South','South Control',71,'P25_PHASE1',852012500,'{}'),
              ('%3$s','TRUNKED',73,'Other P25','East','East Control',71,'P25_PHASE1',853012500,'{}'),
              ('%4$s','CONVENTIONAL',74,'DMR County','','DMR Repeater',74,'DMR',460012500,
               '{"decodeConfiguration":{"channelMode":"CONVENTIONAL"}}')
            """.formatted(SITE_A, SITE_B, OTHER_SITE, DMR_CHANNEL));
        statement.executeUpdate("""
            INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,
                first_seen_ms,last_seen_ms) VALUES
              (71,'%1$s',1,0,0xBEE00,0x49F,1000,4000),
              (73,'%2$s',1,0,0xBEE00,0x4A0,1000,4000)
            """.formatted(SYSTEM, OTHER_SYSTEM));
        statement.executeUpdate("""
            INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,
                radio_system_assigned_at_ms) VALUES
              (71,'%1$s',1000,4000,71,1000),(72,'%2$s',1000,4000,71,1000),
              (73,'%3$s',1000,4000,73,1000),(74,'%4$s',1000,4000,NULL,NULL)
            """.formatted(SITE_A, SITE_B, OTHER_SITE, DMR_CHANNEL));
        statement.executeUpdate("""
            INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,observation_count,protocol,
                rfss,site,primary_frequency_hz,current_control_hz) VALUES
              (71,1000,4000,4,'APCO25',1,1,851012500,851012500),
              (72,1000,4000,4,'APCO25',1,2,852012500,852012500),
              (73,1000,4000,4,'APCO25',1,3,853012500,853012500)
            """);
        statement.executeUpdate("""
            INSERT INTO p25_site_channel_summary(channel_id,channel_key,downlink_hz,
                first_seen_ms,last_seen_ms,observation_count) VALUES
              (71,'a',851012500,1000,4000,2),(71,'b',851012500,1000,4000,3),
              (71,'c',851025000,1000,4000,1),(72,'d',852012500,1000,4000,1),
              (73,'e',853012500,1000,4000,1)
            """);
        statement.executeUpdate("""
            INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,
                home_system_id,identity_id,first_seen_ms,last_seen_ms,logical_call_count) VALUES
              (7101,71,1,0xBEE00,0x49F,101,1000,4000,3),
              (7102,71,2,0xBEE00,0x49F,202,1000,4000,3),
              (7103,71,2,0xBEE01,0x49F,202,1000,4000,1),
              (7301,73,2,0xBEE00,0x4A0,303,1000,4000,3)
            """);
        statement.executeUpdate("""
            INSERT INTO p25_learned_site(learned_site_id,radio_system_id,rfss,site,
                first_seen_ms,last_seen_ms) VALUES (7101,71,1,1,1000,4000)
            """);
        statement.executeUpdate("""
            INSERT INTO conventional_activity_summary(channel_id,frequency_hz,timeslot,
                first_seen_ms,last_seen_ms,call_count)
            VALUES (74,460012500,2,1000,4000,3)
            """);
        statement.executeUpdate("""
            INSERT INTO conventional_activity_bucket(channel_id,frequency_hz,timeslot,bucket_start_ms,call_count)
            VALUES (74,460050000,2,0,1)
            """);
        statement.executeUpdate("""
            INSERT INTO dmr_conventional_talkgroup_summary(channel_id,frequency_hz,timeslot,talkgroup_id,
                first_seen_ms,last_seen_ms,call_count)
            VALUES (74,460012500,2,7,1000,4000,3),
                   (74,460025000,1,7,1000,4000,2)
            """);
        statement.executeUpdate("""
            INSERT INTO dmr_conventional_radio_summary(channel_id,frequency_hz,timeslot,radio_id,
                first_seen_ms,last_seen_ms,call_count)
            VALUES (74,460012500,2,202,1000,4000,3),
                   (74,460037500,1,303,1000,4000,1)
            """);
    }

    private record Session(String cookie, String csrf) {}
}
