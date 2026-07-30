/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
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
    void exposesPatchCallsForCanonicalMembersAndRadioRelationships() throws Exception
    {
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_talkgroup_summary (
                    system_key, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms, call_count,
                    encrypted_count, recorded_count, streamed_count, last_source_radio_id
                ) VALUES
                    (1, 56180, 1, 3000, 3000, 1, 1, 1, 1, 1811332),
                    (1, 56181, 1, 3000, 3000, 1, 1, 1, 1, 1811332),
                    (1, 56182, 3, 3000, 3000, 1, 1, 1, 1, 1811332)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_talkgroup_summary (
                    system_key, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms,
                    call_count, encrypted_count
                ) VALUES
                    (1, 1811332, 56180, 1, 3000, 3000, 1, 1),
                    (1, 1811332, 56181, 1, 3000, 3000, 1, 1),
                    (1, 1811332, 56182, 3, 3000, 3000, 1, 1)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_talkgroup_bucket (
                    context_id, talkgroup_id, bucket_start_ms, call_count, encrypted_count, recorded_count,
                    streamed_count
                ) VALUES
                    (1, 56180, %1$d, 1, 1, 1, 1),
                    (1, 56181, %1$d, 1, 1, 1, 1),
                    (1, 56182, %1$d, 1, 1, 1, 1)
                """.formatted(bucket));
            statement.executeUpdate("""
                INSERT INTO p25_site_activity_bucket (
                    context_id, bucket_start_ms, call_count, continue_count, gps_count, encrypted_count,
                    recorded_count, streamed_count
                ) VALUES (1, %d, 1, 3, 2, 1, 1, 1)
                """.formatted(bucket));
            statement.executeUpdate("""
                INSERT INTO p25_site_frequency_summary (
                    context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, call_count, encrypted_count
                ) VALUES (1, 854187500, 1, 3000, 3000, 1, 1)
                """);
        }

        List<Map<String,Object>> talkgroups = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?wacn=BEE00&system_id=0x348&q=5618&sort=talkgroup&direction=asc")));
        assertEquals(List.of(56180L, 56181L, 56182L), talkgroups.stream()
            .map(row -> number(row.get("talkgroup_id"))).toList());

        for(Map<String,Object> talkgroup: talkgroups)
        {
            assertEquals(1L, number(talkgroup.get("call_count")));
            assertEquals(1L, number(talkgroup.get("encrypted_count")));
            assertEquals(1L, number(talkgroup.get("recorded_count")));
            assertEquals(1L, number(talkgroup.get("streamed_count")));
        }

        Map<String,Object> patch = map(mDatabase.talkgroup(request(
            "/api/talkgroup?wacn=BEE00&system_id=0x348&talkgroup_id=56182")), "talkgroup");
        assertEquals(3L, number(patch.get("target_kind_code")));
        assertEquals(1L, number(patch.get("radios")));

        Map<String,Object> radio = map(mDatabase.radio(request(
            "/api/radio?wacn=BEE00&system_id=0x348&radio_id=1811332")), "radio");
        assertEquals(4L, number(radio.get("talkgroups")));

        List<Map<String,Object>> relationships = rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/radio-talkgroups?wacn=BEE00&system_id=0x348&radio_id=1811332"))).stream()
            .filter(row -> number(row.get("talkgroup_id")) >= 56180L)
            .toList();
        assertEquals(List.of(56180L, 56181L, 56182L), relationships.stream()
            .map(row -> number(row.get("talkgroup_id"))).sorted().toList());
        assertEquals(1L, relationships.stream()
            .filter(row -> number(row.get("target_kind_code")) == 3L).count());

        List<Map<String,Object>> siteTalkgroups = rows(mDatabase.siteTalkgroups(request(
            "/api/site/talkgroups?guid=" + GUID))).stream()
            .filter(row -> number(row.get("talkgroup_id")) >= 56180L)
            .toList();
        assertEquals(3, siteTalkgroups.size());
        assertTrue(siteTalkgroups.stream().allMatch(row -> number(row.get("call_count")) == 1L &&
            number(row.get("encrypted_count")) == 1L && number(row.get("recorded_count")) == 1L &&
            number(row.get("streamed_count")) == 1L));

        Map<String,Object> systemResponse = mDatabase.system(request(
            "/api/system?wacn=BEE00&system_id=0x348"));
        Map<String,Object> system = map(systemResponse, "system");
        assertEquals(1L, number(system.get("activity_calls")));
        assertEquals(1L, number(system.get("activity_retained_calls")));
        assertEquals(1L, number(system.get("activity_recorded")));
        assertEquals(1L, number(system.get("activity_streamed")));
        assertEquals(1L, number(system.get("activity_encrypted")));

        List<Map<String,Object>> actionCounts = rowsFrom(systemResponse, "actionCounts");
        assertEquals(2, actionCounts.size());
        assertEquals("CONTINUE", actionCounts.getFirst().get("action"));
        assertEquals(3L, number(actionCounts.getFirst().get("count")));
        assertEquals("GPS", actionCounts.get(1).get("action"));
        assertEquals(2L, number(actionCounts.get(1).get("count")));
        assertTrue(actionCounts.stream().noneMatch(row ->
            "CALL".equals(row.get("action")) || "ENCRYPTED".equals(row.get("action"))));
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
        assertEquals("trunked", site.get("site_type"));
        Map<String,Object> capabilities = map(site, "capabilities");
        assertEquals(Boolean.TRUE, capabilities.get("quality"));
        assertEquals(Boolean.TRUE, capabilities.get("quality_history"));
        assertEquals(Boolean.TRUE, capabilities.get("band_plan"));
        assertEquals(Boolean.TRUE, capabilities.get("patches"));
        assertEquals(Boolean.TRUE, capabilities.get("activity"));

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
    void sumsAffiliationSignalingForZeroCallTalkgroups() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_talkgroup_summary (
                    system_key, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms, join_count
                ) VALUES (1, 57000, 1, 1000, 3000, 9)
                """);
        }

        List<Map<String,Object>> talkgroups = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?wacn=BEE00&system_id=0x348&sort=signaling&direction=desc")));
        Map<String,Object> affiliatedOnly = talkgroups.stream()
            .filter(row -> number(row.get("talkgroup_id")) == 57000L).findFirst().orElseThrow();
        assertEquals(57000L, number(affiliatedOnly.get("talkgroup_id")));
        assertEquals(0L, number(affiliatedOnly.get("call_count")));
        assertEquals(9L, number(affiliatedOnly.get("signaling_count")));
        assertFalse(affiliatedOnly.containsKey("join_count"));
        assertFalse(affiliatedOnly.containsKey("evidence_total"));
        assertFalse(affiliatedOnly.containsKey("evidence_label"));
        assertFalse(affiliatedOnly.containsKey("evidence_count"));
        assertFalse(affiliatedOnly.containsKey("evidence_kind"));
        assertNull(affiliatedOnly.get("alias_name"));
    }

    @Test
    void sortsTalkgroupsBySignalingWithoutCountingCallOutputs() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_talkgroup_summary (
                    system_key, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms,
                    grant_count, denial_count, request_count
                ) VALUES (1, 57001, 1, 1000, 3000, 3, 7, 5)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_talkgroup_summary (
                    system_key, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms,
                    recorded_count, streamed_count
                ) VALUES (1, 57002, 1, 1000, 3000, 2, 3)
                """);
        }

        List<Map<String,Object>> talkgroups = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?wacn=BEE00&system_id=0x348&sort=signaling&direction=desc")));
        assertEquals(57001L, number(talkgroups.getFirst().get("talkgroup_id")));
        Map<String,Object> signaling = talkgroups.stream()
            .filter(row -> number(row.get("talkgroup_id")) == 57001L).findFirst().orElseThrow();
        assertEquals(15L, number(signaling.get("signaling_count")));
        assertFalse(signaling.containsKey("denial_count"));
        assertFalse(signaling.containsKey("request_count"));
        assertFalse(signaling.containsKey("evidence_total"));

        Map<String,Object> output = talkgroups.stream()
            .filter(row -> number(row.get("talkgroup_id")) == 57002L).findFirst().orElseThrow();
        assertEquals(0L, number(output.get("signaling_count")));
        assertEquals(2L, number(output.get("recorded_count")));
        assertEquals(3L, number(output.get("streamed_count")));
        assertFalse(output.containsKey("evidence_total"));

        List<Map<String,Object>> compatibilitySort = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?wacn=BEE00&system_id=0x348&sort=evidence&direction=desc")));
        assertEquals(57001L, number(compatibilitySort.getFirst().get("talkgroup_id")));
    }

    @Test
    void hidesLegacyReservedP25DirectoryRowsWithoutDeletingActivity() throws Exception
    {
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_talkgroup_summary (
                    system_key, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms
                ) VALUES (1, 0, 1, 1000, 3000),
                         (1, 65535, 1, 1000, 3000),
                         (1, 65536, 1, 1000, 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_summary (system_key, radio_id, first_seen_ms, last_seen_ms)
                VALUES (1, 0, 1000, 3000),
                       (1, 16777212, 1000, 3000),
                       (1, 16777215, 1000, 3000),
                       (1, 16777216, 1000, 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_talkgroup_summary (
                    system_key, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms
                ) VALUES (1, 16777212, 56132, 1, 1000, 3000),
                         (1, 1811332, 65535, 1, 1000, 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_affiliation (system_key, radio_id, talkgroup_id, updated_at_ms)
                VALUES (1, 16777212, 56132, 3000),
                       (1, 1811333, 65535, 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_talkgroup_bucket (
                    context_id, talkgroup_id, bucket_start_ms
                ) VALUES (1, 0, %1$d), (1, 65535, %1$d), (1, 65536, %1$d)
                """.formatted(bucket));
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (
                    context_id, observed_at_ms, action_code, source_radio_id, target_id, target_kind_code, encrypted
                ) VALUES (1, 3000, 1, 16777212, 0, 1, 0)
                """);
        }

        assertEquals(List.of(56132L), rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?wacn=BEE00&system_id=0x348"))).stream()
            .map(row -> number(row.get("talkgroup_id"))).toList());
        assertEquals(List.of(1811332L), rows(mDatabase.systemRadios(request(
            "/api/system/radios?wacn=BEE00&system_id=0x348"))).stream()
            .map(row -> number(row.get("radio_id"))).toList());
        assertEquals(1, rows(mDatabase.currentAffiliations(request(
            "/api/system/affiliations?wacn=BEE00&system_id=0x348"))).size());
        assertEquals(1, rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/radio-talkgroups?wacn=BEE00&system_id=0x348"))).size());
        assertEquals(0, rows(mDatabase.siteTalkgroups(request(
            "/api/site/talkgroups?guid=" + GUID))).stream()
            .filter(row -> number(row.get("talkgroup_id")) == 0 ||
                number(row.get("talkgroup_id")) >= 65535).count());
        assertEquals(1, rows(mDatabase.activity(request("/api/activity?guid=" + GUID))).stream()
            .filter(row -> row.get("source_radio_id") instanceof Number source &&
                source.longValue() == 16777212L &&
                row.get("target_id") instanceof Number target && target.longValue() == 0).count());

        Map<String,Object> system = map(mDatabase.system(request(
            "/api/system?wacn=BEE00&system_id=0x348")), "system");
        assertEquals(1L, number(system.get("talkgroups")));
        assertEquals(1L, number(system.get("radios")));
        assertEquals(1L, number(system.get("affiliations")));
    }

    @Test
    void resolvesNeighborNameAndGuidWithinTheSourceWacn() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (
                    guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac, rfss, site,
                    lra, mfid, micro_slots, data_service, registration_service, tdma, voice_service,
                    primary_frequency_hz, current_control_hz
                ) VALUES ('neighbor-site-guid', 'neighbor-hash', 1000, 4000, 1, 'APCO25',
                    'Neighbor Simulcast', 'County', 'P25-1', 1, 0x49F, 1, 2,
                    0, 0x90, 110, 1, 1, 1, 1, 855137500, 855137500)
                """);
        }

        Map<String,Object> neighbor = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=" + GUID))).stream()
            .filter(row -> number(row.get("rfss")) == 1 && number(row.get("site")) == 2)
            .findFirst().orElseThrow();
        assertEquals("Neighbor Simulcast", neighbor.get("neighbor_name"));
        assertEquals("neighbor-site-guid", neighbor.get("neighbor_guid"));
    }

    @Test
    void exposesProtocolAwareEncryptionNames() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, channel_name, decoder,
                    first_seen_ms, last_seen_ms
                ) VALUES
                    (20, 'dmr-encryption', 'dmr-encryption-guid', 1, 3, 'DMR Encryption', 'DMR', 1000, 3000),
                    (21, 'nxdn-encryption', 'nxdn-encryption-guid', 1, 4, 'NXDN Encryption', 'NXDN', 1000, 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (
                    context_id, observed_at_ms, action_code, event_type_code, encrypted,
                    encryption_algorithm_id, encryption_key_id
                ) VALUES
                    (1, 3001, 0, 0, 1, 1, 17),
                    (20, 3002, 0, 0, 1, 1, 17),
                    (21, 3003, 0, 0, 1, 1, 17),
                    (20, 3004, 0, 0, 1, NULL, NULL)
                """);
            statement.executeUpdate("""
                UPDATE p25_talkgroup_summary
                SET last_encryption_algorithm_id = 132, last_encryption_key_id = 52
                WHERE system_key = 1 AND talkgroup_id = 56132
                """);
            statement.executeUpdate("""
                UPDATE p25_radio_summary
                SET last_encryption_algorithm_id = 132, last_encryption_key_id = 52
                WHERE system_key = 1 AND radio_id = 1811332
                """);
        }

        Map<String,Object> p25 = rows(mDatabase.activity(request(
            "/api/activity?context=site-cleveland"))).getFirst();
        assertEquals("BAT-E K:11", p25.get("encryption_display"));
        assertEquals("BATON AUTO EVEN K:11", p25.get("encryption_full_display"));

        List<Map<String,Object>> dmr = rows(mDatabase.activity(request(
            "/api/activity?context=dmr-encryption")));
        assertEquals("ENC", dmr.getFirst().get("encryption_display"));
        assertEquals("HYT-BP K:11", dmr.get(1).get("encryption_display"));
        assertEquals("Hytera Basic Privacy K:11", dmr.get(1).get("encryption_full_display"));

        Map<String,Object> nxdn = rows(mDatabase.activity(request(
            "/api/activity?context=nxdn-encryption"))).getFirst();
        assertEquals("SCRAM K:11", nxdn.get("encryption_display"));
        assertEquals("Scrambler K:11", nxdn.get("encryption_full_display"));

        long p25Id = number(p25.get("id"));
        assertEquals("BAT-E K:11", mDatabase.activityByIds(List.of(p25Id)).getFirst()
            .get("encryption_display"));

        Map<String,Object> talkgroup = map(mDatabase.talkgroup(request(
            "/api/talkgroup?wacn=BEE00&system_id=0x348&talkgroup_id=56132")), "talkgroup");
        assertEquals("AES256", talkgroup.get("last_encryption_algorithm_display"));
        assertEquals("AES-256", talkgroup.get("last_encryption_algorithm_name"));
        Map<String,Object> radio = map(mDatabase.radio(request(
            "/api/radio?wacn=BEE00&system_id=0x348&radio_id=1811332")), "radio");
        assertEquals("AES256", radio.get("last_encryption_algorithm_display"));
        assertEquals("AES-256", radio.get("last_encryption_algorithm_name"));
    }

    @Test
    void countsRetainedPhysicalP25ChannelsConsistently() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_summary (guid, channel_key, descriptor, downlink_hz, uplink_hz,
                    tdma, timeslots, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', '2-1470', '2-1470', 771193750, NULL, 1, 2, 1000, 2000, 2),
                    ('test-site-guid', '10-2940', '10-2940', 771193750, NULL, 1, 2, 1000, 2000, 2),
                    ('test-site-guid', '2-999', '2-999', NULL, NULL, 1, 2, 1000, 2000, 1),
                    ('test-site-guid', '10-1998', '10-1998', NULL, NULL, 1, 2, 1000, 2000, 1)
                """);
        }

        assertEquals(6, number(map(mDatabase.site(request("/api/site?guid=" + GUID)), "site").get("channels")));
        assertEquals(6, rows(mDatabase.siteChannels(request("/api/site/channels?guid=" + GUID))).size());
        assertEquals(1, rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=" + GUID + "&limit=1"))).size());

        Map<String,Object> recentSite = rowsFrom(mDatabase.dashboard(), "recentReceivers").stream()
            .filter(row -> GUID.equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals(6, number(recentSite.get("channels")));

        Map<String,Object> directorySite = rows(mDatabase.systemDirectory(request("/api/system-directory"))).stream()
            .flatMap(system -> rowsFrom(system, "children").stream())
            .filter(row -> GUID.equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals(6, number(directorySite.get("channels")));
    }

    @Test
    void exposesConventionalContextsSeparately()
    {
        Map<String,Object> conventional = mDatabase.conventional(request("/api/conventional"));
        assertEquals(1, rows(conventional).size());
        assertEquals("County Fire", rows(conventional).get(0).get("channel_name"));
        assertEquals(10L, number(rows(conventional).get(0).get("protocol_code")));

        Map<String,Object> detail = mDatabase.conventionalDetail(request(
            "/api/conventional/detail?context=conventional-fire"));
        assertEquals("County Fire", map(detail, "context").get("channel_name"));
        assertEquals(10L, number(map(detail, "context").get("protocol_code")));
        assertTrue(rowsFrom(detail, "summaries").get(0).containsKey("frequency_hz"));
    }

    @Test
    void exposesConventionalDmrIdentitiesWithExactContextAliases() throws Exception
    {
        seedDmrConventionalRows(mDatabasePath);

        Map<String,Object> detail = mDatabase.conventionalDetail(request(
            "/api/conventional/detail?context=conventional-dmr-county"));
        Map<String,Object> capabilities = map(map(detail, "context"), "capabilities");
        assertTrue((Boolean)capabilities.get("info"));
        assertTrue((Boolean)capabilities.get("talkgroups"));
        assertTrue((Boolean)capabilities.get("radios"));
        assertTrue((Boolean)capabilities.get("activity"));

        Map<String,Object> analogDetail = mDatabase.conventionalDetail(request(
            "/api/conventional/detail?context=conventional-fire"));
        Map<String,Object> analogCapabilities = map(map(analogDetail, "context"), "capabilities");
        assertTrue((Boolean)analogCapabilities.get("info"));
        assertTrue((Boolean)analogCapabilities.get("activity"));
        assertFalse((Boolean)analogCapabilities.get("talkgroups"));
        assertFalse((Boolean)analogCapabilities.get("radios"));

        Map<String,Object> talkgroups = mDatabase.conventionalTalkgroups(request(
            "/api/conventional/talkgroups?context=conventional-dmr-county&sort=talkgroup&direction=asc"));
        assertEquals(2, rows(talkgroups).size());
        Map<String,Object> dispatch = rows(talkgroups).getFirst();
        assertEquals(91L, number(dispatch.get("talkgroup_id")));
        assertEquals("DMR Dispatch", dispatch.get("alias_name"));
        assertEquals("County DMR", dispatch.get("alias_list_name"));
        assertEquals("DMR Engine 1", dispatch.get("last_source_alias_name"));
        assertEquals(451_012_500L, number(dispatch.get("frequency_hz")));
        assertEquals(1L, number(dispatch.get("timeslot")));
        assertEquals(10L, number(dispatch.get("call_count")));
        assertEquals(2L, number(dispatch.get("encrypted_count")));

        Map<String,Object> radios = mDatabase.conventionalRadios(request(
            "/api/conventional/radios?context=conventional-dmr-county&sort=radio&direction=asc"));
        assertEquals(2, rows(radios).size());
        Map<String,Object> engine = rows(radios).getFirst();
        assertEquals(123_456L, number(engine.get("radio_id")));
        assertEquals("DMR Engine 1", engine.get("alias_name"));
        assertEquals("DMR Dispatch", engine.get("last_talkgroup_alias_name"));
        assertEquals("DMR Engine 2", engine.get("last_peer_alias_name"));
        assertEquals(7L, number(engine.get("source_call_count")));
        assertEquals(3L, number(engine.get("target_call_count")));
        assertFalse(engine.values().contains("Other Dispatch"));
        assertFalse(engine.values().contains("Other Engine"));
    }

    @Test
    void conventionalDmrIdentityPagesSortSearchAndStayContextScoped() throws Exception
    {
        seedDmrConventionalRows(mDatabasePath);

        Map<String,Object> firstPage = mDatabase.conventionalTalkgroups(request(
            "/api/conventional/talkgroups?context=conventional-dmr-county&sort=calls&limit=1"));
        assertEquals(92L, number(rows(firstPage).getFirst().get("talkgroup_id")));
        assertEquals(1L, number(firstPage.get("limit")));
        assertEquals(0L, number(firstPage.get("offset")));
        assertTrue((Boolean)firstPage.get("hasMore"));
        assertEquals(1L, number(firstPage.get("nextOffset")));

        Map<String,Object> secondPage = mDatabase.conventionalTalkgroups(request(
            "/api/conventional/talkgroups?context=conventional-dmr-county&sort=calls&limit=1&offset=1"));
        assertEquals(91L, number(rows(secondPage).getFirst().get("talkgroup_id")));
        assertFalse((Boolean)secondPage.get("hasMore"));

        Map<String,Object> aliasSearch = mDatabase.conventionalTalkgroups(request(
            "/api/conventional/talkgroups?context=conventional-dmr-county&q=dispatch"));
        assertEquals(List.of(91L), rows(aliasSearch).stream()
            .map(row -> number(row.get("talkgroup_id"))).toList());
        assertEquals("DMR Dispatch", rows(mDatabase.conventionalTalkgroups(request(
            "/api/conventional/talkgroups?context=conventional-dmr-county&sort=alias&direction=asc&limit=1")))
            .getFirst().get("alias_name"));

        Map<String,Object> radioSearch = mDatabase.conventionalRadios(request(
            "/api/conventional/radios?context=conventional-dmr-county&q=engine%202"));
        assertEquals(List.of(234_567L), rows(radioSearch).stream()
            .map(row -> number(row.get("radio_id"))).toList());
        assertEquals("DMR Engine 2", rows(mDatabase.conventionalRadios(request(
            "/api/conventional/radios?context=conventional-dmr-county&sort=alias&limit=1")))
            .getFirst().get("alias_name"));

        Map<String,Object> otherContext = mDatabase.conventionalTalkgroups(request(
            "/api/conventional/talkgroups?context=conventional-dmr-other"));
        assertEquals(1, rows(otherContext).size());
        assertEquals(999L, number(rows(otherContext).getFirst().get("call_count")));
        assertEquals("Other Dispatch", rows(otherContext).getFirst().get("alias_name"));

        StatsApiException wrongProtocol = assertThrows(StatsApiException.class,
            () -> mDatabase.conventionalTalkgroups(request(
                "/api/conventional/talkgroups?context=conventional-fire")));
        assertEquals(404, wrongProtocol.status());
        StatsApiException missingContext = assertThrows(StatsApiException.class,
            () -> mDatabase.conventionalRadios(request("/api/conventional/radios")));
        assertEquals(400, missingContext.status());
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
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id, call_count
                ) VALUES (3, %d, 1, 1, 56132, 100)
                """.formatted(currentHour));
        }

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

        Map<String,Object> dashboardTalkgroup = rowsFrom(mDatabase.dashboard(), "topDestinations").stream()
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
    void dashboardProvidesProtocolNeutralZeroFilledCallsWithoutDoubleCountingGrants() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    decoder, first_seen_ms, last_seen_ms)
                VALUES (7, 'conventional-p25', 'conventional-p25-guid', 2, 1, 'P25 Conventional',
                           'P25-1', 1000, 2000),
                       (8, 'conventional-dmr', 'conventional-dmr-guid', 3, 3, 'DMR Conventional',
                           'DMR', 1000, 2000),
                       (9, 'site-dmr', 'site-dmr-guid', 1, 3, 'DMR Trunked', 'DMR', 1000, 2000),
                       (10, 'site-nxdn', 'site-nxdn-guid', 1, 4, 'NXDN Trunked', 'NXDN', 1000, 2000),
                       (11, 'site-p25-phase2', 'site-p25-phase2-guid', 1, 2, 'P25 Phase 2',
                           'P25-2', 1000, 2000),
                       (12, 'conventional-nxdn', 'conventional-nxdn-guid', 4, 4, 'NXDN Conventional',
                           'NXDN', 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_activity_bucket
                    (context_id, bucket_start_ms, call_count, grant_count, recorded_count, streamed_count,
                     encrypted_count)
                VALUES (1, %1$d, 7, 9, 5, 4, 2),
                       (9, %1$d, 5, 0, 2, 1, 1),
                       (10, %1$d, 6, 0, 3, 2, 2),
                       (11, %1$d, 1, 0, 1, 1, 0)
                """.formatted(currentHour));
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket
                    (context_id, frequency_hz, timeslot, bucket_start_ms, call_count, recorded_count,
                     streamed_count, encrypted_count)
                VALUES (2, 154310000, -1, %1$d, 3, 1, 1, 0),
                       (7, 154875000, -1, %1$d, 2, 1, 1, 1),
                       (8, 451012500, 1, %1$d, 4, 2, 1, 1),
                       (12, 461125000, -1, %1$d, 7, 2, 1, 2)
                """.formatted(currentHour));
        }

        Map<String,Object> dashboard = mDatabase.dashboard();
        assertFalse(dashboard.containsKey("activityPerHour"));
        assertTrue(rowsFrom(mDatabase.system(request(
            "/api/system?wacn=BEE00&system_id=0x348")), "actionCounts").stream()
            .noneMatch(row -> "CALL".equals(row.get("action")) || "ENCRYPTED".equals(row.get("action"))));
        assertTrue(rowsFrom(mDatabase.system(request(
            "/api/system?wacn=BEE00&system_id=0x348")), "actionCounts").stream()
            .anyMatch(row -> "GRANT".equals(row.get("action"))));
        assertFalse(dashboard.containsKey("p25CallActivity"));
        Map<String,Object> callActivity = map(dashboard, "callActivity");
        Map<String,Object> totals = map(callActivity, "totals");
        assertEquals(35, number(totals.get("call_count")));
        assertEquals(17, number(totals.get("recorded_count")));
        assertEquals(12, number(totals.get("streamed_count")));
        assertEquals(9, number(totals.get("encrypted_count")));
        assertFalse(totals.containsKey("non_p25_call_count"));
        assertTrue(number(callActivity.get("metric_start_ms")) > 0);

        List<Map<String,Object>> breakdown = rowsFrom(callActivity, "breakdown");
        Map<String,Object> p25Trunked = breakdown.stream()
            .filter(row -> "P25".equals(row.get("protocol")) && "TRUNKED".equals(row.get("channel_kind")))
            .findFirst().orElseThrow();
        assertEquals(8, number(map(p25Trunked, "totals").get("call_count")));
        Map<String,Object> p25Conventional = breakdown.stream()
            .filter(row -> "P25".equals(row.get("protocol")) &&
                "CONVENTIONAL".equals(row.get("channel_kind")))
            .findFirst().orElseThrow();
        assertEquals(2, number(map(p25Conventional, "totals").get("call_count")));
        Map<String,Object> nbfmConventional = breakdown.stream()
            .filter(row -> "NBFM".equals(row.get("protocol")) &&
                "CONVENTIONAL".equals(row.get("channel_kind")))
            .findFirst().orElseThrow();
        assertEquals(3, number(map(nbfmConventional, "totals").get("call_count")));
        assertFalse(map(p25Conventional, "totals").containsKey("non_p25_call_count"));

        List<Map<String,Object>> series = rowsFrom(callActivity, "series");
        assertEquals(7 * 24, series.size());
        Map<String,Object> currentP25Conventional = series.stream()
            .filter(row -> number(row.get("time_ms")) == currentHour &&
                "P25".equals(row.get("protocol")) && "CONVENTIONAL".equals(row.get("channel_kind")))
            .findFirst().orElseThrow();
        assertEquals(2, number(currentP25Conventional.get("call_count")));
        assertEquals(1, number(currentP25Conventional.get("recorded_count")));
        assertTrue(series.stream()
            .filter(row -> "P25".equals(row.get("protocol")) &&
                "CONVENTIONAL".equals(row.get("channel_kind")) &&
                number(row.get("time_ms")) < currentHour)
            .allMatch(row -> number(row.get("call_count")) == 0));
        assertTrue(series.stream()
            .filter(row -> ("P25".equals(row.get("protocol")) || "DMR".equals(row.get("protocol"))) &&
                "CONVENTIONAL".equals(row.get("channel_kind")) &&
                number(row.get("time_ms")) < currentHour)
            .allMatch(row -> row.get("encrypted_count") == null));
        assertTrue(series.stream()
            .filter(row -> "NBFM".equals(row.get("protocol")))
            .allMatch(row -> row.get("encrypted_count") == null));
        Map<String,Object> currentNxdnConventional = series.stream()
            .filter(row -> "NXDN".equals(row.get("protocol")) &&
                "CONVENTIONAL".equals(row.get("channel_kind")) &&
                number(row.get("time_ms")) == currentHour)
            .findFirst().orElseThrow();
        assertEquals(7, number(currentNxdnConventional.get("call_count")));
        assertEquals(2, number(currentNxdnConventional.get("recorded_count")));
        assertEquals(1, number(currentNxdnConventional.get("streamed_count")));
        assertEquals(2, number(currentNxdnConventional.get("encrypted_count")));
        assertTrue(series.stream()
            .filter(row -> ("DMR".equals(row.get("protocol")) || "NXDN".equals(row.get("protocol"))) &&
                "TRUNKED".equals(row.get("channel_kind")) &&
                number(row.get("time_ms")) < currentHour)
            .allMatch(row -> row.get("call_count") == null && row.get("encrypted_count") == null));
        assertEquals("PARTIAL", rowsFrom(callActivity, "coverage").stream()
            .filter(row -> "DMR".equals(row.get("protocol")) &&
                "TRUNKED".equals(row.get("channel_kind")))
            .findFirst().orElseThrow().get("call_count"));
        assertEquals("PARTIAL", rowsFrom(callActivity, "coverage").stream()
            .filter(row -> "NXDN".equals(row.get("protocol")) &&
                "TRUNKED".equals(row.get("channel_kind")))
            .findFirst().orElseThrow().get("encrypted_count"));
        Map<String,Object> nxdnConventionalCoverage = rowsFrom(callActivity, "coverage").stream()
            .filter(row -> "NXDN".equals(row.get("protocol")) &&
                "CONVENTIONAL".equals(row.get("channel_kind")))
            .findFirst().orElseThrow();
        assertEquals("PARTIAL", nxdnConventionalCoverage.get("status"));
        assertEquals("PARTIAL", nxdnConventionalCoverage.get("call_count"));

        Map<String,Object> sourceActivity = map(dashboard, "sourceActivity24h");
        List<Map<String,Object>> sources = rows(sourceActivity);
        assertEquals(8, sources.size());
        assertTrue(sources.stream().allMatch(row -> number(row.get("total_call_count")) == 35));
        assertTrue(sources.stream().anyMatch(row -> "P25".equals(row.get("protocol")) &&
            "CONVENTIONAL".equals(row.get("channel_kind")) &&
            number(row.get("call_count")) == 2));
        assertTrue(sources.stream().anyMatch(row -> "NXDN".equals(row.get("protocol")) &&
            "CONVENTIONAL".equals(row.get("channel_kind")) &&
            number(row.get("call_count")) == 7));
    }

    @Test
    void dashboardRanksEachActivitySourceSeparately() throws Exception
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

        Map<String,Object> activity = map(mDatabase.dashboard(), "sourceActivity24h");
        List<Map<String,Object>> rows = rows(activity);
        assertEquals(2, rows.size());
        assertEquals(GUID, rows.getFirst().get("guid"));
        assertEquals("P25", rows.getFirst().get("protocol"));
        assertEquals("TRUNKED", rows.getFirst().get("channel_kind"));
        assertEquals(12, number(rows.getFirst().get("call_count")));
        assertEquals("test-site-lakewood", rows.getLast().get("guid"));
        assertEquals(3, number(rows.getLast().get("call_count")));
        assertTrue(rows.stream().allMatch(row -> number(row.get("total_call_count")) == 15));
        assertEquals(firstHour, number(activity.get("from_ms")));
    }

    @Test
    void dashboardRanksProtocolNeutralDestinationsAndSourcesFromBoundedIdentityBuckets() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        seedDmrConventionalRows(mDatabasePath);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name,
                    decoder, first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES
                    (7, 'conventional-p25-alias', 'conventional-p25-alias-guid', 2, 1,
                        'P25 Conventional', 'County', 'P25-1', 1000, 2000, 154875000),
                    (9, 'site-dmr-alias', 'site-dmr-alias-guid', 1, 3,
                        'DMR Trunked', NULL, 'DMR', 1000, 2000, 461012500),
                    (10, 'site-nxdn-alias', 'site-nxdn-alias-guid', 1, 4,
                        'NXDN Trunked', NULL, 'NXDN', 1000, 2000, 452012500)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_snapshot (
                    guid, snapshot_hash, protocol_code, channel_name, alias_list_name, decoder,
                    primary_frequency_hz, current_control_hz, first_seen_ms, last_seen_ms
                ) VALUES
                    ('site-dmr-alias-guid', 'dmr-alias-hash', 3, 'DMR Trunked',
                        'County DMR', 'DMR', 461012500, 461012500, 1000, 2000),
                    ('site-nxdn-alias-guid', 'nxdn-alias-hash', 4, 'NXDN Trunked',
                        'NXDN County', 'NXDN', 452012500, 452012500, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family)
                VALUES (200, 'NXDN County', 'NXDN')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, color, matcher_type, protocol, value
                ) VALUES
                    (200, 200, 'NXDN Dispatch', 'NXDN Dispatch', 255, 'TALKGROUP', 'NXDN', 77),
                    (201, 200, 'NXDN Unit', 'NXDN Units', 65280, 'RADIO_ID', 'NXDN', 700)
                """);
            statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                    call_count, encrypted_count, recorded_count, streamed_count
                ) VALUES
                    (1, %1$d, 1, 1, 56132, 8, 2, 5, 4),
                    (1, %1$d, 2, 2, 1811332, 8, 2, 5, 4),
                    (5, %1$d, 1, 1, 91, 12, 3, 6, 2),
                    (5, %1$d, 2, 2, 123456, 12, 3, 6, 2),
                    (7, %1$d, 1, 1, 56132, 10, 1, 4, 1),
                    (7, %1$d, 2, 2, 1811332, 10, 1, 4, 1),
                    (9, %1$d, 1, 1, 91, 9, 1, 2, 1),
                    (9, %1$d, 2, 2, 123456, 9, 1, 2, 1),
                    (10, %1$d, 1, 1, 77, 11, 2, 3, 2),
                    (10, %1$d, 2, 2, 700, 11, 2, 3, 2),
                    (2, %1$d, 1, 0, 0, 4, 0, 1, 0)
                """.formatted(currentHour));
        }

        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
        Map<String,Object> dashboard = mDatabase.dashboard();
        List<Map<String,Object>> destinations = rowsFrom(dashboard, "topDestinations");
        List<Map<String,Object>> sources = rowsFrom(dashboard, "topSources");
        assertFalse(dashboard.containsKey("topTalkgroups"));
        assertFalse(dashboard.containsKey("topRadios"));
        assertEquals(List.of("DMR", "NXDN", "P25", "DMR", "P25", "NBFM"), destinations.stream()
            .map(row -> String.valueOf(row.get("protocol"))).toList());
        assertEquals(List.of("DMR", "NXDN", "P25", "DMR", "P25"), sources.stream()
            .map(row -> String.valueOf(row.get("protocol"))).toList());

        Map<String,Object> dmrDestination = destinations.getFirst();
        assertEquals("CONVENTIONAL", dmrDestination.get("channel_kind"));
        assertEquals("Talkgroup", dmrDestination.get("identity_kind"));
        assertEquals("DMR Dispatch", dmrDestination.get("alias_name"));
        assertEquals("conventional-talkgroups", dmrDestination.get("identity_detail_view"));
        assertEquals(12, number(dmrDestination.get("call_count")));
        assertEquals(6, number(dmrDestination.get("recorded_count")));
        assertEquals(2, number(dmrDestination.get("streamed_count")));

        Map<String,Object> nxdnDestination = destinations.stream()
            .filter(row -> "NXDN".equals(row.get("protocol"))).findFirst().orElseThrow();
        assertEquals("NXDN Dispatch", nxdnDestination.get("alias_name"));
        assertEquals("NXDN County", nxdnDestination.get("alias_list_name"));
        assertEquals(0, number(nxdnDestination.get("identity_detail_available")));
        Map<String,Object> nxdnSource = sources.stream()
            .filter(row -> "NXDN".equals(row.get("protocol"))).findFirst().orElseThrow();
        assertEquals("NXDN Unit", nxdnSource.get("alias_name"));

        Map<String,Object> dmrTrunkedDestination = destinations.stream()
            .filter(row -> "site-dmr-alias".equals(row.get("context_key"))).findFirst().orElseThrow();
        assertEquals("DMR Dispatch", dmrTrunkedDestination.get("alias_name"));
        assertEquals("County DMR", dmrTrunkedDestination.get("alias_list_name"));
        Map<String,Object> dmrTrunkedSource = sources.stream()
            .filter(row -> "site-dmr-alias".equals(row.get("context_key"))).findFirst().orElseThrow();
        assertEquals("DMR Engine 1", dmrTrunkedSource.get("alias_name"));

        Map<String,Object> p25Conventional = destinations.stream()
            .filter(row -> "conventional-p25-alias".equals(row.get("context_key"))).findFirst().orElseThrow();
        assertEquals("Dispatch", p25Conventional.get("alias_name"));
        assertEquals(0, number(p25Conventional.get("identity_detail_available")));

        Map<String,Object> p25TrunkedSource = sources.stream()
            .filter(row -> "P25".equals(row.get("protocol")) &&
                "TRUNKED".equals(row.get("channel_kind"))).findFirst().orElseThrow();
        assertEquals("Engine 1", p25TrunkedSource.get("alias_name"));
        assertEquals("CAR 201", p25TrunkedSource.get("last_talker_alias"));
        assertEquals(2000, number(p25TrunkedSource.get("last_talker_alias_seen_ms")));
        assertEquals("radio", p25TrunkedSource.get("identity_detail_view"));
        assertEquals(1, number(p25TrunkedSource.get("identity_detail_available")));
        assertTrue(destinations.stream().allMatch(row -> row.get("last_talker_alias") == null),
            "Destination identities must never inherit a matching radio's talker alias");

        Map<String,Object> unknownDestination = destinations.stream()
            .filter(row -> number(row.get("identity_kind_code")) == 0).findFirst().orElseThrow();
        assertEquals("Channel / Unknown", unknownDestination.get("identity_kind"));
        assertEquals("County Fire", unknownDestination.get("channel_name"));
        assertFalse(unknownDestination.containsKey("alias_name"),
            "NBFM calls are channel-scoped and do not carry a talkgroup/radio identity");
        assertEquals(0, number(unknownDestination.get("identity_detail_available")));
    }

    @Test
    void dashboardActivityQueriesUseBucketIndexesAtRepresentativeVolume() throws Exception
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
                    """);
                PreparedStatement identities = connection.prepareStatement("""
                    INSERT INTO call_identity_bucket (
                        context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id, call_count
                    ) VALUES (?, ?, 1, 1, ?, 1)
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

                        identities.setInt(1, context);
                        identities.setLong(2, previousFirstHour + hour * 3_600_000L);
                        identities.setInt(3, 10_000 + context);
                        identities.addBatch();
                    }
                }

                contexts.executeBatch();
                buckets.executeBatch();
                identities.executeBatch();
            }
            connection.commit();

            for(String sql: List.of(StatsWebDatabase.DASHBOARD_CALL_ACTIVITY_SQL,
                StatsWebDatabase.DASHBOARD_SOURCE_ACTIVITY_SQL))
            {
                List<String> plan = new ArrayList<>();
                try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql))
                {
                    statement.setLong(1, firstHour);
                    statement.setLong(2, currentHour + 3_600_000L);
                    statement.setLong(3, firstHour);
                    statement.setLong(4, currentHour + 3_600_000L);
                    try(ResultSet resultSet = statement.executeQuery())
                    {
                        while(resultSet.next())
                        {
                            plan.add(resultSet.getString("detail"));
                        }
                    }
                }

                assertTrue(plan.stream().anyMatch(
                        detail -> detail.contains("idx_p25_site_activity_bucket_time")),
                    () -> "Expected time-indexed trunked bucket scan, plan was: " + plan);
                assertTrue(plan.stream().anyMatch(
                        detail -> detail.contains("idx_conventional_bucket_dashboard_time")),
                    () -> "Expected indexed conventional bucket scan, plan was: " + plan);
                assertTrue(plan.stream().noneMatch(detail -> detail.contains("p25_activity_event")));
            }

            List<String> identityPlan = new ArrayList<>();
            try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " +
                StatsWebDatabase.DASHBOARD_IDENTITY_ACTIVITY_SQL))
            {
                statement.setLong(1, firstHour);
                statement.setLong(2, currentHour + 3_600_000L);
                statement.setInt(3, 1);
                statement.setInt(4, 20);
                try(ResultSet resultSet = statement.executeQuery())
                {
                    while(resultSet.next())
                    {
                        identityPlan.add(resultSet.getString("detail"));
                    }
                }
            }

            assertTrue(identityPlan.stream().anyMatch(
                    detail -> detail.contains("idx_call_identity_bucket_dashboard_time")),
                () -> "Expected indexed identity bucket scan, plan was: " + identityPlan);
            assertTrue(identityPlan.stream().noneMatch(detail -> detail.contains("p25_activity_event")));
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
                StatsWebDatabase.ACTIVITY_SELECT_SQL +
                    " AND activity.guid = ?" + StatsWebDatabase.ACTIVITY_ORDER_SQL))
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
    void retainedQualityLookupUsesTheGuidTimeIndex() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement query = connection.prepareStatement("""
                EXPLAIN QUERY PLAN
                SELECT frequency_hz, observed_at_ms
                FROM p25_control_channel_quality
                WHERE guid = ?
                ORDER BY observed_at_ms DESC
                LIMIT ?
                """))
        {
            query.setString(1, GUID);
            query.setInt(2, 100);
            List<String> plan = new ArrayList<>();

            try(ResultSet resultSet = query.executeQuery())
            {
                while(resultSet.next())
                {
                    plan.add(resultSet.getString("detail"));
                }
            }

            assertTrue(plan.stream().anyMatch(detail -> detail.contains("idx_p25_control_quality_guid_time")),
                () -> "Expected GUID/time-indexed quality lookup, plan was: " + plan);
        }
    }

    @Test
    void retainedQualityHistoryUsesTheGuidTimeIndex() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement query = connection.prepareStatement("""
                EXPLAIN QUERY PLAN
                SELECT guid, (observed_at_ms / ?) * ? AS time_ms,
                    avg(average_signal_dbfs), avg(decode_health_pct)
                FROM p25_control_channel_quality
                WHERE observed_at_ms >= ? AND observed_at_ms <= ? AND guid = ?
                GROUP BY guid, time_ms
                ORDER BY guid, time_ms
                """))
        {
            query.setLong(1, 60_000L);
            query.setLong(2, 60_000L);
            query.setLong(3, 0L);
            query.setLong(4, System.currentTimeMillis());
            query.setString(5, GUID);
            List<String> plan = new ArrayList<>();

            try(ResultSet resultSet = query.executeQuery())
            {
                while(resultSet.next())
                {
                    plan.add(resultSet.getString("detail"));
                }
            }

            assertTrue(plan.stream().anyMatch(detail -> detail.contains("idx_p25_control_quality_guid_time") &&
                    detail.contains("guid=?") && detail.contains("observed_at_ms>?")),
                () -> "Expected GUID/time-indexed quality history, plan was: " + plan);
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
    void dashboardRecentReceiversIncludeBothTopologiesAndAllSupportedProtocols() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot
                    (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count, channel_name, decoder)
                VALUES ('unidentified-site-guid', 'empty', 3000, 4000, 1, 'No Signal', 'P25-1')
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, kind_code, protocol_code, channel_name, decoder, nac,
                    first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES
                    (12, 'conventional-no-calls', 10, 0, 'Weather', 'NBFM', NULL,
                        3000, 7000, 162550000),
                    (13, 'trunked-call-before-metadata', 1, 3, 'DMR Call Context', 'DMR', NULL,
                        3000, 8000, 461025000),
                    (14, 'conventional-p25-no-calls', 2, 1, 'Sheriff P25', 'P25-1', 0x293,
                        3000, 9000, 154875000)
                """);
            TrunkedSiteSchema.upsert(connection, trunkedSnapshotAt(5000, "dashboard-dmr",
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, "Metro DMR", "DMR Dashboard",
                10, 20, 2, null, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshotAt(6000, "dashboard-nxdn",
                TrunkedSiteSchema.PROTOCOL_NXDN, 2, 4, "Regional NXDN", "NXDN Dashboard",
                7, 8, 9, 5, List.of(), List.of()));
        }

        Map<String,Object> dashboard = mDatabase.dashboard();
        List<Map<String,Object>> receivers = rowsFrom(dashboard, "recentReceivers");
        assertFalse(dashboard.containsKey("recentTrunkedSites"));
        Map<String,Object> p25 = receivers.stream().filter(row -> GUID.equals(row.get("guid")))
            .findFirst().orElseThrow();
        assertEquals(0x49F, number(p25.get("nac")));
        assertEquals(1, number(p25.get("rfss")));
        assertEquals(1, number(p25.get("site")));
        Map<String,Object> dmr = receivers.stream()
            .filter(row -> "dashboard-dmr".equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals("DMR", dmr.get("protocol"));
        assertEquals(2, number(dmr.get("site_id")));
        assertNull(dmr.get("nac"));
        assertTrue(receivers.stream().anyMatch(row -> "dashboard-nxdn".equals(row.get("guid")) &&
            "NXDN".equals(row.get("protocol"))));
        assertTrue(receivers.stream().anyMatch(row -> "conventional-fire".equals(row.get("context_key")) &&
            "CONVENTIONAL".equals(row.get("channel_kind")) && "NBFM".equals(row.get("protocol"))));
        assertTrue(receivers.stream().anyMatch(row -> "conventional-no-calls".equals(row.get("context_key"))));
        Map<String,Object> conventionalP25 = receivers.stream()
            .filter(row -> "conventional-p25-no-calls".equals(row.get("context_key")))
            .findFirst().orElseThrow();
        assertEquals(0x293, number(conventionalP25.get("nac")));
        assertNull(conventionalP25.get("rfss"));
        assertNull(conventionalP25.get("site"));
        Map<String,Object> orphanTrunked = receivers.stream()
            .filter(row -> "trunked-call-before-metadata".equals(row.get("context_key")))
            .findFirst().orElseThrow();
        assertEquals("TRUNKED", orphanTrunked.get("channel_kind"));
        assertEquals("DMR", orphanTrunked.get("protocol"));
        assertEquals(0, number(orphanTrunked.get("detail_available")));
        assertEquals(3, number(map(dashboard, "counts").get("conventional_channels")));
        assertTrue(receivers.stream().filter(row -> "TRUNKED".equals(row.get("channel_kind")) &&
                !"trunked-call-before-metadata".equals(row.get("context_key")))
            .allMatch(row -> number(row.get("detail_available")) == 1));
        assertTrue(receivers.stream().anyMatch(row -> "unidentified-site-guid".equals(row.get("guid")) &&
            "P25".equals(row.get("protocol")) && "TRUNKED".equals(row.get("channel_kind"))));
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
        assertEquals(0x49FL, number(site.get("nac")));
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
    void exposesRetainedQualityForP25DmrAndNxdnThroughOneContract() throws Exception
    {
        long now = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("quality-dmr", TrunkedSiteSchema.PROTOCOL_DMR,
                1, 2, "Metro DMR", "DMR Quality", 10, 20, 3, null, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("quality-nxdn", TrunkedSiteSchema.PROTOCOL_NXDN,
                2, 4, "Regional NXDN", "NXDN Quality", 7, 8, 9, 5, List.of(), List.of()));
            insertQuality(connection, "quality-dmr", 451_012_500L, now - 2_000L, -52.0, 94.0);
            insertQuality(connection, "quality-nxdn", 155_012_500L, now - 1_000L, -61.0, 88.0);
        }

        Map<String,Object> dmrResponse = mDatabase.qualityHistory(request(
            "/api/quality?guid=quality-dmr&range=1h&points=60"));
        Map<String,Object> dmr = rowsFrom(dmrResponse, "sites").getFirst();
        assertEquals("DMR", dmr.get("protocol"));
        assertEquals(TrunkedSiteSchema.PROTOCOL_DMR, number(dmr.get("protocol_code")));
        assertEquals("Metro DMR", dmr.get("configured_system"));
        assertEquals(3, number(dmr.get("site_id")));
        assertEquals(451_012_500L, number(dmr.get("quality_frequency_hz")));
        assertEquals(94.0, ((Number)dmr.get("decode_health_pct")).doubleValue());
        assertEquals(1, rowsFrom(dmr, "series").size());

        Map<String,Object> nxdnResponse = mDatabase.qualityHistory(request(
            "/api/quality?guid=quality-nxdn&range=1h&points=60"));
        Map<String,Object> nxdn = rowsFrom(nxdnResponse, "sites").getFirst();
        assertEquals("NXDN", nxdn.get("protocol"));
        assertEquals(TrunkedSiteSchema.PROTOCOL_NXDN, number(nxdn.get("protocol_code")));
        assertEquals(5, number(nxdn.get("ran")));
        assertEquals(155_012_500L, number(nxdn.get("quality_frequency_hz")));
        assertEquals(88.0, ((Number)nxdn.get("decode_health_pct")).doubleValue());
        assertEquals(1, rowsFrom(nxdn, "series").size());

        List<Map<String,Object>> dmrRows = rows(mDatabase.siteQuality(request(
            "/api/site/quality?guid=quality-dmr&limit=1")));
        assertEquals(1, dmrRows.size());
        assertEquals(451_012_500L, number(dmrRows.getFirst().get("frequency_hz")));

        List<Map<String,Object>> allSites = rowsFrom(mDatabase.qualityHistory(request(
            "/api/quality?include_history=false")), "sites");
        assertTrue(allSites.stream().anyMatch(row -> "P25".equals(row.get("protocol"))));
        assertTrue(allSites.stream().anyMatch(row -> "DMR".equals(row.get("protocol"))));
        assertTrue(allSites.stream().anyMatch(row -> "NXDN".equals(row.get("protocol"))));
    }

    @Test
    void resolvesRetainedProtocolTransitionsByLatestObservationWithP25TieBreak() throws Exception
    {
        long trunkedLastSeen = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshotAt(trunkedLastSeen, GUID,
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, "Transitioned DMR", "DMR Receiver", 10, 20, 2, null,
                List.of(new TrunkedSiteSchema.Channel(42, null, 1, 451_000_000L, 456_000_000L, 1)),
                List.of(new TrunkedSiteSchema.Neighbor(1, 2, 10, 20, 3, 43, 452_000_000L, 1))));

            try(PreparedStatement statement = connection.prepareStatement(
                "UPDATE receiver_context SET last_seen_ms = ? WHERE guid = ?"))
            {
                statement.setLong(1, trunkedLastSeen + 1_000L);
                statement.setString(2, GUID);
                statement.executeUpdate();
            }
        }

        Map<String,Object> latest = map(mDatabase.site(request("/api/site?guid=" + GUID)), "site");
        assertEquals("DMR", latest.get("protocol"));
        assertEquals(TrunkedSiteSchema.PROTOCOL_DMR, number(latest.get("protocol_code")));
        assertEquals(42, number(rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=" + GUID))).getFirst().get("channel_number")));
        assertEquals(3, number(rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=" + GUID))).getFirst().get("site_id")));

        List<Map<String,Object>> qualitySites = rowsFrom(mDatabase.qualityHistory(request(
            "/api/quality?guid=" + GUID + "&include_history=false")), "sites");
        assertEquals(1, qualitySites.size());
        assertEquals("DMR", qualitySites.getFirst().get("protocol"));
        assertNull(qualitySites.getFirst().get("nac"));

        List<Map<String,Object>> directory = rows(mDatabase.systemDirectory(request(
            "/api/system-directory")));
        List<Map<String,Object>> directoryChildren = directory.stream()
            .flatMap(parent -> rowsFrom(parent, "children").stream())
            .filter(child -> GUID.equals(child.get("guid"))).toList();
        assertEquals(1, directoryChildren.size());
        assertEquals("DMR", directoryChildren.getFirst().get("protocol"));
        Map<String,Object> retainedP25Parent = directory.stream()
            .filter(parent -> "P25".equals(parent.get("protocol"))).findFirst().orElseThrow();
        assertEquals(0, number(retainedP25Parent.get("sites")));
        assertNull(retainedP25Parent.get("site_names"));
        Map<String,Object> recentReceiver = rowsFrom(mDatabase.dashboard(), "recentReceivers").stream()
            .filter(receiver -> GUID.equals(receiver.get("guid"))).findFirst().orElseThrow();
        assertEquals("DMR", recentReceiver.get("protocol"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement statement = connection.prepareStatement(
                "UPDATE p25_site_snapshot SET last_seen_ms = ? WHERE guid = ?"))
        {
            statement.setLong(1, trunkedLastSeen);
            statement.setString(2, GUID);
            statement.executeUpdate();
        }

        Map<String,Object> tied = map(mDatabase.site(request("/api/site?guid=" + GUID)), "site");
        assertEquals(1, number(tied.get("protocol_code")));
        assertEquals("p25", tied.get("site_kind"));
        assertNotNull(rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=" + GUID))).getFirst().get("descriptor"));
        assertFalse(rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=" + GUID))).getFirst().containsKey("site_id"));

        qualitySites = rowsFrom(mDatabase.qualityHistory(request(
            "/api/quality?guid=" + GUID + "&include_history=false")), "sites");
        assertEquals(1, qualitySites.size());
        assertEquals("P25", qualitySites.getFirst().get("protocol"));
        assertEquals(0x49FL, number(qualitySites.getFirst().get("nac")));

        directory = rows(mDatabase.systemDirectory(request("/api/system-directory")));
        directoryChildren = directory.stream().flatMap(parent -> rowsFrom(parent, "children").stream())
            .filter(child -> GUID.equals(child.get("guid"))).toList();
        assertEquals(1, directoryChildren.size());
        assertEquals("P25", directoryChildren.getFirst().get("protocol"));
        retainedP25Parent = directory.stream()
            .filter(parent -> "P25".equals(parent.get("protocol"))).findFirst().orElseThrow();
        assertEquals(1, number(retainedP25Parent.get("sites")));
        assertEquals("Cleveland Simulcast", retainedP25Parent.get("site_names"));
        recentReceiver = rowsFrom(mDatabase.dashboard(), "recentReceivers").stream()
            .filter(receiver -> GUID.equals(receiver.get("guid"))).findFirst().orElseThrow();
        assertEquals("P25", recentReceiver.get("protocol"));
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

    @Test
    void usesConsensusConfiguredChannelSystemForP25Directory() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz)
                VALUES ('consensus-site-guid', 'consensus-hash', 1000, 2500, 1, 'APCO25', 'Consensus Child',
                    'County', 'P25-1', 1, 0x49F, 1, 2, 857137500, 857137500)
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel (sort_order, system_name, radres_guid, config_json)
                VALUES (1, ' Greater Cleveland ', 'test-site-guid', '{}'),
                       (2, 'greater cleveland', 'consensus-site-guid', '{}')
                """);
        }

        Map<String,Object> parent = rows(mDatabase.systemDirectory(request("/api/system-directory"))).stream()
            .filter(row -> number(row.get("system_id")) == SYSTEM).findFirst().orElseThrow();
        assertEquals("Greater Cleveland", parent.get("configured_system"));
        assertEquals(2, number(parent.get("sites")));
        assertEquals(2, rowsFrom(parent, "children").size());
        assertEquals(1, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=greater%20cleveland"))).size());
        assertEquals(1, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=BEE00"))).size());
        assertEquals(1, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=consensus-site-guid"))).size());
        assertEquals(1, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=Consensus%20Child"))).size());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE configuration_channel SET system_name = 'Other System'
                WHERE radres_guid = 'consensus-site-guid'
                """);
        }

        parent = rows(mDatabase.systemDirectory(request("/api/system-directory"))).stream()
            .filter(row -> number(row.get("system_id")) == SYSTEM).findFirst().orElseThrow();
        assertNull(parent.get("configured_system"));
        assertEquals(1, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=Other%20System"))).size());
    }

    @Test
    void exposesBoundedDmrAndNxdnSystemDirectoryAndSiteDetails() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-a", TrunkedSiteSchema.PROTOCOL_DMR,
                1, 2, "Metro DMR", "DMR Downtown", 10, 20, 1, null,
                List.of(
                    new TrunkedSiteSchema.Channel(42, null, 1, 451_000_000L, 456_000_000L,
                        TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC |
                            TrunkedSiteSchema.CHANNEL_ROLE_OBSERVED |
                            TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_FROM_CONFIGURED_MAP |
                            TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_ANNOUNCED_OVER_THE_AIR),
                    new TrunkedSiteSchema.Channel(43, null, 2, 452_000_000L, 457_000_000L, 2)),
                List.of(new TrunkedSiteSchema.Neighbor(1, 2, 10, 20, 2, 44, 453_000_000L, 1))));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-b", TrunkedSiteSchema.PROTOCOL_DMR,
                1, 2, "Metro DMR", "DMR Airport", 10, 20, 2, null,
                List.of(new TrunkedSiteSchema.Channel(52, null, 1, 461_000_000L, 466_000_000L, 1)),
                List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("nxdn-a", TrunkedSiteSchema.PROTOCOL_NXDN,
                2, 4, "Regional NXDN", "NXDN North", 7, 8, 9, 5,
                List.of(new TrunkedSiteSchema.Channel(120, 121, null, 155_000_000L, 160_000_000L, 1)),
                List.of(new TrunkedSiteSchema.Neighbor(2, 4, 7, 8, 10, 122, 155_012_500L, 2))));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("nxdn-b", TrunkedSiteSchema.PROTOCOL_NXDN,
                2, 4, "Regional NXDN", "NXDN South", 7, 8, 10, 5,
                List.of(new TrunkedSiteSchema.Channel(130, 131, null, 155_025_000L, 160_025_000L, 1)),
                List.of()));
        }

        Map<String,Object> directory = mDatabase.systemDirectory(request("/api/system-directory"));
        List<Map<String,Object>> systems = rows(directory);
        assertEquals(5, systems.size());
        List<Map<String,Object>> dmr = systems.stream().filter(row -> "DMR".equals(row.get("protocol"))).toList();
        assertEquals(2, dmr.size());
        assertEquals(List.of("trunked:3:guid:dmr-a", "trunked:3:guid:dmr-b"),
            dmr.stream().map(row -> String.valueOf(row.get("system_group_key"))).toList());
        assertTrue(dmr.stream().allMatch(row -> number(row.get("sites")) == 1));
        assertTrue(dmr.stream().allMatch(row -> "Metro DMR".equals(row.get("configured_system"))));
        assertTrue(dmr.stream().allMatch(row -> rowsFrom(row, "children").size() == 1));
        assertEquals("dmr-a", rowsFrom(dmr.getFirst(), "children").getFirst().get("guid"));
        assertEquals("dmr-b", rowsFrom(dmr.getLast(), "children").getFirst().get("guid"));
        assertTrue(dmr.stream().flatMap(row -> rowsFrom(row, "children").stream())
            .allMatch(row -> "trunked".equals(row.get("site_kind"))));
        assertEquals("dmr-b", rowsFrom(rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=DMR%20Airport"))).getFirst(), "children").getFirst().get("guid"));
        assertEquals(2, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=20"))).size());

        List<Map<String,Object>> nxdn = systems.stream().filter(row -> "NXDN".equals(row.get("protocol"))).toList();
        assertEquals(2, nxdn.size());
        assertEquals(List.of("trunked:4:guid:nxdn-a", "trunked:4:guid:nxdn-b"),
            nxdn.stream().map(row -> String.valueOf(row.get("system_group_key"))).toList());
        assertTrue(nxdn.stream().allMatch(row -> number(row.get("sites")) == 1));
        assertTrue(nxdn.stream().allMatch(row -> rowsFrom(row, "children").size() == 1));

        List<Map<String,Object>> nxdnSearch = rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=NXDN")));
        assertEquals(2, nxdnSearch.size());
        assertEquals("NXDN", nxdnSearch.getFirst().get("protocol"));
        assertEquals("nxdn-a", rowsFrom(nxdnSearch.getFirst(), "children").getFirst().get("guid"));
        assertEquals(1, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=NXDN%20North"))).size());
        assertEquals("nxdn-a", rowsFrom(rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=9"))).getFirst(), "children").getFirst().get("guid"));

        Map<String,Object> site = map(mDatabase.site(request("/api/site?guid=dmr-a")), "site");
        assertEquals("DMR", site.get("protocol"));
        assertEquals("trunked", site.get("site_kind"));
        assertEquals("trunked", site.get("site_type"));
        assertEquals(2, number(site.get("identity_domain_code")));
        assertEquals(20, number(site.get("system_id")));
        assertEquals(2, number(site.get("channels")));
        assertEquals(1, number(site.get("neighbors")));
        Map<String,Object> dmrCapabilities = map(site, "capabilities");
        assertEquals(Boolean.TRUE, dmrCapabilities.get("quality"));
        assertEquals(Boolean.TRUE, dmrCapabilities.get("quality_history"));
        assertEquals(Boolean.FALSE, dmrCapabilities.get("band_plan"));
        assertEquals(Boolean.FALSE, dmrCapabilities.get("patches"));
        assertEquals(Boolean.TRUE, dmrCapabilities.get("activity"));

        Map<String,Object> nxdnSite = map(mDatabase.site(request("/api/site?guid=nxdn-a")), "site");
        assertEquals("NXDN", nxdnSite.get("protocol"));
        assertEquals(4, number(nxdnSite.get("identity_domain_code")));
        assertEquals(5, number(nxdnSite.get("ran")));
        assertEquals(Boolean.TRUE, map(nxdnSite, "capabilities").get("quality"));
        assertEquals(Boolean.TRUE, map(nxdnSite, "capabilities").get("activity"));

        List<Map<String,Object>> channels = rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=dmr-a&limit=1")));
        assertEquals(1, channels.size());
        assertEquals(42, number(channels.getFirst().get("channel_number")));
        assertEquals(451_000_000L, number(channels.getFirst().get("frequency_hz")));
        assertEquals(TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC |
            TrunkedSiteSchema.CHANNEL_ROLE_OBSERVED |
            TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_FROM_CONFIGURED_MAP |
            TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_ANNOUNCED_OVER_THE_AIR,
            number(channels.getFirst().get("role_flags")));

        List<Map<String,Object>> neighbors = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=nxdn-a&limit=1")));
        assertEquals(1, neighbors.size());
        assertEquals(10, number(neighbors.getFirst().get("site_id")));
        assertEquals(4, number(neighbors.getFirst().get("identity_domain_code")));
        assertEquals(155_012_500L, number(neighbors.getFirst().get("frequency_hz")));

        List<Map<String,Object>> dmrNeighbors = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=dmr-a&limit=1")));
        assertEquals(1, dmrNeighbors.size());
        assertEquals(2, number(dmrNeighbors.getFirst().get("site_id")));

        List<Map<String,Object>> nxdnChannels = rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=nxdn-a&limit=1")));
        assertEquals(1, nxdnChannels.size());
        assertEquals(120, number(nxdnChannels.getFirst().get("channel_number")));

        Map<String,Object> counts = map(mDatabase.dashboard(), "counts");
        assertEquals(5, number(counts.get("trunked_systems")));
        assertEquals(5, number(counts.get("trunked_sites")));
        assertFalse(counts.containsKey("talkgroups"));
        assertFalse(counts.containsKey("radios"));
        assertFalse(counts.containsKey("frequencies"));
    }

    @Test
    void keepsDmrModelsAndNxdnLocationCategoriesInSeparateSystemGroups() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-tiny", TrunkedSiteSchema.PROTOCOL_DMR,
                1, 1, "Shared DMR", "Tiny Site", 10, null, 1, null, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-small", TrunkedSiteSchema.PROTOCOL_DMR,
                1, 2, "Shared DMR", "Small Site", 10, null, 2, null, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("nxdn-global", TrunkedSiteSchema.PROTOCOL_NXDN,
                1, 1, "Shared NXDN", "Global Site", null, 8, 1, 5, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("nxdn-local", TrunkedSiteSchema.PROTOCOL_NXDN,
                1, 3, "Shared NXDN", "Local Site", null, 8, 2, 5, List.of(), List.of()));
        }

        List<Map<String,Object>> systems = rows(mDatabase.systemDirectory(request("/api/system-directory")));
        List<Map<String,Object>> dmr = systems.stream().filter(row -> "DMR".equals(row.get("protocol"))).toList();
        List<Map<String,Object>> nxdn = systems.stream().filter(row -> "NXDN".equals(row.get("protocol"))).toList();
        assertEquals(2, dmr.size());
        assertEquals(2, nxdn.size());
        assertEquals(List.of(1L, 2L), dmr.stream().map(row -> number(row.get("identity_domain_code"))).sorted()
            .toList());
        assertEquals(List.of(1L, 3L), nxdn.stream().map(row -> number(row.get("identity_domain_code"))).sorted()
            .toList());
    }

    @Test
    void keepsDmrVariantsWithOverlappingUnqualifiedIdsInSeparateSystemGroups() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-tier3", TrunkedSiteSchema.PROTOCOL_DMR,
                1, 0, "Tier III", "Tier III Site", 10, 20, 1, null, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-connect-plus",
                TrunkedSiteSchema.PROTOCOL_DMR, 2, 0, "Connect Plus", "Connect Plus Site", 10, 20, 2, null,
                List.of(), List.of()));
        }

        List<Map<String,Object>> dmr = rows(mDatabase.systemDirectory(request("/api/system-directory"))).stream()
            .filter(row -> "DMR".equals(row.get("protocol")))
            .toList();
        assertEquals(2, dmr.size());
        assertEquals(List.of(1L, 2L), dmr.stream().map(row -> number(row.get("variant_code"))).sorted().toList());
        assertTrue(dmr.stream().allMatch(row -> rowsFrom(row, "children").size() == 1));
        assertEquals(3, number(map(mDatabase.dashboard(), "counts").get("trunked_systems")));
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

    private static TrunkedSiteSchema.Snapshot trunkedSnapshot(String guid, int protocol, int variant, int domain,
                                                               String configuredSystem, String channelName,
                                                               Integer network, Integer system, Integer site,
                                                               Integer ran, List<TrunkedSiteSchema.Channel> channels,
                                                               List<TrunkedSiteSchema.Neighbor> neighbors)
    {
        return trunkedSnapshotAt(System.currentTimeMillis(), guid, protocol, variant, domain, configuredSystem,
            channelName, network, system, site, ran, channels, neighbors);
    }

    private static TrunkedSiteSchema.Snapshot trunkedSnapshotAt(long observedAt, String guid, int protocol,
                                                                 int variant, int domain, String configuredSystem,
                                                                 String channelName, Integer network, Integer system,
                                                                 Integer site, Integer ran,
                                                                 List<TrunkedSiteSchema.Channel> channels,
                                                                 List<TrunkedSiteSchema.Neighbor> neighbors)
    {
        return new TrunkedSiteSchema.Snapshot(observedAt, guid, "hash-" + guid, protocol, variant,
            domain, configuredSystem, channelName, "County", protocol == TrunkedSiteSchema.PROTOCOL_DMR ?
            "DMR" : "NXDN", network, system, site, ran, null, null, null, null, 1, 1, null, 0, null,
            channels.isEmpty() ? null : channels.getFirst().frequencyHertz(),
            channels.isEmpty() ? null : channels.getFirst().frequencyHertz(), channels, neighbors);
    }

    private static void insertQuality(Connection connection, String guid, long frequency, long observedAt,
                                      double signal, double decode) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_control_channel_quality (
                guid, frequency_hz, bucket_start_ms, observed_at_ms, signal_dbfs, average_signal_dbfs,
                minimum_signal_dbfs, maximum_signal_dbfs, decode_health_pct, valid_frames, invalid_frames,
                corrected_bits, sync_loss_bits, dropped_bits, last_valid_decode_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 100, 1, 2, 0, 0, ?)
            """))
        {
            statement.setString(1, guid);
            statement.setLong(2, frequency);
            statement.setLong(3, observedAt - Math.floorMod(observedAt, 10_000L));
            statement.setLong(4, observedAt);
            statement.setDouble(5, signal);
            statement.setDouble(6, signal);
            statement.setDouble(7, signal - 2.0);
            statement.setDouble(8, signal + 2.0);
            statement.setDouble(9, decode);
            statement.setLong(10, observedAt);
            statement.executeUpdate();
        }
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
                INSERT INTO alias_list
                    (id, name, family)
                VALUES (1, 'County', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, color, matcher_type, protocol, value
                )
                VALUES (1, 1, 'Dispatch', 'Law Dispatch', 255,
                            'TALKGROUP', 'APCO25', 56132),
                       (2, 1, 'Engine 1', 'Fire', 65280,
                            'RADIO_ID', 'APCO25', 1811332)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, first_seen_ms, last_seen_ms, primary_frequency_hz)
                VALUES (2, 'conventional-fire', NULL, 10, 0, 'County Fire', 'County', 'NBFM', 1000, 2000,
                    154310000)
                """);
            statement.executeUpdate("""
                INSERT INTO conventional_activity_summary (context_id, frequency_hz, timeslot, first_seen_ms,
                    last_seen_ms, call_count) VALUES (2, 154310000, -1, 1000, 2000, 4)
                """);
        }
    }

    private static void seedDmrConventionalRows(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, first_seen_ms, last_seen_ms, primary_frequency_hz)
                VALUES (5, 'conventional-dmr-county', 'dmr-county-guid', 3, 3, 'County DMR',
                        'County DMR', 'DMR', 1000, 5000, 451012500),
                       (6, 'conventional-dmr-other', 'dmr-other-guid', 3, 3, 'Other DMR',
                        'Other DMR', 'DMR', 1000, 6000, 461012500)
                """);
            statement.executeUpdate("""
                INSERT INTO conventional_activity_summary (context_id, frequency_hz, timeslot, first_seen_ms,
                    last_seen_ms, call_count)
                VALUES (5, 451012500, 1, 1000, 5000, 10),
                       (5, 451012500, 2, 2000, 5000, 20),
                       (6, 461012500, 1, 1000, 6000, 999)
                """);
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_talkgroup_summary (
                    context_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms,
                    call_count, encrypted_count, last_source_radio_id
                ) VALUES (5, 451012500, 1, 91, 1000, 5000, 10, 2, 123456),
                         (5, 451012500, 2, 92, 2000, 5000, 20, 0, 234567),
                         (6, 461012500, 1, 91, 1000, 6000, 999, 0, 123456)
                """);
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_radio_summary (
                    context_id, frequency_hz, timeslot, radio_id, first_seen_ms, last_seen_ms, call_count,
                    source_call_count, target_call_count, group_call_count, private_call_count,
                    encrypted_count, last_talkgroup_id, last_peer_radio_id
                ) VALUES (5, 451012500, 1, 123456, 1000, 5000, 10, 7, 3, 8, 2, 1, 91, 234567),
                         (5, 451012500, 2, 234567, 2000, 5000, 20, 15, 5, 18, 2, 0, 92, 123456),
                         (6, 461012500, 1, 123456, 1000, 6000, 999, 999, 0, 999, 0, 0, 91, 234567)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_list
                    (id, name, family)
                VALUES (100, 'County DMR', 'DMR'),
                       (101, 'Other DMR', 'DMR')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, color, matcher_type, protocol, value
                )
                VALUES (100, 100, 'DMR Dispatch', 'Fire Dispatch', 255,
                            'TALKGROUP', 'DMR', 91),
                       (101, 100, 'DMR Operations', 'Fire Operations', 255,
                            'TALKGROUP', 'DMR', 92),
                       (102, 100, 'DMR Engine 1', 'Fire Units', 65280,
                            'RADIO_ID', 'DMR', 123456),
                       (103, 100, 'DMR Engine 2', 'Fire Units', 65280,
                            'RADIO_ID', 'DMR', 234567),
                       (104, 101, 'Other Dispatch', 'Other Dispatch', 255,
                            'TALKGROUP', 'DMR', 91),
                       (105, 101, 'Other Engine', 'Other Units', 65280,
                            'RADIO_ID', 'DMR', 123456)
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
                INSERT INTO alias_list
                    (id, name, family)
                VALUES (2, 'Second', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, color, matcher_type, protocol, value
                )
                VALUES (3, 2, 'Second Dispatch', 'Second Law', 255,
                            'TALKGROUP', 'APCO25', 56132),
                       (4, 2, 'Second Engine', 'Second Fire', 65280,
                            'RADIO_ID', 'APCO25', 1811332)
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
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, color, matcher_type, protocol, value
                )
                VALUES (5, 1, 'Zulu Dispatch', 'Zulu Law', 255,
                            'TALKGROUP', 'APCO25', 100),
                       (6, 1, 'Zulu Unit', 'Zulu Fire', 65280,
                            'RADIO_ID', 'APCO25', 100)
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
