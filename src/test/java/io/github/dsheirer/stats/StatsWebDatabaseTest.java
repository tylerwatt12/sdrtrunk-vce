/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebDatabaseTest
{
    private static final int WACN = 0xBEE00;
    private static final int SYSTEM = 0x348;
    private static final int SECOND_SYSTEM = 0x49F;
    private static final String GUID = "test-site-guid";

    @TempDir
    Path mTemporaryFolder;
    private Path mDatabasePath;
    private StatsWebDatabase mDatabase;

    @Test
    void formatsKnownAndUnknownMfids()
    {
        assertEquals("Motorola (0x90)", StatsWebDatabase.mfidDisplay(0x90));
        assertEquals("0xAB", StatsWebDatabase.mfidDisplay(0xAB));
    }

    @BeforeEach
    void setUp() throws Exception
    {
        mDatabasePath = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(mDatabasePath);
        seed(mDatabasePath);
        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
    }

    @Test
    void exposesSystemEntitiesWithAliasesAndLinks()
    {
        Map<String,Object> system = mDatabase.system(request(
            "/api/system?wacn=BEE00&system_id=0x348"));
        assertEquals(1L, number(map(system, "system").get("sites")));

        Map<String,Object> talkgroups = mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?wacn=BEE00&system_id=0x348&limit=1"));
        Map<String,Object> talkgroup = rows(talkgroups).get(0);
        assertEquals("Dispatch", talkgroup.get("alias_name"));
        assertEquals(56132L, number(talkgroup.get("talkgroup_id")));
        assertEquals(0, number(talkgroup.get("recorded_count")));
        assertEquals(0, number(talkgroup.get("streamed_count")));
        assertFalse(talkgroup.containsKey("grant_count"));
        assertFalse((Boolean)talkgroups.get("hasMore"));

        Map<String,Object> radios = mDatabase.systemRadios(request(
            "/api/system/radios?wacn=BEE00&system_id=0x348"));
        Map<String,Object> radio = rows(radios).get(0);
        assertEquals("Engine 1", radio.get("alias_name"));
        assertEquals(56132L, number(radio.get("affiliated_talkgroup_id")));
        assertEquals("Dispatch", radio.get("affiliated_talkgroup_alias_name"));

        Map<String,Object> talkerAliases = mDatabase.systemTalkerAliases(request(
            "/api/system/talker-aliases?wacn=BEE00&system_id=0x348"));
        Map<String,Object> talkerAlias = rows(talkerAliases).get(0);
        assertEquals(1811332L, number(talkerAlias.get("radio_id")));
        assertEquals("CAR 201", talkerAlias.get("last_talker_alias"));
        assertEquals("Engine 1", talkerAlias.get("alias_name"));
        assertEquals("Dispatch", talkerAlias.get("talkgroup_alias_name"));

        Map<String,Object> relationships = mDatabase.radioTalkgroupRelationships(request(
            "/api/radio-talkgroups?wacn=BEE00&system_id=0x348&radio_id=1811332"));
        assertEquals("Dispatch", rows(relationships).get(0).get("talkgroup_alias_name"));
    }

    @Test
    void exposesSiteRfTablesAndTypedActivity()
    {
        Map<String,Object> site = map(mDatabase.site(request("/api/site?guid=" + GUID)), "site");
        assertEquals("Cleveland Simulcast", site.get("channel_name"));
        assertEquals(856_137_500L, number(site.get("current_control_hz")));
        assertEquals("WPFF205", site.get("callsign"));
        assertEquals("Motorola (0x90)", site.get("mfid_display"));
        assertEquals(110L, number(site.get("micro_slots")));
        assertEquals("Autonomous and by Request", site.get("data_access"));

        List<Map<String,Object>> channels = rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=" + GUID)));
        assertTrue(channels.get(0).get("tags").toString().contains("VOICE"));
        assertTrue(channels.get(0).get("tags").toString().contains("DATA"));
        assertTrue(channels.get(0).get("channel_key").toString().contains("0-509"));
        assertTrue(channels.get(0).get("channel_key").toString().contains("0-510"));
        assertEquals(4L, number(channels.get(0).get("voice_grant_observations")));
        assertEquals(2L, number(channels.get(0).get("data_grant_observations")));
        assertEquals(854_187_500L, number(channels.get(0).get("downlink_hz")));
        assertEquals("0-821", channels.get(1).get("descriptor"));
        assertEquals("WPFF205", channels.get(1).get("callsign"));
        assertEquals("CURRENT", channels.get(1).get("state"));
        assertEquals("CURRENT", channels.stream().filter(row -> "0-900".equals(row.get("descriptor")))
            .findFirst().orElseThrow().get("state"));

        List<Map<String,Object>> neighbors = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=" + GUID)));
        assertEquals("CURRENT", neighbors.get(0).get("state"));
        assertEquals("HISTORICAL", neighbors.get(1).get("state"));
        assertEquals("CURRENT", neighbors.get(2).get("state"));
        assertEquals(5, neighbors.size());
        assertEquals("ISSI", neighbors.get(3).get("entry_type"));
        assertEquals(0xBEE00L, number(neighbors.get(3).get("wacn")));
        assertEquals(0x954L, number(neighbors.get(3).get("system_id")));
        assertEquals(1L, number(neighbors.get(3).get("band_count")));
        assertEquals(1L, number(neighbors.get(3).get("has_fdma")));
        assertEquals("ISSI", neighbors.get(4).get("entry_type"));
        assertEquals(0x9EFL, number(neighbors.get(4).get("system_id")));
        assertEquals(2L, number(neighbors.get(4).get("band_count")));
        assertEquals(1L, number(neighbors.get(4).get("has_fdma")));
        assertEquals(1L, number(neighbors.get(4).get("has_tdma")));

        Map<String,Object> bands = mDatabase.siteBands(request("/api/site/bands?guid=" + GUID));
        List<Map<String,Object>> foreignBands = rowsFrom(bands, "foreign_rows");
        assertEquals(3, foreignBands.size());
        assertEquals(0x954L, number(foreignBands.get(0).get("foreign_system_id")));
        assertEquals(0x9EFL, number(foreignBands.get(1).get("foreign_system_id")));
        assertEquals(4L, number(foreignBands.get(1).get("band")));
        assertEquals(1L, number(foreignBands.get(1).get("channel_type")));
        assertEquals(935_012_500L, number(foreignBands.get(1).get("base_hz")));

        Map<String,Object> patches = mDatabase.sitePatches(request("/api/site/patches?guid=" + GUID));
        assertEquals("Dispatch", rowsFrom(patches, "groups").get(0).get("patch_alias_name"));
        assertEquals("Dispatch", rowsFrom(patches, "talkgroups").get(0).get("alias_name"));
        assertEquals("Engine 1", rowsFrom(patches, "radios").get(0).get("alias_name"));

        List<Map<String,Object>> quality = rows(mDatabase.siteQuality(request(
            "/api/site/quality?guid=" + GUID)));
        assertEquals(856_137_500L, number(quality.getFirst().get("frequency_hz")));
        assertEquals(98.5, ((Number)quality.getFirst().get("decode_health_pct")).doubleValue());

        Map<String,Object> activity = mDatabase.activity(request(
            "/api/activity?wacn=BEE00&system_id=0x348&talkgroup_id=56132"));
        Map<String,Object> event = rows(activity).get(0);
        assertEquals(1L, number(event.get("target_kind_code")));
        assertEquals("Dispatch", event.get("target_alias_name"));
        assertEquals("Engine 1", event.get("source_alias_name"));
        assertNotNull(activity.get("nextBeforeId"));
        assertEquals("Dispatch", mDatabase.activityByIds(List.of(number(event.get("id"))))
            .getFirst().get("target_alias_name"));

        Map<String,Object> radioActivity = mDatabase.activity(request(
            "/api/activity?wacn=BEE00&system_id=0x348&radio_id=1811332"));
        Map<String,Object> radioTargetEvent = rows(radioActivity).get(0);
        assertEquals(2L, number(radioTargetEvent.get("target_kind_code")));
        assertEquals("Engine 1", radioTargetEvent.get("target_alias_name"));
    }

    @Test
    void exposesConventionalContextsSeparately()
    {
        Map<String,Object> conventional = mDatabase.conventional(request("/api/conventional"));
        assertEquals(1, rows(conventional).size());
        assertEquals("County Fire", rows(conventional).get(0).get("channel_name"));

        Map<String,Object> detail = mDatabase.conventionalDetail(request(
            "/api/conventional/detail?context=conventional-fire"));
        assertEquals("County Fire", map(detail, "context").get("channel_name"));
        assertTrue(rowsFrom(detail, "summaries").get(0).containsKey("frequency_hz"));
    }

    @Test
    void statusReportsRetainedDetailedHistory()
    {
        Map<String,Object> status = mDatabase.status();
        assertTrue((Boolean)status.get("detailedHistoryAvailable"));
        assertEquals(2001L, number(status.get("lastDetailedHistoryMs")));
        assertFalse(status.containsKey("logger"));
        assertFalse(status.toString().contains(mDatabasePath.toString()));
    }

    @Test
    void canHideGrantRowsBeforeActivityPagination() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, event_type_code)
                VALUES (1, 3000, 11, 0)
                """);
        }

        Map<String,Object> activity = mDatabase.activity(request(
            "/api/activity?hide_grants=true&limit=1"));
        assertEquals(1, rows(activity).size());
        assertFalse("GRANT".equals(rows(activity).getFirst().get("action")));
        assertTrue((Boolean)activity.get("hasMore"));
    }

    @Test
    void activityPaginatesByTimestampAndIdInsteadOfInsertionOrder() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, event_type_code)
                VALUES (1, 5000, 0, 0), (1, 4000, 0, 0), (1, 6000, 0, 0), (1, 4000, 0, 0)
                """);
        }

        Map<String,Object> firstPage = mDatabase.activity(request(
            "/api/activity?guid=" + GUID + "&limit=2"));
        assertEquals(List.of(6000L, 5000L), rows(firstPage).stream()
            .map(row -> number(row.get("observed_at_ms"))).toList());
        assertTrue((Boolean)firstPage.get("hasMore"));

        long firstCursor = number(firstPage.get("nextBeforeId"));
        Map<String,Object> secondPage = mDatabase.activity(request(
            "/api/activity?guid=" + GUID + "&limit=2&before_id=" + firstCursor));
        assertEquals(List.of(4000L, 4000L), rows(secondPage).stream()
            .map(row -> number(row.get("observed_at_ms"))).toList());
        assertTrue(number(rows(secondPage).getFirst().get("id")) >
            number(rows(secondPage).getLast().get("id")));

        long secondCursor = number(secondPage.get("nextBeforeId"));
        Map<String,Object> thirdPage = mDatabase.activity(request(
            "/api/activity?guid=" + GUID + "&limit=2&before_id=" + secondCursor));
        assertEquals(List.of(2001L, 2000L), rows(thirdPage).stream()
            .map(row -> number(row.get("observed_at_ms"))).toList());
        assertFalse((Boolean)thirdPage.get("hasMore"));
    }

    @Test
    void scopesAliasesToEachSystemsAssignedAliasList() throws Exception
    {
        seedSecondSystem(mDatabasePath);
        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);

        Map<String,Object> talkgroup = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?wacn=BEE00&system_id=0x49F"))).get(0);
        assertEquals("Second Dispatch", talkgroup.get("alias_name"));

        Map<String,Object> radio = rows(mDatabase.systemRadios(request(
            "/api/system/radios?wacn=BEE00&system_id=0x49F"))).get(0);
        assertEquals("Second Engine", radio.get("alias_name"));

        Map<String,Object> relationship = rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/radio-talkgroups?wacn=BEE00&system_id=0x49F&radio_id=1811332"))).get(0);
        assertEquals("Second Dispatch", relationship.get("talkgroup_alias_name"));
        assertEquals("Second Engine", relationship.get("radio_alias_name"));

        Map<String,Object> event = rows(mDatabase.activity(request(
            "/api/activity?wacn=BEE00&system_id=0x49F&talkgroup_id=56132"))).get(0);
        assertEquals("Second Dispatch", event.get("target_alias_name"));
        assertEquals("Second Engine", event.get("source_alias_name"));

        Map<String,Object> dashboardTalkgroup = rowsFrom(mDatabase.dashboard(), "topTalkgroups").stream()
            .filter(row -> number(row.get("system_id")) == SECOND_SYSTEM)
            .findFirst().orElseThrow();
        assertEquals("Second Dispatch", dashboardTalkgroup.get("alias_name"));
    }

    @Test
    void doesNotUseGlobalOrdinaryAliasesWithoutAnAssignedAliasList() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_system VALUES (2, " + WACN + ", " + SECOND_SYSTEM +
                ", 1000, 2000)");
            statement.executeUpdate("""
                INSERT INTO p25_talkgroup_summary (system_key, talkgroup_id, target_kind_code, first_seen_ms,
                    last_seen_ms, call_count, grant_count)
                VALUES (2, 56132, 1, 1000, 2000, 1, 1)
                """);
        }

        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
        Map<String,Object> talkgroup = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?wacn=BEE00&system_id=0x49F"))).get(0);
        assertFalse(talkgroup.containsKey("alias_name"));
    }

    @Test
    void dashboardProvidesZeroFilledCallsPerHourWithoutDoubleCountingGrants() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_activity_bucket
                    (context_id, bucket_start_ms, call_count, grant_count, recorded_count, streamed_count)
                VALUES (1, %d, 7, 9, 5, 4)
                """.formatted(currentHour));
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket
                    (context_id, frequency_hz, timeslot, bucket_start_ms, call_count)
                VALUES (2, 154310000, -1, %d, 3)
                """.formatted(currentHour));
        }

        Map<String,Object> dashboard = mDatabase.dashboard();
        List<Map<String,Object>> hours = rowsFrom(dashboard, "activityPerHour");
        assertEquals(24, hours.size());
        assertEquals(currentHour, number(hours.getLast().get("hour_ms")));
        assertEquals(10, number(hours.getLast().get("call_count")));
        assertFalse(hours.getLast().containsKey("grant_count"));
        assertTrue(hours.stream().limit(23).allMatch(row -> number(row.get("call_count")) == 0));
        assertTrue(rowsFrom(mDatabase.system(request(
            "/api/system?wacn=BEE00&system_id=0x348")), "actionCounts").stream()
            .noneMatch(row -> "GRANT".equals(row.get("action"))));
        Map<String,Object> p25CallActivity = map(dashboard, "p25CallActivity");
        Map<String,Object> p25Totals = map(p25CallActivity, "totals");
        assertEquals(7, number(p25Totals.get("call_count")));
        assertEquals(3, number(p25Totals.get("non_p25_call_count")));
        assertEquals(5, number(p25Totals.get("recorded_count")));
        assertEquals(4, number(p25Totals.get("streamed_count")));
        List<Map<String,Object>> p25Series = rowsFrom(p25CallActivity, "series");
        assertEquals(24, p25Series.size());
        assertEquals(currentHour, number(p25Series.getLast().get("time_ms")));
        assertEquals(7, number(p25Series.getLast().get("call_count")));
        assertEquals(3, number(p25Series.getLast().get("non_p25_call_count")));
        assertEquals(5, number(p25Series.getLast().get("recorded_count")));
        assertEquals(4, number(p25Series.getLast().get("streamed_count")));
        assertTrue(number(p25CallActivity.get("metric_start_ms")) > 0);
        Map<String,Object> siteActivity = map(dashboard, "siteActivity24h");
        List<Map<String,Object>> sites = rows(siteActivity);
        assertEquals(1, sites.size());
        assertEquals(GUID, sites.getFirst().get("guid"));
        assertEquals("Cleveland Simulcast", sites.getFirst().get("channel_name"));
        assertEquals(SYSTEM, number(sites.getFirst().get("system_id")));
        assertEquals(7, number(sites.getFirst().get("call_count")));
        assertEquals(7, number(sites.getFirst().get("total_call_count")));
    }

    @Test
    void dashboardRanksEachSiteSeparately() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        long firstHour = currentHour - 23L * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    first_seen_ms, last_seen_ms, system_key, rfss, site)
                VALUES (3, 'site-lakewood', 'test-site-lakewood', 1, 1, 'Lakewood', 1000, 2000, 1, 1, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_activity_bucket (context_id, bucket_start_ms, call_count)
                VALUES (1, %d, 7), (3, %d, 3), (1, %d, 5)
                """.formatted(currentHour, currentHour, firstHour));
        }

        Map<String,Object> activity = map(mDatabase.dashboard(), "siteActivity24h");
        List<Map<String,Object>> rows = rows(activity);
        assertEquals(2, rows.size());
        assertEquals(GUID, rows.getFirst().get("guid"));
        assertEquals(12, number(rows.getFirst().get("call_count")));
        assertEquals("test-site-lakewood", rows.getLast().get("guid"));
        assertEquals(3, number(rows.getLast().get("call_count")));
        assertTrue(rows.stream().allMatch(row -> number(row.get("total_call_count")) == 15));
        assertEquals(firstHour, number(activity.get("from_ms")));
    }

    @Test
    void siteActivityDashboardQueryUsesTheTimeIndexAtRepresentativeVolume() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        long firstHour = currentHour - 23L * 3_600_000L;
        long previousFirstHour = firstHour - 24L * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            connection.setAutoCommit(false);
            try(PreparedStatement contexts = connection.prepareStatement("""
                INSERT INTO receiver_context (id, context_key, kind_code, protocol_code, first_seen_ms,
                    last_seen_ms, system_key) VALUES (?, ?, 1, 1, 1000, 2000, 1)
                """);
                PreparedStatement buckets = connection.prepareStatement("""
                    INSERT INTO p25_site_activity_bucket (context_id, bucket_start_ms, call_count)
                    VALUES (?, ?, ?)
                    """))
            {
                for(int context = 100; context < 150; context++)
                {
                    contexts.setInt(1, context);
                    contexts.setString(2, "volume-site-" + context);
                    contexts.addBatch();

                    for(int hour = 0; hour < 48; hour++)
                    {
                        buckets.setInt(1, context);
                        buckets.setLong(2, previousFirstHour + hour * 3_600_000L);
                        buckets.setInt(3, 1);
                        buckets.addBatch();
                    }
                }

                contexts.executeBatch();
                buckets.executeBatch();
            }
            connection.commit();

            List<String> plan = new ArrayList<>();
            try(PreparedStatement statement = connection.prepareStatement(
                "EXPLAIN QUERY PLAN " + StatsWebDatabase.DASHBOARD_SITE_ACTIVITY_SQL))
            {
                statement.setLong(1, firstHour);
                statement.setLong(2, currentHour + 3_600_000L);
                try(ResultSet resultSet = statement.executeQuery())
                {
                    while(resultSet.next())
                    {
                        plan.add(resultSet.getString("detail"));
                    }
                }
            }

            assertTrue(plan.stream().anyMatch(detail -> detail.contains("idx_p25_site_activity_bucket_time")),
                () -> "Expected time-indexed bucket scan, plan was: " + plan);
            assertTrue(plan.stream().noneMatch(detail -> detail.contains("p25_activity_event")));
        }
    }

    @Test
    void detailedSiteActivityQueryUsesTheContextTimeIndexAtRepresentativeVolume() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE sequence(value) AS (
                    SELECT 1
                    UNION ALL
                    SELECT value + 1 FROM sequence WHERE value < 50000
                )
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, event_type_code)
                SELECT 1, 10000 + value, 0, 0 FROM sequence
                """);

            List<String> plan = new ArrayList<>();
            try(PreparedStatement query = connection.prepareStatement("EXPLAIN QUERY PLAN " +
                StatsWebDatabase.ACTIVITY_SELECT_SQL + " AND guid = ?" + StatsWebDatabase.ACTIVITY_ORDER_SQL))
            {
                query.setString(1, GUID);
                query.setInt(2, 201);

                try(ResultSet resultSet = query.executeQuery())
                {
                    while(resultSet.next())
                    {
                        plan.add(resultSet.getString("detail"));
                    }
                }
            }

            assertTrue(plan.stream().anyMatch(detail -> detail.contains("idx_p25_activity_event_context_time")),
                () -> "Expected context/time-indexed activity scan, plan was: " + plan);
            assertTrue(plan.stream().noneMatch(detail -> detail.contains("USE TEMP B-TREE")),
                () -> "Expected index-ordered activity results, plan was: " + plan);
        }
    }

    @Test
    void foreignBandQueriesUseCompositePrimaryKeys() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            for(String table: List.of("p25_foreign_system_band", "p25_foreign_system_band_summary"))
            {
                List<String> plan = new ArrayList<>();
                try(PreparedStatement query = connection.prepareStatement(
                    "EXPLAIN QUERY PLAN SELECT * FROM " + table + " WHERE guid = ?"))
                {
                    query.setString(1, GUID);
                    try(ResultSet resultSet = query.executeQuery())
                    {
                        while(resultSet.next())
                        {
                            plan.add(resultSet.getString("detail"));
                        }
                    }
                }

                assertTrue(plan.stream().anyMatch(detail -> detail.contains("PRIMARY KEY") &&
                        detail.contains("guid=?")),
                    () -> "Expected GUID-scoped primary-key lookup for " + table + ", plan was: " + plan);
            }
        }
    }

    @Test
    void providesSystemScopedZeroFilledTalkgroupActivityHistory() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        seedSecondSystem(mDatabasePath);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_talkgroup_bucket
                    (context_id, talkgroup_id, bucket_start_ms, call_count, emergency_count,
                     grant_count, recorded_count, streamed_count)
                VALUES (1, 56132, %d, 7, 1, 9, 5, 4),
                       (3, 56132, %d, 100, 2, 100, 90, 80)
                """.formatted(currentHour, currentHour));
        }

        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
        Map<String,Object> response = mDatabase.talkgroupActivity(request(
            "/api/talkgroup/activity?wacn=BEE00&system_id=0x348&talkgroup_id=56132&range=24h"));
        assertEquals("24h", response.get("range"));
        assertEquals(3_600_000L, number(response.get("bucket_ms")));
        assertTrue(number(response.get("metric_start_ms")) > 0);
        List<Map<String,Object>> series = rowsFrom(response, "series");
        Map<String,Object> current = series.stream()
            .filter(row -> number(row.get("time_ms")) == currentHour)
            .findFirst().orElseThrow();
        assertEquals(7, number(current.get("call_count")));
        assertEquals(1, number(current.get("emergency_count")));
        assertEquals(5, number(current.get("recorded_count")));
        assertEquals(4, number(current.get("streamed_count")));
        assertFalse(current.containsKey("grant_count"));
        assertTrue(series.stream().anyMatch(row -> number(row.get("call_count")) == 0));
        Map<String,Object> totals = map(response, "totals");
        assertEquals(7, number(totals.get("call_count")));
        assertEquals(5, number(totals.get("recorded_count")));
        assertEquals(4, number(totals.get("streamed_count")));
        assertFalse(totals.containsKey("grant_count"));

        StatsApiException error = assertThrows(StatsApiException.class, () -> mDatabase.talkgroupActivity(request(
            "/api/talkgroup/activity?wacn=BEE00&system_id=0x348&talkgroup_id=56132&range=forever")));
        assertEquals(400, error.status());
    }

    @Test
    void aggregatesTopTalkgroupsForOneSiteAndSelectedRange() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        seedSecondSystem(mDatabasePath);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_talkgroup_bucket
                    (context_id, talkgroup_id, bucket_start_ms, call_count, encrypted_count,
                     recorded_count, streamed_count)
                VALUES (1, 56132, %d, 7, 1, 2, 3),
                       (1, 56132, %d, 5, 2, 4, 1),
                       (1, 60000, %d, 20, 0, 0, 0),
                       (1, 56132, %d, 50, 0, 50, 50),
                       (3, 56132, %d, 100, 0, 90, 80)
                """.formatted(currentHour - 3_600_000L, currentHour, currentHour,
                    currentHour - 25L * 3_600_000L, currentHour));
        }

        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
        Map<String,Object> response = mDatabase.siteTalkgroups(request(
            "/api/site/talkgroups?guid=" + GUID + "&range=24h&limit=20"));
        assertEquals("24h", response.get("range"));
        assertEquals(3_600_000L, number(response.get("bucket_ms")));
        assertEquals(2, rows(response).size());
        assertEquals(60000L, number(rows(response).getFirst().get("talkgroup_id")));

        Map<String,Object> dispatch = rows(response).stream()
            .filter(row -> number(row.get("talkgroup_id")) == 56132L)
            .findFirst().orElseThrow();
        assertEquals("Dispatch", dispatch.get("alias_name"));
        assertEquals(12L, number(dispatch.get("call_count")));
        assertEquals(6L, number(dispatch.get("recorded_count")));
        assertEquals(4L, number(dispatch.get("streamed_count")));
        assertEquals(3L, number(dispatch.get("encrypted_count")));
        assertEquals(currentHour, number(dispatch.get("last_active_ms")));
        assertFalse(dispatch.containsKey("first_seen_ms"));

        Map<String,Object> oneHour = mDatabase.siteTalkgroups(request(
            "/api/site/talkgroups?guid=" + GUID + "&range=1h&limit=20"));
        Map<String,Object> currentDispatch = rows(oneHour).stream()
            .filter(row -> number(row.get("talkgroup_id")) == 56132L)
            .findFirst().orElseThrow();
        assertEquals(5L, number(currentDispatch.get("call_count")));
        assertEquals(4L, number(currentDispatch.get("recorded_count")));
        assertEquals(1L, number(currentDispatch.get("streamed_count")));
    }

    @Test
    void dashboardRecentSitesRequireDecodedSiteIdentity() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot
                    (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count, channel_name, decoder)
                VALUES ('unidentified-site-guid', 'empty', 3000, 4000, 1, 'No Signal', 'P25-1')
                """);
        }

        List<Map<String,Object>> recentSites = rowsFrom(mDatabase.dashboard(), "recentSites");
        assertTrue(recentSites.stream().anyMatch(row -> GUID.equals(row.get("guid"))));
        assertFalse(recentSites.stream().anyMatch(row -> "unidentified-site-guid".equals(row.get("guid"))));
    }

    @Test
    void dashboardQualityAggregatesBoundedSiteSeries() throws Exception
    {
        long minute = Math.floorDiv(System.currentTimeMillis(), 60_000L) * 60_000L;
        long first = minute - 120_000L + 1_000L;
        long second = minute - 120_000L + 11_000L;
        long latest = minute - 60_000L + 1_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality (guid, frequency_hz, bucket_start_ms, observed_at_ms,
                    signal_dbfs, average_signal_dbfs, minimum_signal_dbfs, maximum_signal_dbfs,
                    decode_health_pct, valid_frames, invalid_frames, corrected_bits, sync_loss_bits,
                    dropped_bits, last_valid_decode_ms)
                VALUES ('test-site-guid', 856137500, %d, %d, -51.0, -50.0, -55.0, -45.0,
                    80.0, 80, 20, 4, 0, 0, %d),
                    ('test-site-guid', 856137500, %d, %d, -41.0, -40.0, -60.0, -35.0,
                    100.0, 100, 0, 2, 0, 0, %d),
                    ('test-site-guid', 855137500, %d, %d, -61.0, -60.0, -65.0, -55.0,
                    75.0, 75, 25, 6, 0, 0, %d)
                """.formatted(first - Math.floorMod(first, 10_000L), first, first,
                    second - Math.floorMod(second, 10_000L), second, second,
                    latest - Math.floorMod(latest, 10_000L), latest, latest));
        }

        Map<String,Object> response = mDatabase.qualityHistory(request(
            "/api/quality?guid=test-site-guid&range=1h&points=60"));
        assertEquals("1h", response.get("range"));
        assertEquals(60_000L, number(response.get("bucket_ms")));
        assertEquals(60L, number(response.get("target_points")));
        List<Map<String,Object>> sites = rowsFrom(response, "sites");
        assertEquals(1, sites.size());
        Map<String,Object> site = sites.getFirst();
        assertEquals("Cleveland Simulcast", site.get("channel_name"));
        assertEquals(855_137_500L, number(site.get("quality_frequency_hz")));
        assertEquals(-60.0, ((Number)site.get("average_signal_dbfs")).doubleValue());
        List<Map<String,Object>> series = rowsFrom(site, "series");
        assertEquals(2, series.size());
        assertEquals(-45.0, ((Number)series.getFirst().get("average_signal_dbfs")).doubleValue());
        assertEquals(-60.0, ((Number)series.getFirst().get("minimum_signal_dbfs")).doubleValue());
        assertEquals(-35.0, ((Number)series.getFirst().get("maximum_signal_dbfs")).doubleValue());
        assertEquals(90.0, ((Number)series.getFirst().get("decode_health_pct")).doubleValue());
        assertEquals(2L, number(series.getFirst().get("sample_count")));

        Map<String,Object> current = mDatabase.qualityHistory(request(
            "/api/quality?include_history=false"));
        assertFalse((Boolean)current.get("history_included"));
        assertTrue(rowsFrom(rowsFrom(current, "sites").getFirst(), "series").isEmpty());

        StatsApiException error = assertThrows(StatsApiException.class, () ->
            mDatabase.qualityHistory(request("/api/quality?range=forever")));
        assertEquals(400, error.status());
    }

    @Test
    void sortsDisplayedDirectoryColumnsBeforePagination() throws Exception
    {
        seedSecondSystem(mDatabasePath);
        seedSortingRows(mDatabasePath);
        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);

        assertEquals(SYSTEM, number(rows(mDatabase.systems(request(
            "/api/systems?sort=site_names&direction=asc"))).getFirst().get("system_id")));
        assertEquals(SYSTEM, number(rows(mDatabase.systems(request(
            "/api/systems?sort=affiliations&direction=desc"))).getFirst().get("system_id")));

        assertEquals(GUID, rows(mDatabase.sites(request(
            "/api/sites?sort=system&direction=asc"))).getFirst().get("guid"));
        assertEquals(GUID, rows(mDatabase.sites(request(
            "/api/sites?sort=channels&direction=desc"))).getFirst().get("guid"));
        assertEquals(GUID, rows(mDatabase.sites(request(
            "/api/sites?sort=control&direction=desc"))).getFirst().get("guid"));

        Map<String,Object> talkgroup = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?wacn=BEE00&system_id=0x348&sort=alias&direction=asc&limit=1"))).getFirst();
        assertEquals("Dispatch", talkgroup.get("alias_name"));
        assertEquals("Dispatch", rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?wacn=BEE00&system_id=0x348&sort=group&direction=asc&limit=1")))
            .getFirst().get("alias_name"));
        assertEquals(100, number(rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?wacn=BEE00&system_id=0x348&sort=recorded&direction=desc&limit=1")))
            .getFirst().get("talkgroup_id")));
        assertEquals(100, number(rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?wacn=BEE00&system_id=0x348&sort=streamed&direction=desc&limit=1")))
            .getFirst().get("talkgroup_id")));

        assertEquals("Engine 1", rows(mDatabase.systemRadios(request(
            "/api/system/radios?wacn=BEE00&system_id=0x348&sort=alias&direction=asc&limit=1")))
            .getFirst().get("alias_name"));
        assertEquals("Engine 1", rows(mDatabase.systemRadios(request(
            "/api/system/radios?wacn=BEE00&system_id=0x348&sort=talker_alias&direction=desc&limit=1")))
            .getFirst().get("alias_name"));

        assertEquals("Engine 1", rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/radio-talkgroups?wacn=BEE00&system_id=0x348&talkgroup_id=56132" +
                "&sort=radio_alias&direction=asc&limit=1"))).getFirst().get("radio_alias_name"));
        assertEquals("Dispatch", rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/radio-talkgroups?wacn=BEE00&system_id=0x348&radio_id=1811332" +
                "&sort=talkgroup_alias&direction=asc&limit=1"))).getFirst().get("talkgroup_alias_name"));

        assertEquals("Alpha Channel", rows(mDatabase.conventional(request(
            "/api/conventional?sort=name&direction=asc&limit=1"))).getFirst().get("channel_name"));
    }

    @Test
    void groupsSystemDirectoryParentsAndChildrenInFixedIdentityOrder() throws Exception
    {
        seedSecondSystem(mDatabasePath);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_system VALUES (3, 1, 4095, 1000, 4000)");
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz)
                VALUES ('earlier-child', 'earlier-child-hash', 1000, 2500, 1, 'APCO25', 'Earlier Child',
                    'County', 'P25-1', 1, 0x49F, 0, 9, 857137500, 857137500)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac,
                    primary_frequency_hz, current_control_hz)
                VALUES ('unknown-child', 'unknown-child-hash', 1000, 2400, 1, 'APCO25', 'Unknown Child',
                    'County', 'P25-1', 1, 0x49F, 858137500, 858137500)
                """);
        }

        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
        Map<String,Object> directory = mDatabase.systemDirectory(request(
            "/api/system-directory?sort=last_seen&direction=desc"));
        List<Map<String,Object>> systems = rows(directory);
        assertEquals(1, number(systems.getFirst().get("wacn")));
        assertEquals(4095, number(systems.getFirst().get("system_id")));
        assertEquals(SYSTEM, number(systems.get(1).get("system_id")));
        assertEquals(SECOND_SYSTEM, number(systems.getLast().get("system_id")));
        List<Map<String,Object>> children = rowsFrom(systems.get(1), "children");
        assertEquals("earlier-child", children.getFirst().get("guid"));
        assertEquals(GUID, children.get(1).get("guid"));
        assertEquals("unknown-child", children.getLast().get("guid"));
        assertFalse((Boolean)systems.get(1).get("children_truncated"));
    }

    private static StatsRequest request(String uri)
    {
        return StatsRequest.from(URI.create(uri));
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Map<String,Object> response, String key)
    {
        return (Map<String,Object>)response.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> response)
    {
        return (List<Map<String,Object>>)response.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rowsFrom(Map<String,Object> response, String key)
    {
        return (List<Map<String,Object>>)response.get(key);
    }

    private static long number(Object value)
    {
        return ((Number)value).longValue();
    }

    private static void seed(Path database) throws Exception
    {
        long now = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_system VALUES (1, " + WACN + ", " + SYSTEM + ", " +
                (now - 10_000) + ", " + now + ")");
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, first_seen_ms, last_seen_ms, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz)
                VALUES (1, 'site-cleveland', 'test-site-guid', 1, 1, 'Cleveland Simulcast', 'County', 'P25-1',
                    1000, 2000, 1, 0x49F, 1, 1, 856137500, 856137500)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac, rfss, site,
                    lra, mfid, broadcast_clock_ms, micro_slots, data_service, data_access, wuid_lease_minutes,
                    registration_service, tdma, voice_service, primary_frequency_hz, current_control_hz)
                VALUES ('test-site-guid', 'hash', 1000, 2000, 10, 'APCO25', 'Cleveland Simulcast', 'County',
                    'P25-1', 1, 0x49F, 1, 1, 0, 0x90, 1784000000000, 110, 1,
                    'Autonomous and by Request', 240, 1, 1, 1, 856137500, 856137500)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality (guid, frequency_hz, bucket_start_ms, observed_at_ms,
                    signal_dbfs, average_signal_dbfs, minimum_signal_dbfs, maximum_signal_dbfs,
                    decode_health_pct, valid_frames, invalid_frames, corrected_bits, sync_loss_bits,
                    dropped_bits, last_valid_decode_ms)
                VALUES ('test-site-guid', 856137500, 0, 2000, -20.0, -21.0, -25.0, -18.0,
                    98.5, 100, 1, 4, 0, 0, 1999)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel (guid, channel_key, descriptor, downlink_hz, uplink_hz, tdma,
                    timeslots, callsign, confirmed_at_ms) VALUES ('test-site-guid', '0-821', '0-821',
                    856137500, 811137500, 0, 1, 'WPFF205', %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_summary (guid, channel_key, descriptor, downlink_hz, uplink_hz,
                    tdma, timeslots, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', '0-821', '0-821', 856137500, 811137500,
                    0, 1, 1000, 2000, 10)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_summary (guid, channel_key, descriptor, downlink_hz, uplink_hz,
                    tdma, timeslots, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', '0-509', '0-509', 854187500, NULL,
                    0, 1, 1000, 2000, 4),
                    ('test-site-guid', '0-510', '0-510', 854187500, NULL,
                    0, 1, 1000, 2000, 2),
                    ('test-site-guid', '0-900', '0-900', 857137500, NULL,
                    0, 1, 1000, %d, 2)
                """.formatted(now - 3_600_000L));
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_tag (guid, channel_key, tag, confirmed_at_ms)
                VALUES ('test-site-guid', '0-821', 'CURRENT_CONTROL', %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_tag_summary
                    (guid, channel_key, tag, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', '0-821', 'CONTROL', 1000, 2000, 10),
                    ('test-site-guid', '0-509', 'VOICE', 1000, 2000, 4),
                    ('test-site-guid', '0-510', 'DATA', 1000, 2000, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_neighbor (guid, neighbor_key, system_id, rfss, site, channel_descriptor,
                    downlink_hz, status, confirmed_at_ms)
                VALUES ('test-site-guid', '348:1:2:0-661', 0x348, 1, 2, '0-661', 855137500,
                    '[VALID INFORMATION, ACTIVE RFSS CONNECTION]', %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_neighbor_summary (guid, neighbor_key, system_id, rfss, site,
                    channel_descriptor, downlink_hz, status, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', '348:1:2:0-661', 0x348, 1, 2, '0-661', 855137500,
                    '[VALID INFORMATION, ACTIVE RFSS CONNECTION]', 1000, 2000, 10),
                    ('test-site-guid', '348:1:3:0-677', 0x348, 1, 3, '0-677', 855237500,
                    '[VALID INFORMATION]', 1000, 1500, 2),
                    ('test-site-guid', '348:1:4:0-693', 0x348, 1, 4, '0-693', 855337500,
                    '[VALID INFORMATION]', 1000, %d, 2)
                """.formatted(now - 3_600_000L));
            statement.executeUpdate("""
                INSERT INTO p25_foreign_system_band
                    (guid, foreign_wacn, foreign_system_id, band, channel_type, base_hz, spacing_hz,
                     transmit_offset_hz, confirmed_at_ms)
                VALUES ('test-site-guid', 0xBEE00, 0x9EF, 4, 1, 935012500, 12500, -39000000, %1$d),
                    ('test-site-guid', 0xBEE00, 0x9EF, 5, 3, 935012500, 12500, -39000000, %1$d),
                    ('test-site-guid', 0xBEE00, 0x954, 0, 1, 851006250, 6250, -45000000, %1$d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_foreign_system_band_summary
                    (guid, foreign_wacn, foreign_system_id, band, channel_type, base_hz, spacing_hz,
                     transmit_offset_hz, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', 0xBEE00, 0x9EF, 4, 1, 935012500, 12500, -39000000,
                        1000, %1$d, 10),
                    ('test-site-guid', 0xBEE00, 0x9EF, 5, 3, 935012500, 12500, -39000000,
                        1000, %1$d, 10),
                    ('test-site-guid', 0xBEE00, 0x954, 0, 1, 851006250, 6250, -45000000,
                        1000, %1$d, 5)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group (guid, patch_group, version, confirmed_at_ms)
                VALUES ('test-site-guid', 56132, 0, %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_summary
                    (guid, patch_group, version, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', 56132, 0, 1000, 2000, 10)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_talkgroup (guid, patch_group, talkgroup_id, confirmed_at_ms)
                VALUES ('test-site-guid', 56132, 56132, %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_talkgroup_summary
                    (guid, patch_group, talkgroup_id, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', 56132, 56132, 1000, 2000, 10)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_radio (guid, patch_group, radio_id, confirmed_at_ms)
                VALUES ('test-site-guid', 56132, 1811332, %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_radio_summary
                    (guid, patch_group, radio_id, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', 56132, 1811332, 1000, 2000, 10)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_talkgroup_summary (system_key, talkgroup_id, target_kind_code, first_seen_ms,
                    last_seen_ms, call_count, grant_count, encrypted_count, last_source_radio_id)
                VALUES (1, 56132, 1, 1000, 2000, 12, 12, 2, 1811332)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_summary (system_key, radio_id, first_seen_ms, last_seen_ms, call_count,
                    grant_count, encrypted_count, last_talkgroup_id, last_talker_alias, last_talker_alias_seen_ms)
                VALUES (1, 1811332, 1000, 2000, 8, 8, 1, 56132, 'CAR 201', 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_talkgroup_summary (system_key, radio_id, talkgroup_id, target_kind_code,
                    first_seen_ms, last_seen_ms, call_count, grant_count, encrypted_count)
                VALUES (1, 1811332, 56132, 1, 1000, 2000, 8, 8, 1)
                """);
            statement.executeUpdate("INSERT INTO p25_radio_affiliation VALUES (1, 1811332, 56132, 2000)");
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, event_type_code,
                    source_radio_id, target_id, target_kind_code, frequency_hz, lcn_band, lcn_number, timeslot,
                    encrypted, encryption_algorithm_id, encryption_key_id)
                VALUES (1, 2000, 0, 0, 1811332, 56132, 1, 855612500, 0, 737, 1, 1, 132, 52)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, event_type_code,
                    source_radio_id, target_id, target_kind_code, frequency_hz, encrypted)
                VALUES (1, 2001, 0, 0, NULL, 1811332, 2, 856137500, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (id, sort_order, name, alias_list_name, group_name, color)
                VALUES (1, 0, 'Dispatch', 'County', 'Law Dispatch', 255),
                       (2, 0, 'Engine 1', 'County', 'Fire', 65280)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_talkgroup (alias_id, sort_order, protocol, value, fully_qualified, ranged)
                VALUES (1, 0, 'APCO25', 56132, 0, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_radio (alias_id, sort_order, protocol, value, fully_qualified, ranged)
                VALUES (2, 0, 'APCO25', 1811332, 0, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, first_seen_ms, last_seen_ms, primary_frequency_hz)
                VALUES (2, 'conventional-fire', NULL, 10, 10, 'County Fire', 'County', 'NBFM', 1000, 2000,
                    154310000)
                """);
            statement.executeUpdate("""
                INSERT INTO conventional_activity_summary (context_id, frequency_hz, timeslot, first_seen_ms,
                    last_seen_ms, call_count) VALUES (2, 154310000, -1, 1000, 2000, 4)
                """);
        }
    }

    private static void seedSecondSystem(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_system VALUES (2, " + WACN + ", " + SECOND_SYSTEM +
                ", 1000, 3000)");
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, first_seen_ms, last_seen_ms, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz)
                VALUES (3, 'site-second', 'second-site-guid', 1, 1, 'Second Simulcast', 'Second', 'P25-1',
                    1000, 3000, 2, 0x123, 1, 1, 855137500, 855137500)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz)
                VALUES ('second-site-guid', 'second-hash', 1000, 3000, 10, 'APCO25', 'Second Simulcast',
                    'Second', 'P25-1', 2, 0x123, 1, 1, 855137500, 855137500)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_talkgroup_summary (system_key, talkgroup_id, target_kind_code, first_seen_ms,
                    last_seen_ms, call_count, grant_count, encrypted_count, last_source_radio_id)
                VALUES (2, 56132, 1, 1000, 3000, 100, 100, 0, 1811332)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_summary (system_key, radio_id, first_seen_ms, last_seen_ms, call_count,
                    grant_count, encrypted_count, last_talkgroup_id)
                VALUES (2, 1811332, 1000, 3000, 100, 100, 0, 56132)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_talkgroup_summary (system_key, radio_id, talkgroup_id, target_kind_code,
                    first_seen_ms, last_seen_ms, call_count, grant_count, encrypted_count)
                VALUES (2, 1811332, 56132, 1, 1000, 3000, 100, 100, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, event_type_code,
                    source_radio_id, target_id, target_kind_code, frequency_hz, encrypted)
                VALUES (3, 3000, 0, 0, 1811332, 56132, 1, 855612500, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (id, sort_order, name, alias_list_name, group_name, color)
                VALUES (3, 5000, 'Second Dispatch', 'Second', 'Second Law', 255),
                       (4, 5001, 'Second Engine', 'Second', 'Second Fire', 65280)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_talkgroup (alias_id, sort_order, protocol, value, fully_qualified, ranged)
                VALUES (3, 0, 'APCO25', 56132, 0, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_radio (alias_id, sort_order, protocol, value, fully_qualified, ranged)
                VALUES (4, 0, 'APCO25', 1811332, 0, 0)
                """);
        }
    }

    private static void seedSortingRows(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_talkgroup_summary (system_key, talkgroup_id, target_kind_code, first_seen_ms,
                    last_seen_ms, call_count, grant_count, encrypted_count, recorded_count, streamed_count,
                    last_source_radio_id)
                VALUES (1, 100, 1, 1000, 3000, 100, 100, 0, 10, 12, 100)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_summary (system_key, radio_id, first_seen_ms, last_seen_ms, call_count,
                    grant_count, encrypted_count, last_talkgroup_id, last_talker_alias, last_talker_alias_seen_ms)
                VALUES (1, 100, 1000, 3000, 100, 100, 0, 56132, 'AAA', 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_talkgroup_summary (system_key, radio_id, talkgroup_id, target_kind_code,
                    first_seen_ms, last_seen_ms, call_count, grant_count, encrypted_count)
                VALUES (1, 100, 56132, 1, 1000, 3000, 100, 100, 0),
                       (1, 1811332, 100, 1, 1000, 3000, 100, 100, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (id, sort_order, name, alias_list_name, group_name, color)
                VALUES (5, 0, 'Zulu Dispatch', 'County', 'Zulu Law', 255),
                       (6, 0, 'Zulu Unit', 'County', 'Zulu Fire', 65280)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_talkgroup (alias_id, sort_order, protocol, value, fully_qualified, ranged)
                VALUES (5, 0, 'APCO25', 100, 0, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_radio (alias_id, sort_order, protocol, value, fully_qualified, ranged)
                VALUES (6, 0, 'APCO25', 100, 0, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, nac, first_seen_ms, last_seen_ms, primary_frequency_hz)
                VALUES (4, 'conventional-alpha', 10, 20, 'Alpha Channel', 'County', 'P25-1', 0x123,
                    1000, 3000, 800000000)
                """);
            statement.executeUpdate("""
                INSERT INTO conventional_activity_summary (context_id, frequency_hz, timeslot, first_seen_ms,
                    last_seen_ms, call_count, last_event_type_code)
                VALUES (4, 800000000, 1, 1000, 3000, 100, 1)
                """);
        }
    }
}
