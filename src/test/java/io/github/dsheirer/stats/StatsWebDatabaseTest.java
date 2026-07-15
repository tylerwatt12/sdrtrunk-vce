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
                    (context_id, bucket_start_ms, call_count, grant_count)
                VALUES (1, %d, 7, 9)
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
        assertEquals(10, rowsFrom(dashboard, "actionMix").stream()
            .filter(row -> "CALL".equals(row.get("action")))
            .mapToLong(row -> number(row.get("count")))
            .findFirst().orElseThrow());
        assertTrue(rowsFrom(dashboard, "actionMix").stream()
            .noneMatch(row -> "GRANT".equals(row.get("action"))));
        assertTrue(rowsFrom(mDatabase.system(request(
            "/api/system?wacn=BEE00&system_id=0x348")), "actionCounts").stream()
            .noneMatch(row -> "GRANT".equals(row.get("action"))));
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
                    last_seen_ms, call_count, grant_count, encrypted_count, last_source_radio_id)
                VALUES (1, 100, 1, 1000, 3000, 100, 100, 0, 100)
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
