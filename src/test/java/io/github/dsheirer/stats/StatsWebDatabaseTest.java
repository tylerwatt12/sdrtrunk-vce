/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
        assertFalse((Boolean)talkgroups.get("hasMore"));

        Map<String,Object> radios = mDatabase.systemRadios(request(
            "/api/system/radios?wacn=BEE00&system_id=0x348"));
        Map<String,Object> radio = rows(radios).get(0);
        assertEquals("Engine 1", radio.get("alias_name"));
        assertEquals(56132L, number(radio.get("affiliated_talkgroup_id")));
        assertEquals("Dispatch", radio.get("affiliated_talkgroup_alias_name"));

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

        List<Map<String,Object>> channels = rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=" + GUID)));
        assertEquals("traffic", channels.get(0).get("role"));
        assertEquals(854_187_500L, number(channels.get(0).get("downlink_hz")));
        assertEquals("0-821", channels.get(1).get("descriptor"));
        assertEquals("CURRENT", channels.get(1).get("state"));

        List<Map<String,Object>> neighbors = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=" + GUID)));
        assertEquals("CURRENT", neighbors.get(0).get("state"));
        assertEquals("HISTORICAL", neighbors.get(1).get("state"));

        Map<String,Object> patches = mDatabase.sitePatches(request("/api/site/patches?guid=" + GUID));
        assertEquals("Dispatch", rowsFrom(patches, "groups").get(0).get("patch_alias_name"));
        assertEquals("Dispatch", rowsFrom(patches, "talkgroups").get(0).get("alias_name"));
        assertEquals("Engine 1", rowsFrom(patches, "radios").get(0).get("alias_name"));

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
    void dashboardProvidesZeroFilledCallsAndGrantsPerHour() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_activity_bucket
                    (context_id, bucket_start_ms, call_count, grant_count)
                VALUES (1, %d, 7, 9)
                """.formatted(currentHour));
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket
                    (context_id, frequency_hz, timeslot, bucket_start_ms, call_count)
                VALUES (2, 154310000, -1, %d, 3)
                """.formatted(currentHour));
        }

        List<Map<String,Object>> hours = rowsFrom(mDatabase.dashboard(), "activityPerHour");
        assertEquals(24, hours.size());
        assertEquals(currentHour, number(hours.getLast().get("hour_ms")));
        assertEquals(10, number(hours.getLast().get("call_count")));
        assertEquals(9, number(hours.getLast().get("grant_count")));
        assertTrue(hours.stream().limit(23).allMatch(row -> number(row.get("call_count")) == 0));
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
                    primary_frequency_hz, current_control_hz)
                VALUES ('test-site-guid', 'hash', 1000, 2000, 10, 'APCO25', 'Cleveland Simulcast', 'County',
                    'P25-1', 1, 0x49F, 1, 1, 856137500, 856137500)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel (guid, channel_key, descriptor, role, downlink_hz, uplink_hz, tdma,
                    timeslots, confirmed_at_ms) VALUES ('test-site-guid', '0-821', '0-821', 'primary_control',
                    856137500, 811137500, 0, 1, %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_summary (guid, channel_key, descriptor, role, downlink_hz, uplink_hz,
                    tdma, timeslots, first_seen_ms, last_seen_ms, observation_count,
                    primary_control_observations, alternate_control_observations, traffic_observations)
                VALUES ('test-site-guid', '0-821', '0-821', 'primary_control', 856137500, 811137500,
                    0, 1, 1000, 2000, 10, 10, 0, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_summary (guid, channel_key, descriptor, role, downlink_hz, uplink_hz,
                    tdma, timeslots, first_seen_ms, last_seen_ms, observation_count,
                    primary_control_observations, alternate_control_observations, traffic_observations)
                VALUES ('test-site-guid', '0-509', '0-509', 'traffic', 854187500, NULL,
                    0, 1, 1000, 2000, 4, 0, 0, 4)
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
                    '[VALID INFORMATION]', 1000, 1500, 2)
                """);
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
}
