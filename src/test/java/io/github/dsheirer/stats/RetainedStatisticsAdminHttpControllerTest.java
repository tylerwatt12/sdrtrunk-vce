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
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
    void issiAssignmentHistoryCatalogAndRoutesUseOneP25SystemOwnedSummaryTarget() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabase);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
                "VALUES(71,0xBEE00,0x348,1103)");
            statement.executeUpdate("INSERT INTO p25_wuid_assignment_observation_summary " +
                "(radio_system_id,working_id,p25_subscriber_identity_id,first_observed_ms,last_observed_ms," +
                "last_registration_ms,registration_count,last_evidence_code,last_channel_id) " +
                "VALUES(71,77,71,1000,4000,4000,4,1,71),(71,78,71,1000,4000,4000,1,1,72)," +
                "(73,77,71,1000,4000,4000,1,1,73)");
            statement.executeUpdate("INSERT INTO radio_system(id,system_key,protocol_code,dmr_model_code,dmr_network_id,first_seen_ms,last_seen_ms) " +
                "VALUES(75,'dmr:tier3:small:42',3,2,42,1000,1000)");
        }
        var page = mCatalog.results("radio_system", SYSTEM, "issi_assignment_history", null, null, 10, 0);
        assertEquals(1, page.totalCount());
        assertEquals("ISSI assignment history", page.rows().getFirst().get("label"));
        assertEquals("Shared P25", page.rows().getFirst().get("detail"));
        assertEquals(List.of("summary"), page.rows().getFirst().get("available_parts"));
        JsonNode target = MAPPER.valueToTree(page.rows().getFirst().get("target"));
        assertEquals("radio_system", target.get("source_kind").textValue());
        assertEquals(SYSTEM, target.get("source_key").textValue());
        assertEquals("issi_assignment_history", target.get("data_type").textValue());
        assertFalse(target.has("site_configuration_id"));
        assertFalse(target.has("record_key"));
        assertEquals(1, mCatalog.results("radio_system", SYSTEM, "issi_assignment_history", null,
            "Shared P25", 10, 0).totalCount());
        assertEquals(0, mCatalog.results("radio_system", SYSTEM, "issi_assignment_history", null,
            "Other P25", 10, 0).totalCount());
        assertEquals(0, mCatalog.results("radio_system", SYSTEM, "issi_assignment_history", null,
            null, 10, 1).rows().size());
        var scoped = new StatsDatabaseMaintenanceRequest.ScopedData("radio_system", SYSTEM, null, null,
            "issi_assignment_history", null, List.of("summary"));
        assertEquals("ISSI assignment history · Shared P25", mCatalog.jobLabel(scoped));
        assertEquals(2, mCatalog.preview(scoped).rowsTotal());
        assertThrows(StatsApiException.class, () -> mCatalog.results("radio_system", SYSTEM,
            "issi_assignment_history", SITE_A, null, 10, 0));
        assertThrows(StatsApiException.class, () -> mCatalog.results("saved_channel", DMR_CHANNEL,
            "issi_assignment_history", null, null, 10, 0));
        assertThrows(StatsApiException.class, () -> mCatalog.results("radio_system", "dmr:tier3:small:42",
            "issi_assignment_history", null, null, 10, 0));
        assertThrows(StatsApiException.class, () -> mCatalog.results("radio_system", "p25:bee00:4a1",
            "issi_assignment_history", null, null, 10, 0));

        Session admin = login();
        String base = RetainedStatisticsAdminHttpController.PATH;
        HttpResponse<String> results = send(request(base + "/results?source_kind=radio_system&source_key=" +
            SYSTEM + "&data_type=issi_assignment_history").header("Cookie", admin.cookie()).GET());
        assertEquals(200, results.statusCode(), results.body());
        HttpResponse<String> preview = send(mutation(base + "/preview", admin).POST(HttpRequest.BodyPublishers.ofString(
            MAPPER.writeValueAsString(Map.of("target", target)))));
        assertEquals(200, preview.statusCode(), preview.body());
        assertEquals(2, json(preview).at("/data/rows_total").intValue());
        assertEquals(2, json(preview).at("/data/counts_by_part/summary").intValue());
        assertEquals(400, send(request(base + "/results?source_kind=radio_system&source_key=dmr:tier3:small:42" +
            "&data_type=issi_assignment_history").header("Cookie", admin.cookie()).GET()).statusCode());
        JsonNode invalidPart = target.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)invalidPart).putArray("parts").add("current");
        assertEquals(400, send(mutation(base + "/preview", admin).POST(HttpRequest.BodyPublishers.ofString(
            MAPPER.writeValueAsString(Map.of("target", invalidPart))))).statusCode());
        String body = MAPPER.writeValueAsString(Map.of("request_id", UUID.randomUUID().toString(), "target", target));
        assertEquals(202, send(mutation(base + "/deletions", admin).POST(HttpRequest.BodyPublishers.ofString(body))).statusCode());
        assertEquals(scoped, mDispatched.get().deletionTarget());
        assertEquals(1, mDispatchCount.get());
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
        assertEquals(RetainedSiteKey.p25(1, 1, 71, 71L, 1000),
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
        assertEquals("scoped_data", target.get("kind").textValue());
        assertEquals(SITE_A, target.get("site_configuration_id").textValue());
        assertEquals(RetainedSiteKey.p25(1, 1, 71, 71L, 1000),
            target.get("expected_site_key").textValue());
        assertEquals("851012500", target.get("record_key").textValue());
        assertEquals("frequencies", target.get("data_type").textValue());

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
        assertEquals("scoped_data", ((Map<?,?>)conventionalRadios.rows().getFirst()
            .get("target")).get("kind"));
        assertEquals(2, mCatalog.results("saved_channel", DMR_CHANNEL, "talkgroups", null,
            null, 10, 0).totalCount());
        RetainedStatisticsCatalog.Page conventionalSources = mCatalog.sources("saved_channel", null, 10, 0);
        assertEquals(1, conventionalSources.totalCount(), "trunked saved sites belong under radio systems");
        assertEquals(460012500L, ((Number)conventionalSources.rows().getFirst()
            .get("primary_frequency_hz")).longValue());
        assertEquals(1, mCatalog.sources("saved_channel", "460.012500", 10, 0).totalCount());
        assertEquals(0, mCatalog.sources("saved_channel", "North Control", 10, 0).totalCount());
        assertThrows(StatsApiException.class, () -> mCatalog.results("saved_channel", SITE_A,
            "radios", null, null, 10, 0));
        assertThrows(StatsApiException.class, () -> mCatalog.results("saved_channel", SITE_A,
            "frequencies", null, null, 10, 0));
        assertThrows(StatsApiException.class, () -> mCatalog.results("radio_system", SYSTEM,
            "alias_activity", null, null, 10, 0));
    }

    @Test
    void bandPlansAndCurrentOnlyFrequenciesKeepTheirSavedSiteAndDetailedEvidence() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabase);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_frequency_band(channel_id,band,base_hz,bandwidth,spacing_hz,
                    transmit_offset_hz,timeslots,confirmed_at_ms)
                VALUES (71,0,851000000,12500,12500,-45000000,2,4000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_frequency_band_summary(channel_id,band,base_hz,bandwidth,spacing_hz,
                    transmit_offset_hz,timeslots,first_seen_ms,last_seen_ms,observation_count)
                VALUES (71,0,851000000,12500,12500,-45000000,2,1000,4000,9)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel(channel_id,channel_key,descriptor,downlink_hz,uplink_hz,
                    timeslots,confirmed_at_ms)
                VALUES (71,'a','0-001',851012500,806012500,2,4000),
                       (71,'current-only','0-004',851050000,806050000,2,4000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_tag_summary(channel_id,channel_key,tag,
                    first_seen_ms,last_seen_ms,observation_count)
                VALUES (71,'a','VOICE',1000,4000,7)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_neighbor(channel_id,neighbor_key,rfss,site,downlink_hz,confirmed_at_ms)
                VALUES (71,'1:2',1,2,852012500,4000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_neighbor_summary(channel_id,neighbor_key,rfss,site,downlink_hz,
                    first_seen_ms,last_seen_ms,observation_count)
                VALUES (71,'1:2',1,2,852012500,1000,4000,3)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group(channel_id,local_patch_group_id,version,confirmed_at_ms)
                VALUES (71,3102,1,4000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_summary(channel_id,local_patch_group_id,version,
                    first_seen_ms,last_seen_ms,observation_count)
                VALUES (71,3102,1,1000,4000,3)
                """);
        }
        RetainedStatisticsCatalog.Page plans = mCatalog.results("radio_system", SYSTEM,
            "band_plans", SITE_A, "851", 10, 0);
        assertEquals(1, plans.totalCount());
        Map<String,Object> band = plans.rows().getFirst();
        assertEquals(0, ((Number)band.get("band")).intValue());
        assertEquals(851000000L, ((Number)band.get("base_hz")).longValue());
        assertEquals(9, ((Number)band.get("observation_count")).intValue());
        assertEquals("0", ((Map<?,?>)band.get("target")).get("record_key"));
        assertEquals(2, ((java.util.List<?>)band.get("available_parts")).size());
        assertEquals(0, mCatalog.results("radio_system", OTHER_SYSTEM, "band_plans",
            OTHER_SITE, null, 10, 0).totalCount());
        assertThrows(StatsApiException.class, () -> mCatalog.results("radio_system", SYSTEM,
            "band_plans", OTHER_SITE, null, 10, 0), "a selected site must belong to the system");
        assertEquals("1:2", ((Map<?,?>)mCatalog.results("radio_system", SYSTEM, "neighbors",
            SITE_A, "852.012500", 10, 0).rows().getFirst().get("target")).get("record_key"));
        assertEquals("3102", ((Map<?,?>)mCatalog.results("radio_system", SYSTEM, "patches",
            SITE_A, "3102", 10, 0).rows().getFirst().get("target")).get("record_key"));

        RetainedStatisticsCatalog.Page frequencies = mCatalog.results("radio_system", SYSTEM,
            "frequencies", SITE_A, null, 10, 0);
        assertEquals(3, frequencies.totalCount(), "current-only observations must be discoverable");
        Map<String,Object> voice = frequencies.rows().getFirst();
        assertEquals(806012500L, ((Number)voice.get("uplink_hz")).longValue());
        assertEquals(7L, ((Number)voice.get("voice_grant_observations")).longValue());
        assertEquals("0-001", voice.get("descriptor"));
        assertEquals(2, ((java.util.List<?>)voice.get("channel_keys")).size(),
            "one physical frequency may have several logical channel keys");
        Map<String,Object> currentOnly = frequencies.rows().getLast();
        assertNull(currentOnly.get("observation_count"));
        assertEquals("HISTORICAL", currentOnly.get("state"),
            "the stored current projection can still be older than the six-hour display window");
        assertEquals("851050000", ((Map<?,?>)currentOnly.get("target")).get("record_key"));
    }

    @Test
    void aliasActivityPreviewIsReadOnlyAndSearchesBeyondTheFirstPage() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabase);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value)
                VALUES (711,71,'Dispatch Alpha','TALKGROUP','APCO25',101)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_activity_summary(alias_id,alias_list_id,protocol_code,metrics_state,
                    logical_call_count,signaling_observation_count,first_evidence_ms,last_evidence_ms,
                    updated_at_ms)
                VALUES (711,71,1,'observed',4,2,1000,4000,5000)
                """);
            for(int index = 0; index < 60; index++)
            {
                statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) " +
                    "VALUES (71,'Other alias " + index + "','RADIO_ID','APCO25'," + (1000 + index) + ")");
            }
            statement.executeUpdate("""
                INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value)
                VALUES (71,'Very distant radio','RADIO_ID','APCO25',2000)
                """);
        }
        RetainedStatisticsCatalog.Page aliasPage = mCatalog.results("alias_activity", null,
            "alias_activity", null, "Very distant", 10, 0);
        assertEquals(1, aliasPage.totalCount(), "friendly-name search must run before pagination");
        assertEquals("Very distant radio", aliasPage.rows().getFirst().get("label"));
        assertEquals(4000L, ((Number)mCatalog.results("alias_activity", null,
            "alias_activity", null, "Dispatch Alpha", 10, 0).rows().getFirst()
            .get("last_seen_ms")).longValue());
        RetainedStatisticsCatalog.Page source = mCatalog.sources("alias_activity", null, 10, 0);
        assertEquals(1, source.totalCount());
        assertFalse(((Map<?,?>)source.rows().getFirst().get("target")).containsKey("source_key"));

        Session admin = login();
        Map<?,?> target = (Map<?,?>)mCatalog.results("alias_activity", null,
            "alias_activity", null, "Dispatch Alpha", 10, 0).rows().getFirst().get("target");
        String previewBody = MAPPER.writeValueAsString(Map.of("target", target));
        String base = RetainedStatisticsAdminHttpController.PATH;
        assertEquals(403, send(request(base + "/preview").header("Origin", mOrigin.toString())
            .header("Cookie", admin.cookie()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(previewBody))).statusCode());
        HttpResponse<String> preview = send(mutation(base + "/preview", admin)
            .POST(HttpRequest.BodyPublishers.ofString(previewBody)));
        assertEquals(200, preview.statusCode(), preview.body());
        assertEquals(1, json(preview).at("/data/counts_by_part/summary").intValue());
        assertEquals(1, json(preview).at("/data/rows_total").intValue());
        assertEquals(1, mCatalog.results("alias_activity", null, "alias_activity", null,
            "Dispatch Alpha", 10, 0).totalCount(), "preview must preserve saved aliases");

        String submission = MAPPER.writeValueAsString(Map.of("request_id", UUID.randomUUID().toString(),
            "target", target));
        assertEquals(202, send(mutation(base + "/deletions", admin)
            .POST(HttpRequest.BodyPublishers.ofString(submission))).statusCode());
        assertTrue(mDispatched.get().deletionTarget() instanceof StatsDatabaseMaintenanceRequest.ScopedData);
        assertEquals(400, send(mutation(base + "/preview", admin)
            .POST(HttpRequest.BodyPublishers.ofString(previewBody.replace("summary", "current"))))
            .statusCode(), "Alias Activity has no current part");
        String unsupported = MAPPER.writeValueAsString(Map.of("target", Map.of("kind", "scoped_data",
            "source_kind", "alias_activity", "data_type", "unknown_type", "parts",
            java.util.List.of("summary"))));
        assertEquals(400, send(mutation(base + "/preview", admin)
            .POST(HttpRequest.BodyPublishers.ofString(unsupported))).statusCode());
        String trunkedAsConventional = MAPPER.writeValueAsString(Map.of("target",
            Map.of("kind", "scoped_data", "source_kind", "saved_channel", "source_key", SITE_A,
                "data_type", "all", "parts", java.util.List.of("summary"))));
        assertEquals(400, send(mutation(base + "/preview", admin)
            .POST(HttpRequest.BodyPublishers.ofString(trunkedAsConventional))).statusCode());
    }

    @Test
    void largeScopedDirectoriesFilterBeforePagination() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabase))
        {
            connection.setAutoCommit(false);
            try(PreparedStatement frequency = connection.prepareStatement("""
                    INSERT INTO p25_site_channel_summary(channel_id,channel_key,downlink_hz,
                        first_seen_ms,last_seen_ms,observation_count)
                    VALUES (71,?,?,1000,4000,1)
                    """);
                PreparedStatement radio = connection.prepareStatement("""
                    INSERT INTO radio_system_identity_summary(radio_system_id,identity_kind_code,
                        home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms,logical_call_count)
                    VALUES (71,2,0xBEE00,0x49F,?,1000,4000,1)
                    """))
            {
                for(int index = 0; index < 300; index++)
                {
                    frequency.setString(1, "bulk-" + index);
                    frequency.setLong(2, 760000000L + index * 12500L);
                    frequency.addBatch();
                }
                for(int index = 0; index < 1500; index++)
                {
                    radio.setInt(1, 50000 + index);
                    radio.addBatch();
                }
                frequency.executeBatch();
                radio.executeBatch();
                connection.commit();
            }
        }
        RetainedStatisticsCatalog.Page firstFrequencies = mCatalog.results("radio_system", SYSTEM,
            "frequencies", SITE_A, null, 25, 0);
        assertEquals(302, firstFrequencies.totalCount());
        assertTrue(firstFrequencies.hasMore());
        assertEquals(25, firstFrequencies.rows().size());
        assertEquals(1, mCatalog.results("radio_system", SYSTEM, "frequencies", SITE_A,
            "763.737500", 25, 0).totalCount(), "frequency search must run before page limiting");
        RetainedStatisticsCatalog.Page lastFrequencies = mCatalog.results("radio_system", SYSTEM,
            "frequencies", SITE_A, null, 25, 300);
        assertEquals(2, lastFrequencies.rows().size());
        assertFalse(lastFrequencies.hasMore());

        RetainedStatisticsCatalog.Page firstRadios = mCatalog.results("radio_system", SYSTEM,
            "radios", null, null, 25, 0);
        assertEquals(1502, firstRadios.totalCount());
        assertEquals(25, firstRadios.rows().size());
        assertEquals(1, mCatalog.results("radio_system", SYSTEM, "radios", null,
            "51499", 25, 0).totalCount(), "ID search must run before page limiting");
        assertEquals(2, mCatalog.results("radio_system", SYSTEM, "radios", null,
            null, 25, 1500).rows().size());
    }

    @Test
    void snapshotlessSavedSiteKeepsChannelHistoryReachableAndDetectsRecreatedState() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabase);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("DELETE FROM p25_site_snapshot WHERE channel_id=72");
        }
        RetainedStatisticsCatalog.Page sites = mCatalog.sites("radio_system", SYSTEM, null, 10, 0);
        assertEquals(2, sites.totalCount());
        Map<String,Object> snapshotless = sites.rows().stream()
            .filter(row -> SITE_B.equals(row.get("configuration_id"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(snapshotless.get("label")).contains("no current site state"));
        String key = String.valueOf(snapshotless.get("site_key"));
        assertTrue(key.startsWith("trunked-unsited:"));
        RetainedStatisticsCatalog.Page quality = mCatalog.results("radio_system", SYSTEM,
            "control_quality", SITE_B, null, 10, 0);
        assertEquals(key, ((Map<?,?>)quality.rows().getFirst().get("target")).get("expected_site_key"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabase);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,
                    observation_count,protocol,rfss,site)
                VALUES (72,5000,5000,1,'APCO25',1,2)
                """);
        }
        var target = new StatsDatabaseMaintenanceRequest.ScopedData("radio_system", SYSTEM,
            SITE_B, key, "control_quality", null, java.util.List.of("buckets"));
        assertEquals(ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE,
            mCatalog.preview(target).outcome());
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
                "expected_site_key", RetainedSiteKey.p25(1, 1, 71, 71L, 1000),
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
        String thirdId = UUID.randomUUID().toString();
        String thirdBody = body.replace(requestId, thirdId);
        assertEquals(202, send(mutation(base + "/deletions", admin)
            .POST(HttpRequest.BodyPublishers.ofString(thirdBody))).statusCode());
        mDispatched.get().result().complete(new ReceiverActivityMaintenance.Result(
            ReceiverActivityMaintenance.Operation.DELETE_RETAINED_STATS, 0, null, 0, 0, 0, 0,
            ReceiverActivityMaintenance.DeletionOutcome.TOO_LARGE));
        assertEquals("too_large", json(send(request(base + "/deletions/" + thirdId)
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

    @Test
    void qualityPreviewAcceptsLargeScopeAndStrictOptionalHistoryFilters() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabase);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE buckets(value) AS (
                    SELECT 0 UNION ALL SELECT value+1 FROM buckets WHERE value<100000
                ) INSERT INTO trunked_control_channel_quality(channel_id,frequency_hz,bucket_start_ms,observed_at_ms)
                  SELECT 71,851012500,value*10000,value*10000+1000 FROM buckets
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_control_channel_quality(channel_id,frequency_hz,bucket_start_ms,observed_at_ms)
                VALUES(72,851012500,10000,11000),(71,851025000,10000,11000)
                """);
        }
        Session admin = login();
        String path = RetainedStatisticsAdminHttpController.PATH + "/preview";
        Map<String,Object> target = qualityTarget();
        HttpResponse<String> large = send(mutation(path, admin).POST(HttpRequest.BodyPublishers.ofString(
            MAPPER.writeValueAsString(Map.of("target", target)))));
        assertEquals(200, large.statusCode(), large.body());
        assertEquals("found", json(large).at("/data/outcome").textValue());
        assertEquals(100002, json(large).at("/data/rows_total").longValue());

        target.put("from_ms", 11000);
        target.put("to_ms", 21000);
        target.put("frequency_hz", 851012500L);
        HttpResponse<String> filtered = send(mutation(path, admin).POST(HttpRequest.BodyPublishers.ofString(
            MAPPER.writeValueAsString(Map.of("target", target)))));
        assertEquals(200, filtered.statusCode(), filtered.body());
        assertEquals(1, json(filtered).at("/data/rows_total").longValue(),
            "From is inclusive and Before exclusive, with exact site and physical frequency ownership");

        for(Object invalid: new Object[]{-1, "11000", 11000.5, null})
        {
            target.put("from_ms", invalid);
            assertEquals(400, send(mutation(path, admin).POST(HttpRequest.BodyPublishers.ofString(
                MAPPER.writeValueAsString(Map.of("target", target))))).statusCode());
        }
        target.put("from_ms", 21000);
        HttpResponse<String> reversed = send(mutation(path, admin).POST(HttpRequest.BodyPublishers.ofString(
            MAPPER.writeValueAsString(Map.of("target", target)))));
        assertEquals(400, reversed.statusCode());
        assertEquals("to_ms", json(reversed).at("/error/field").textValue());
        target.put("from_ms", 0);
        target.put("frequency_hz", 0);
        assertEquals(400, send(mutation(path, admin).POST(HttpRequest.BodyPublishers.ofString(
            MAPPER.writeValueAsString(Map.of("target", target))))).statusCode());
        target.put("frequency_hz", 851012500L);
        target.put("data_type", "call_activity");
        assertEquals(400, send(mutation(path, admin).POST(HttpRequest.BodyPublishers.ofString(
            MAPPER.writeValueAsString(Map.of("target", target))))).statusCode(),
            "history filters cannot silently change summary deletion scope");
        target.put("data_type", "control_quality");
        target.put("unsupported_filter", true);
        assertEquals(400, send(mutation(path, admin).POST(HttpRequest.BodyPublishers.ofString(
            MAPPER.writeValueAsString(Map.of("target", target))))).statusCode());
        target.remove("unsupported_filter");
        String overflow = MAPPER.writeValueAsString(Map.of("target", target))
            .replace("\"from_ms\":0", "\"from_ms\":9223372036854775808");
        assertEquals(400, send(mutation(path, admin).POST(HttpRequest.BodyPublishers.ofString(overflow)))
            .statusCode());
    }

    @Test
    void cancellationResumeAndRecentJobsKeepOneTargetAndCumulativeProgress() throws Exception
    {
        Session admin = login();
        String base = RetainedStatisticsAdminHttpController.PATH + "/deletions";
        String id = UUID.randomUUID().toString();
        String body = MAPPER.writeValueAsString(Map.of("request_id", id, "target", qualityTarget()));
        assertEquals(202, send(mutation(base, admin).POST(HttpRequest.BodyPublishers.ofString(body))).statusCode());
        StatsDatabaseMaintenanceRequest first = mDispatched.get();
        writerProgress(first, "initializeProgress", new Class<?>[]{long.class, long.class, long.class},
            213000L, 4000L, 5L);
        writerProgress(first, "committedBatch", new Class<?>[]{long.class, long.class}, 1000L, 11L);
        writerProgress(first, "observeWriter", new Class<?>[]{int.class, long.class}, 17, 5L);
        JsonNode progress = json(send(request(base + "/" + id).header("Cookie", admin.cookie()).GET()))
            .get("data");
        assertEquals(213000, progress.get("rows_total").longValue());
        assertEquals(1000, progress.get("rows_deleted").longValue());
        assertEquals(1, progress.get("batches_completed").longValue());
        assertEquals(4000, progress.get("cutoff_ms").longValue());
        assertEquals(11, progress.get("maximum_batch_ms").longValue());
        assertEquals(17, progress.get("observation_queue_high_water").longValue());
        assertEquals(0, progress.get("records_dropped").longValue());
        assertEquals("scoped_data", progress.at("/target/kind").textValue());
        assertEquals(SYSTEM, progress.at("/target/source_key").textValue());
        assertFalse(progress.get("target").has("from_ms"), "Unselected filters must remain absent on recovery");
        assertEquals("Quality history · Shared P25 · North", progress.get("label").textValue());
        assertEquals(202, send(mutation(base, admin).POST(HttpRequest.BodyPublishers.ofString(
            MAPPER.writeValueAsString(Map.of("request_id", id, "target", progress.get("target")))))).statusCode());
        assertEquals(1, mDispatchCount.get(), "Recovered target retries the original job without another deletion");

        assertEquals(401, send(request(base + "/" + id + "/cancel")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")))
            .statusCode());
        assertEquals(403, send(request(base + "/" + id + "/cancel")
            .header("Cookie", admin.cookie()).header("Origin", mOrigin.toString())
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")))
            .statusCode());
        HttpResponse<String> cancelling = send(mutation(base + "/" + id + "/cancel", admin)
            .POST(HttpRequest.BodyPublishers.ofString("{}")));
        assertEquals(202, cancelling.statusCode(), cancelling.body());
        assertEquals("cancelling", json(cancelling).at("/data/state").textValue());
        assertEquals(202, send(mutation(base + "/" + id + "/cancel", admin)
            .POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
        writerProgress(first, "setResumable", new Class<?>[]{boolean.class}, true);
        first.result().complete(new ReceiverActivityMaintenance.Result(
            ReceiverActivityMaintenance.Operation.DELETE_RETAINED_STATS, 1000, null, 0, 0, 0, 0,
            ReceiverActivityMaintenance.DeletionOutcome.INTERRUPTED));
        JsonNode cancelled = json(send(request(base + "/" + id).header("Cookie", admin.cookie()).GET()))
            .get("data");
        assertEquals("cancelled", cancelled.get("state").textValue());
        assertTrue(cancelled.get("can_resume").booleanValue());
        assertEquals(1000, cancelled.get("rows_deleted").longValue());

        String otherId = UUID.randomUUID().toString();
        assertEquals(202, send(mutation(base, admin).POST(HttpRequest.BodyPublishers.ofString(
            body.replace(id, otherId)))).statusCode());
        StatsDatabaseMaintenanceRequest other = mDispatched.get();
        assertEquals(429, send(mutation(base + "/" + id + "/resume", admin)
            .POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
        other.result().completeExceptionally(new IllegalStateException("test dispatcher failed"));
        assertEquals(202, send(mutation(base + "/" + id + "/resume", admin)
            .POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
        StatsDatabaseMaintenanceRequest resumed = mDispatched.get();
        assertEquals(first.deletionTarget(), resumed.deletionTarget());
        assertEquals(4000, resumed.progress().cutoffMs(), "Resume must retain the original observation cutoff");
        assertEquals(1000, resumed.progress().rowsDeleted());
        assertEquals(3, mDispatchCount.get());
        assertEquals(202, send(mutation(base + "/" + id + "/resume", admin)
            .POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
        assertEquals(3, mDispatchCount.get(), "Retrying Resume must dispatch once");
        writerProgress(resumed, "committedBatch", new Class<?>[]{long.class, long.class}, 212000L, 12L);
        writerProgress(resumed, "setResumable", new Class<?>[]{boolean.class}, false);
        resumed.result().complete(new ReceiverActivityMaintenance.Result(
            ReceiverActivityMaintenance.Operation.DELETE_RETAINED_STATS, 213000, null, 0, 0, 0, 0,
            ReceiverActivityMaintenance.DeletionOutcome.DELETED));
        JsonNode recent = json(send(request(base).header("Cookie", admin.cookie()).GET())).get("data");
        assertEquals(2, recent.size());
        assertTrue(recent.findValuesAsText("job_id").contains(id));
        JsonNode complete = json(send(request(base + "/" + id).header("Cookie", admin.cookie()).GET()))
            .get("data");
        assertEquals("succeeded", complete.get("state").textValue());
        assertEquals(213000, complete.get("rows_deleted").longValue());
        assertEquals(2, complete.get("batches_completed").longValue());
        assertFalse(complete.get("can_resume").booleanValue());
        assertEquals(409, send(mutation(base + "/" + id + "/resume", admin)
            .POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
        assertEquals(202, send(mutation(base + "/" + id + "/cancel", admin)
            .POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode(), "Cancel terminal job is harmless");
        assertEquals(400, send(mutation(base + "/" + id + "/cancel", admin)
            .POST(HttpRequest.BodyPublishers.ofString("{\"target\":{}}"))).statusCode());
        HttpResponse<String> missing = send(request(base + "/" + UUID.randomUUID())
            .header("Cookie", admin.cookie()).GET());
        assertEquals(404, missing.statusCode());
        assertEquals("job_not_found", json(missing).at("/error/code").textValue());
        assertTrue(json(missing).at("/error/message").textValue().contains("remaining records"));

        String failedId = UUID.randomUUID().toString();
        assertEquals(202, send(mutation(base, admin).POST(HttpRequest.BodyPublishers.ofString(
            body.replace(id, failedId)))).statusCode());
        StatsDatabaseMaintenanceRequest interrupted = mDispatched.get();
        writerProgress(interrupted, "initializeProgress", new Class<?>[]{long.class, long.class, long.class},
            2000L, 5000L, 0L);
        writerProgress(interrupted, "committedBatch", new Class<?>[]{long.class, long.class}, 500L, 9L);
        writerProgress(interrupted, "databaseBusy", new Class<?>[]{});
        writerProgress(interrupted, "setResumable", new Class<?>[]{boolean.class}, true);
        interrupted.result().completeExceptionally(new IllegalStateException("SQLite busy"));
        JsonNode failed = json(send(request(base + "/" + failedId).header("Cookie", admin.cookie()).GET()))
            .get("data");
        assertEquals("failed", failed.get("state").textValue());
        assertEquals(500, failed.get("rows_deleted").longValue());
        assertEquals(1, failed.get("database_busy_retries").longValue());
        assertTrue(failed.get("can_resume").booleanValue());
        assertTrue(failed.get("error").textValue().contains("Resume"));
        assertEquals(202, send(mutation(base + "/" + failedId + "/resume", admin)
            .POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
        assertEquals(5000, mDispatched.get().progress().cutoffMs());
        assertEquals(500, mDispatched.get().progress().rowsDeleted());
    }

    private static Map<String,Object> qualityTarget()
    {
        Map<String,Object> target = new LinkedHashMap<>();
        target.put("kind", "scoped_data");
        target.put("source_kind", "radio_system");
        target.put("source_key", SYSTEM);
        target.put("site_configuration_id", SITE_A);
        target.put("expected_site_key", RetainedSiteKey.p25(1, 1, 71, 71L, 1000));
        target.put("data_type", "control_quality");
        target.put("parts", List.of("buckets"));
        return target;
    }

    /** Stand-in for the background writer; the HTTP controller only reads these counters. */
    private static void writerProgress(StatsDatabaseMaintenanceRequest request, String method,
                                       Class<?>[] types, Object... values) throws Exception
    {
        var writerMethod = StatsDatabaseMaintenanceRequest.class.getDeclaredMethod(method, types);
        writerMethod.setAccessible(true);
        writerMethod.invoke(request, values);
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
