/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivityFilterCatalog;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.ProgressHandler;

/**
 * Focused read-model coverage for the current radio-system and saved-channel API. Historical schema shapes and
 * compatibility routes belong to migration tests, not to this current-schema contract.
 */
class StatsWebDatabaseTest
{
    private static final String RADIO_SYSTEM_KEY = "p25:bee00:49f";
    private static final String P25_CHANNEL_A = "00000000-0000-0000-0000-000000000071";
    private static final String P25_CHANNEL_B = "00000000-0000-0000-0000-000000000072";
    private static final String ANALOG_CHANNEL = "00000000-0000-0000-0000-000000000073";
    private static final String DMR_CHANNEL = "00000000-0000-0000-0000-000000000074";
    private static final String CONVENTIONAL_P25_CHANNEL = "00000000-0000-0000-0000-000000000075";
    private static final String CONVENTIONAL_NXDN_CHANNEL = "00000000-0000-0000-0000-000000000076";
    private static final String SECOND_P25_CHANNEL = "00000000-0000-0000-0000-000000000077";
    private static final String DMR_TRUNKED_CHANNEL = "00000000-0000-0000-0000-000000000078";
    private static final String NXDN_TRUNKED_CHANNEL = "00000000-0000-0000-0000-000000000079";
    private static final String QUIET_CHANNEL = "00000000-0000-0000-0000-000000000080";
    private static final String DMR_NATIVE_CHANNEL_A = "00000000-0000-0000-0000-000000000081";
    private static final String DMR_NATIVE_CHANNEL_B = "00000000-0000-0000-0000-000000000082";
    private static final String NXDN_NATIVE_CHANNEL_A = "00000000-0000-0000-0000-000000000083";
    private static final String NXDN_NATIVE_CHANNEL_B = "00000000-0000-0000-0000-000000000084";

    @TempDir
    Path mTemporaryFolder;

    private Path mDatabasePath;
    private StatsWebDatabase mDatabase;

    @BeforeEach
    void setUp() throws Exception
    {
        mDatabasePath = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        createCurrentWebTestDatabase();
        seedCurrentRadioModel();
        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
    }

    /** Read-model fixtures use the same separate configuration/history files admitted by production startup. */
    private void createCurrentWebTestDatabase() throws Exception
    {
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.createGlobalDatabase(mDatabasePath);
    }

    /** Fixture writes may address both isolated stores; production writers always attach configuration read-only. */
    private Connection openFixture() throws Exception
    {
        Connection connection=DriverManager.getConnection("jdbc:sqlite:" +
            io.github.dsheirer.database.SdrTrunkDatabase.historyPath(mDatabasePath));
        try(var attach=connection.prepareStatement("ATTACH DATABASE ? AS configuration"))
        { attach.setString(1,mDatabasePath.toString());attach.execute(); }
        StatsHistoryContext.installSubscriberContext(connection);
        ReceiverActivitySchema.installConfigurationReadView(connection);
        // Fixture summaries describe the Alias currently inserted by this test, rather than a replaced matcher.
        try(Statement statement=connection.createStatement())
        {
            statement.execute("""
                CREATE TEMP TRIGGER fixture_summary_identity AFTER INSERT ON main.alias_activity_summary
                WHEN EXISTS(SELECT 1 FROM alias WHERE id=new.alias_id)
                BEGIN
                    UPDATE alias_activity_summary
                    SET activity_identity=(SELECT activity_identity FROM alias WHERE id=new.alias_id)
                    WHERE alias_id=new.alias_id;
                END
                """);
        }
        return connection;
    }

    /** Canonical Alias fixtures explicitly seed their independent configuration dictionary with the same tuples. */
    private static void seedConfiguredSubscribers(Statement statement) throws Exception
    {
        statement.executeUpdate("INSERT OR IGNORE INTO configuration.p25_subscriber_identity " +
            "SELECT * FROM main.p25_subscriber_identity");
    }

    @Test
    void messageDefinitionsComeFromOneTableAndKeepActionAndEventTypesSeparate() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE activity_message_type SET label='Disable command', " +
                "meaning='A command asking the addressed radio to stop operating.', dmr=0 " +
                "WHERE definition_kind='ACTION' AND name='STUN'");
            statement.executeUpdate("UPDATE activity_message_type SET label='Stun radio message' " +
                "WHERE definition_kind='EVENT' AND name='STUN'");
        }
        Map<String,Object> action = mDatabase.activityMessageTypes().stream()
            .filter(row -> "ACTION".equals(row.get("definition_kind")) && "STUN".equals(row.get("name")))
            .findFirst().orElseThrow();
        Map<String,Object> event = mDatabase.activityMessageTypes().stream()
            .filter(row -> "EVENT".equals(row.get("definition_kind")) && "STUN".equals(row.get("name")))
            .findFirst().orElseThrow();
        assertEquals("Disable command", action.get("label"));
        assertEquals("Stun radio message", event.get("label"));
        assertEquals(false, action.get("dmr"));
        assertEquals(ReceiverActivityFilterCatalog.actionCode("STUN").longValue(), number(action.get("code")));
        assertEquals(ReceiverActivityFilterCatalog.eventTypeCode("STUN").longValue(), number(event.get("code")));
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE activity_message_type SET label='Radio disable' " +
                "WHERE definition_kind='ACTION' AND name='STUN'");
        }
        assertEquals("Radio disable", mDatabase.activityMessageTypes().stream()
            .filter(row -> "ACTION".equals(row.get("definition_kind")) && "STUN".equals(row.get("name")))
            .findFirst().orElseThrow().get("label"));
    }

    @Test
    void namedCommandsUseTheSameTypeForSummaryCountsAndActivityFilters() throws Exception
    {
        long now = System.currentTimeMillis();
        long bucket = now / 3_600_000L * 3_600_000L;
        int stun = ReceiverActivityFilterCatalog.actionCode("STUN");
        int event = ReceiverActivityFilterCatalog.eventTypeCode("STUN");
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket
                    (channel_id,frequency_hz,timeslot,bucket_start_ms,stun_count,revive_count)
                VALUES (74,460012500,2,%d,2,1)
                """.formatted(bucket));
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event
                    (channel_id,observed_at_ms,action_code,event_type_code,source_observed_local_id)
                VALUES (74,%d,%d,%d,303)
                """.formatted(now, stun, event));
        }
        Map<String,Object> totals = mDatabase.dashboardActivityActions(request("/?range=24h"));
        assertEquals(2L, number(rowWith(rowsFrom(totals,"rows"),"action","STUN").get("observation_count")));
        assertEquals(1L, number(rowWith(rowsFrom(totals,"rows"),"action","REVIVE").get("observation_count")));
        Map<String,Object> radios = mDatabase.dashboardActivityRadios(request("/?range=24h&action=STUN"));
        assertEquals(2L, number(radios.get("action_observation_count")));
        assertEquals(1L, number(radios.get("identified_observation_count")));
        assertEquals(303L, number(rowsFrom(radios,"rows").getFirst().get("native_id")));
        Map<String,Object> activity = mDatabase.activity(request("/?configuration_id=" + DMR_CHANNEL +
            "&action=STUN&event_type=STUN&limit=10"));
        assertEquals(1, rowsFrom(activity,"rows").size());
        assertEquals("STUN", rowsFrom(activity,"rows").getFirst().get("action"));
        assertEquals("STUN", rowsFrom(activity,"rows").getFirst().get("event_type"));
        assertTrue(rowsFrom(mDatabase.activity(request("/?configuration_id=" + DMR_CHANNEL +
            "&action=REVIVE&limit=10")),"rows").isEmpty());
    }

    @Test
    void homeSubscriberEnrichmentKeepsOneOwnerAcrossDirectoriesRoutesActivityAndChannelAliases() throws Exception
    {
        seedActivityFilterRows();
        Map<String,Object> provisional = map(mDatabase.radio(RADIO_SYSTEM_KEY,p25LocalRadioKey(202)),"radio");
        String receivingKey = p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO,202);
        assertEquals(receivingKey,provisional.get("identity_key"));
        assertNull(provisional.get("p25_subscriber_identity_id"));
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
                "VALUES(9901,0xBEE00,0x49F,202)");
            statement.executeUpdate("UPDATE radio_system_identity_summary SET p25_subscriber_identity_id=9901," +
                "last_talker_alias='Home OTA',last_talker_alias_seen_ms=13000 WHERE id=7102");
            statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(9901,71,'Home Unit','RADIO_ID','APCO25',202)");
            statement.executeUpdate("INSERT INTO trunked_radio_group_summary(radio_system_id,radio_identity_id," +
                "group_identity_id,group_kind_code,first_seen_ms,last_seen_ms,logical_call_count) " +
                "VALUES(71,7102,7101,1,1000,13000,3)");
            statement.executeUpdate("INSERT INTO p25_learned_site(learned_site_id,radio_system_id,rfss,site," +
                "first_seen_ms,last_seen_ms) VALUES(9901,71,1,2,1000,13000)");
            statement.executeUpdate("INSERT INTO p25_site_call_identity_bucket(radio_system_id,channel_id," +
                "learned_site_id,bucket_start_ms,identity_role_code,identity_kind_code,identity_summary_id," +
                "observed_local_id,last_observed_at_ms,observed_call_count) " +
                "VALUES(71,71,9901,0,2,2,7102,202,13000,3)");
        }
        String local = p25LocalRadioKey(202);
        String full = p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 202);
        Map<String,Object> localDetail = map(mDatabase.radio(RADIO_SYSTEM_KEY, local), "radio");
        Map<String,Object> fullDetail = map(mDatabase.radio(RADIO_SYSTEM_KEY, full), "radio");
        assertEquals(receivingKey,localDetail.get("identity_key"));
        assertEquals(provisional.get("identity_summary_id"),localDetail.get("identity_summary_id"));
        assertEquals(7102, number(localDetail.get("identity_summary_id")));
        assertEquals(localDetail.get("identity_summary_id"), fullDetail.get("identity_summary_id"));
        assertEquals("Home OTA", localDetail.get("last_talker_alias"));
        assertEquals("Home Unit", localDetail.get("alias_name"));
        Map<String,Object> systemRadio = rows(mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?q=home%20unit&limit=1"))).getFirst();
        assertEquals(7102, number(systemRadio.get("identity_summary_id")));
        Map<String,Object> relation = rows(mDatabase.radioSystemRelationships(RADIO_SYSTEM_KEY,
            request("/?group_identity_key=" + p25IdentityKey(1,101)))).getFirst();
        assertEquals(7102, number(relation.get("radio_identity_summary_id")));
        assertEquals("Home OTA", relation.get("last_talker_alias"));
        assertEquals("Home Unit", relation.get("radio_alias_name"));
        Map<String,Object> activity = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&radio_identity_key=" + local + "&from_ms=9000&to_ms=15001&hide_grants=false&limit=20"));
        assertEquals(activityTimes(activity), activityTimes(mDatabase.activity(request("/?radio_system_key=" +
            RADIO_SYSTEM_KEY + "&radio_identity_key=" + full +
            "&from_ms=9000&to_ms=15001&hide_grants=false&limit=20"))));
        assertTrue(rows(activity).stream().anyMatch(row -> "Home Unit".equals(row.get("source_alias_name"))));
        Map<String,Object> channel = rows(mDatabase.channelRadios(P25_CHANNEL_A,
            request("/?q=home%20unit&limit=1"))).getFirst();
        assertEquals(7102, number(channel.get("identity_summary_id")));
        assertEquals("Home Unit", channel.get("alias_name"));
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type) " +
                "VALUES(9902,71,'Canonical Home Unit','P25_SUBSCRIBER_IDENTITY')");
            seedConfiguredSubscribers(statement);
            statement.executeUpdate("INSERT INTO alias_p25_subscriber_identity(alias_id,p25_subscriber_identity_id) " +
                "VALUES(9902,9901)");
        }
        Map<String,Object> namedCanonical = rows(mDatabase.channelRadios(P25_CHANNEL_A,
            request("/?q=canonical%20home%20unit&sort=alias&limit=1"))).getFirst();
        assertEquals("Canonical Home Unit",namedCanonical.get("alias_name"));
        //The canonical Alias owns every presented/searchable field, including empty optional fields.
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE alias SET description='Local fallback description'," +
                "group_name='Local fallback group',color=123 WHERE id=9901");
            statement.executeUpdate("UPDATE alias SET description='',group_name=NULL,color=456 WHERE id=9902");
        }
        Map<String,Object> emptyCanonical = rows(mDatabase.channelRadios(P25_CHANNEL_A,
            request("/?q=canonical%20home%20unit&sort=alias&limit=1"))).getFirst();
        assertEquals("", emptyCanonical.get("alias_description"));
        assertNull(emptyCanonical.get("alias_group"));
        assertEquals(456, number(emptyCanonical.get("alias_color")));
        assertTrue(rows(mDatabase.channelRadios(P25_CHANNEL_A, request("/?q=local%20fallback"))).isEmpty());
        assertTrue(emptyCanonical.keySet().stream().noneMatch(key -> key.startsWith("matched_alias_")));
    }

    @Test
    void provisionalHomeAndRetainedUnknownHistoryHaveSeparateExactRoutes() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code," +
                "home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms,logical_call_count) " +
                "VALUES(9903,71,2,-1,-1,202,1000,2000,1)");
        }
        String home = p25IdentityKey(2,202);
        String unknown = p25LocalRadioKey(202);
        List<Map<String,Object>> directory = rows(mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?q=202&limit=20")));
        assertEquals(2,directory.size());
        assertEquals(java.util.Set.of(home,unknown),directory.stream()
            .map(row -> String.valueOf(row.get("identity_key"))).collect(java.util.stream.Collectors.toSet()));
        assertEquals(7102,number(map(mDatabase.radio(RADIO_SYSTEM_KEY,home),"radio").get("identity_summary_id")));
        assertEquals(9903,number(map(mDatabase.radio(RADIO_SYSTEM_KEY,unknown),"radio").get("identity_summary_id")));
    }

    @Test
    void formatsKnownAndUnknownMfids()
    {
        assertEquals("Motorola (0x90)", StatsWebDatabase.mfidDisplay(0x90));
        assertEquals("0xAB", StatsWebDatabase.mfidDisplay(0xAB));
    }

    @Test
    void callCountCoverageKeepsSummaryCollectionDatesSeparateFromAvailableChannelHistory() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE database_metadata SET value='500' WHERE key='" +
                ReceiverActivitySchema.TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY + "'");
            statement.executeUpdate("UPDATE database_metadata SET value='700' WHERE key='" +
                ReceiverActivitySchema.RADIO_SYSTEM_METRICS_STARTED_AT_KEY + "'");
            statement.executeUpdate("""
                INSERT INTO p25_learned_site (
                    learned_site_id, radio_system_id, rfss, site, first_seen_ms, last_seen_ms
                ) VALUES (711,71,1,1,1000,4000), (712,71,1,2,1000,4000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_bucket(radio_system_id,bucket_start_ms,logical_call_count)
                VALUES (71,7200000,2), (71,10800000,3)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_call_bucket(radio_system_id,learned_site_id,bucket_start_ms,
                    observed_call_count)
                VALUES (71,711,3600000,3), (71,712,10800000,5)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_call_identity_bucket(radio_system_id,learned_site_id,channel_id,
                    bucket_start_ms,identity_role_code,identity_kind_code,identity_summary_id,
                    last_observed_at_ms,observed_call_count)
                VALUES (71,711,71,3600000,1,1,7101,3610000,3),
                       (71,712,72,10800000,1,1,7101,10812000,5),
                       (71,711,71,0,2,2,7102,12000000,100)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_signaling_activity_bucket(radio_system_id,channel_id,bucket_start_ms,
                    grant_count,join_count)
                VALUES (71,71,7200000,1,2), (71,72,10800000,3,4)
                """);
        }

        long requestedAt = System.currentTimeMillis();
        Map<String,Object> system = map(mDatabase.radioSystem(RADIO_SYSTEM_KEY), "radio_system");
        Map<String,Object> group = map(mDatabase.radioSystemGroupIdentity(RADIO_SYSTEM_KEY,
            p25IdentityKey(1,101)), "group_identity");
        Map<String,Object> radio = map(mDatabase.radio(RADIO_SYSTEM_KEY,p25IdentityKey(2,202)), "radio");
        for(Map<String,Object> detail: List.of(system,group,radio))
        {
            assertEquals(500,number(detail.get("call_totals_started_at_ms")));
            assertTrue(number(detail.get("call_totals_as_of_ms")) >= requestedAt);
        }
        assertEquals(5,number(system.get("logical_call_count")));
        assertEquals(7200000,number(system.get("calls_from_ms")));
        assertEquals(10800000,number(system.get("calls_through_hour_ms")));
        assertEquals(3600000,number(system.get("channel_observations_from_ms")));
        assertEquals(10800000,number(system.get("channel_observations_through_hour_ms")));
        assertEquals(8,number(group.get("channel_observation_count")));
        assertEquals(3600000,number(group.get("channel_observations_from_ms")));
        assertEquals(10812000,number(group.get("channel_observations_to_ms")));
        assertEquals(700,number(group.get("signaling_totals_started_at_ms")));
        assertEquals(700,number(radio.get("signaling_totals_started_at_ms")));
        assertEquals(7200000,number(system.get("signaling_from_ms")));
        assertEquals(10800000,number(system.get("signaling_through_hour_ms")));
        assertEquals(1000,number(group.get("first_seen_ms")),
            "identity activity dates remain independent of the call count's coverage");

        Map<String,Object> activity = mDatabase.radioSystemGroupIdentityActivity(RADIO_SYSTEM_KEY,
            p25IdentityKey(1,101),request("/?range=24h"));
        assertEquals(500,number(activity.get("call_totals_started_at_ms")));
        assertEquals(700,number(activity.get("signaling_totals_started_at_ms")));
        assertTrue(number(activity.get("counts_as_of_ms")) >= requestedAt);
        Map<String,Object> unobserved = map(mDatabase.radioSystemGroupIdentity(RADIO_SYSTEM_KEY,
            p25IdentityKey(1,999)), "group_identity");
        assertEquals(0,number(unobserved.get("channel_observation_count")));
        assertNull(unobserved.get("channel_observations_from_ms"));
        assertNull(unobserved.get("channel_observations_to_ms"));

        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            try(ResultSet plan = statement.executeQuery("""
                EXPLAIN QUERY PLAN SELECT coalesce(SUM(observed_call_count),0),
                    MIN(bucket_start_ms),MAX(last_observed_at_ms)
                FROM p25_site_call_identity_bucket INDEXED BY idx_p25_site_call_identity_identity
                WHERE identity_summary_id=7101 AND radio_system_id=71 AND identity_role_code=1
                """))
            {
                assertTrue(plan.next());
                String detail = plan.getString("detail");
                assertTrue(detail.contains("SEARCH") && detail.contains("identity_summary_id=?"),detail);
            }
            statement.executeUpdate("DELETE FROM p25_site_call_identity_bucket WHERE identity_summary_id=7101");
            statement.executeUpdate("DELETE FROM trunked_logical_call_bucket");
        }
        Map<String,Object> afterPruning = map(mDatabase.radioSystemGroupIdentity(RADIO_SYSTEM_KEY,
            p25IdentityKey(1,101)), "group_identity");
        assertEquals(3,number(afterPruning.get("logical_call_count")));
        assertEquals(500,number(afterPruning.get("call_totals_started_at_ms")));
        assertEquals(0,number(afterPruning.get("channel_observation_count")));
        assertNull(afterPruning.get("channel_observations_from_ms"));
        assertNull(afterPruning.get("channel_observations_to_ms"));
        Map<String,Object> emptySystem = map(mDatabase.radioSystem(RADIO_SYSTEM_KEY), "radio_system");
        assertNull(emptySystem.get("calls_from_ms"));
        assertNull(emptySystem.get("calls_through_hour_ms"));
    }

    @Test
    void radioSystemDirectoryOwnsSystemsWhileSavedChannelsOwnConfiguration()
    {
        Map<String,Object> directory = mDatabase.radioSystemDirectory(request("/"));
        List<Map<String,Object>> systems = rows(directory);
        assertEquals(1, systems.size());
        Map<String,Object> listed = systems.getFirst();
        assertEquals(RADIO_SYSTEM_KEY, listed.get("radio_system_key"));
        assertEquals("Shared P25", listed.get("system_name"));
        assertEquals(2, number(listed.get("channels")));
        assertEquals(2, number(listed.get("alias_list_count")));
        assertEquals(List.of("County A", "County B"), aliasListNames(listed));
        assertFalse(listed.containsKey("alias_list_id"), "A shared system must not own one arbitrary alias list");
        assertFalse(listed.containsKey("alias_list_name"), "A shared system must not own one arbitrary alias list");
        assertEquals(Map.of("kind", "radio_system", "key", RADIO_SYSTEM_KEY), listed.get("entity_ref"));

        Map<String,Object> detail = map(mDatabase.radioSystem(RADIO_SYSTEM_KEY), "radio_system");
        assertEquals(List.of("County A", "County B"), aliasListNames(detail));

        List<Map<String,Object>> channels = rows(mDatabase.radioSystemChannels(RADIO_SYSTEM_KEY, request("/")));
        assertEquals(2, channels.size());
        assertEquals(P25_CHANNEL_A, channels.getFirst().get("configuration_id"));
        assertTrue(channels.stream().allMatch(channel ->
            "channel".equals(map(channel, "entity_ref").get("kind"))));

        List<Map<String,Object>> allChannels = rows(mDatabase.channelDirectory(request("/")));
        assertEquals(4, allChannels.size());
        assertEquals(2, allChannels.stream().filter(row -> "TRUNKED".equals(row.get("channel_kind"))).count());
        assertEquals(2, allChannels.stream().filter(row -> "CONVENTIONAL".equals(row.get("channel_kind"))).count());
        assertTrue(allChannels.stream().allMatch(channel -> channel.containsKey("primary_frequency_hz")));
        assertTrue(allChannels.stream().noneMatch(channel -> channel.containsKey("frequency_hz")));
    }

    @Test
    void publicAliasInventoryIncludesNonIdentityMatchersWithoutCountingThemAsUnheard() throws Exception
    {
        long observedAt = System.currentTimeMillis();
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO alias_list(id, name, family) " +
                "VALUES (971, 'Mixed P25 coverage', 'P25')");
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value,
                    min_value, max_value, numeric_value, tone_sequence)
                VALUES (97101, 971, 'Alpha Talkgroup', 'TALKGROUP', 'APCO25', 101,
                            NULL, NULL, NULL, NULL),
                       (97102, 971, 'Bravo Radio Range', 'RADIO_ID_RANGE', 'APCO25', NULL,
                            200, 201, NULL, NULL),
                       (97103, 971, 'Charlie User Status', 'STATUS', NULL, NULL,
                            NULL, NULL, 11, NULL),
                       (97104, 971, 'Delta Unit Status', 'UNIT_STATUS', NULL, NULL,
                            NULL, NULL, 12, NULL),
                       (97105, 971, 'Echo Tones', 'TONES', NULL, NULL,
                            NULL, NULL, NULL, 'DTMF_1:200')
                """);
            statement.executeUpdate("""
                INSERT INTO alias_activity_summary(alias_id, alias_list_id, protocol_code,
                    metrics_state, logical_call_count, signaling_observation_count,
                    first_evidence_ms, last_evidence_ms, updated_at_ms)
                VALUES (97101, 971, 1, 'observed', 2, 3, %1$d, %1$d, %1$d)
                """.formatted(observedAt));
        }

        Map<String,Object> listed = rowWith(rows(mDatabase.publicIdentityLists(request("/?limit=100"))),
            "alias_list_id", 971);
        assertEquals(5, number(listed.get("alias_count")));

        Map<String,Object> totals = map(mDatabase.publicIdentityOverview(971, request("/?range=24h")), "totals");
        assertEquals(5, number(totals.get("configured_alias_count")));
        assertEquals(2, number(totals.get("activity_eligible_alias_count")));
        assertEquals(1, number(totals.get("active_alias_count")));
        assertEquals(1, number(totals.get("zero_call_alias_count")));
        assertEquals(1, number(totals.get("never_heard_alias_count")));

        Map<String,Object> all = mDatabase.publicIdentityAliases(971,
            request("/?range=24h&status=all&sort=name&direction=asc&limit=2"));
        assertEquals(List.of("Alpha Talkgroup", "Bravo Radio Range"), rows(all).stream()
            .map(row -> row.get("name")).toList());
        assertTrue((Boolean)all.get("has_more"));
        assertEquals(2, number(all.get("next_offset")));
        Map<String,Object> remaining = mDatabase.publicIdentityAliases(971,
            request("/?range=24h&status=all&sort=name&direction=asc&limit=10&offset=2"));
        assertEquals(List.of("Charlie User Status", "Delta Unit Status", "Echo Tones"), rows(remaining).stream()
            .map(row -> row.get("name")).toList());

        Map<String,Object> talkgroup = rowWith(rows(all), "name", "Alpha Talkgroup");
        assertEquals("talkgroup", talkgroup.get("identity_type"));
        assertEquals("TALKGROUP", talkgroup.get("matcher_type"));
        assertEquals("101", talkgroup.get("identity_display"));
        assertEquals("observed", talkgroup.get("metrics_state"));
        assertEquals(2, number(talkgroup.get("logical_call_count")));
        Map<String,Object> radio = rowWith(rows(all), "name", "Bravo Radio Range");
        assertEquals("radio", radio.get("identity_type"));
        assertEquals("200–201", radio.get("identity_display"));
        assertEquals("not_collected", radio.get("metrics_state"));
        Map<String,Object> userStatus = rowWith(rows(remaining), "name", "Charlie User Status");
        assertEquals("other", userStatus.get("identity_type"));
        assertEquals("User status", userStatus.get("matcher_label"));
        assertEquals("11", userStatus.get("identity_display"));
        assertEquals("unsupported", userStatus.get("metrics_state"));
        assertNull(userStatus.get("logical_call_count"));
        Map<String,Object> tones = rowWith(rows(remaining), "name", "Echo Tones");
        assertEquals("Tones", tones.get("matcher_label"));
        assertEquals("DTMF_1:200", tones.get("identity_display"));

        assertEquals(List.of("Charlie User Status", "Delta Unit Status", "Echo Tones"),
            rows(mDatabase.publicIdentityAliases(971,
                request("/?type=other&sort=name&direction=asc&limit=100"))).stream()
                .map(row -> row.get("name")).toList());
        assertEquals(List.of("Charlie User Status"),
            rows(mDatabase.publicIdentityAliases(971, request("/?q=11&limit=100"))).stream()
                .map(row -> row.get("name")).toList());
        assertEquals(List.of("Alpha Talkgroup"),
            rows(mDatabase.publicIdentityAliases(971, request("/?status=recent&limit=100"))).stream()
                .map(row -> row.get("name")).toList());
        for(String status: List.of("zero_calls", "never_heard"))
        {
            assertEquals(List.of("Bravo Radio Range"),
                rows(mDatabase.publicIdentityAliases(971, request("/?status=" + status + "&limit=100"))).stream()
                    .map(row -> row.get("name")).toList());
        }
    }

    @Test
    void radioSystemDetailIncludesRetainedSignalingOwnedByTheSystem() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO trunked_signaling_activity_bucket (
                    channel_id, radio_system_id, bucket_start_ms, denial_count, register_count)
                VALUES (71, 71, 3600000, 2, 1)
                """);
            statement.executeUpdate("""
                UPDATE receiver_channel
                SET radio_system_id = NULL, radio_system_assigned_at_ms = NULL
                WHERE id = 71
                """);
        }

        Map<String,Object> detail = map(mDatabase.radioSystem(RADIO_SYSTEM_KEY), "radio_system");
        List<Map<String,Object>> actions = rowsFrom(detail, "action_counts");
        assertEquals(2, actions.size());
        assertEquals(2, number(rowWith(actions, "action", "DENIAL").get("observation_count")));
        assertEquals(1, number(rowWith(actions, "action", "REGISTER").get("observation_count")));
    }

    @Test
    void nativeDmrAndNxdnSystemFieldsAndObservedGroupsComeFromTheSharedRadioSystem() throws Exception
    {
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family)
                VALUES (81, 'Shared DMR aliases', 'DMR'), (82, 'Shared NXDN aliases', 'NXDN')
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    alias_list_id, decoder_type, address_domain_code, primary_frequency_hz, config_json
                ) VALUES
                    ('%1$s', 'TRUNKED', 81, 'Shared DMR', 'DMR A', 'DMR A', 81, 'DMR', 0, 451012500,
                        '{"decodeConfiguration":{"channelMode":"TRUNKED"}}'),
                    ('%2$s', 'TRUNKED', 82, 'Shared DMR', 'DMR B', 'DMR B', 81, 'DMR', 0, 452012500,
                        '{"decodeConfiguration":{"channelMode":"TRUNKED"}}'),
                    ('%3$s', 'TRUNKED', 83, 'Shared NXDN', 'NXDN A', 'NXDN A', 82, 'NXDN', 1, 155012500,
                        '{"decodeConfiguration":{"channelMode":"TRUNKED"}}'),
                    ('%4$s', 'TRUNKED', 84, 'Shared NXDN', 'NXDN B', 'NXDN B', 82, 'NXDN', 1, 156012500,
                        '{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
                """.formatted(DMR_NATIVE_CHANNEL_A, DMR_NATIVE_CHANNEL_B,
                    NXDN_NATIVE_CHANNEL_A, NXDN_NATIVE_CHANNEL_B));
            statement.executeUpdate("""
                INSERT INTO radio_system (
                    id, system_key, protocol_code, address_domain_code,
                    dmr_model_code, dmr_network_id, nxdn_location_category_code, nxdn_system_id,
                    first_seen_ms, last_seen_ms
                ) VALUES
                    (81, 'dmr:tier3:small:42', 3, 0, 2, 42, NULL, NULL, 1000, 4000),
                    (82, 'nxdn-c:local:303', 4, 1, NULL, NULL, 3, 303, 1000, 4000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel (
                    id, configuration_id, first_seen_ms, last_seen_ms,
                    radio_system_id, radio_system_assigned_at_ms
                ) VALUES
                    (81, '%1$s', 1000, 4000, 81, 1000),
                    (82, '%2$s', 1000, 4000, 81, 1000),
                    (83, '%3$s', 1000, 4000, 82, 1000),
                    (84, '%4$s', 1000, 4000, 82, 1000)
                """.formatted(DMR_NATIVE_CHANNEL_A, DMR_NATIVE_CHANNEL_B,
                    NXDN_NATIVE_CHANNEL_A, NXDN_NATIVE_CHANNEL_B));
            //Each receiver reports matching local evidence, while system endpoints take the shared identity from
            //radio_system instead of selecting one receiver's snapshot as authoritative.
            statement.executeUpdate("""
                INSERT INTO trunked_site_snapshot (
                    channel_id, snapshot_hash, protocol_code, variant_code, observed_location_category_code,
                    observed_network_id, observed_system_id, observed_site_id, observed_model_code,
                    service_flags, primary_frequency_hz, current_control_hz,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES
                    (81, '%1$s', 3, 1, 0, 42, NULL, 1, 2,
                        0, 451012500, 451012500, 1000, 4000, 4),
                    (82, '%2$s', 3, 1, 0, 42, NULL, 2, 2,
                        0, 452012500, 452012500, 1000, 4000, 4),
                    (83, '%3$s', 4, 1, 3, NULL, 303, 1, NULL,
                        0, 155012500, 155012500, 1000, 4000, 4),
                    (84, '%4$s', 4, 1, 3, NULL, 303, 2, NULL,
                        0, 156012500, 156012500, 1000, 4000, 4)
                """.formatted("c".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64)));
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, identity_id,
                    first_seen_ms, last_seen_ms, logical_call_count,
                    last_talker_alias, last_talker_alias_seen_ms
                ) VALUES
                    (8101, 81, 1, 101, 1000, 4000, 2, NULL, NULL),
                    (8102, 81, 2, 202, 1000, 4000, 2, 'DMR Radio 202', 4000),
                    (8201, 82, 1, 101, 1000, 4000, 2, NULL, NULL),
                    (8202, 82, 2, 202, 1000, 4000, 2, 'NXDN Radio 202', 4000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_identity_bucket (
                    radio_system_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_summary_id,
                    logical_call_count
                ) VALUES
                    (81, %1$d, 1, 1, 8101, 2), (81, %1$d, 2, 2, 8102, 2),
                    (82, %1$d, 1, 1, 8201, 2), (82, %1$d, 2, 2, 8202, 2)
                """.formatted(bucket));
            statement.executeUpdate("""
                INSERT INTO trunked_radio_group_summary (
                    radio_system_id, radio_identity_id, group_identity_id, group_kind_code,
                    first_seen_ms, last_seen_ms, logical_call_count
                ) VALUES (81, 8102, 8101, 1, 1000, 4000, 2),
                         (82, 8202, 8201, 1, 1000, 4000, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence (
                    radio_system_id, radio_identity_id, channel_id, observed_local_id,
                    evidence_code, confirmed_at_ms
                ) VALUES (81, 8102, 82, 202, 2, 4000),
                         (82, 8202, 84, 202, 2, 4000)
                """);
        }

        Map<String,Object> dmr = map(mDatabase.radioSystem("dmr:tier3:small:42"), "radio_system");
        assertEquals(2, number(dmr.get("channels")));
        assertEquals(2, number(dmr.get("dmr_model_code")));
        assertEquals(42, number(dmr.get("network_id")));
        assertEquals("TIER_III", dmr.get("variant"));
        JsonNode presentedDmr = StatsApiV1Payload.present(dmr);
        assertEquals("small", presentedDmr.path("model").textValue());
        assertEquals(42, presentedDmr.path("network_id").longValue());

        String dmrGroupKey = RadioSystemIdentityKey.format(1, -1, -1, 101);
        String dmrRadioKey = RadioSystemIdentityKey.format(2, -1, -1, 202);
        assertDmrNativeIdentity(rows(mDatabase.radioSystemChannels(
            "dmr:tier3:small:42", request("/"))).getFirst());
        assertDmrNativeIdentity(rows(mDatabase.radioSystemGroupIdentities(
            "dmr:tier3:small:42", request("/"))).getFirst());
        Map<String,Object> dmrRadio = rows(mDatabase.radioSystemRadios(
            "dmr:tier3:small:42", request("/?sort=channel&direction=asc"))).getFirst();
        assertDmrNativeIdentity(dmrRadio);
        Map<String,Object> dmrPresence = map(map(dmrRadio, "presence"), "channel");
        assertEquals(DMR_NATIVE_CHANNEL_B, dmrPresence.get("configuration_id"));
        assertEquals(42, number(dmrPresence.get("network_id")));
        assertEquals(2, number(dmrPresence.get("site_id")));
        assertDmrNativeIdentity(rows(mDatabase.radioSystemTalkerAliases(
            "dmr:tier3:small:42", request("/"))).getFirst());
        assertDmrNativeIdentity(map(mDatabase.radioSystemGroupIdentity(
            "dmr:tier3:small:42", dmrGroupKey), "group_identity"));
        assertEquals("Shared DMR", map(mDatabase.radioSystemGroupIdentity(
            "dmr:tier3:small:42", dmrGroupKey), "group_identity").get("system_name"));
        assertDmrNativeIdentity(map(mDatabase.radio(
            "dmr:tier3:small:42", dmrRadioKey), "radio"));
        assertEquals("Shared DMR", map(mDatabase.radio(
            "dmr:tier3:small:42", dmrRadioKey), "radio").get("system_name"));
        assertDmrNativeIdentity(rows(mDatabase.radioSystemRelationships(
            "dmr:tier3:small:42", request("/?group_identity_key=" + dmrGroupKey))).getFirst());
        long observedRequestedAt = System.currentTimeMillis();
        Map<String,Object> observedDmrPage = mDatabase.observedGroupIdentities(81, request("/"));
        List<Map<String,Object>> observedDmr = rows(observedDmrPage);
        assertEquals(1, observedDmr.size(),
            "Two channels using the list must not duplicate one native system identity");
        assertDmrNativeIdentity(observedDmr.getFirst());
        assertEquals("Shared DMR", observedDmr.getFirst().get("system_name"));
        long observedAsOf = number(observedDmrPage.get("counts_as_of_ms"));
        assertTrue(observedAsOf >= observedRequestedAt && observedAsOf <= System.currentTimeMillis());
        assertTrue(observedDmr.stream().allMatch(row -> number(row.get("counts_as_of_ms")) == observedAsOf),
            "all current-hour rows need one shared snapshot time to prevent future popup end dates");
        assertEquals(bucket,number(observedDmr.getFirst().get("last_seen_ms")));
        assertEquals(1, number(mDatabase.observedGroupIdentities(81,
            request("/?search=Shared%20DMR")).get("total_count")));
        assertNull(observedDmr.getFirst().get("channel_id"));
        assertNull(observedDmr.getFirst().get("configuration_id"));
        assertDmrNativeIdentity(map(mDatabase.radioSystemGroupIdentity("dmr:tier3:small:42",
            RadioSystemIdentityKey.format(1, -1, -1, 999)), "group_identity"));

        Map<String,Object> nxdn = map(mDatabase.radioSystem("nxdn-c:local:303"), "radio_system");
        assertEquals(2, number(nxdn.get("channels")));
        assertEquals(3, number(nxdn.get("nxdn_location_category_code")));
        assertEquals(303, number(nxdn.get("system_id")));
        assertEquals("TYPE_C", nxdn.get("variant"));
        JsonNode presentedNxdn = StatsApiV1Payload.present(nxdn);
        assertEquals("local", presentedNxdn.path("location_category").textValue());
        assertEquals(303, presentedNxdn.path("system_id").longValue());

        assertNxdnNativeIdentity(rows(mDatabase.radioSystemChannels(
            "nxdn-c:local:303", request("/"))).getFirst());
        assertNxdnNativeIdentity(rows(mDatabase.radioSystemGroupIdentities(
            "nxdn-c:local:303", request("/"))).getFirst());
        Map<String,Object> nxdnRadio = rows(mDatabase.radioSystemRadios(
            "nxdn-c:local:303", request("/?sort=channel&direction=asc"))).getFirst();
        assertNxdnNativeIdentity(nxdnRadio);
        Map<String,Object> nxdnPresence = map(map(nxdnRadio, "presence"), "channel");
        assertEquals(NXDN_NATIVE_CHANNEL_B, nxdnPresence.get("configuration_id"));
        assertEquals(303, number(nxdnPresence.get("system_id")));
        assertEquals(2, number(nxdnPresence.get("site_id")));
        List<Map<String,Object>> observedNxdn = rows(mDatabase.observedGroupIdentities(82, request("/")));
        assertEquals(1, observedNxdn.size(),
            "Two channels using the list must not duplicate one native system identity");
        assertNxdnNativeIdentity(observedNxdn.getFirst());
        assertEquals("Shared NXDN", observedNxdn.getFirst().get("system_name"));
        assertNull(observedNxdn.getFirst().get("channel_id"));
        assertNull(observedNxdn.getFirst().get("configuration_id"));

        for(String configurationId: List.of(DMR_NATIVE_CHANNEL_A, NXDN_NATIVE_CHANNEL_A))
        {
            Map<String,Object> nativeChannel = map(mDatabase.channelDetail(configurationId, request("/")), "channel");
            assertEquals(false, map(nativeChannel, "capabilities").get("group_identities"));
            assertTrue(rows(mDatabase.channelGroupIdentities(configurationId,
                request("/?range=24h"))).isEmpty(), configurationId);
            assertTrue(rows(mDatabase.channelRadios(configurationId, request("/"))).isEmpty(), configurationId);
        }

        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM trunked_site_snapshot WHERE channel_id IN (81,82,83,84)");
        }

        Map<String,Object> dmrWithoutSite = map(mDatabase.channelDetail(DMR_NATIVE_CHANNEL_A, request("/")), "channel");
        assertEquals(42, number(dmrWithoutSite.get("network_id")));
        assertEquals(2, number(dmrWithoutSite.get("dmr_model_code")));
        assertEquals("TIER_III", dmrWithoutSite.get("variant"));
        JsonNode presentedDmrWithoutSite = StatsApiV1Payload.present(dmrWithoutSite);
        assertEquals("small", presentedDmrWithoutSite.path("model").textValue());
        assertEquals(42, presentedDmrWithoutSite.path("network_id").longValue());

        Map<String,Object> nxdnWithoutSite = map(mDatabase.channelDetail(NXDN_NATIVE_CHANNEL_A,
            request("/")), "channel");
        assertEquals(303, number(nxdnWithoutSite.get("system_id")));
        assertEquals(3, number(nxdnWithoutSite.get("nxdn_location_category_code")));
        assertEquals("TYPE_C", nxdnWithoutSite.get("variant"));
        JsonNode presentedNxdnWithoutSite = StatsApiV1Payload.present(nxdnWithoutSite);
        assertEquals("local", presentedNxdnWithoutSite.path("location_category").textValue());
        assertEquals(303, presentedNxdnWithoutSite.path("system_id").longValue());
    }

    @Test
    void dashboardUsesSystemConsensusForTrunkedAliasesAndExactListsForConventionalAliases() throws Exception
    {
        long now = System.currentTimeMillis();
        long bucket = Math.floorDiv(now, 3_600_000L) * 3_600_000L;

        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family)
                VALUES (81, 'DMR North', 'DMR'), (82, 'DMR South', 'DMR')
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    alias_list_id, decoder_type, address_domain_code, primary_frequency_hz, config_json
                ) VALUES
                    ('%1$s', 'TRUNKED', 81, 'Shared DMR', 'North', 'North Control',
                        81, 'DMR', 0, 451012500,
                        '{"decodeConfiguration":{"channelMode":"TRUNKED"}}'),
                    ('%2$s', 'TRUNKED', 82, 'Shared DMR', 'South', 'South Control',
                        82, 'DMR', 0, 452012500,
                        '{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
                """.formatted(DMR_NATIVE_CHANNEL_A, DMR_NATIVE_CHANNEL_B));
            statement.executeUpdate("""
                INSERT INTO radio_system (
                    id, system_key, protocol_code, address_domain_code, dmr_model_code, dmr_network_id,
                    first_seen_ms, last_seen_ms
                ) VALUES (81, 'dmr:tier3:small:42', 3, 0, 2, 42, 1000, %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO receiver_channel (
                    id, configuration_id, first_seen_ms, last_seen_ms,
                    radio_system_id, radio_system_assigned_at_ms
                ) VALUES (81, '%1$s', 1000, %3$d, 81, 1000),
                         (82, '%2$s', 1000, %3$d, 81, 1000)
                """.formatted(DMR_NATIVE_CHANNEL_A, DMR_NATIVE_CHANNEL_B, now));
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, identity_id,
                    first_seen_ms, last_seen_ms, logical_call_count
                ) VALUES (8101, 81, 1, 101, 1000, %1$d, 4),
                         (8102, 81, 2, 202, 1000, %1$d, 4)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, description, matcher_type, protocol, value)
                VALUES (8111, 81, 'Shared Dispatch', 'Trunked group details', 'TALKGROUP', 'DMR', 101),
                       (8112, 81, 'Shared Unit', 'Trunked radio details', 'RADIO_ID', 'DMR', 202),
                       (8113, 81, 'Silent Dispatch', NULL, 'TALKGROUP', 'DMR', 303),
                       (8211, 82, 'Shared Dispatch', 'Trunked group details', 'TALKGROUP', 'DMR', 101),
                       (8212, 82, 'Shared Unit', 'Trunked radio details', 'RADIO_ID', 'DMR', 202),
                       (7402, 74, 'Conventional Unit', 'Conventional radio details', 'RADIO_ID', 'DMR', 303)
                """);
            statement.executeUpdate("UPDATE alias SET description='Conventional group details' WHERE id=7401");
            statement.executeUpdate("""
                INSERT INTO alias_activity_summary (
                    alias_id, alias_list_id, protocol_code, metrics_state,
                    logical_call_count, grant_observation_count, signaling_observation_count,
                    first_evidence_ms, last_evidence_ms, updated_at_ms
                ) VALUES (8111,81,3,'observed',4,2,2,1000,%1$d,%1$d),
                         (8112,81,3,'observed',4,0,1,1000,%1$d,%1$d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_identity_bucket (
                    radio_system_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_summary_id,
                    logical_call_count
                ) VALUES (81, %1$d, 1, 1, 8101, 4), (81, %1$d, 2, 2, 8102, 4)
                """.formatted(bucket));
            statement.executeUpdate("""
                INSERT INTO conventional_call_identity_bucket (
                    channel_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                    call_count
                ) VALUES (74, %1$d, 1, 1, 7, 3), (74, %1$d, 2, 2, 303, 3)
                """.formatted(bucket));
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event (
                    channel_id, radio_system_id, observed_at_ms, action_code,
                    source_observed_local_id, source_identity_summary_id, source_identity_kind_code
                ) VALUES (81, 81, %1$d, 23, 202, 8102, 2),
                         (74, NULL, %1$d, 23, 303, NULL, 2)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO trunked_signaling_activity_bucket (
                    channel_id, radio_system_id, bucket_start_ms, unknown_count
                ) VALUES (81, 81, %d, 1)
                """.formatted(bucket));
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket (
                    channel_id, frequency_hz, timeslot, bucket_start_ms, unknown_count
                ) VALUES (74, 460012500, 2, %d, 1)
                """.formatted(bucket));
        }

        Map<String,Object> dashboard = mDatabase.dashboard();
        Map<String,Object> trunkedDestination = rowWith(rowsFrom(dashboard, "top_destinations"),
            "radio_system_key", "dmr:tier3:small:42");
        Map<String,Object> conventionalDestination = rowWith(rowsFrom(dashboard, "top_destinations"),
            "configuration_id", DMR_CHANNEL);
        Map<String,Object> trunkedSource = rowWith(rowsFrom(dashboard, "top_sources"),
            "radio_system_key", "dmr:tier3:small:42");
        Map<String,Object> conventionalSource = rowWith(rowsFrom(dashboard, "top_sources"),
            "configuration_id", DMR_CHANNEL);
        assertEquals("Shared Dispatch", trunkedDestination.get("alias_name"));
        assertEquals("Shared DMR", trunkedDestination.get("system_name"));
        assertEquals("DMR Dispatch", conventionalDestination.get("alias_name"));
        assertEquals("Shared Unit", trunkedSource.get("alias_name"));
        assertEquals("Shared DMR", trunkedSource.get("system_name"));
        assertEquals("Conventional Unit", conventionalSource.get("alias_name"));

        Map<String,Object> identities = mDatabase.identityDirectory(request("/?range=24h&limit=500"));
        assertEquals("24h", identities.get("range"));
        assertFalse((Boolean)identities.get("candidate_limit_reached"));
        assertEquals(4, number(identities.get("total_count")));
        assertEquals("Shared Dispatch", rowWith(rows(identities), "radio_system_key",
            "dmr:tier3:small:42").get("alias_name"));
        assertEquals("Conventional Unit", rows(identities).stream()
            .filter(row -> DMR_CHANNEL.equals(row.get("configuration_id")))
            .filter(row -> number(row.get("identity_kind_code")) == 2)
            .findFirst().orElseThrow().get("alias_name"));

        Map<String,Object> firstIdentityPage = mDatabase.identityDirectory(request("/?range=24h&limit=2"));
        Map<String,Object> laterIdentityPage = mDatabase.identityDirectory(
            request("/?range=24h&limit=2&offset=2"));
        for(Map<String,Object> identityPage: List.of(firstIdentityPage, laterIdentityPage))
        {
            assertEquals("24h", identityPage.get("range"));
            assertEquals(2, number(identityPage.get("limit")));
            assertEquals(4, number(identityPage.get("total_count")));
            assertFalse((Boolean)identityPage.get("candidate_limit_reached"));
            assertEquals(2, rows(identityPage).size());
        }
        assertEquals(0, number(firstIdentityPage.get("offset")));
        assertTrue((Boolean)firstIdentityPage.get("has_more"));
        assertEquals(2, number(firstIdentityPage.get("next_offset")));
        assertEquals(2, number(laterIdentityPage.get("offset")));
        assertFalse((Boolean)laterIdentityPage.get("has_more"));
        assertNull(laterIdentityPage.get("next_offset"));

        List<Map<String,Object>> pagedIdentities = new ArrayList<>(rows(firstIdentityPage));
        pagedIdentities.addAll(rows(laterIdentityPage));
        assertEquals(List.of(101L, 202L, 7L, 303L), pagedIdentities.stream()
            .map(row -> number(row.get("native_id"))).toList());
        assertEquals(List.of(4L, 4L, 3L, 3L), pagedIdentities.stream()
            .map(row -> number(row.get("logical_call_count"))).toList());
        assertEquals(List.of("Shared Dispatch", "Shared Unit", "DMR Dispatch", "Conventional Unit"),
            pagedIdentities.stream().map(row -> row.get("alias_name")).toList());
        assertEquals(List.of("Trunked group details", "Trunked radio details",
                "Conventional group details", "Conventional radio details"),
            pagedIdentities.stream().map(row -> row.get("alias_description")).toList());
        assertEquals(List.of(1L, 2L, 1L, 2L), pagedIdentities.stream()
            .map(row -> number(row.get("identity_role_code"))).toList());
        assertEquals(List.of("Destination", "Source", "Destination", "Source"),
            pagedIdentities.stream().map(row -> row.get("identity_role")).toList());
        assertEquals(List.of(
                Map.of("kind", "talkgroup", "radio_system_key", "dmr:tier3:small:42",
                    "identity_key", "v1-g-x-x-101"),
                Map.of("kind", "radio", "radio_system_key", "dmr:tier3:small:42",
                    "identity_key", "v1-r-x-x-202"),
                Map.of("kind", "channel", "key", DMR_CHANNEL),
                Map.of("kind", "channel", "key", DMR_CHANNEL)),
            pagedIdentities.stream().map(row -> map(row, "entity_ref")).toList());
        assertEquals("groups", pagedIdentities.get(2).get("entity_tab"));
        assertEquals("radios", pagedIdentities.get(3).get("entity_tab"));

        Map<String,Object> publicLists = mDatabase.publicIdentityLists(request("/?limit=100"));
        assertEquals("DMR North", rows(publicLists).stream()
            .filter(row -> number(row.get("alias_list_id")) == 81).findFirst().orElseThrow().get("name"));
        Map<String,Object> publicOverview = mDatabase.publicIdentityOverview(81, request("/?range=24h"));
        assertEquals(1, number(publicOverview.get("correlated_channel_count")));
        assertEquals(3, number(map(publicOverview, "totals").get("configured_alias_count")));
        assertEquals(2, number(map(publicOverview, "totals").get("active_alias_count")));
        assertEquals(1, number(map(publicOverview, "totals").get("zero_call_alias_count")));
        assertEquals(1, number(map(publicOverview, "totals").get("never_heard_alias_count")));
        assertTrue(rowsFrom(publicOverview, "channels").getFirst().containsKey("channel_entity_ref"));
        assertTrue(rowsFrom(publicOverview, "channels").getFirst().containsKey("radio_system_entity_ref"));
        Map<String,Object> allPublicAliases = mDatabase.publicIdentityAliases(81,
            request("/?range=24h&type=talkgroup&limit=100"));
        assertEquals(List.of("Shared Dispatch", "Silent Dispatch"), rows(allPublicAliases).stream()
            .map(row -> row.get("name")).toList());
        Map<String,Object> linkedPublicAlias = rowWith(rows(allPublicAliases), "name", "Shared Dispatch");
        assertEquals("talkgroup", map(linkedPublicAlias, "entity_ref").get("kind"));
        assertEquals("dmr:tier3:small:42", map(linkedPublicAlias, "entity_ref").get("radio_system_key"));
        assertEquals("talkgroup", map(rowWith(rows(allPublicAliases), "name", "Silent Dispatch"),
            "entity_ref").get("kind"));
        Map<String,Object> recentPublicAliases = mDatabase.publicIdentityAliases(81,
            request("/?range=24h&type=talkgroup&status=recent&limit=100"));
        assertEquals(List.of("Shared Dispatch"), rows(recentPublicAliases).stream()
            .map(row -> row.get("name")).toList());
        Map<String,Object> zeroCallPublicAliases = mDatabase.publicIdentityAliases(81,
            request("/?range=24h&status=zero_calls&limit=100"));
        assertEquals(List.of("Silent Dispatch"), rows(zeroCallPublicAliases).stream()
            .map(row -> row.get("name")).toList());

        Map<String,Object> activity = mDatabase.dashboardActivityRadios(
            request("/?range=1h&action=UNKNOWN&limit=20"));
        Map<String,Object> trunkedActivity = rowWith(rows(activity),
            "radio_system_key", "dmr:tier3:small:42");
        Map<String,Object> conventionalActivity = rowWith(rows(activity),
            "configuration_id", DMR_CHANNEL);
        assertEquals("Shared Unit", trunkedActivity.get("alias_name"));
        assertEquals("Conventional Unit", conventionalActivity.get("alias_name"));
    }

    @Test
    void dashboardActivityRanksBothChannelKindsWithExactCountsAndStablePaging() throws Exception
    {
        long now = System.currentTimeMillis();
        long hour = Math.floorDiv(now, 3_600_000L) * 3_600_000L;
        long older = hour - 3 * 86_400_000L;
        long expired = hour - 8 * 86_400_000L;
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE radio_system_identity_summary SET " +
                "last_talker_alias='Unit 202 OTA',last_talker_alias_seen_ms=4000 WHERE id=7102");
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(channel_id, radio_system_id, observed_at_ms, action_code,
                    source_observed_local_id, source_identity_summary_id) VALUES
                (71,71,%1$d,20,202,7102), (72,71,%1$d,20,202,7102),
                (71,71,%2$d+100,20,202,7102), (71,71,%1$d,20,NULL,NULL),
                (73,NULL,%1$d,20,202,NULL), (74,NULL,%1$d,20,202,NULL),
                (74,NULL,%1$d-500,20,303,NULL), (74,NULL,%2$d+100,20,0,NULL),
                (71,71,%3$d+100,20,202,7102), (71,71,%1$d,13,202,7102)
                """.formatted(now, older, expired));
            statement.executeUpdate("""
                INSERT INTO trunked_signaling_activity_bucket(channel_id, radio_system_id, bucket_start_ms,
                    register_count, join_count) VALUES
                (71,71,%1$d,2,1), (72,71,%1$d,1,0), (71,71,%2$d,1,0), (71,71,%3$d,1,0)
                """.formatted(hour, older, expired));
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket(channel_id, frequency_hz, timeslot, bucket_start_ms,
                    register_count) VALUES (73,0,-1,%1$d,1), (74,0,-1,%1$d,2), (74,0,-1,%2$d,1)
                """.formatted(hour, older));
        }
        Map<String,Object> page = mDatabase.dashboardActivityRadios(request("/?range=7d&action=REGISTER&limit=2"));
        assertEquals(4, number(page.get("total_count")));
        assertEquals(8, number(page.get("retained_observation_count")));
        assertEquals(6, number(page.get("identified_observation_count")));
        assertTrue((Boolean)page.get("has_more"));
        List<Map<String,Object>> first = rows(page);
        assertEquals(RADIO_SYSTEM_KEY, first.getFirst().get("radio_system_key"));
        assertEquals("Unit 202 OTA", first.getFirst().get("last_talker_alias"));
        assertEquals(4000, number(first.getFirst().get("last_talker_alias_seen_ms")));
        assertEquals(3, number(first.getFirst().get("observation_count")));
        assertEquals(ANALOG_CHANNEL, first.get(1).get("configuration_id"));
        Map<String,Object> next = mDatabase.dashboardActivityRadios(
            request("/?range=7d&action=REGISTER&limit=2&offset=2"));
        assertEquals(4, number(next.get("total_count")));
        assertFalse((Boolean)next.get("has_more"));
        assertTrue(rows(next).stream().allMatch(row -> DMR_CHANNEL.equals(row.get("configuration_id"))));
        Map<String,Object> recent = mDatabase.dashboardActivityRadios(
            request("/?range=24h&action=REGISTER&limit=100"));
        assertEquals(6, number(recent.get("retained_observation_count")));
        assertEquals(5, number(recent.get("identified_observation_count")));
    }

    @Test
    void dashboardActivityCountsEachRetainedEventOnceAndKeepsTotalsAfterPagingOrRetention() throws Exception
    {
        long now = System.currentTimeMillis();
        long hour = Math.floorDiv(now, 3_600_000L) * 3_600_000L;
        int stun = ReceiverActivityFilterCatalog.actionCode("STUN");
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("PRAGMA foreign_keys=ON");
            statement.executeUpdate("UPDATE receiver_channel SET radio_system_id=NULL, " +
                "radio_system_assigned_at_ms=NULL WHERE id=71");
            // Historical counts can span channel kinds, frequencies and slots within one channel-hour.
            statement.executeUpdate("""
                INSERT INTO trunked_signaling_activity_bucket(channel_id, radio_system_id, bucket_start_ms,
                    stun_count) VALUES (71,71,%1$d,2), (74,71,%1$d,3)
                """.formatted(hour));
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket(channel_id, frequency_hz, timeslot, bucket_start_ms,
                    stun_count) VALUES (74,460012500,1,%1$d,3), (74,460012500,2,%1$d,2),
                    (74,460025000,1,%1$d,4), (73,0,-1,%1$d,5)
                """.formatted(hour));
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(channel_id, radio_system_id, observed_at_ms, action_code,
                    source_observed_local_id, source_identity_summary_id) VALUES
                (71,71,%1$d,%2$d,202,7102), (74,71,%1$d,%2$d,202,NULL),
                (74,71,%1$d,%2$d,202,NULL), (74,NULL,%1$d,%2$d,0,NULL),
                (74,NULL,%1$d,%2$d,NULL,NULL)
                """.formatted(now, stun));
        }

        String scope = "/?range=24h&action=STUN&limit=10";
        Map<String,Object> page = mDatabase.dashboardActivityRadios(request(scope));
        assertEquals(19, number(page.get("action_observation_count")));
        assertEquals(5, number(page.get("retained_observation_count")));
        assertEquals(3, number(page.get("identified_observation_count")));
        assertEquals(2, number(page.get("unknown_source_observation_count")));
        assertEquals(2, number(page.get("total_count")));
        assertEquals(2, rows(page).size());
        Map<String,Object> trunked = rowWith(rows(page), "radio_system_key", RADIO_SYSTEM_KEY);
        Map<String,Object> conventional = rowWith(rows(page), "configuration_id", DMR_CHANNEL);
        assertEquals(1, number(trunked.get("observation_count")),
            "Retained events keep their original system after the receiver channel is reassigned");
        assertEquals(2, number(conventional.get("observation_count")),
            "Each event is counted once despite multiple matching summary buckets");
        assertEquals(202, number(trunked.get("native_id")));
        assertEquals(202, number(conventional.get("native_id")));
        assertEquals("radio", map(trunked, "radio_entity_ref").get("kind"));
        assertEquals("channel", map(conventional, "radio_entity_ref").get("kind"));

        Map<String,Object> beyondEnd = mDatabase.dashboardActivityRadios(request(scope + "&offset=2"));
        assertTrue(rows(beyondEnd).isEmpty());
        assertFalse((Boolean)beyondEnd.get("has_more"));
        assertNull(beyondEnd.get("next_offset"));
        for(String field: List.of("action_observation_count", "retained_observation_count",
            "identified_observation_count", "unknown_source_observation_count", "total_count"))
        {
            assertEquals(page.get(field), beyondEnd.get(field), field);
        }

        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM receiver_activity_event WHERE action_code=" + stun);
        }
        Map<String,Object> summaryOnly = mDatabase.dashboardActivityRadios(request(scope));
        assertEquals(19, number(summaryOnly.get("action_observation_count")));
        assertEquals(0, number(summaryOnly.get("retained_observation_count")));
        assertEquals(0, number(summaryOnly.get("identified_observation_count")));
        assertEquals(0, number(summaryOnly.get("unknown_source_observation_count")));
        assertEquals(0, number(summaryOnly.get("total_count")));
        assertTrue(rows(summaryOnly).isEmpty());
        assertFalse((Boolean)summaryOnly.get("has_more"));
        assertNull(summaryOnly.get("next_offset"));
    }

    @Test
    void radioAliasSortUsesExactAndRangeIndexesWithoutChangingConsensusOrPaging() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id, radio_system_id, identity_kind_code,
                    identity_id, first_seen_ms, last_seen_ms) VALUES
                (7105,71,2,303,1000,4000), (7106,71,2,404,1000,4000)
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value,min_value,max_value) VALUES
                (7102,71,'Middle Unit','RADIO_ID','APCO25',202,NULL,NULL),
                (7202,72,'Middle Unit','RADIO_ID','APCO25_PHASE2',202,NULL,NULL),
                (7103,71,'Alpha Range','RADIO_ID_RANGE','APCO25',NULL,300,350),
                (7203,72,'Alpha Range','RADIO_ID_RANGE','APCO25_PHASE2',NULL,300,350),
                (7104,71,'Conflict A','RADIO_ID','APCO25',404,NULL,NULL),
                (7204,72,'Conflict B','RADIO_ID','APCO25',404,NULL,NULL)
                """);
            var expression = StatsWebDatabase.class.getDeclaredMethod("radioSystemAliasSortExpression",
                String.class, String.class, String.class);
            expression.setAccessible(true);
            String sql = "EXPLAIN QUERY PLAN SELECT summary.id FROM radio_system_identity_summary summary " +
                "JOIN radio_system system ON system.id=summary.radio_system_id WHERE system.id=71 ORDER BY " +
                expression.invoke(null, "alias_radio", "summary.identity_id", "name");
            StringBuilder plan = new StringBuilder();
            try(ResultSet result = statement.executeQuery(sql))
            {
                while(result.next()) plan.append(result.getString("detail")).append('\n');
            }
            assertTrue(plan.toString().contains("idx_alias_radio_value (protocol=? AND value=?)"), plan.toString());
            assertTrue(plan.toString().contains("idx_alias_radio_range"), plan.toString());
        }
        Map<String,Object> first = mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?sort=alias&direction=asc&limit=2"));
        assertEquals(List.of(404L,303L), rows(first).stream().map(row -> number(row.get("native_id"))).toList());
        assertTrue((Boolean)first.get("has_more"));
        Map<String,Object> last = mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?sort=alias&direction=asc&limit=2&offset=2"));
        assertEquals(List.of(202L), rows(last).stream().map(row -> number(row.get("native_id"))).toList());
        assertFalse((Boolean)last.get("has_more"));
    }

    @Test
    void historicalChannelScopedSystemAndObservedGroupKeepTheirOwnerAfterNativePromotion() throws Exception
    {
        String configurationId = "90000000-0000-4000-8000-000000000090";
        String fallbackKey = "dmr:channel:" + configurationId;
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES (90,'Historical DMR aliases','DMR')");
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    configuration_id,channel_kind,sort_order,system_name,site_name,name,
                    alias_list_id,decoder_type,address_domain_code,config_json)
                VALUES ('%1$s','TRUNKED',90,'Historical DMR','Old site','Old receiver',
                    90,'DMR',0,'{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
                """.formatted(configurationId));
            statement.executeUpdate("""
                INSERT INTO radio_system(
                    id,system_key,configuration_id,protocol_code,address_domain_code,first_seen_ms,last_seen_ms)
                VALUES (90,'%1$s','%2$s',3,0,1000,2000)
                """.formatted(fallbackKey, configurationId));
            statement.executeUpdate("""
                INSERT INTO radio_system(
                    id,system_key,protocol_code,address_domain_code,dmr_model_code,dmr_network_id,
                    first_seen_ms,last_seen_ms)
                VALUES (91,'dmr:tier3:small:42',3,0,2,42,2000,3000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(
                    id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,radio_system_assigned_at_ms)
                VALUES (90,'%1$s',1000,3000,91,2000)
                """.formatted(configurationId));
            statement.executeUpdate("""
                INSERT INTO alias(
                    id,alias_list_id,name,matcher_type,protocol,value,min_value,max_value)
                VALUES (9001,90,'Zulu Historical Group','TALKGROUP_RANGE','DMR',NULL,90,91),
                       (9002,90,'Alpha Historical Group','TALKGROUP_RANGE','DMR',NULL,92,93)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(
                    id,radio_system_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms,logical_call_count)
                VALUES (9001,90,1,91,1000,1000,1),
                       (9002,90,1,92,1000,1000,1)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_identity_bucket(
                    radio_system_id,bucket_start_ms,identity_role_code,identity_kind_code,
                    identity_summary_id,logical_call_count)
                VALUES (90,0,1,1,9001,1), (90,0,1,1,9002,1)
                """);
        }

        Map<String,Object> historical = rows(mDatabase.radioSystemDirectory(request("/"))).stream()
            .filter(row -> fallbackKey.equals(row.get("radio_system_key")))
            .findFirst().orElseThrow();
        assertEquals(0, number(historical.get("channels")));
        assertEquals("HISTORICAL", historical.get("assignment_state"));
        assertEquals("Historical DMR", historical.get("system_name"));
        assertEquals("Old receiver", historical.get("channel_names"));
        assertEquals(List.of("Historical DMR aliases"), aliasListNames(historical));

        Map<String,Object> detail = map(mDatabase.radioSystem(fallbackKey), "radio_system");
        assertEquals("Historical DMR", detail.get("system_name"));
        assertEquals(List.of("Historical DMR aliases"), aliasListNames(detail));
        assertTrue(rows(mDatabase.radioSystemChannels(fallbackKey, request("/"))).isEmpty());

        StatsRequest firstAliasPage = request("/?sort=alias&direction=asc&limit=1&offset=0");
        Map<String,Object> firstPage = mDatabase.radioSystemGroupIdentities(fallbackKey, firstAliasPage);
        firstAliasPage.requireFullyConsumed();
        assertEquals("Alpha Historical Group", rows(firstPage).getFirst().get("alias_name"));
        assertEquals(true, firstPage.get("has_more"));
        StatsRequest secondAliasPage = request("/?sort=alias&direction=asc&limit=1&offset=1");
        Map<String,Object> secondPage = mDatabase.radioSystemGroupIdentities(fallbackKey, secondAliasPage);
        secondAliasPage.requireFullyConsumed();
        assertEquals("Zulu Historical Group", rows(secondPage).getFirst().get("alias_name"));
        assertEquals(false, secondPage.get("has_more"));

        List<Map<String,Object>> observed = rows(mDatabase.observedGroupIdentities(90, request("/")));
        assertEquals(2, observed.size(), "Retained fallback history must remain discoverable from its Alias List");
        Map<String,Object> unassigned = mDatabase.unassignedGroupIdentities(90, request("/?limit=100"));
        assertTrue(rows(unassigned).isEmpty(), "Range aliases must not be reported as unassigned");
        assertEquals(0, number(unassigned.get("total_count")));
        Map<String,Object> retained = observed.stream()
            .filter(row -> number(row.get("group_identity_id")) == 91).findFirst().orElseThrow();
        assertEquals(fallbackKey, retained.get("radio_system_key"));
        assertEquals(configurationId, retained.get("configuration_id"));
        assertEquals(90, number(retained.get("channel_id")));
        assertEquals("Historical DMR", retained.get("system_name"));
        assertEquals("Old site", retained.get("channel_names"));
    }

    @Test
    void channelPagesUseConfigurationIdAndKeepRadioResolveCorrelationPrivate()
    {
        Map<String,Object> analog = map(mDatabase.channelDetail(ANALOG_CHANNEL, request("/")), "channel");
        assertEquals(ANALOG_CHANNEL, analog.get("configuration_id"));
        assertEquals("CONVENTIONAL", analog.get("channel_kind"));
        assertEquals("County Fire Dispatch", analog.get("name"));
        assertEquals(Map.of("kind", "channel", "key", ANALOG_CHANNEL), analog.get("entity_ref"));
        assertFalse(analog.containsKey("channel_name"));
        assertFalse(analog.containsKey("configured_name"));
        assertFalse(analog.containsKey("configured_system"));
        assertFalse(analog.containsKey("configured_site"));
        assertFalse(analog.containsKey("radioresolve_id"));
        assertFalse(analog.containsKey("guid"));

        WebEntityNavigationCatalog.Snapshot navigation = mDatabase.webEntityNavigationSnapshot();
        assertEquals(ANALOG_CHANNEL, navigation.channel(ANALOG_CHANNEL).entityRef().key());
        assertNull(navigation.channel("10000000-0000-0000-0000-000000000073"));
    }

    @Test
    void analogChannelsDoNotExposeTheirSyntheticRoutingNumberAsAGroupIdentity()
    {
        assertTrue(rows(mDatabase.channelGroupIdentities(ANALOG_CHANNEL, request("/"))).isEmpty());
        assertTrue(rows(mDatabase.channelRadios(ANALOG_CHANNEL, request("/"))).isEmpty());

        Map<String,Object> detail = mDatabase.channelDetail(ANALOG_CHANNEL, request("/"));
        List<Map<String,Object>> summaries = rowsFrom(detail, "summaries");
        assertEquals(1, summaries.size());
        assertEquals(154_310_000L, number(summaries.getFirst().get("frequency_hz")));
        assertFalse(summaries.getFirst().containsKey("talkgroup_id"));
    }

    @Test
    void p25PatchTelemetryUsesCanonicalLocalFieldsAndNavigableSystemIdentities() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE radio_system_identity_summary SET " +
                "last_talker_alias='Home OTA',last_talker_alias_seen_ms=4000 WHERE id=7102");
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,
                    home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms,
                    last_talker_alias,last_talker_alias_seen_ms)
                VALUES(7199,71,2,0x92498,0x926,202,1000,4000,'Foreign OTA',4000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group(channel_id, local_patch_group_id, version, confirmed_at_ms)
                VALUES (71, 500, 1, 4000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_summary(
                    channel_id, local_patch_group_id, version, first_seen_ms, last_seen_ms, observation_count)
                VALUES (71, 500, 1, 1000, 4000, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_talkgroup(
                    channel_id, local_patch_group_id, local_talkgroup_id, confirmed_at_ms)
                VALUES (71, 500, 101, 4000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_radio(
                    channel_id, local_patch_group_id, local_radio_id, confirmed_at_ms)
                VALUES (71, 500, 202, 4000)
                """);
        }

        Map<String,Object> response = mDatabase.trunkedChannelPatchGroups(P25_CHANNEL_A, request("/"));
        Map<String,Object> patch = rowsFrom(response, "groups").getFirst();
        Map<String,Object> talkgroup = rowsFrom(response, "talkgroups").getFirst();
        Map<String,Object> radio = rowsFrom(response, "radios").getFirst();

        assertEquals(500, number(patch.get("local_patch_group_id")));
        assertEquals(101, number(talkgroup.get("local_talkgroup_id")));
        assertEquals(202, number(radio.get("local_radio_id")));
        assertEquals("Home OTA", radio.get("last_talker_alias"),
            "Protocol-local patch members must not borrow a foreign same-number subscriber's OTA alias");
        assertEquals(4000, number(radio.get("last_talker_alias_seen_ms")));
        assertEquals(Map.of("kind", "patch_group", "radio_system_key", RADIO_SYSTEM_KEY,
            "identity_key", "v1-p-bee00-49f-500"), patch.get("entity_ref"));
        assertEquals(Map.of("kind", "talkgroup", "radio_system_key", RADIO_SYSTEM_KEY,
            "identity_key", "v1-g-bee00-49f-101"), talkgroup.get("entity_ref"));
        assertEquals(Map.of("kind", "radio", "radio_system_key", RADIO_SYSTEM_KEY,
            "identity_key", "v1-r-bee00-49f-202"), radio.get("entity_ref"));
    }

    @Test
    void conventionalDmrGroupIdentityKeepsItsTimeslotAndExactChannelAliasList()
    {
        List<Map<String,Object>> groups = rows(mDatabase.channelGroupIdentities(DMR_CHANNEL, request("/")));
        assertEquals(1, groups.size());
        Map<String,Object> group = groups.getFirst();
        assertEquals(7, number(group.get("native_id")));
        assertEquals(1, number(group.get("group_identity_kind_code")));
        assertEquals(2, number(group.get("timeslot")));
        assertEquals("DMR Dispatch", group.get("alias_name"));
        assertEquals(DMR_CHANNEL, group.get("configuration_id"));
        assertEquals(Map.of("kind", "channel", "key", DMR_CHANNEL), group.get("entity_ref"));
    }

    @Test
    void systemAliasIsBlankOnChannelListConflictAndReturnsAfterAgreement() throws Exception
    {
        Map<String,Object> conflict = rows(mDatabase.radioSystemGroupIdentities(
            RADIO_SYSTEM_KEY, request("/"))).getFirst();
        assertEquals(101, number(conflict.get("native_id")));
        assertNull(conflict.get("alias_name"), "Conflicting channel-owned alias lists must not pick a winner");

        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE alias SET name='Dispatch' WHERE id=7201");
        }

        Map<String,Object> agreed = rows(mDatabase.radioSystemGroupIdentities(
            RADIO_SYSTEM_KEY, request("/"))).getFirst();
        assertEquals("Dispatch", agreed.get("alias_name"));

        String identityKey = p25IdentityKey(RadioSystemIdentityKey.KIND_TALKGROUP, 101);
        Map<String,Object> detail = map(mDatabase.radioSystemGroupIdentity(
            RADIO_SYSTEM_KEY, identityKey),
            "group_identity");
        assertEquals(Map.of("kind", "talkgroup", "radio_system_key", RADIO_SYSTEM_KEY,
            "identity_key", identityKey),
            detail.get("entity_ref"));
        assertEquals(Map.of("kind", "radio_system", "key", RADIO_SYSTEM_KEY),
            detail.get("radio_system_entity_ref"));
    }

    @Test
    void radioSystemMetricsUseTheirDedicatedStartBoundary() throws Exception
    {
        try(Connection connection = openFixture();
            PreparedStatement statement = connection.prepareStatement(
                "UPDATE database_metadata SET value = ? WHERE key = ?"))
        {
            statement.setString(1, "111");
            statement.setString(2, ReceiverActivitySchema.RADIO_SYSTEM_METRICS_STARTED_AT_KEY);
            statement.executeUpdate();
            statement.setString(1, "222");
            statement.setString(2, ReceiverActivitySchema.TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY);
            statement.executeUpdate();
            statement.setString(1, "333");
            statement.setString(2, ReceiverActivitySchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY);
            statement.executeUpdate();
        }

        String identityKey = p25IdentityKey(RadioSystemIdentityKey.KIND_TALKGROUP, 101);
        Map<String,Object> activity = mDatabase.radioSystemGroupIdentityActivity(
            RADIO_SYSTEM_KEY, identityKey, request("/?range=24h"));
        assertEquals(111, number(activity.get("logical_metric_start_ms")));

        Map<String,Object> channelGroups = mDatabase.channelGroupIdentities(
            P25_CHANNEL_A, request("/?range=24h"));
        assertEquals(111, number(channelGroups.get("logical_metric_start_ms")));
    }

    @Test
    void identityPagesStayInsideTheirRadioSystem()
    {
        List<Map<String,Object>> radios = rows(mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY, request("/")));
        assertEquals(1, radios.size());
        assertEquals(202, number(radios.getFirst().get("native_id")));
        assertEquals(RADIO_SYSTEM_KEY, radios.getFirst().get("radio_system_key"));

        String identityKey = p25LocalRadioKey(202);
        Map<String,Object> radio = map(mDatabase.radio(RADIO_SYSTEM_KEY, identityKey), "radio");
        assertEquals("Shared P25", radio.get("system_name"));
        assertFalse(radio.containsKey("last_group_identity_id"));
        assertEquals(Map.of("kind", "radio", "radio_system_key", RADIO_SYSTEM_KEY,
            "identity_key", p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO,202)),
            radio.get("entity_ref"));
    }

    @Test
    void lastEncryptionNamesDoNotRequireCompletedEncryptedCalls() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE radio_system_identity_summary
                SET last_encryption_algorithm_id = 0x84, last_encryption_key_id = 7
                WHERE id IN (7101, 7102)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms, last_encryption_algorithm_id
                ) VALUES (7103, 71, 3, 0xBEE00, 0x49F, 101, 1000, 4000, 0x84)
                """);
        }

        List<Map<String,Object>> summaries = List.of(
            map(mDatabase.radioSystemGroupIdentity(RADIO_SYSTEM_KEY,
                p25IdentityKey(RadioSystemIdentityKey.KIND_TALKGROUP, 101)), "group_identity"),
            map(mDatabase.radioSystemGroupIdentity(RADIO_SYSTEM_KEY,
                p25IdentityKey(RadioSystemIdentityKey.KIND_PATCH_GROUP, 101)), "group_identity"),
            map(mDatabase.radio(RADIO_SYSTEM_KEY, p25LocalRadioKey(202)), "radio"));
        for(Map<String,Object> summary: summaries)
        {
            assertEquals(0, number(summary.get("encrypted_logical_call_count")));
            assertEquals(0x84, number(summary.get("last_encryption_algorithm_id")));
            assertEquals("AES256", summary.get("last_encryption_algorithm_display"));
            assertEquals("AES-256", summary.get("last_encryption_algorithm_name"));
        }
    }

    @Test
    void lastEncryptionNamesUseTheSummaryProtocol() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            seedTrunkedDmrAndNxdn(statement);
            statement.executeUpdate("""
                UPDATE radio_system_identity_summary SET last_encryption_algorithm_id = 1
                WHERE id IN (7801, 7803, 7901, 7903)
                """);
        }

        String dmrSystem = "dmr:channel:" + DMR_TRUNKED_CHANNEL;
        String nxdnSystem = "nxdn-c:channel:" + NXDN_TRUNKED_CHANNEL;
        for(Map<String,Object> summary: List.of(
            map(mDatabase.radioSystemGroupIdentity(dmrSystem,
                RadioSystemIdentityKey.format(1, -1, -1, 7)), "group_identity"),
            map(mDatabase.radio(dmrSystem, RadioSystemIdentityKey.format(2, -1, -1, 501)), "radio")))
        {
            assertEquals(0, number(summary.get("encrypted_logical_call_count")));
            assertEquals("Hytera Basic Privacy", summary.get("last_encryption_algorithm_name"));
        }
        for(Map<String,Object> summary: List.of(
            map(mDatabase.radioSystemGroupIdentity(nxdnSystem,
                RadioSystemIdentityKey.format(1, -1, -1, 9)), "group_identity"),
            map(mDatabase.radio(nxdnSystem, RadioSystemIdentityKey.format(2, -1, -1, 601)), "radio")))
        {
            assertEquals(0, number(summary.get("encrypted_logical_call_count")));
            assertEquals("Scrambler", summary.get("last_encryption_algorithm_name"));
        }
    }

    @Test
    void lastEncryptionNamesPreserveMissingAndUnknownEvidence() throws Exception
    {
        String groupKey = p25IdentityKey(RadioSystemIdentityKey.KIND_TALKGROUP, 101);
        String radioKey = p25LocalRadioKey(202);
        for(Map<String,Object> summary: List.of(
            map(mDatabase.radioSystemGroupIdentity(RADIO_SYSTEM_KEY, groupKey), "group_identity"),
            map(mDatabase.radio(RADIO_SYSTEM_KEY, radioKey), "radio")))
        {
            assertNull(summary.get("last_encryption_algorithm_display"));
            assertNull(summary.get("last_encryption_algorithm_name"));
        }

        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE radio_system_identity_summary SET encrypted_logical_call_count = 1
                WHERE id IN (7101, 7102)
                """);
        }
        for(Map<String,Object> summary: List.of(
            map(mDatabase.radioSystemGroupIdentity(RADIO_SYSTEM_KEY, groupKey), "group_identity"),
            map(mDatabase.radio(RADIO_SYSTEM_KEY, radioKey), "radio")))
        {
            assertEquals("ENC", summary.get("last_encryption_algorithm_display"));
            assertEquals("ENC", summary.get("last_encryption_algorithm_name"));
        }

        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE radio_system_identity_summary
                SET encrypted_logical_call_count = 0, last_encryption_algorithm_id = 0x3C
                WHERE id IN (7101, 7102)
                """);
        }
        for(Map<String,Object> summary: List.of(
            map(mDatabase.radioSystemGroupIdentity(RADIO_SYSTEM_KEY, groupKey), "group_identity"),
            map(mDatabase.radio(RADIO_SYSTEM_KEY, radioKey), "radio")))
        {
            assertEquals(0x3C, number(summary.get("last_encryption_algorithm_id")));
            assertEquals("ALG:3C", summary.get("last_encryption_algorithm_display"));
            assertEquals("ALG:3C", summary.get("last_encryption_algorithm_name"));
        }
    }

    @Test
    void sitePresenceAndSystemSummaryPreferUniqueConfiguredChannelNames() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE configuration_channel
                SET site_name = 'Shared Site',
                    name = CASE configuration_id
                        WHEN '%1$s' THEN 'Zulu Control'
                        WHEN '%2$s' THEN 'Alpha Control'
                    END
                WHERE configuration_id IN ('%1$s', '%2$s')
                """.formatted(P25_CHANNEL_A, P25_CHANNEL_B));
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms, logical_call_count
                ) VALUES (7103, 71, 2, 0xBEE00, 0x49F, 303, 1000, 4000, 1)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence (
                    radio_system_id, radio_identity_id, channel_id, observed_local_id,
                    evidence_code, confirmed_at_ms
                ) VALUES (71, 7102, 71, 202, 1, 4000),
                         (71, 7103, 72, 303, 1, 4000)
                """);
        }

        Map<String,Object> system = map(mDatabase.radioSystem(RADIO_SYSTEM_KEY), "radio_system");
        assertEquals("Alpha Control, Zulu Control", system.get("channel_names"),
            "Shared site labels must not collapse distinct configured channels");
        assertEquals(2, number(system.get("channel_name_count")));

        List<Map<String,Object>> radios = rows(mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?sort=channel&direction=asc")));
        assertEquals(List.of(303L, 202L), radios.stream().map(row -> number(row.get("native_id"))).toList(),
            "Site sorting must follow the prominent configured channel name before RFSS/Site identity");
        assertEquals("Alpha Control", map(map(radios.getFirst(), "presence"), "channel").get("name"));
        assertEquals("Shared Site", map(map(radios.getFirst(), "presence"), "channel").get("site_name"));
    }

    @Test
    void zeroCallRadioAliasOpensItsRadioPage() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (7102, 71, 'Quiet Unit', 'RADIO_ID', 'APCO25', 303),
                       (7202, 72, 'Quiet Unit', 'RADIO_ID', 'APCO25', 303)
                """);
        }

        Map<String,Object> alias = rowWith(rows(mDatabase.publicIdentityAliases(71,
            request("/?type=radio&status=zero_calls&limit=100"))), "name", "Quiet Unit");
        Map<String,Object> destination = map(alias, "entity_ref");
        assertEquals("radio", destination.get("kind"));
        Map<String,Object> radio = map(mDatabase.radio((String)destination.get("radio_system_key"),
            (String)destination.get("identity_key")), "radio");
        assertEquals("Quiet Unit", radio.get("alias_name"));
        assertEquals(0L, radio.get("logical_call_count"));
        assertNull(radio.get("presence"));
    }

    @Test
    void allChannelSubresourcesUseOneSavedChannelIdentity()
    {
        Map<String,Object> detail = map(mDatabase.channelDetail(P25_CHANNEL_A, request("/")), "channel");
        assertEquals(P25_CHANNEL_A, detail.get("configuration_id"));
        assertEquals(RADIO_SYSTEM_KEY, detail.get("radio_system_key"));
        assertEquals(0x49f, number(detail.get("nac")));
        assertEquals(1, number(detail.get("rfss")));
        assertEquals(1, number(detail.get("site_id")));

        assertTrue(rows(mDatabase.channelFrequencies(P25_CHANNEL_A, request("/"))).isEmpty());
        assertTrue(rows(mDatabase.channelNeighbors(P25_CHANNEL_A, request("/"))).isEmpty());
        assertTrue(rows(mDatabase.channelGroupIdentities(P25_CHANNEL_A, request("/"))).isEmpty());
        assertFalse(rowsFrom(mDatabase.channelQuality(P25_CHANNEL_A, request("/")), "channels").isEmpty());
        assertTrue(rows(mDatabase.channelBands(P25_CHANNEL_A, request("/"))).isEmpty());
        assertTrue(rowsFrom(mDatabase.channelPatches(P25_CHANNEL_A, request("/")), "groups").isEmpty());
    }

    @Test
    void trunkedChannelFrequencyMetricMatchesTheFrequencyTable() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_summary (
                    channel_id, channel_key, descriptor, downlink_hz, uplink_hz,
                    tdma, timeslots, first_seen_ms, last_seen_ms, observation_count)
                VALUES (71, '0-1', '0-1', 851012500, 806012500, 0, 1, 1000, 4000, 2),
                       (71, '0-2', '0-2', 851012500, 806012500, 0, 1, 1000, 4000, 1)
                """);
        }

        Map<String,Object> channel = map(mDatabase.channelDetail(P25_CHANNEL_A, request("/")), "channel");
        List<Map<String,Object>> frequencies = rows(mDatabase.channelFrequencies(P25_CHANNEL_A, request("/")));
        assertEquals(1, number(channel.get("frequencies")));
        assertEquals(1, frequencies.size());
        assertEquals(851012500, number(frequencies.getFirst().get("downlink_hz")));
    }

    @Test
    void channelFilteringSortingAndTotalsHappenBeforePagination()
    {
        Map<String,Object> firstPage = mDatabase.channelDirectory(request(
            "/?type=conventional&q=dispatch&sort=name&direction=asc&limit=1"));
        assertEquals(2, number(firstPage.get("total_count")));
        assertTrue((Boolean)firstPage.get("has_more"));
        assertEquals("County Fire Dispatch", rows(firstPage).getFirst().get("name"));

        Map<String,Object> secondPage = mDatabase.channelDirectory(request(
            "/?type=conventional&q=dispatch&sort=name&direction=asc&limit=1&offset=1"));
        assertEquals(2, number(secondPage.get("total_count")));
        assertFalse((Boolean)secondPage.get("has_more"));
        assertEquals("DMR Dispatch Repeater", rows(secondPage).getFirst().get("name"));

        Map<String,Object> systems = mDatabase.radioSystemDirectory(request(
            "/?q=south&sort=channel_names&direction=asc&limit=1"));
        assertEquals(1, number(systems.get("total_count")));
        assertEquals(RADIO_SYSTEM_KEY, rows(systems).getFirst().get("radio_system_key"));
    }

    @Test
    void channelDirectoryIncludesStoredCallCountsAndSortsThemBeforePagination() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_learned_site (
                    learned_site_id, radio_system_id, rfss, site, first_seen_ms, last_seen_ms
                ) VALUES (711, 71, 1, 1, 1000, 4000),
                         (712, 71, 1, 2, 1000, 4000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_call_bucket (
                    radio_system_id, learned_site_id, bucket_start_ms,
                    observed_call_count, encrypted_observed_call_count
                ) VALUES (71, 711, 3600000, 7, 0),
                         (71, 712, 3600000, 2, 0)
                """);
        }

        Map<String,Object> directory = mDatabase.channelDirectory(request(
            "/?sort=logical_call_count&direction=desc&limit=3"));
        assertEquals(4, number(directory.get("total_count")));
        assertTrue((Boolean)directory.get("has_more"));
        assertEquals(List.of(P25_CHANNEL_A, ANALOG_CHANNEL, P25_CHANNEL_B), rows(directory).stream()
            .map(row -> String.valueOf(row.get("configuration_id"))).toList());
        assertEquals(List.of(7L, 5L, 2L), rows(directory).stream()
            .map(row -> number(row.get("logical_call_count"))).toList());

        List<Map<String,Object>> byFrequency = rows(mDatabase.channelDirectory(request(
            "/?sort=frequency&direction=asc")));
        assertEquals(ANALOG_CHANNEL, byFrequency.getFirst().get("configuration_id"));
    }

    @Test
    void csvIgnoresPageControlsButKeepsFiltersAndSortOrder()
    {
        StatsCsvExport export = mDatabase.csvExport("channels", request(
            "/?type=conventional&q=dispatch&sort=name&direction=asc&limit=1&offset=1"));
        String csv = new String(export.content(), StandardCharsets.UTF_8);
        assertEquals(2, export.rowCount());
        assertTrue(csv.contains("County Fire Dispatch"));
        assertTrue(csv.contains("DMR Dispatch Repeater"));
        assertTrue(csv.indexOf("County Fire Dispatch") < csv.indexOf("DMR Dispatch Repeater"));
        assertFalse(csv.contains("North Control"));
    }

    @Test
    void boundedAliasActivityCsvRetainsLiveRelationshipMetrics() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE configuration_channel SET alias_list_id = 71
                WHERE configuration_id = '%s'
                """.formatted(P25_CHANNEL_B));
            statement.executeUpdate("""
                INSERT INTO trunked_radio_group_summary (
                    radio_system_id, radio_identity_id, group_identity_id, group_kind_code,
                    first_seen_ms, last_seen_ms, join_count, logical_call_count
                ) VALUES (71, 7102, 7101, 1, 1000, 4000, 2, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation (
                    radio_system_id, radio_identity_id, talkgroup_identity_id, channel_id,
                    radio_observed_local_id, talkgroup_observed_local_id, confirmed_at_ms)
                VALUES (71, 7102, 7101, 71, 202, 101, 4000)
                """);
        }

        StatsCsvExport export = mDatabase.csvExport("aliases", request("/?list=71&sort=name&direction=asc"));
        String csv = new String(export.content(), 3, export.content().length - 3, StandardCharsets.UTF_8);

        try(CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
            .parse(new StringReader(csv)))
        {
            CSVRecord dispatch = parser.getRecords().stream()
                .filter(row -> "Dispatch".equals(row.get("name"))).findFirst().orElseThrow();
            assertEquals("1", dispatch.get("relationships"));
            assertEquals("1", dispatch.get("join_relationships"));
            assertEquals("1", dispatch.get("current_affiliations"));
        }
    }

    @Test
    void talkgroupAndPatchGroupWithTheSameNumberStayDistinct() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms, logical_call_count
                ) VALUES (7103, 71, 3, 0xBEE00, 0x49F, 101, 1000, 4000, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_group_summary (
                    radio_system_id, radio_identity_id, group_identity_id, group_kind_code,
                    first_seen_ms, last_seen_ms,
                    logical_call_count
                ) VALUES (71, 7102, 7101, 1, 1000, 4000, 3),
                         (71, 7102, 7103, 3, 1000, 4000, 2)
                """);
        }

        List<Map<String,Object>> groups = rows(mDatabase.radioSystemGroupIdentities(
            RADIO_SYSTEM_KEY, request("/?sort=group_identity&direction=asc")));
        assertEquals(List.of(1L, 3L), groups.stream()
            .filter(row -> number(row.get("native_id")) == 101)
            .map(row -> number(row.get("group_identity_kind_code"))).toList());

        String talkgroupKey = p25IdentityKey(RadioSystemIdentityKey.KIND_TALKGROUP, 101);
        String patchGroupKey = p25IdentityKey(RadioSystemIdentityKey.KIND_PATCH_GROUP, 101);
        Map<String,Object> talkgroup = map(mDatabase.radioSystemGroupIdentity(
            RADIO_SYSTEM_KEY, talkgroupKey), "group_identity");
        Map<String,Object> patchGroup = map(mDatabase.radioSystemGroupIdentity(
            RADIO_SYSTEM_KEY, patchGroupKey), "group_identity");
        assertEquals(1, number(talkgroup.get("group_identity_kind_code")));
        assertEquals(3, number(patchGroup.get("group_identity_kind_code")));

        Map<String,Object> talkgroupRelationships = mDatabase.radioSystemRelationships(RADIO_SYSTEM_KEY,
            request("/?group_identity_key=" + talkgroupKey));
        Map<String,Object> patchRelationships = mDatabase.radioSystemRelationships(RADIO_SYSTEM_KEY,
            request("/?group_identity_key=" + patchGroupKey));
        assertEquals(1, number(talkgroupRelationships.get("total_count")));
        assertEquals(1, number(patchRelationships.get("total_count")));
        assertEquals(1, number(rows(talkgroupRelationships).getFirst().get("group_identity_kind_code")));
        assertEquals(3, number(rows(patchRelationships).getFirst().get("group_identity_kind_code")));
    }

    @Test
    void sameNumericIdentitiesStayInsideTheirOwnRadioSystem() throws Exception
    {
        String secondSystemKey = "p25:bee00:4a0";
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list (id, name, family) VALUES (77, 'County C', 'P25')");
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    alias_list_id, decoder_type, primary_frequency_hz, config_json
                ) VALUES ('%1$s', 'TRUNKED', 77, 'Other P25', 'East', 'East Control',
                    77, 'P25_PHASE1', 853012500, '{}')
                """.formatted(SECOND_P25_CHANNEL));
            statement.executeUpdate("""
                INSERT INTO radio_system (
                    id, system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                    first_seen_ms, last_seen_ms
                ) VALUES (77, '%s', 1, 0, 0xBEE00, 0x4A0, 1000, 4000)
                """.formatted(secondSystemKey));
            statement.executeUpdate("""
                INSERT INTO receiver_channel (
                    id, configuration_id, first_seen_ms, last_seen_ms,
                    radio_system_id, radio_system_assigned_at_ms
                ) VALUES (77, '%s', 1000, 4000, 77, 1000)
                """.formatted(SECOND_P25_CHANNEL));
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (
                    channel_id, first_seen_ms, last_seen_ms, observation_count, protocol,
                    nac, rfss, site, primary_frequency_hz, current_control_hz
                ) VALUES (77, 1000, 4000, 4, 'APCO25', 0x4A0, 1, 1, 853012500, 853012500)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms, logical_call_count
                ) VALUES (7701, 77, 1, 0xBEE00, 0x4A0, 101, 1000, 4000, 8),
                         (7702, 77, 2, 0xBEE00, 0x4A0, 202, 1000, 4000, 8)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (7701, 77, 'Other Dispatch', 'TALKGROUP', 'APCO25', 101),
                       (7702, 77, 'Other Radio', 'RADIO_ID', 'APCO25', 202)
                """);
        }

        Map<String,Object> original = rows(mDatabase.radioSystemGroupIdentities(
            RADIO_SYSTEM_KEY, request("/"))).getFirst();
        Map<String,Object> other = rows(mDatabase.radioSystemGroupIdentities(secondSystemKey, request("/"))).getFirst();
        assertEquals(3, number(original.get("logical_call_count")));
        assertEquals(8, number(other.get("logical_call_count")));
        assertEquals("Other Dispatch", other.get("alias_name"));
        assertEquals(RADIO_SYSTEM_KEY, map(mDatabase.radio(RADIO_SYSTEM_KEY,
            p25LocalRadioKey(202)), "radio")
            .get("radio_system_key"));
        assertEquals(secondSystemKey, map(mDatabase.radio(secondSystemKey,
            p25LocalRadioKey(202)), "radio")
            .get("radio_system_key"));
    }

    @Test
    void affiliationAndPresenceFiltersUseTheExactSavedChannel() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation (
                    radio_system_id, radio_identity_id, talkgroup_identity_id, channel_id,
                    radio_observed_local_id, talkgroup_observed_local_id, confirmed_at_ms)
                VALUES (71, 7102, 7101, 71, 202, 101, 4000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence (
                    radio_system_id, radio_identity_id, channel_id, observed_local_id,
                    evidence_code, confirmed_at_ms
                ) VALUES (71, 7102, 71, 202, 2, 4000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_group_summary (
                    radio_system_id, radio_identity_id, group_identity_id, group_kind_code,
                    first_seen_ms, last_seen_ms,
                    logical_call_count
                ) VALUES (71, 7102, 7101, 1, 1000, 4000, 3)
                """);
        }

        Map<String,Object> channelA = mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?configuration_id=" + P25_CHANNEL_A + "&affiliated=true"));
        assertEquals(1, number(channelA.get("total_count")));
        assertEquals("Dispatch", rows(channelA).getFirst().get("affiliated_talkgroup_alias_name"));
        assertEquals(P25_CHANNEL_A,
            map(map(rows(channelA).getFirst(), "presence"), "channel").get("configuration_id"));

        Map<String,Object> radio = map(mDatabase.radio(RADIO_SYSTEM_KEY,
            p25LocalRadioKey(202)), "radio");
        assertEquals("Dispatch", radio.get("affiliated_talkgroup_alias_name"));

        Map<String,Object> channelB = mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?configuration_id=" + P25_CHANNEL_B + "&affiliated=true"));
        assertEquals(0, number(channelB.get("total_count")));
        assertTrue(rows(channelB).isEmpty());

        assertEquals(1, number(mDatabase.radioSystemRelationships(RADIO_SYSTEM_KEY,
            request("/?radio_identity_key=" + p25LocalRadioKey(202) +
                "&configuration_id=" + P25_CHANNEL_A)).get("total_count")));
        assertEquals(0, number(mDatabase.radioSystemRelationships(RADIO_SYSTEM_KEY,
            request("/?radio_identity_key=" + p25LocalRadioKey(202) +
                "&configuration_id=" + P25_CHANNEL_B)).get("total_count")));
    }

    @Test
    void conventionalNxdnZeroActivityKeepsItsAliasSearchAndChannelDrilldown() throws Exception
    {
        conventionalP25AndNxdnExposeRealChannelOwnedGroupsAndRadios();
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value)
                VALUES(7603,76,'Unaddressed Dispatch','TALKGROUP','NXDN',0)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code,event_type_code,
                    target_kind_code,target_observed_local_id)
                VALUES(76,5000,%1$d,7,1,0),(76,6000,%1$d,7,2,0),(76,7000,%1$d,7,NULL,NULL),(75,8000,%1$d,7,1,0)
                """.formatted(ReceiverActivityFilterCatalog.actionCode("CALL")));
        }

        List<Map<String,Object>> found = rows(mDatabase.activity(request("/?configuration_id=" +
            CONVENTIONAL_NXDN_CHANNEL + "&target_id=0&target_kind=talkgroup&q=Unaddressed&from_ms=4000&to_ms=9000")));
        assertEquals(1, found.size());
        assertEquals(0, number(found.getFirst().get("target_id")));
        assertEquals("Unaddressed Dispatch", found.getFirst().get("target_alias_name"));
        assertEquals(Map.of("kind", "channel", "key", CONVENTIONAL_NXDN_CHANNEL),
            found.getFirst().get("target_entity_ref"));
        assertNull(found.getFirst().get("source_entity_ref"));

        List<Map<String,Object>> nxdn = rows(mDatabase.activity(request("/?configuration_id=" +
            CONVENTIONAL_NXDN_CHANNEL + "&from_ms=4000&to_ms=9000")));
        assertNull(nxdn.stream().filter(row -> number(row.get("observed_at_ms")) == 6_000L)
                .findFirst().orElseThrow().get("target_entity_ref"),
            "Radio zero remains an unspecified destination");
        assertNull(nxdn.stream().filter(row -> number(row.get("observed_at_ms")) == 7_000L)
                .findFirst().orElseThrow().get("target_entity_ref"),
            "A missing NXDN group is not converted to Null Group");
        List<Map<String,Object>> p25 = rows(mDatabase.activity(request("/?configuration_id=" +
            CONVENTIONAL_P25_CHANNEL + "&from_ms=4000&to_ms=9000")));
        assertNull(p25.getFirst().get("target_entity_ref"), "The zero exception is specific to NXDN talkgroups");
    }

    @Test
    void conventionalP25AndNxdnExposeRealChannelOwnedGroupsAndRadios() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family)
                VALUES (75, 'P25 Conventional', 'P25'), (76, 'NXDN Conventional', 'NXDN')
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    alias_list_id, decoder_type, address_domain_code, primary_frequency_hz, config_json
                ) VALUES
                    ('%1$s', 'CONVENTIONAL', 75, 'County P25', '', 'P25 Dispatch',
                        75, 'P25_PHASE1', 0, 154875000, '{}'),
                    ('%2$s', 'CONVENTIONAL', 76, 'County NXDN', '', 'NXDN Dispatch',
                        76, 'NXDN', 1, 452125000,
                        '{"decodeConfiguration":{"channelMode":"CONVENTIONAL"}}')
                """.formatted(CONVENTIONAL_P25_CHANNEL, CONVENTIONAL_NXDN_CHANNEL));
            statement.executeUpdate("""
                INSERT INTO receiver_channel (
                    id, configuration_id, first_seen_ms, last_seen_ms,
                    radio_system_id, radio_system_assigned_at_ms
                )
                VALUES (75, '%1$s', 1000, 4000, NULL, NULL),
                       (76, '%2$s', 1000, 4000, NULL, NULL)
                """.formatted(CONVENTIONAL_P25_CHANNEL, CONVENTIONAL_NXDN_CHANNEL));
            statement.executeUpdate("""
                INSERT INTO conventional_call_identity_bucket (
                    channel_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                    call_count, encrypted_count, recorded_count, streamed_count
                ) VALUES
                    (75, 3600000, 1, 1, 42, 4, 1, 2, 3),
                    (75, 3600000, 2, 2, 900, 4, 1, 2, 3),
                    (76, 3600000, 1, 1, 43, 5, 2, 1, 0),
                    (76, 3600000, 1, 1, 0, 2, 0, 1, 1),
                    (76, 3600000, 2, 2, 901, 5, 2, 1, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (7501, 75, 'P25 Group', 'TALKGROUP', 'APCO25', 42),
                       (7502, 75, 'P25 Radio', 'RADIO_ID', 'APCO25', 900),
                       (7601, 76, 'NXDN Group', 'TALKGROUP', 'NXDN', 43),
                       (7602, 76, 'NXDN Radio', 'RADIO_ID', 'NXDN', 901)
                """);
        }

        Map<String,Object> p25Group = rows(mDatabase.channelGroupIdentities(
            CONVENTIONAL_P25_CHANNEL, request("/"))).getFirst();
        assertEquals(42, number(p25Group.get("native_id")));
        assertEquals("P25 Group", p25Group.get("alias_name"));
        assertEquals(CONVENTIONAL_P25_CHANNEL, p25Group.get("configuration_id"));
        assertEquals("groups", p25Group.get("entity_tab"));

        List<Map<String,Object>> nxdnGroups = rows(mDatabase.channelGroupIdentities(
            CONVENTIONAL_NXDN_CHANNEL, request("/")));
        Map<String,Object> nxdnGroup = nxdnGroups.getFirst();
        assertEquals(43, number(nxdnGroup.get("native_id")));
        assertEquals("NXDN Group", nxdnGroup.get("alias_name"));
        assertEquals(CONVENTIONAL_NXDN_CHANNEL, nxdnGroup.get("configuration_id"));
        Map<String,Object> nullGroup = nxdnGroups.stream()
            .filter(row -> number(row.get("native_id")) == 0).findFirst().orElseThrow();
        assertEquals(1, number(nullGroup.get("group_identity_kind_code")));
        assertEquals(2, number(nullGroup.get("logical_call_count")));
        assertEquals(1, number(nullGroup.get("recorded_logical_call_count")));
        assertEquals(1, number(nullGroup.get("stream_submitted_logical_call_count")));
        assertEquals(4, number(nullGroup.get("protocol_code")));

        Map<String,Object> p25Radio = rows(mDatabase.channelRadios(
            CONVENTIONAL_P25_CHANNEL, request("/"))).getFirst();
        Map<String,Object> nxdnRadio = rows(mDatabase.channelRadios(
            CONVENTIONAL_NXDN_CHANNEL, request("/"))).getFirst();
        assertEquals(900, number(p25Radio.get("native_id")));
        assertEquals("P25 Radio", p25Radio.get("alias_name"));
        assertEquals(901, number(nxdnRadio.get("native_id")));
        assertEquals("NXDN Radio", nxdnRadio.get("alias_name"));
    }

    @Test
    void trunkedChannelIdentityPagesAreChannelScopedFilteredSortedAndPaged() throws Exception
    {
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = openFixture();
            PreparedStatement calls = connection.prepareStatement("""
                INSERT INTO p25_site_call_identity_bucket (
                    radio_system_id, learned_site_id, channel_id, bucket_start_ms, identity_role_code,
                    identity_kind_code, identity_summary_id, observed_local_id, last_observed_at_ms,
                    observed_call_count, encrypted_observed_call_count, observed_working_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_learned_site (
                    learned_site_id, radio_system_id, rfss, site, first_seen_ms, last_seen_ms
                ) VALUES (710, 71, 1, 1, 1000, 4000), (720, 71, 1, 2, 1000, 4000)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (7112, 71, 'Alpha Group', 'TALKGROUP', 'APCO25', 102),
                       (7113, 71, 'Zulu Group', 'TALKGROUP', 'APCO25', 103),
                       (7131, 71, 'Alpha Radio', 'RADIO_ID', 'APCO25', 301),
                       (7132, 71, 'Zulu Radio', 'RADIO_ID', 'APCO25', 302),
                       (7299, 72, 'Other Site Only', 'TALKGROUP', 'APCO25', 999)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_subscriber_identity (
                    id, home_wacn, home_system_id, subscriber_id)
                VALUES (9131, 0x92498, 0x926, 9601699)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms, p25_subscriber_identity_id)
                VALUES (7112, 71, 1, 0xBEE00, 0x49F, 102, 1000, 4000, NULL),
                       (7113, 71, 1, 0xBEE00, 0x49F, 103, 1000, 4000, NULL),
                       (7131, 71, 2, 0x92498, 0x926, 9601699, 1000, 4000, 9131),
                       (7132, 71, 2, 0xBEE00, 0x49F, 302, 1000, 4000, NULL),
                       (7191, 71, 1, 0xBEE00, 0x49F, 999, 1000, 4000, NULL),
                       (7192, 71, 2, 0xBEE00, 0x49F, 999, 1000, 4000, NULL)
                """);
            insertP25ChannelIdentity(calls, bucket, 710, 71, 1, 1, 7112, 102, null, 2);
            insertP25ChannelIdentity(calls, bucket, 710, 71, 1, 1, 7113, 103, null, 3);
            insertP25ChannelIdentity(calls, bucket, 710, 71, 2, 2, 7131, 301, 301, 2);
            insertP25ChannelIdentity(calls, bucket, 710, 71, 2, 2, 7132, 302, null, 3);
            insertP25ChannelIdentity(calls, bucket, 720, 72, 1, 1, 7191, 999, null, 9);
            insertP25ChannelIdentity(calls, bucket, 720, 72, 2, 2, 7192, 999, null, 9);
        }

        StatsRequest firstGroupRequest = request("/?sort=alias&direction=asc&range=24h&limit=1&offset=0");
        Map<String,Object> firstGroup = mDatabase.channelGroupIdentities(P25_CHANNEL_A, firstGroupRequest);
        firstGroupRequest.requireFullyConsumed();
        assertEquals("Alpha Group", rows(firstGroup).getFirst().get("alias_name"));
        assertEquals(P25_CHANNEL_A, rows(firstGroup).getFirst().get("configuration_id"));
        assertEquals(1, number(firstGroup.get("limit")));
        assertEquals(0, number(firstGroup.get("offset")));
        assertEquals(true, firstGroup.get("has_more"));
        assertEquals(1, number(firstGroup.get("next_offset")));
        assertEquals(Math.floorDiv(number(firstGroup.get("to_ms")),3_600_000L) * 3_600_000L -
            23 * 3_600_000L,number(firstGroup.get("from_ms")),
            "reported coverage must use the same 24 complete/current hour buckets as the query");

        StatsRequest secondGroupRequest = request("/?sort=alias&direction=asc&range=24h&limit=1&offset=1");
        Map<String,Object> secondGroup = mDatabase.channelGroupIdentities(P25_CHANNEL_A, secondGroupRequest);
        secondGroupRequest.requireFullyConsumed();
        assertEquals("Zulu Group", rows(secondGroup).getFirst().get("alias_name"));
        assertEquals(false, secondGroup.get("has_more"));

        StatsRequest groupSearch = request("/?q=zulu&sort=group_identity&direction=desc&range=24h");
        Map<String,Object> matchingGroup = mDatabase.channelGroupIdentities(P25_CHANNEL_A, groupSearch);
        groupSearch.requireFullyConsumed();
        assertEquals(List.of(103L), rows(matchingGroup).stream()
            .map(row -> number(row.get("native_id"))).toList());

        StatsRequest radioRequest = request("/?q=radio&sort=alias&direction=asc&limit=1&offset=0");
        Map<String,Object> firstRadio = mDatabase.channelRadios(P25_CHANNEL_A, radioRequest);
        radioRequest.requireFullyConsumed();
        assertEquals("Alpha Radio", rows(firstRadio).getFirst().get("alias_name"));
        assertEquals(0x92498, number(rows(firstRadio).getFirst().get("canonical_wacn")));
        assertEquals(9_601_699, number(rows(firstRadio).getFirst().get("canonical_subscriber_id")));
        assertEquals(301, number(rows(firstRadio).getFirst().get("observed_working_id")));
        assertEquals(true, firstRadio.get("has_more"));
        assertEquals(1, number(firstRadio.get("next_offset")));
        assertTrue(rows(firstRadio).stream().noneMatch(row -> number(row.get("native_id")) == 999));

        StatsRequest otherSite = request("/?q=999&sort=radio&direction=asc");
        assertTrue(rows(mDatabase.channelRadios(P25_CHANNEL_A, otherSite)).isEmpty());
        otherSite.requireFullyConsumed();
    }

    @Test
    void trunkedChannelRadiosExposeAndSearchOnlyTheirOwnedOtaAliases() throws Exception
    {
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            seedTrunkedDmrAndNxdn(statement);
            statement.executeUpdate("""
                INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id)
                VALUES(9131,0x92498,0x926,202)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,
                    home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms,p25_subscriber_identity_id)
                VALUES(7131,71,2,0x92498,0x926,202,1000,4000,9131)
                """);
            statement.executeUpdate("""
                UPDATE radio_system_identity_summary SET last_talker_alias=CASE id
                    WHEN 7102 THEN 'Local OTA' WHEN 7131 THEN 'Foreign OTA'
                    WHEN 7803 THEN 'DMR OTA' WHEN 7903 THEN 'NXDN OTA' END,
                    last_talker_alias_seen_ms=4000 WHERE id IN(7102,7131,7803,7903)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_learned_site(learned_site_id,radio_system_id,rfss,site,first_seen_ms,last_seen_ms)
                VALUES(710,71,1,1,1000,4000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_call_identity_bucket(radio_system_id,learned_site_id,channel_id,
                    bucket_start_ms,identity_role_code,identity_kind_code,identity_summary_id,
                    observed_local_id,last_observed_at_ms,observed_call_count,observed_working_id)
                VALUES(71,710,71,%1$d,2,2,7102,202,%1$d+1,2,NULL),
                      (71,710,71,%1$d,2,2,7131,303,%1$d+1,3,303)
                """.formatted(bucket));
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_identity_bucket(radio_system_id,bucket_start_ms,
                    identity_role_code,identity_kind_code,identity_summary_id,logical_call_count)
                VALUES(78,%1$d,2,2,7803,2),(79,%1$d,2,2,7903,2)
                """.formatted(bucket));
        }
        Map<String,Object> foreign = rows(mDatabase.channelRadios(P25_CHANNEL_A,
            request("/?q=Foreign%20OTA&limit=1"))).getFirst();
        assertEquals("Foreign OTA", foreign.get("last_talker_alias"));
        assertEquals(4000, number(foreign.get("last_talker_alias_seen_ms")));
        assertEquals(202, number(foreign.get("canonical_subscriber_id")));
        assertEquals(303, number(foreign.get("observed_working_id")));
        assertEquals("v1-r-92498-926-202", foreign.get("identity_key"));
        assertEquals("Local OTA", rows(mDatabase.channelRadios(P25_CHANNEL_A,
            request("/?q=Local%20OTA&limit=1"))).getFirst().get("last_talker_alias"));
        assertTrue(rows(mDatabase.channelRadios(P25_CHANNEL_B, request("/?q=Foreign%20OTA"))).isEmpty());
        for(var selected: Map.of(DMR_TRUNKED_CHANNEL, "DMR OTA", NXDN_TRUNKED_CHANNEL, "NXDN OTA").entrySet())
        {
            List<Map<String,Object>> radios = rows(mDatabase.channelRadios(selected.getKey(),
                request("/?q=" + selected.getValue().replace(" ", "%20") + "&limit=1")));
            assertEquals(1, radios.size());
            assertEquals(selected.getValue(), radios.getFirst().get("last_talker_alias"));
            assertEquals(4000, number(radios.getFirst().get("last_talker_alias_seen_ms")));
        }
    }

    @Test
    void trunkedDmrAndNxdnChannelIdentityPagesUseTheSameQueryContract() throws Exception
    {
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = openFixture();
            PreparedStatement calls = connection.prepareStatement("""
                INSERT INTO trunked_logical_call_identity_bucket (
                    radio_system_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_summary_id,
                    logical_call_count, encrypted_logical_call_count, recorded_output_count, streamed_output_count
                ) VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0)
                """);
            Statement statement = connection.createStatement())
        {
            seedTrunkedDmrAndNxdn(statement);
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (7801, 78, 'Alpha DMR Group', 'TALKGROUP', 'DMR', 7),
                       (7802, 78, 'Zulu DMR Group', 'TALKGROUP', 'DMR', 8),
                       (7803, 78, 'Alpha DMR Radio', 'RADIO_ID', 'DMR', 501),
                       (7804, 78, 'Zulu DMR Radio', 'RADIO_ID', 'DMR', 502),
                       (7901, 79, 'Alpha NXDN Group', 'TALKGROUP', 'NXDN', 9),
                       (7902, 79, 'Zulu NXDN Group', 'TALKGROUP', 'NXDN', 10),
                       (7903, 79, 'Alpha NXDN Radio', 'RADIO_ID', 'NXDN', 601),
                       (7904, 79, 'Zulu NXDN Radio', 'RADIO_ID', 'NXDN', 602)
                """);
            for(int[] identity: List.of(
                new int[]{78, 1, 7801, 7}, new int[]{78, 1, 7802, 8},
                new int[]{78, 2, 7803, 501}, new int[]{78, 2, 7804, 502},
                new int[]{79, 1, 7901, 9}, new int[]{79, 1, 7902, 10},
                new int[]{79, 2, 7903, 601}, new int[]{79, 2, 7904, 602}))
            {
                calls.setInt(1, identity[0]);
                calls.setLong(2, bucket);
                calls.setInt(3, identity[1]);
                calls.setInt(4, identity[1]);
                calls.setInt(5, identity[2]);
                calls.setInt(6, identity[3] % 5 + 1);
                calls.executeUpdate();
            }
        }

        for(String configurationId: List.of(DMR_TRUNKED_CHANNEL, NXDN_TRUNKED_CHANNEL))
        {
            Map<String,Object> channel = map(mDatabase.channelDetail(configurationId, request("/")), "channel");
            assertEquals(true, map(channel, "capabilities").get("group_identities"), configurationId);
            StatsRequest groupsRequest = request("/?q=alpha&sort=alias&direction=asc&range=24h&limit=1");
            Map<String,Object> groups = mDatabase.channelGroupIdentities(configurationId, groupsRequest);
            groupsRequest.requireFullyConsumed();
            assertEquals(1, rows(groups).size(), configurationId);
            assertTrue(String.valueOf(rows(groups).getFirst().get("alias_name")).startsWith("Alpha"));

            StatsRequest pageRequest = request("/?sort=alias&direction=asc&range=24h&limit=1");
            Map<String,Object> page = mDatabase.channelGroupIdentities(configurationId, pageRequest);
            pageRequest.requireFullyConsumed();
            assertEquals(true, page.get("has_more"), configurationId);
            assertEquals(1, number(page.get("next_offset")), configurationId);

            StatsRequest radiosRequest = request("/?q=alpha&sort=alias&direction=asc&limit=1");
            Map<String,Object> radios = mDatabase.channelRadios(configurationId, radiosRequest);
            radiosRequest.requireFullyConsumed();
            assertEquals(1, rows(radios).size(), configurationId);
            assertTrue(String.valueOf(rows(radios).getFirst().get("alias_name")).startsWith("Alpha"));

            StatsRequest radioPageRequest = request("/?sort=alias&direction=asc&limit=1");
            Map<String,Object> radioPage = mDatabase.channelRadios(configurationId, radioPageRequest);
            radioPageRequest.requireFullyConsumed();
            assertEquals(true, radioPage.get("has_more"), configurationId);
            assertEquals(1, number(radioPage.get("next_offset")), configurationId);
        }
    }

    @Test
    void trunkedNeighborPagesKeepDmrModelAndNxdnLocationCategorySeparate() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("PRAGMA foreign_keys=ON");
            seedTrunkedDmrAndNxdn(statement);
            statement.executeUpdate("""
                INSERT INTO trunked_site_neighbor_summary (
                    channel_id, protocol_code, variant_code, dmr_model_code,
                    nxdn_location_category_code, network_id, system_id, site_id,
                    channel_number, frequency_hz, status_flags, first_seen_ms, last_seen_ms,
                    observation_count
                ) VALUES
                    (78, 3, 1, 2, 0, 42, -1, 7, 12, 451012500, 1, 1000, 4000, 2),
                    (79, 4, 1, 0, 3, 4, 303, 9, 20, 155012500, 1, 1000, 4000, 2)
                """);
        }

        Map<String,Object> dmr = rows(mDatabase.trunkedChannelNeighbors(DMR_TRUNKED_CHANNEL,
            request("/"))).getFirst();
        assertEquals(3, number(dmr.get("protocol_code")));
        assertEquals(2, number(dmr.get("dmr_model_code")));
        assertNull(dmr.get("nxdn_location_category_code"));
        JsonNode presentedDmr = StatsApiV1Payload.present(dmr);
        assertEquals("small", presentedDmr.path("model").textValue());
        assertFalse(presentedDmr.has("location_category"));

        Map<String,Object> nxdn = rows(mDatabase.trunkedChannelNeighbors(NXDN_TRUNKED_CHANNEL,
            request("/"))).getFirst();
        assertEquals(4, number(nxdn.get("protocol_code")));
        assertEquals(3, number(nxdn.get("nxdn_location_category_code")));
        assertNull(nxdn.get("dmr_model_code"));
        JsonNode presentedNxdn = StatsApiV1Payload.present(nxdn);
        assertEquals("local", presentedNxdn.path("location_category").textValue());
        assertFalse(presentedNxdn.has("model"));
    }

    @Test
    void conventionalIdentityPagesShareFilterSortPagingAndRejectRange() throws Exception
    {
        conventionalP25AndNxdnExposeRealChannelOwnedGroupsAndRadios();

        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO conventional_call_identity_bucket (
                    channel_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                    call_count, encrypted_count, recorded_count, streamed_count
                ) VALUES
                    (75, 7200000, 1, 1, 44, 2, 0, 0, 0),
                    (75, 7200000, 2, 2, 902, 2, 0, 0, 0),
                    (76, 7200000, 1, 1, 45, 2, 0, 0, 0),
                    (76, 7200000, 2, 2, 903, 2, 0, 0, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (7503, 75, 'Alpha P25 Group', 'TALKGROUP', 'APCO25', 44),
                       (7504, 75, 'Alpha P25 Radio', 'RADIO_ID', 'APCO25', 902),
                       (7603, 76, 'Alpha NXDN Group', 'TALKGROUP', 'NXDN', 45),
                       (7604, 76, 'Alpha NXDN Radio', 'RADIO_ID', 'NXDN', 903),
                       (7402, 74, 'Alpha DMR Group', 'TALKGROUP', 'DMR', 8),
                       (7403, 74, 'Alpha DMR Radio', 'RADIO_ID', 'DMR', 501),
                       (7404, 74, 'Zulu DMR Radio', 'RADIO_ID', 'DMR', 502)
                """);
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_talkgroup_summary (
                    channel_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms,
                    call_count, encrypted_count
                ) VALUES (74, 460012500, 1, 8, 1000, 5000, 2, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_radio_summary (
                    channel_id, frequency_hz, timeslot, radio_id, first_seen_ms, last_seen_ms,
                    call_count, source_call_count, target_call_count, group_call_count,
                    private_call_count, encrypted_count
                ) VALUES (74, 460012500, 1, 501, 1000, 5000, 2, 2, 0, 2, 0, 0),
                         (74, 460012500, 2, 502, 1000, 4000, 3, 3, 0, 3, 0, 0)
                """);
        }

        for(String configurationId: List.of(CONVENTIONAL_P25_CHANNEL, CONVENTIONAL_NXDN_CHANNEL, DMR_CHANNEL))
        {
            StatsRequest groupsRequest = request("/?q=alpha&sort=alias&direction=asc&limit=1&offset=0");
            Map<String,Object> groups = mDatabase.channelGroupIdentities(configurationId, groupsRequest);
            groupsRequest.requireFullyConsumed();
            assertEquals(1, rows(groups).size(), configurationId);
            assertTrue(String.valueOf(rows(groups).getFirst().get("alias_name")).startsWith("Alpha"),
                configurationId);

            StatsRequest allGroupsRequest = request("/?sort=alias&direction=asc&limit=1&offset=0");
            Map<String,Object> allGroups = mDatabase.channelGroupIdentities(configurationId, allGroupsRequest);
            allGroupsRequest.requireFullyConsumed();
            assertEquals(true, allGroups.get("has_more"), configurationId);
            assertEquals(1, number(allGroups.get("next_offset")), configurationId);

            StatsRequest radiosRequest = request("/?q=alpha&sort=alias&direction=asc&limit=1&offset=0");
            Map<String,Object> radios = mDatabase.channelRadios(configurationId, radiosRequest);
            radiosRequest.requireFullyConsumed();
            assertEquals(1, rows(radios).size(), configurationId);
            assertTrue(String.valueOf(rows(radios).getFirst().get("alias_name")).startsWith("Alpha"),
                configurationId);

            StatsRequest radioPageRequest = request("/?sort=alias&direction=asc&limit=1");
            Map<String,Object> radioPage = mDatabase.channelRadios(configurationId, radioPageRequest);
            radioPageRequest.requireFullyConsumed();
            assertEquals(true, radioPage.get("has_more"), configurationId);
            assertEquals(1, number(radioPage.get("next_offset")), configurationId);
        }

        StatsApiException unsupportedRange = assertThrows(StatsApiException.class,
            () -> mDatabase.channelGroupIdentities(CONVENTIONAL_P25_CHANNEL, request("/?range=24h")));
        assertEquals("range", unsupportedRange.field());
        assertEquals("invalid_parameter", unsupportedRange.code());
    }

    @Test
    void unassignedP25GroupsReuseExactCoverageAndDeduplicateTheirProtocolRanges() throws Exception
    {
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement();
            PreparedStatement calls = connection.prepareStatement("""
                INSERT INTO p25_site_call_identity_bucket (
                    radio_system_id, learned_site_id, channel_id, bucket_start_ms, identity_role_code,
                    identity_kind_code, identity_summary_id, observed_local_id, last_observed_at_ms,
                    observed_call_count, encrypted_observed_call_count, observed_working_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """))
        {
            statement.executeUpdate("UPDATE configuration_channel SET alias_list_id=71 WHERE sort_order=72");
            statement.executeUpdate("""
                INSERT INTO p25_learned_site (
                    learned_site_id, radio_system_id, rfss, site, first_seen_ms, last_seen_ms
                ) VALUES (710,71,1,1,1000,4000),(720,71,1,2,1000,4000)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id,radio_system_id,identity_kind_code,home_wacn,home_system_id,identity_id,
                    first_seen_ms,last_seen_ms)
                VALUES (7112,71,1,0xBEE00,0x49F,102,1000,4000),
                       (7113,71,1,0xBEE00,0x49F,103,1000,4000),
                       (7114,71,3,0xBEE00,0x49F,103,1000,4000),
                       (7115,71,1,0xBEE00,0x49F,104,1000,4000),
                       (7116,71,1,0xBEE00,0x49F,105,1000,4000)
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value,min_value,max_value)
                VALUES (7112,71,'Phase 2 exact','TALKGROUP','APCO25_PHASE2',102,NULL,NULL),
                       (7113,71,'Phase 2 range','TALKGROUP_RANGE','APCO25_PHASE2',NULL,104,105),
                       (7114,72,'Other list','TALKGROUP_RANGE','APCO25',NULL,103,104),
                       (7115,71,'Other protocol','TALKGROUP_RANGE','DMR',NULL,103,104),
                       (7116,71,'Same bounds phase 1','TALKGROUP_RANGE','APCO25',NULL,104,105),
                       (7117,71,'Same bounds phase 2','TALKGROUP_RANGE','APCO25_PHASE2',NULL,104,105)
                """);
            insertP25ChannelIdentity(calls,bucket,710,71,1,1,7101,101,null,1);
            insertP25ChannelIdentity(calls,bucket,710,71,1,1,7112,102,null,1);
            insertP25ChannelIdentity(calls,bucket,710,71,1,1,7113,103,null,1);
            insertP25ChannelIdentity(calls,bucket,720,72,1,1,7113,103,null,1);
            insertP25ChannelIdentity(calls,bucket,710,71,1,3,7114,103,null,1);
            insertP25ChannelIdentity(calls,bucket,710,71,1,1,7115,104,null,1);
            insertP25ChannelIdentity(calls,bucket,710,71,1,1,7116,105,null,1);
        }
        StatsWebDatabase.ObservedGroupIdentityQuery[] query = new StatsWebDatabase.ObservedGroupIdentityQuery[1];
        var result = mDatabase.observedGroupIdentities(71,
            request("/?include_exact=true&limit=100&sort=group_identity&direction=asc"), value -> query[0]=value, true);
        assertEquals(3,number(result.get("total_count")));
        assertEquals(List.of(103L,103L,103L),rows(result).stream()
            .map(row -> number(row.get("group_identity_id"))).toList());
        assertEquals(Set.of(P25_CHANNEL_A,P25_CHANNEL_B),rows(result).stream()
            .map(row -> row.get("configuration_id")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1,rows(result).stream().filter(row -> number(row.get("group_identity_kind_code"))==3).count());
        var all = mDatabase.unassignedGroupIdentities(71,request("/?include_exact=true&limit=100&sort=group_identity&direction=asc"));
        var second = mDatabase.unassignedGroupIdentities(71,request("/?include_exact=true&limit=1&offset=1&sort=group_identity&direction=asc"));
        var expected = new LinkedHashMap<>(rows(all).get(1));
        var actual = new LinkedHashMap<>(rows(second).getFirst());
        expected.remove("counts_as_of_ms");
        actual.remove("counts_as_of_ms");
        assertEquals(expected,actual);
        assertEquals(3,number(second.get("total_count")));
        assertEquals(3,number(mDatabase.unassignedGroupIdentities(71,request("/?q=103")).get("total_count")));
        assertEquals(0,number(mDatabase.unassignedGroupIdentities(71,request("/?q=missing")).get("total_count")));
        assertTrue(rows(mDatabase.unassignedGroupIdentities(71,request("/?offset=20"))).isEmpty());
        try(Connection connection = openFixture())
        {
            List<String> plan = explain(connection,query[0].sql(),query[0].parameters().toArray());
            assertTrue(plan.stream().anyMatch(detail -> detail.contains(
                "idx_alias_activity_talkgroup_range (alias_list_id=? AND protocol=?")),plan.toString());
            assertFalse(plan.stream().anyMatch(detail -> detail.contains("MULTI-INDEX OR")),plan.toString());
        }
    }

    @Test
    void unassignedRangesKeepEachFamiliesExactAliasesAndInclusiveBounds() throws Exception
    {
        for(String family : List.of("P25", "DMR", "NXDN"))
        {
            int id = switch(family) { case "P25" -> 91; case "DMR" -> 92; default -> 93; };
            String protocol = "P25".equals(family) ? "APCO25_PHASE2" : family;
            String other = "DMR".equals(family) ? "NXDN" : "DMR";
            String configuration = "90000000-0000-4000-8000-0000000000" + id;
            try(Connection connection = openFixture();
                Statement statement = connection.createStatement())
            {
                statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(" + id + ",'" + family + " ranges','" + family + "')");
                statement.executeUpdate("INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order," +
                    "name,alias_list_id,decoder_type,address_domain_code,primary_frequency_hz,config_json) VALUES('" + configuration +
                    "','CONVENTIONAL'," + id + ",'Range receiver'," + id + ",'" +
                    ("P25".equals(family) ? "P25_PHASE1" : family) + "'," + ("NXDN".equals(family) ? 1 : 0) +
                    ",460012500,'{\"decodeConfiguration\":{\"channelMode\":\"CONVENTIONAL\"}}')");
                statement.executeUpdate("INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms) " +
                    "VALUES(" + id + ",'" + configuration + "',1000,2000)");
                for(int group = 11; group <= 17; group++)
                {
                    statement.executeUpdate("DMR".equals(family) ?
                        "INSERT INTO dmr_conventional_talkgroup_summary(channel_id,frequency_hz,timeslot,talkgroup_id," +
                            "first_seen_ms,last_seen_ms,call_count,encrypted_count) VALUES(" + id +
                            ",460012500,1," + group + ",1000,2000,1,0)" :
                        "INSERT INTO conventional_call_identity_bucket(channel_id,bucket_start_ms,identity_role_code," +
                            "identity_kind_code,identity_id,call_count) VALUES(" + id + ",0,1,1," + group + ",1)");
                }
                statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) VALUES(" +
                    id + ",'Exact','TALKGROUP','" + protocol + "',11)");
                statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,min_value,max_value) VALUES" +
                    "(" + id + ",'Range','TALKGROUP_RANGE','" + protocol + "',12,14)," +
                    "(" + id + ",'Duplicate','TALKGROUP_RANGE','" + protocol + "',12,14)," +
                    "(" + id + ",'Other protocol','TALKGROUP_RANGE','" + other + "',15,17)," +
                    "(72,'Other list','TALKGROUP_RANGE','" + protocol + "',15,17)," +
                    "(" + id + ",'Radio range','RADIO_ID_RANGE','" + protocol + "',15,17)");
            }
            var result = mDatabase.unassignedGroupIdentities(id,
                request("/?include_exact=true&limit=100&sort=group_identity&direction=asc"));
            assertEquals(3,number(result.get("total_count")),family);
            assertEquals(List.of(15L,16L,17L),rows(result).stream()
                .map(row -> number(row.get("group_identity_id"))).toList(),family);
        }
    }

    @Test
    void unassignedRangeCoverageKeepsNullInvertedAndBoundarySqlSemantics() throws Exception
    {
        StatsWebDatabase.ObservedGroupIdentityQuery[] captured = new StatsWebDatabase.ObservedGroupIdentityQuery[1];
        mDatabase.observedGroupIdentities(71,request("/?limit=1"),query -> captured[0] = query,true);
        String sql = captured[0].sql();
        int select = sql.indexOf(" SELECT observed.*");
        String coverage = sql.substring(sql.indexOf(", unassigned_ranges AS MATERIALIZED"),select);
        String exclusion = sql.substring(sql.indexOf(" AND NOT EXISTS (",select),sql.indexOf(" ORDER BY",select));
        // VALUES exercise SQL truth without admitting invalid bounds into the current persisted schema.
        String facts = """
            WITH alias(alias_list_id,matcher_type,protocol,min_value,max_value) AS (
                VALUES (71,'TALKGROUP_RANGE','APCO25',10,20),
                       (71,'TALKGROUP_RANGE','APCO25_PHASE2',10,20),
                       (71,'TALKGROUP_RANGE','APCO25',NULL,25),
                       (71,'TALKGROUP_RANGE','APCO25',5,NULL),
                       (71,'TALKGROUP_RANGE','APCO25',25,5),
                       (71,'TALKGROUP_RANGE','APCO25',30,30),
                       (71,'TALKGROUP_RANGE','DMR',1,40),
                       (72,'TALKGROUP_RANGE','APCO25',1,40)
            ), observed(group_identity_id) AS (VALUES(NULL),(9),(10),(15),(20),(21),(30))
            """;
        try(Connection connection = openFixture();
            PreparedStatement statement = connection.prepareStatement(facts + coverage +
                " SELECT group_identity_id FROM observed WHERE 1=1" + exclusion + " ORDER BY group_identity_id"))
        {
            statement.setLong(1,71);
            try(ResultSet result = statement.executeQuery())
            {
                List<Long> missing = new ArrayList<>();
                while(result.next()) { long group = result.getLong(1); missing.add(result.wasNull() ? null : group); }
                assertEquals(java.util.Arrays.asList(null,9L,21L),missing);
            }
        }
    }

    @Test
    void unassignedDuplicateRangesDoNotMultiplyCoverageWork() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order," +
                "name,alias_list_id,decoder_type,config_json) VALUES('" + CONVENTIONAL_P25_CHANNEL +
                "','CONVENTIONAL',75,'P25 receiver',71,'P25_PHASE1','{}')");
            statement.executeUpdate("INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms) " +
                "VALUES(75,'" + CONVENTIONAL_P25_CHANNEL + "',1000,2000)");
            statement.executeUpdate("""
                WITH RECURSIVE groups(value) AS (VALUES(3001) UNION ALL SELECT value+1 FROM groups WHERE value<3100)
                INSERT INTO conventional_call_identity_bucket(channel_id,bucket_start_ms,identity_role_code,
                    identity_kind_code,identity_id,call_count) SELECT 75,0,1,1,value,1 FROM groups
                """);
            statement.executeUpdate("""
                WITH RECURSIVE ranges(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM ranges WHERE value<5000)
                INSERT INTO alias(alias_list_id,name,matcher_type,protocol,min_value,max_value)
                SELECT 71,'Repeated range '||value,'TALKGROUP_RANGE','APCO25',1,3000 FROM ranges
                """);
        }
        StatsWebDatabase.ObservedGroupIdentityQuery[] captured = new StatsWebDatabase.ObservedGroupIdentityQuery[1];
        var page = mDatabase.observedGroupIdentities(71,request("/?limit=100"),query -> captured[0] = query,true);
        assertEquals(100,number(page.get("total_count")));
        try(Connection connection = openFixture())
        {
            StatsSystemNameResolver.installCatalog(connection);
            AtomicInteger progressCalls = new AtomicInteger();
            ProgressHandler.setHandler(connection,1000,new ProgressHandler()
            {
                @Override protected int progress() { return progressCalls.incrementAndGet() >= 500 ? 1 : 0; }
            });
            try(PreparedStatement statement = connection.prepareStatement(captured[0].sql()))
            {
                for(int index = 0; index < captured[0].parameters().size(); index++)
                    statement.setObject(index + 1,captured[0].parameters().get(index));
                try(ResultSet result = statement.executeQuery())
                {
                    int count = 0;
                    while(result.next()) count++;
                    assertEquals(100,count);
                }
            }
            finally { ProgressHandler.clearHandler(connection); }
            assertTrue(progressCalls.get() < 500,"Repeated aliases must not be tested again for every observed identity");
        }
    }

    @Test
    void conventionalDmrAliasDiscoveryKeepsCarrierTimeslotsSeparate() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_talkgroup_summary (
                    channel_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms,
                    call_count, encrypted_count
                ) VALUES (74, 460012500, 1, 7, 2000, 5000, 2, 1)
                """);
        }

        List<Map<String,Object>> hiddenExact = rows(mDatabase.observedGroupIdentities(74, request("/")));
        assertTrue(hiddenExact.isEmpty(), "The default discovery page must omit the existing exact DMR Alias");

        List<Map<String,Object>> observed = rows(mDatabase.observedGroupIdentities(74,
            request("/?include_exact=true&sort=slot&direction=asc")));
        assertEquals(2, observed.size());
        assertEquals(List.of(1L, 2L), observed.stream().map(row -> number(row.get("timeslot"))).toList());

        for(Map<String,Object> row: observed)
        {
            assertEquals(DMR_CHANNEL, row.get("configuration_id"));
            assertEquals("DMR Dispatch Repeater", row.get("channel_names"));
            assertEquals(460012500L, number(row.get("frequency_hz")));
            assertEquals(7L, number(row.get("group_identity_id")));
            assertEquals("exact", row.get("match_kind"));
        }

        assertEquals(2L, number(observed.getFirst().get("logical_call_count")));
        assertEquals(3L, number(observed.getLast().get("logical_call_count")));

        List<Map<String,Object>> searched = rows(mDatabase.observedGroupIdentities(74,
            request("/?include_exact=true&q=dispatch%20repeater")));
        assertEquals(2, searched.size(), "Search must use the same saved-channel label shown in the browser");
        assertTrue(rows(mDatabase.unassignedGroupIdentities(74,request("/?include_exact=true"))).isEmpty(),
            "Unassigned results must exclude exact definitions even when include_exact is requested");
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_talkgroup_summary (
                    channel_id,frequency_hz,timeslot,talkgroup_id,first_seen_ms,last_seen_ms,call_count,encrypted_count)
                VALUES (74,460012500,1,8,2000,5000,1,0),(74,460012500,2,9,2000,5000,1,0),
                       (74,460012500,1,10,2000,5000,1,0),(74,460012500,2,10,2000,5000,1,0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,min_value,max_value)
                VALUES (7411,74,'DMR range','TALKGROUP_RANGE','DMR',8,9),
                       (7412,74,'Wrong protocol','TALKGROUP_RANGE','NXDN',10,11),
                       (7413,71,'Wrong list','TALKGROUP_RANGE','DMR',10,11)
                """);
        }
        var unassigned = mDatabase.unassignedGroupIdentities(74,
            request("/?include_exact=true&sort=slot&direction=asc"));
        assertEquals(2,number(unassigned.get("total_count")));
        assertEquals(List.of(1L,2L),rows(unassigned).stream().map(row -> number(row.get("timeslot"))).toList());
        assertTrue(rows(unassigned).stream().allMatch(row -> number(row.get("group_identity_id"))==10));
    }

    @Test
    void configuredQuietChannelRemainsVisibleWithoutRuntimeFacts() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    alias_list_id, decoder_type, primary_frequency_hz, config_json
                ) VALUES ('%s', 'CONVENTIONAL', 80, 'Quiet County', '', 'Quiet Dispatch',
                    73, 'NBFM', 155550000, '{}')
                """.formatted(QUIET_CHANNEL));
        }

        Map<String,Object> directory = mDatabase.channelDirectory(request("/?q=quiet"));
        assertEquals(1, number(directory.get("total_count")));
        assertEquals(QUIET_CHANNEL, rows(directory).getFirst().get("configuration_id"));
        assertEquals("Quiet Dispatch", rows(directory).getFirst().get("name"));

        Map<String,Object> detail = mDatabase.channelDetail(QUIET_CHANNEL, request("/"));
        assertEquals(QUIET_CHANNEL, map(detail, "channel").get("configuration_id"));
        assertTrue(rowsFrom(detail, "summaries").isEmpty());
    }

    @Test
    void analogInventoryStartsProspectivelyAndKeepsUnsupportedMatchersOutOfHeardCounts() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (7301, 73, 'Fire Dispatch', 'TALKGROUP', 'NBFM', 100),
                    (7302, 73, 'Aircraft', 'TALKGROUP', 'AM', 100)
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, text_value)
                VALUES (7303, 73, 'Squelch code', 'DCS', NULL, 'N023')
                """);
        }
        Map<String,Object> list = rowWith(rows(mDatabase.publicIdentityLists(request("/?limit=100"))),
            "name", "Analog County");
        assertEquals(true, map(list, "capabilities").get("configured_assignment"));
        Map<String,Object> overview = mDatabase.publicIdentityOverview(73, request("/?range=24h"));
        Map<String,Object> totals = map(overview, "totals");
        assertEquals(3, number(totals.get("configured_alias_count")));
        assertEquals(2, number(totals.get("activity_eligible_alias_count")));
        assertEquals(2, number(totals.get("never_heard_alias_count")));
        assertEquals(0, number(totals.get("logical_call_count")),
            "Old channel totals cannot establish which Alias List received the calls");
        assertTrue(rows(mDatabase.observedGroupIdentities(73, request("/"))).isEmpty());
        List<Map<String,Object>> aliases = rows(mDatabase.publicIdentityAliases(73, request("/?limit=100")));
        assertEquals("configured_assignment", rowWith(aliases, "name", "Fire Dispatch").get("identity_origin"));
        assertEquals("unsupported", rowWith(aliases, "name", "Squelch code").get("metrics_state"));
        assertEquals(2, rows(mDatabase.publicIdentityAliases(73,
            request("/?status=never_heard&limit=100"))).size());
    }

    @Test
    void analogDiscoveryUsesReceivedAssignmentsAndCurrentCoverageWithoutRewritingHistoricalCounts() throws Exception
    {
        String amChannel = "00000000-0000-0000-0000-000000000085";
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id, channel_kind, sort_order, name, alias_list_id,
                    decoder_type, primary_frequency_hz, config_json)
                VALUES ('%s', 'CONVENTIONAL', 85, 'Airport Ground', 73, 'AM', 121900000,
                    '{"decodeConfiguration":{"talkgroup":100}}')
                """.formatted(amChannel));
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (7301, 73, 'Exact Match Label', 'TALKGROUP', 'NBFM', 100)
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, min_value, max_value)
                VALUES (7302, 73, 'Range Match Label', 'TALKGROUP_RANGE', 'NBFM', 140, 160)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_list_analog_activity_summary(alias_list_id, channel_configuration_id,
                    protocol_code, assigned_talkgroup, call_count, unmatched_call_count, recorded_count,
                    streamed_count, first_seen_ms, last_seen_ms, updated_at_ms)
                VALUES (73, '%1$s', 10, 100, 3, 2, 1, 1, 1000, 4000, 4000),
                    (73, '%1$s', 10, 150, 1, 0, 0, 0, 2000, 5000, 5000),
                    (73, '%2$s', 11, 100, 4, 4, 0, 0, 3000, 6000, 6000)
                """.formatted(ANALOG_CHANNEL, amChannel));
        }
        List<Map<String,Object>> missing = rows(mDatabase.observedGroupIdentities(73, request("/")));
        assertEquals(1, missing.size(), "An exact alias or range covers only its own protocol");
        Map<String,Object> assignment = missing.getFirst();
        assertEquals("AM", assignment.get("protocol"));
        assertEquals("configured_assignment", assignment.get("identity_origin"));
        assertEquals("Airport Ground", assignment.get("channel_names"));
        assertEquals("none", assignment.get("match_kind"));
        assertEquals(true, assignment.get("promotion_supported"));
        assertEquals(Map.of("kind", "channel", "key", amChannel), map(assignment, "entity_ref"));
        assertEquals(4, number(assignment.get("logical_call_count")));
        assertEquals(3000, number(assignment.get("first_seen_ms")));

        Map<String,Object> firstPage = mDatabase.observedGroupIdentities(73,
            request("/?include_exact=true&sort=protocol&direction=asc&limit=2"));
        assertEquals(3, number(firstPage.get("total_count")));
        assertEquals(true, firstPage.get("has_more"));
        Map<String,Object> stalePage = mDatabase.observedGroupIdentities(73,
            request("/?include_exact=true&limit=2&offset=10"));
        assertTrue(rows(stalePage).isEmpty());
        assertEquals(3, number(stalePage.get("total_count")),
            "A stale page must retain the actual filtered total after results shrink");
        assertEquals(List.of("exact", "range"), rows(firstPage).stream().map(row -> row.get("match_kind")).toList());
        assertEquals(1, rows(mDatabase.observedGroupIdentities(73,
            request("/?include_exact=true&q=exact%20match%20label"))).size());
        assertEquals(1, rows(mDatabase.unassignedGroupIdentities(73,
            request("/?include_exact=true"))).size());

        Map<String,Object> before = map(mDatabase.publicIdentityOverview(73, request("/")), "totals");
        assertEquals(8, number(before.get("logical_call_count")));
        assertEquals(2, number(before.get("matched_logical_call_count")));
        assertEquals(6, number(before.get("unmatched_logical_call_count")));
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (7303, 73, 'Ground', 'TALKGROUP', 'AM', 100)
                """);
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES (85,'New Analog','NBFM')");
            statement.executeUpdate("UPDATE configuration_channel SET alias_list_id=85 WHERE configuration_id='" +
                amChannel + "'");
        }
        assertTrue(rows(mDatabase.observedGroupIdentities(73, request("/"))).isEmpty(),
            "Creating the alias closes the configuration gap");
        Map<String,Object> after = map(mDatabase.publicIdentityOverview(73, request("/")), "totals");
        for(String metric: List.of("logical_call_count", "matched_logical_call_count", "unmatched_logical_call_count"))
        {
            assertEquals(before.get(metric), after.get(metric),
                "Creating an alias or reassigning a channel must preserve historic " + metric);
        }
        assertEquals(0, number(map(mDatabase.publicIdentityOverview(85, request("/")), "totals")
            .get("logical_call_count")));
        assertTrue(rows(mDatabase.observedGroupIdentities(85, request("/"))).isEmpty());
    }

    @Test
    void analogInventoryLinksOnlyTheChannelWhoseAssignedNumberMatchesAndDiscoveryUsesCompactSummary() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE configuration_channel SET config_json=" +
                "'{\"decodeConfiguration\":{\"talkgroup\":100}}' WHERE configuration_id='" + ANALOG_CHANNEL + "'");
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (7301, 73, 'Matching', 'TALKGROUP', 'NBFM', 100),
                    (7302, 73, 'Another number', 'TALKGROUP', 'NBFM', 101),
                    (7303, 73, 'Other protocol', 'TALKGROUP', 'AM', 100)
                """);
        }
        List<Map<String,Object>> aliases = rows(mDatabase.publicIdentityAliases(73, request("/?limit=100")));
        assertEquals(Map.of("kind", "channel", "key", ANALOG_CHANNEL),
            map(rowWith(aliases, "name", "Matching"), "entity_ref"));
        assertEquals("covered_no_evidence", rowWith(aliases, "name", "Matching").get("metrics_state"));
        assertEquals(0, number(rowWith(aliases, "name", "Matching").get("logical_call_count")));
        assertEquals("not_collected", rowWith(aliases, "name", "Another number").get("metrics_state"));
        assertNull(rowWith(aliases, "name", "Another number").get("logical_call_count"));
        assertEquals("not_collected", rowWith(aliases, "name", "Other protocol").get("metrics_state"));
        assertNull(rowWith(aliases, "name", "Another number").get("entity_ref"));
        assertNull(rowWith(aliases, "name", "Other protocol").get("entity_ref"));
        StatsWebDatabase.ObservedGroupIdentityQuery[] query = new StatsWebDatabase.ObservedGroupIdentityQuery[1];
        mDatabase.observedGroupIdentities(73, request("/?limit=25"), observed -> query[0] = observed);
        assertFalse(query[0].sql().contains("receiver_activity_event"));
        try(Connection connection = openFixture())
        {
            List<String> plan = explain(connection, query[0].sql(), query[0].parameters().toArray());
            assertTrue(plan.stream().anyMatch(detail -> detail.contains("SEARCH summary") &&
                detail.contains("alias_list_id=?")), plan.toString());
        }
    }

    @Test
    void protocolReservedIdentitiesAreRejectedByDetailsAndRelationships()
    {
        StatsApiException group = assertThrows(StatsApiException.class,
            () -> mDatabase.radioSystemGroupIdentity(RADIO_SYSTEM_KEY, "v1-g-bee00-49f-65535"));
        assertEquals(400, group.status());

        StatsApiException groupRelationship = assertThrows(StatsApiException.class,
            () -> mDatabase.radioSystemRelationships(RADIO_SYSTEM_KEY,
                request("/?group_identity_key=v1-g-bee00-49f-65535")));
        assertEquals(400, groupRelationship.status());

        StatsApiException radioRelationship = assertThrows(StatsApiException.class,
            () -> mDatabase.radioSystemRelationships(RADIO_SYSTEM_KEY,
                request("/?radio_identity_key=v1-r-bee00-49f-16777215")));
        assertEquals(400, radioRelationship.status());
    }

    @Test
    void qualityUsesOneChannelContractForP25DmrAndNxdn() throws Exception
    {
        long now = System.currentTimeMillis();
        long bucket = Math.floorDiv(now, 10_000L) * 10_000L;
        long observed = Math.min(now, bucket + 9_000L);

        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            seedTrunkedDmrAndNxdn(statement);
            statement.executeUpdate("""
                INSERT INTO trunked_control_channel_quality (
                    channel_id, frequency_hz, bucket_start_ms, observed_at_ms,
                    signal_dbfs, average_signal_dbfs, minimum_signal_dbfs, maximum_signal_dbfs,
                    decode_health_pct, last_valid_decode_ms
                ) VALUES
                    (71, 851012500, %1$d, %2$d, -45, -46, -50, -42, 97, %2$d),
                    (78, 451012500, %1$d, %2$d, -52, -53, -58, -49, 94, %2$d),
                    (79, 155012500, %1$d, %2$d, -61, -62, -66, -58, 88, %2$d)
                """.formatted(bucket, observed));
        }

        assertQualityChannel(P25_CHANNEL_A, RADIO_SYSTEM_KEY, "P25", 97);
        assertQualityChannel(DMR_TRUNKED_CHANNEL, "dmr:channel:" + DMR_TRUNKED_CHANNEL, "DMR", 94);
        assertQualityChannel(NXDN_TRUNKED_CHANNEL, "nxdn-c:channel:" + NXDN_TRUNKED_CHANNEL, "NXDN", 88);

        StatsCsvExport export = mDatabase.csvExport("signal-health", request("/"));
        String csv = new String(export.content(), StandardCharsets.UTF_8);
        assertTrue(csv.contains(P25_CHANNEL_A));
        assertTrue(csv.contains(DMR_TRUNKED_CHANNEL));
        assertTrue(csv.contains(NXDN_TRUNKED_CHANNEL));
        assertFalse(csv.contains("channel_id"));
    }

    @Test
    void dashboardLastSeenUsesIndexedMaximaAndOmitsUnusedCollections() throws Exception
    {
        String prior = """
            SELECT MAX(last_seen_ms) FROM (
                SELECT last_seen_ms FROM p25_site_snapshot
                UNION ALL SELECT last_seen_ms FROM radio_system
                UNION ALL SELECT last_seen_ms FROM radio_system_identity_summary
                UNION ALL SELECT last_seen_ms FROM p25_site_frequency_band_summary
                UNION ALL SELECT last_seen_ms FROM conventional_activity_summary
                UNION ALL SELECT last_seen_ms FROM trunked_site_snapshot
            )
            """;
        long expected;
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            try(ResultSet original = statement.executeQuery(prior))
            {
                assertTrue(original.next());
                expected = original.getLong(1);
            }
            try(ResultSet current = statement.executeQuery(StatsWebDatabase.DASHBOARD_LAST_SEEN_SQL))
            {
                assertTrue(current.next());
                assertEquals(expected, current.getLong(1));
            }
            List<String> plan = explain(connection, StatsWebDatabase.DASHBOARD_LAST_SEEN_SQL);
            for(String table: List.of("p25_site_snapshot", "radio_system_identity_summary",
                "p25_site_frequency_band_summary", "trunked_site_snapshot"))
            {
                assertTrue(plan.stream().anyMatch(detail -> detail.startsWith("SEARCH " + table + " ") &&
                    detail.contains("COVERING INDEX")), () -> "Last activity must seek the indexed maximum: " + plan);
                assertFalse(plan.stream().anyMatch(detail -> detail.startsWith("SCAN " + table + " ")),
                    () -> "Last activity must not scan retained summaries: " + plan);
            }
            expected += 100_000;
            statement.executeUpdate("UPDATE radio_system_identity_summary SET last_seen_ms=" + expected);
        }
        Map<String,Object> dashboard = mDatabase.dashboard();
        assertEquals(expected, number(dashboard.get("last_seen_ms")));
        assertEquals(Set.of("last_seen_ms", "top_destinations", "top_sources", "call_activity",
            "source_activity_24h"), dashboard.keySet());
    }

    @Test
    void mainUsesOnlyCardFieldsAndPreservesNamesAliasesAndCurrentFrequencies() throws Exception
    {
        long now = System.currentTimeMillis();
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            seedTrunkedDmrAndNxdn(statement);
            statement.executeUpdate("UPDATE configuration_channel SET system_name='Shared P25 alternate' " +
                "WHERE configuration_id='" + P25_CHANNEL_B + "'");
            statement.executeUpdate("""
                INSERT INTO trunked_control_channel_quality (
                    channel_id, frequency_hz, bucket_start_ms, observed_at_ms,
                    signal_dbfs, average_signal_dbfs, minimum_signal_dbfs, maximum_signal_dbfs,
                    decode_health_pct, valid_frames, invalid_frames, corrected_bits, sync_loss_bits,
                    dropped_bits, last_valid_decode_ms
                ) VALUES
                    (71, 851012500, %1$d, %1$d, -45, -46, -50, -42, 97, 100, 2, 3, 4, 5, %1$d),
                    (78, 451012500, %1$d, %1$d, -52, -53, -58, -49, 94, 200, 2, 3, 4, 5, %1$d),
                    (79, 155012500, %1$d, %1$d, -61, -62, -66, -58, 88, 300, 2, 3, 4, 5, %1$d)
                """.formatted(now));
        }
        var running = ChannelAdministrationService.ProcessingState.RUNNING;
        var stopped = ChannelAdministrationService.ProcessingState.STOPPED;
        List<ChannelAdministrationService.ChannelSummary> channels = List.of(
            new ChannelAdministrationService.ChannelSummary(P25_CHANNEL_A, "p25-phase1", "P25", "TRUNKED",
                "", "North", "North Control", List.of(851012500L), running, 1, 71, "County A", true,
                null, null, 853012500L, "active-control"),
            new ChannelAdministrationService.ChannelSummary(P25_CHANNEL_B, "p25-phase1", "P25", "TRUNKED",
                "Shared P25", "South", "South Control", List.of(852012500L), stopped, null, 72, "County B", true,
                null, Map.of("remote", true), 852012500L, "configured"),
            new ChannelAdministrationService.ChannelSummary(ANALOG_CHANNEL, "nbfm", "FM", "CONVENTIONAL",
                "County Fire", "", "County Fire Dispatch", List.of(154310000L), running, 2, 73, "Analog County", true,
                null, null, 154310000L, "configured"),
            new ChannelAdministrationService.ChannelSummary(DMR_CHANNEL, "unsupported", "Unsupported", "UNSUPPORTED",
                "Other", "", "Unsupported", List.of(), stopped, null, 74, "DMR County", false,
                null, null, null, "configured"),
            new ChannelAdministrationService.ChannelSummary(DMR_TRUNKED_CHANNEL, "dmr", "DMR", "TRUNKED",
                "Metro DMR", "Central", "DMR Control", List.of(451012500L), running, 3, 78, "DMR Trunked", true,
                null, null, 451012500L, "active-control"),
            new ChannelAdministrationService.ChannelSummary(NXDN_TRUNKED_CHANNEL, "nxdn", "NXDN", "TRUNKED",
                "Regional NXDN", "West", "NXDN Control", List.of(155012500L), running, 4, 79, "NXDN Trunked", true,
                null, null, 155012500L, "active-control"));
        Map<String,Object> response = mDatabase.dashboardMain(channels);
        assertEquals(Set.of("quality", "directory"), response.keySet());
        Map<String,Object> directory = map(response, "directory");
        List<Map<String,Object>> systems = rowsFrom(directory, "systems");
        assertEquals(3, systems.size());
        Map<String,Object> system = systems.getFirst();
        assertEquals(RADIO_SYSTEM_KEY, system.get("radio_system_key"));
        assertEquals("Shared P25 / Shared P25 alternate", system.get("system_name"));
        assertEquals("radio_system", map(system, "entity_ref").get("kind"));
        assertEquals(List.of("County A", "County B"), rowsFrom(system, "alias_lists").stream()
            .map(row -> row.get("name")).toList());
        assertEquals(2, rowsFrom(system, "children").size());
        Map<String,Object> activeControl = rowWith(rowsFrom(system, "children"), "configuration_id", P25_CHANNEL_A);
        assertEquals(853012500L, number(activeControl.get("display_frequency_hz")),
            "The Main card must use the live control frequency rather than configured or retained quality data");
        assertEquals("active-control", activeControl.get("display_frequency_source"));
        Map<String,Object> stoppedControl = rowWith(rowsFrom(system, "children"), "configuration_id", P25_CHANNEL_B);
        assertEquals(852012500L, number(stoppedControl.get("display_frequency_hz")));
        assertEquals("configured", stoppedControl.get("display_frequency_source"));
        assertEquals(ANALOG_CHANNEL, rowsFrom(directory, "channels").getFirst().get("configuration_id"));
        assertEquals(154310000L, number(((List<?>)rowsFrom(directory, "channels").getFirst()
            .get("frequencies_hz")).getFirst()));
        assertFalse(rowsFrom(directory, "channels").getFirst().containsKey("display_frequency_hz"));
        assertFalse(rowsFrom(directory, "channels").getFirst().containsKey("display_frequency_source"));
        List<Map<String,Object>> quality = rowsFrom(map(response, "quality"), "current");
        assertEquals(4, quality.size());
        assertEquals(97, number(rowWith(quality, "configuration_id", P25_CHANNEL_A).get("decode_health_pct")));
        assertEquals(94, number(rowWith(quality, "configuration_id", DMR_TRUNKED_CHANNEL).get("decode_health_pct")));
        assertEquals(88, number(rowWith(quality, "configuration_id", NXDN_TRUNKED_CHANNEL).get("decode_health_pct")));
        assertEquals(Map.of("remote", true), rowWith(quality, "configuration_id", P25_CHANNEL_B).get("remote_origin"));
        assertEquals(0xBEE00, number(rowWith(quality, "configuration_id", P25_CHANNEL_A).get("wacn")));
        assertEquals(0x49F, number(rowWith(quality, "configuration_id", P25_CHANNEL_A).get("system_id")));

        // Verify the final API presentation, not just the database maps: native facts cannot recreate rich exports.
        JsonNode presented = StatsApiV1Payload.present(response);
        assertEquals(Set.of("configuration_id", "channel_kind", "name", "site_name", "system_name", "protocol",
            "signal_dbfs", "average_signal_dbfs", "decode_health_pct", "last_observed_ms", "radio_system_key",
            "wacn", "system_id", "rfss", "site_id", "ran"),
            fieldNames(presented.path("quality").path("current").get(0)));
        assertEquals(Set.of("system_name", "radio_system_key", "entity_ref", "protocol", "protocol_label",
            "wacn", "system_id", "alias_lists", "children"),
            fieldNames(presented.path("directory").path("systems").get(0)));
        assertEquals(Set.of("configuration_id", "name", "site_name", "system_name", "protocol", "protocol_label",
            "alias_list_id", "alias_list_name", "processing_state", "frequencies_hz", "display_frequency_hz",
            "display_frequency_source", "radio_system_key"),
            fieldNames(presented.path("directory").path("systems").get(0).path("children").get(0)));
        for(String unused: List.of("system_identity", "system_names", "radio_system_entity_ref", "channel_name",
            "valid_frames", "invalid_frames", "minimum_signal_dbfs", "maximum_signal_dbfs", "corrected_bits",
            "sync_loss_bits", "dropped_bits", "last_valid_decode_ms", "sample_age_seconds", "current_control_hz"))
            assertTrue(presented.findValues(unused).isEmpty(), unused + " is not consumed by the Main cards");

        String export = new String(mDatabase.csvExport("signal-health", request("/")).content(), StandardCharsets.UTF_8);
        assertTrue(export.contains("valid_frames_rolling_30s"));
        assertTrue(export.contains("minimum_signal_dbfs"));
        assertTrue(export.contains("corrected_bits_rolling_30s"));
    }

    private static Set<String> fieldNames(JsonNode object)
    {
        Set<String> fields = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    @Test
    void boundedDirectoryHandlesRepresentativeIdentityVolume() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE identities(value) AS (
                    SELECT 1000 UNION ALL SELECT value + 1 FROM identities WHERE value < 1149
                )
                INSERT INTO radio_system_identity_summary (
                    radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms,
                    logical_call_count
                ) SELECT 71, 1, 0xBEE00, 0x49F, value, 1000, 4000, value FROM identities
                """);
        }

        Map<String,Object> firstPage = mDatabase.radioSystemGroupIdentities(RADIO_SYSTEM_KEY,
            request("/?sort=group_identity&direction=asc&limit=25"));
        assertEquals(151, number(firstPage.get("total_count")));
        assertEquals(25, rows(firstPage).size());
        assertTrue((Boolean)firstPage.get("has_more"));
        assertEquals(101, number(rows(firstPage).getFirst().get("native_id")));

        Map<String,Object> filtered = mDatabase.radioSystemGroupIdentities(RADIO_SYSTEM_KEY,
            request("/?q=1149&limit=25"));
        assertEquals(1, number(filtered.get("total_count")));
        assertEquals(1149, number(rows(filtered).getFirst().get("native_id")));
    }

    @Test
    void radioSystemIdentitySearchUsesConfiguredAndOverTheAirAliasesBeforePaging() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES (75,'Unassigned','P25')");
            statement.executeUpdate("""
                UPDATE radio_system_identity_summary
                SET last_talker_alias = 'Unit 202 OTA', last_talker_alias_seen_ms = 5000
                WHERE id = 7102
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms, logical_call_count
                ) VALUES (7103, 71, 3, 0xBEE00, 0x49F, 900, 1000, 4000, 2),
                         (7104, 71, 2, 0xBEE00, 0x49F, 203, 1000, 4000, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, description, group_name,
                    matcher_type, protocol, value, min_value, max_value
                ) VALUES
                    (7110, 71, 'Engine Twelve', 'West apparatus', 'Fire radios',
                        'RADIO_ID', 'APCO25', 202, NULL, NULL),
                    (7210, 72, 'Engine Twelve', 'West apparatus', 'Fire radios',
                        'RADIO_ID', 'APCO25_PHASE2', 202, NULL, NULL),
                    (7111, 71, 'Engine Fleet', 'Regional apparatus', 'Fire radios',
                        'RADIO_ID_RANGE', 'APCO25', NULL, 200, 300),
                    (7211, 72, 'Engine Fleet', 'Regional apparatus', 'Fire radios',
                        'RADIO_ID_RANGE', 'APCO25_PHASE2', NULL, 200, 300),
                    (7112, 71, 'Regional Patch', 'Mutual aid patch', 'Operations',
                        'TALKGROUP', 'APCO25', 900, NULL, NULL),
                    (7212, 72, 'Regional Patch', 'Mutual aid patch', 'Operations',
                        'TALKGROUP', 'APCO25_PHASE2', 900, NULL, NULL),
                    (7510, 75, 'Foreign Unit', NULL, NULL,
                        'RADIO_ID', 'APCO25', 202, NULL, NULL),
                    (7511, 75, 'Foreign Group', NULL, NULL,
                        'TALKGROUP', 'APCO25', 101, NULL, NULL)
                """);
        }

        Map<String,Object> firstRadioPage = mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?q=ENGINE&sort=radio&direction=asc&limit=1"));
        assertEquals(2, number(firstRadioPage.get("total_count")));
        assertEquals(202, number(rows(firstRadioPage).getFirst().get("native_id")));
        assertEquals(true, firstRadioPage.get("has_more"));

        Map<String,Object> secondRadioPage = mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?q=engine&sort=radio&direction=asc&limit=1&offset=1"));
        assertEquals(2, number(secondRadioPage.get("total_count")));
        assertEquals(203, number(rows(secondRadioPage).getFirst().get("native_id")));
        assertEquals(false, secondRadioPage.get("has_more"));

        assertEquals(202, number(rows(mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?q=west%20apparatus"))).getFirst().get("native_id")));
        assertEquals(2, number(mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?q=fire%20radios")).get("total_count")));
        assertEquals(202, number(rows(mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?q=unit%20202%20ota"))).getFirst().get("native_id")));
        assertEquals(202, number(rows(mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?q=202"))).getFirst().get("native_id")));
        assertTrue(rows(mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?q=foreign%20unit"))).isEmpty(),
            "An Alias List not assigned to the selected system must not contribute search results");

        assertEquals(101, number(rows(mDatabase.radioSystemGroupIdentities(RADIO_SYSTEM_KEY,
            request("/?q=operations"))).getFirst().get("native_id")));
        Map<String,Object> patch = rows(mDatabase.radioSystemGroupIdentities(RADIO_SYSTEM_KEY,
            request("/?q=mutual%20aid%20patch"))).getFirst();
        assertEquals(900, number(patch.get("native_id")));
        assertEquals(3, number(patch.get("group_identity_kind_code")));
        assertTrue(rows(mDatabase.radioSystemGroupIdentities(RADIO_SYSTEM_KEY,
            request("/?q=foreign%20group"))).isEmpty(),
            "Configured group aliases must preserve radio-system ownership");

        // The canonical radio address can differ from the channel's locally observed address.
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias (id,alias_list_id,name,matcher_type,protocol,value)
                VALUES (7113,71,'Local Assignment','RADIO_ID','APCO25',555)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence (
                    radio_system_id,radio_identity_id,channel_id,observed_local_id,
                    evidence_code,confirmed_at_ms)
                VALUES (71,7102,71,555,1,6000)
                """);
        }
        Map<String,Object> local = mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?q=local%20assignment&limit=1"));
        assertEquals(1, number(local.get("total_count")));
        assertEquals(202, number(rows(local).getFirst().get("native_id")));
        assertEquals("Local Assignment", rows(local).getFirst().get("alias_name"));
        assertTrue(rows(mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?q=engine%20twelve"))).isEmpty(),
            "Local P25 evidence must suppress a canonical-ID Alias search");
    }

    @Test
    void homeNameSearchKeepsFiltersCountsAndNonDefaultPageOrder() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id,radio_system_id,identity_kind_code,home_wacn,home_system_id,identity_id,
                    first_seen_ms,last_seen_ms,logical_call_count)
                VALUES (7103,71,2,0xBEE00,0x49F,203,1000,3000,1),
                    (7104,71,2,0xBEE00,0x49F,204,1000,6000,2),
                    (7105,71,1,0xBEE00,0x49F,102,1000,6000,1),
                    (7106,71,3,0xBEE00,0x49F,900,1000,3000,1)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation (
                    radio_system_id,radio_identity_id,talkgroup_identity_id,channel_id,
                    radio_observed_local_id,talkgroup_observed_local_id,confirmed_at_ms)
                VALUES (71,7102,7101,71,202,101,4000),
                    (71,7103,7101,71,203,101,4000),(71,7104,7101,71,204,101,4000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence (
                    radio_system_id,radio_identity_id,channel_id,observed_local_id,evidence_code,confirmed_at_ms)
                VALUES (71,7102,71,202,2,4000),(71,7103,71,203,2,4000),(71,7104,71,204,2,4000)
                """);
        }
        String filter = "/?q=shared%20p25&sort=last_seen&direction=desc&limit=1&offset=1";
        Map<String,Object> radios = mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request(filter + "&affiliated=true&configuration_id=" + P25_CHANNEL_A));
        assertEquals(3,number(radios.get("total_count")));
        assertEquals(202,number(rows(radios).getFirst().get("native_id")));
        assertEquals(true,radios.get("has_more"));
        assertEquals(0,number(mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request(filter + "&affiliated=false")).get("total_count")));
        assertEquals(0,number(mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request(filter + "&configuration_id=" + P25_CHANNEL_B)).get("total_count")));
        Map<String,Object> groups = mDatabase.radioSystemGroupIdentities(RADIO_SYSTEM_KEY,request(filter));
        assertEquals(3,number(groups.get("total_count")));
        assertEquals(101,number(rows(groups).getFirst().get("native_id")));
        assertEquals(true,groups.get("has_more"));
    }

    @Test
    void talkerAliasSearchFindsConfiguredNamesBeforePaging() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE configuration_channel SET alias_list_id = 71 " +
                "WHERE configuration_id = '" + P25_CHANNEL_B + "'");
            statement.executeUpdate("""
                UPDATE radio_system_identity_summary
                SET last_talker_alias = 'Unit 202 OTA', last_talker_alias_seen_ms = 5000
                WHERE id = 7102
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms, last_talker_alias, last_talker_alias_seen_ms
                ) VALUES (7103, 71, 2, 0xBEE00, 0x49F, 203, 1000, 5000, 'Unit 203 OTA', 5000),
                         (7104, 71, 2, 0xBEE00, 0x49F, 204, 1000, 5000, NULL, NULL)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, value, min_value, max_value)
                VALUES (7110, 71, 'Engine Twelve', 'RADIO_ID', 'APCO25', 202, NULL, NULL),
                       (7111, 71, 'Engine Fleet', 'RADIO_ID_RANGE', 'APCO25_PHASE2', NULL, 203, 205),
                       (7210, 72, 'Foreign Unit', 'RADIO_ID', 'APCO25', 202, NULL, NULL)
                """);
        }

        Map<String,Object> firstPage = mDatabase.radioSystemTalkerAliases(RADIO_SYSTEM_KEY,
            request("/?q=ENGINE&sort=radio&direction=asc&limit=1"));
        assertEquals(2, number(firstPage.get("total_count")),
            "Configured aliases must match before paging, excluding radios without an OTA alias");
        assertEquals(202, number(rows(firstPage).getFirst().get("native_id")));
        assertEquals("Engine Twelve", rows(firstPage).getFirst().get("alias_name"));
        assertEquals(true, firstPage.get("has_more"));

        Map<String,Object> secondPage = mDatabase.radioSystemTalkerAliases(RADIO_SYSTEM_KEY,
            request("/?q=engine&sort=radio&direction=asc&limit=1&offset=1"));
        assertEquals(2, number(secondPage.get("total_count")));
        assertEquals(203, number(rows(secondPage).getFirst().get("native_id")));
        assertEquals("Engine Fleet", rows(secondPage).getFirst().get("alias_name"));
        assertEquals(false, secondPage.get("has_more"));

        for(String query: List.of("engine%20twelve", "unit%20202%20ota", "202"))
        {
            Map<String,Object> result = mDatabase.radioSystemTalkerAliases(RADIO_SYSTEM_KEY,
                request("/?q=" + query));
            assertEquals(1, number(result.get("total_count")));
            assertEquals(202, number(rows(result).getFirst().get("native_id")));
        }
        Map<String,Object> foreign = mDatabase.radioSystemTalkerAliases(RADIO_SYSTEM_KEY,
            request("/?q=foreign%20unit"));
        assertEquals(0, number(foreign.get("total_count")));
        assertTrue(rows(foreign).isEmpty(), "Unassigned Alias Lists must not supply search matches");

        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (7112, 71, 'Local Assignment', 'RADIO_ID', 'APCO25', 555)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence (
                    radio_system_id, radio_identity_id, channel_id, observed_local_id,
                    evidence_code, confirmed_at_ms)
                VALUES (71, 7102, 71, 555, 1, 6000)
                """);
        }
        Map<String,Object> local = mDatabase.radioSystemTalkerAliases(RADIO_SYSTEM_KEY,
            request("/?q=local%20assignment"));
        assertEquals(1, number(local.get("total_count")));
        assertEquals(202, number(rows(local).getFirst().get("native_id")));
        assertEquals("Local Assignment", rows(local).getFirst().get("alias_name"));
        assertEquals(0, number(mDatabase.radioSystemTalkerAliases(RADIO_SYSTEM_KEY,
            request("/?q=engine%20twelve")).get("total_count")),
            "Search must use the same locally observed identity as the displayed alias");
    }

    @Test
    void radioSystemIdentityAliasSearchUsesSummaryAndMatcherIndexes() throws Exception
    {
        StatsWebDatabase.IdentityDirectoryQuery[] groupQuery = new StatsWebDatabase.IdentityDirectoryQuery[1];
        StatsWebDatabase.IdentityDirectoryQuery[] radioQuery = new StatsWebDatabase.IdentityDirectoryQuery[1];
        mDatabase.radioSystemGroupIdentities(RADIO_SYSTEM_KEY, request("/?q=dispatch&limit=25"),
            query -> groupQuery[0] = query);
        mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY, request("/?q=unit&limit=25"),
            query -> radioQuery[0] = query);

        try(Connection connection = openFixture())
        {
            List<String> groupPlan = explain(connection, groupQuery[0].sql(),
                groupQuery[0].parameters().toArray());
            assertTrue(groupPlan.stream().anyMatch(detail ->
                    detail.contains("idx_radio_system_identity_last_seen") ||
                        detail.contains("sqlite_autoindex_radio_system_identity_summary_1")),
                () -> "Expected a system-owned identity lookup, plan was: " + groupPlan);
            assertTrue(groupPlan.stream().anyMatch(detail ->
                    detail.contains("idx_alias_talkgroup_value (protocol=? AND value=?")),
                () -> "Expected exact configured-talkgroup lookup, plan was: " + groupPlan);
            assertTrue(groupPlan.stream().anyMatch(detail ->
                    detail.contains("SEARCH definition") &&
                        detail.contains("INDEX idx_alias_talkgroup_range (protocol=? AND min_value<?")),
                () -> "Expected range configured-talkgroup lookup, plan was: " + groupPlan);
            assertTrue(groupPlan.stream().anyMatch(detail -> detail.contains(
                    "idx_receiver_activity_event_source_identity_address (source_identity_summary_id=? AND " +
                        "source_observed_local_id>?)")),
                () -> "Expected covered identity/address activity fallback, plan was: " + groupPlan);
            assertTrue(groupPlan.stream().noneMatch(detail -> detail.contains("SCAN evidence") ||
                    detail.contains("SCAN receiver_activity_event")),
                () -> "Identity alias search must not scan retained evidence: " + groupPlan);

            List<String> radioPlan = explain(connection, radioQuery[0].sql(),
                radioQuery[0].parameters().toArray());
            assertTrue(radioPlan.stream().anyMatch(detail ->
                    detail.contains("idx_alias_radio_value (protocol=? AND value=?")),
                () -> "Expected exact configured-radio lookup, plan was: " + radioPlan);
            assertTrue(radioPlan.stream().anyMatch(detail ->
                    detail.contains("SEARCH definition") &&
                        detail.contains("INDEX idx_alias_radio_range (protocol=? AND min_value<?")),
                () -> "Expected range configured-radio lookup, plan was: " + radioPlan);
            assertTrue(radioPlan.stream().anyMatch(detail -> detail.contains(
                    "idx_receiver_activity_event_target_identity_address (target_identity_summary_id=? AND " +
                        "target_observed_local_id>?)")),
                () -> "Expected covered identity/address activity fallback, plan was: " + radioPlan);
            assertTrue(radioPlan.stream().noneMatch(detail -> detail.contains("SCAN evidence") ||
                    detail.contains("SCAN receiver_activity_event")),
                () -> "Radio alias search must not scan retained evidence: " + radioPlan);
        }
    }

    @Test
    void activityCursorAndGrantFilterApplyBeforeTheBoundedPage() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE radio_system_identity_summary
                SET last_talker_alias = 'Unit 202 OTA', last_talker_alias_seen_ms = 6000
                WHERE id = 7102
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event (
                    channel_id, radio_system_id, observed_at_ms, action_code, event_type_code,
                    source_observed_local_id, target_observed_local_id, target_kind_code,
                    source_identity_summary_id, source_identity_kind_code, target_identity_summary_id
                ) VALUES
                    (71, 71, 4000, 23, NULL, 202, 101, 1, 7102, 2, 7101),
                    (71, 71, 5000, 23, NULL, 202, 101, 1, 7102, 2, 7101),
                    (71, 71, 6000, 12, NULL, 202, 101, 1, 7102, 2, 7101),
                    (71, 71, 4000, 23, NULL, 202, 101, 1, 7102, 2, 7101)
                """);
        }

        Map<String,Object> firstPage = mDatabase.activity(request(
            "/?configuration_id=" + P25_CHANNEL_A + "&hide_grants=true&limit=2"));
        assertEquals(List.of(5000L, 4000L), rows(firstPage).stream()
            .map(row -> number(row.get("observed_at_ms"))).toList());
        assertTrue((Boolean)firstPage.get("has_more"));
        assertTrue(rows(firstPage).stream().noneMatch(row -> "GRANT".equals(row.get("action"))));
        assertTrue(rows(firstPage).stream().allMatch(row ->
            "Unit 202 OTA".equals(row.get("source_talker_alias"))));

        long cursor = number(firstPage.get("next_before_id"));
        Map<String,Object> secondPage = mDatabase.activity(request(
            "/?configuration_id=" + P25_CHANNEL_A + "&hide_grants=true&limit=2&before_id=" + cursor));
        assertEquals(1, rows(secondPage).size());
        assertEquals(4000L, number(rows(secondPage).getFirst().get("observed_at_ms")));
        assertFalse((Boolean)secondPage.get("has_more"));
    }

    @Test
    void tonesFilterKeepsCallClassificationAndFindsEvidenceBeforePagination() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,event_type_code)
                VALUES(990001,71,71,10000,4,7),(990002,71,71,11000,4,7),(990003,71,71,12000,4,7)
                """);
            statement.executeUpdate("INSERT INTO receiver_activity_tone_sequence VALUES(990001,1,3,1,9000,9000,10500,2)");
            statement.executeUpdate("""
                INSERT INTO receiver_activity_tone VALUES
                (990001,0,437500,NULL,NULL,0,500,990000),
                (990001,1,593750,NULL,NULL,500,500,990000)
                """);
        }
        Map<String,Object> response = mDatabase.activity(request("/?configuration_id=" + P25_CHANNEL_A +
            "&action=CALL&event_type=CALL_GROUP&has_tones=true&limit=1"));
        List<Map<String,Object>> found = rows(response);
        assertEquals(1, found.size());
        assertEquals(990001L, ((Number)found.getFirst().get("id")).longValue());
        assertEquals("CALL", found.getFirst().get("action"));
        assertEquals("CALL_GROUP", found.getFirst().get("event_type"));
        assertTrue(found.getFirst().containsKey("paging_tone_summary"));
        assertTrue(rows(mDatabase.activity(request("/?configuration_id=" + P25_CHANNEL_A +
            "&event_type=CALL&has_tones=true"))).isEmpty());
    }

    @Test
    void sparseToneHistoryKeepsScopeFiltersAndBothCursorOrders() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,
                    home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms)
                VALUES(7105,71,2,0xBEE00,0x49F,303,1000,40000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,
                    action_code,event_type_code,source_identity_summary_id,source_observed_local_id,
                    target_identity_summary_id,target_observed_local_id,target_kind_code,encrypted) VALUES
                (990001,71,71,10000,4,7,7102,202,7102,202,2,0),
                (990002,71,71,10000,4,7,7102,202,7101,101,1,1),
                (990003,71,71,20000,4,7,7102,202,7101,101,1,1),
                (990004,72,71,30000,4,7,7102,202,7101,101,1,1),
                (990005,71,71,40000,4,7,7105,303,7101,101,1,0)
                """);
            statement.executeUpdate("""
                WITH RECURSIVE events(value) AS (
                    VALUES(1) UNION ALL SELECT value+1 FROM events WHERE value<10000
                )
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,
                    action_code,event_type_code,encrypted)
                SELECT 1000000+value,71,71,1000000+value,4,7,1 FROM events
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_tone_sequence
                SELECT id,1,3,1,observed_at_ms-1000,observed_at_ms-1000,observed_at_ms+500,1
                FROM receiver_activity_event WHERE id BETWEEN 990001 AND 990005
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_tone
                SELECT event_id,0,437500,NULL,NULL,0,500,990000 FROM receiver_activity_tone_sequence
                """);
        }

        List<StatsWebDatabase.ActivityQuery> queries = new ArrayList<>();
        String scope = "/?configuration_id=" + P25_CHANNEL_A + "&has_tones=true";
        Map<String,Object> first = mDatabase.activity(request(scope + "&limit=2"), queries::add);
        assertEquals(List.of(990005L, 990003L), rows(first).stream().map(row -> number(row.get("id"))).toList());
        assertTrue((Boolean)first.get("has_more"));
        Map<String,Object> second = mDatabase.activity(request(scope + "&limit=1&before_id=" +
            first.get("next_before_id")));
        assertEquals(990002, number(rows(second).getFirst().get("id")));
        assertTrue((Boolean)second.get("has_more"));
        Map<String,Object> third = mDatabase.activity(request(scope + "&limit=1&before_id=" +
            second.get("next_before_id")));
        assertEquals(990001, number(rows(third).getFirst().get("id")));
        assertFalse((Boolean)third.get("has_more"));

        Map<String,Object> forward = mDatabase.activity(request(scope +
            "&limit=2&after_id=0&watermark_id=990003"));
        assertEquals(List.of(990001L, 990002L), rows(forward).stream().map(row -> number(row.get("id"))).toList());
        assertTrue((Boolean)forward.get("has_more"));
        Map<String,Object> forwardNext = mDatabase.activity(request(scope + "&limit=2&after_id=" +
            forward.get("next_after_id") + "&watermark_id=990003"));
        assertEquals(List.of(990003L), rows(forwardNext).stream().map(row -> number(row.get("id"))).toList());
        assertFalse((Boolean)forwardNext.get("has_more"));

        Map<String,Object> encrypted = mDatabase.activity(request(scope + "&encryption=encrypted&limit=10"), queries::add);
        assertEquals(List.of(990003L, 990002L), rows(encrypted).stream().map(row -> number(row.get("id"))).toList());
        Map<String,Object> radio = mDatabase.activity(request(scope + "&radio_system_key=" + RADIO_SYSTEM_KEY +
            "&radio_identity_key=" + p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO,202) + "&limit=10"), queries::add);
        assertEquals(List.of(990003L, 990002L, 990001L), rows(radio).stream().map(row -> number(row.get("id"))).toList(),
            "A tone event matching both source and target roles is returned once");
        Map<String,Object> window = mDatabase.activity(request(scope + "&from_ms=10000&to_ms=20000&limit=10"));
        assertEquals(List.of(990002L, 990001L), rows(window).stream().map(row -> number(row.get("id"))).toList());

        try(Connection connection = openFixture())
        {
            for(StatsWebDatabase.ActivityQuery query: queries)
            {
                List<String> plan = explain(connection, query.sql(), query.parameters().toArray());
                List<String> candidateAccess = plan.stream().filter(line ->
                    line.startsWith("SEARCH candidate ") || line.startsWith("SCAN candidate ")).toList();
                assertFalse(candidateAccess.isEmpty(), () -> "Missing Activity candidate access: " + plan);
                assertTrue(candidateAccess.stream().allMatch(line -> line.contains("USING INTEGER PRIMARY KEY")),
                    () -> "Every tone-filtered branch must seek event IDs instead of scanning owner history: " + plan);
                assertTrue(plan.stream().anyMatch(line -> line.contains("receiver_activity_tone_sequence") &&
                        line.contains("FOR IN-OPERATOR")),
                    () -> "Tone evidence must supply the sparse event-ID set: " + plan);
            }
        }
    }

    @Test
    void activityFiltersComposeAcrossScopeRolePatchTimeAndRadioFrequencyFields() throws Exception
    {
        seedActivityFilterRows();
        String talkgroup = p25IdentityKey(RadioSystemIdentityKey.KIND_TALKGROUP, 101);
        String radio202 = p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 202);
        String radio303 = p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 303);
        String patch900 = p25IdentityKey(RadioSystemIdentityKey.KIND_PATCH_GROUP, 900);

        Map<String,Object> direct = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&group_identity_key=" + talkgroup + "&group_match=direct&limit=20"));
        assertEquals(List.of(10_000L), activityTimes(direct));

        Map<String,Object> viaPatch = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&group_identity_key=" + talkgroup + "&group_match=via_patch&limit=20"));
        assertEquals(List.of(11_000L, 10_000L), activityTimes(viaPatch));

        Map<String,Object> all = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&group_identity_key=" + talkgroup + "&group_match=all&limit=20"));
        assertEquals(List.of(11_000L, 10_000L), activityTimes(all),
            "A direct event that is also a member candidate must be returned once");

        StatsWebDatabase.ActivityQuery[] combinedQuery = new StatsWebDatabase.ActivityQuery[1];
        Map<String,Object> combined = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&group_identity_key=" + talkgroup + "&radio_identity_key=" + radio303 +
            "&radio_role=source&limit=20"), query -> combinedQuery[0] = query);
        assertEquals(List.of(11_000L), activityTimes(combined));
        assertFalse(combinedQuery[0].sql().contains("INDEXED BY"),
            "Combined group/radio queries retain their direct-group and patch-member access paths");

        Map<String,Object> source = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&radio_identity_key=" + radio202 + "&radio_role=source&limit=20"));
        assertEquals(List.of(13_000L, 10_000L), activityTimes(source));
        Map<String,Object> target = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&radio_identity_key=" + radio202 + "&radio_role=target&limit=20"));
        assertEquals(List.of(12_000L), activityTimes(target));
        Map<String,Object> any = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&radio_identity_key=" + radio202 + "&radio_role=any&limit=20"));
        assertEquals(List.of(13_000L, 12_000L, 10_000L), activityTimes(any));

        Map<String,Object> bounded = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&from_ms=11000&to_ms=13000&limit=20"));
        assertEquals(List.of(12_000L, 11_000L), activityTimes(bounded),
            "from_ms is inclusive and to_ms is exclusive");

        Map<String,Object> exact = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&configuration_id=" + P25_CHANNEL_A + "&from_ms=11000&to_ms=11001" +
            "&action=grant&event_type=call_group&encryption=encrypted" +
            "&source_identity_key=" + radio303 + "&target_identity_key=" + patch900 +
            "&source_id=303&target_id=900&target_kind=patch_group" +
            "&frequency_hz=852012500&lcn=3-4&timeslot=2&limit=20"));
        assertEquals(List.of(11_000L), activityTimes(exact));

        StatsApiException ownership = assertThrows(StatsApiException.class, () -> mDatabase.activity(request(
            "/?radio_system_key=" + RADIO_SYSTEM_KEY + "&configuration_id=" + DMR_CHANNEL)));
        assertEquals("configuration_id", ownership.field());
        StatsApiException grants = assertThrows(StatsApiException.class, () -> mDatabase.activity(request(
            "/?radio_system_key=" + RADIO_SYSTEM_KEY + "&action=GRANT&hide_grants=true")));
        assertEquals("hide_grants", grants.field());
        StatsApiException patchMatch = assertThrows(StatsApiException.class, () -> mDatabase.activity(request(
            "/?radio_system_key=" + RADIO_SYSTEM_KEY + "&group_identity_key=" + patch900 +
                "&group_match=direct")));
        assertEquals("group_match", patchMatch.field());
        StatsApiException targetKindMismatch = assertThrows(StatsApiException.class, () -> mDatabase.activity(
            request("/?radio_system_key=" + RADIO_SYSTEM_KEY + "&target_identity_key=" + patch900 +
                "&target_kind=talkgroup")));
        assertEquals("target_kind", targetKindMismatch.field());
    }

    @Test
    void activityOwnerActionWindowUsesStableForwardWatermark() throws Exception
    {
        seedActivityFilterRows();
        String query = "/?radio_system_key=" + RADIO_SYSTEM_KEY + "&from_ms=9000&to_ms=15001&actions=call,denial&after_id=0&limit=2";
        StatsWebDatabase.ActivityQuery[] observedQuery = new StatsWebDatabase.ActivityQuery[1];
        Map<String,Object> first = mDatabase.activity(request(query), activityQuery ->
            observedQuery[0] = activityQuery);
        assertEquals(List.of(10_000L, 12_000L), activityTimes(first));
        assertEquals(List.of("CALL", "DENIAL"), rows(first).stream().map(row ->
            String.valueOf(row.get("action"))).toList());
        assertEquals("Shared P25", rows(first).getFirst().get("system_name"));
        assertTrue((Boolean)first.get("has_more"));
        long firstCursor = number(first.get("next_after_id"));
        long watermark = number(first.get("watermark_id"));
        assertTrue(watermark > firstCursor);
        assertEquals(false, first.get("reset_required"));

        try(Connection connection = openFixture())
        {
            List<String> plan = explain(connection, observedQuery[0].sql(),
                observedQuery[0].parameters().toArray());
            assertTrue(plan.stream().anyMatch(detail ->
                    detail.contains("INTEGER PRIMARY KEY") || detail.contains("rowid>")),
                () -> "Expected bounded forward history to seek by row id, plan was: " + plan);
        }

        try(Connection connection = openFixture();
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO receiver_activity_event (
                    channel_id, radio_system_id, observed_at_ms, action_code, event_type_code,
                    source_observed_local_id, target_observed_local_id, target_kind_code,
                    source_identity_summary_id, source_identity_kind_code, target_identity_summary_id)
                VALUES (71, 71, 12500, 9, 57, 303, 202, 2, 7105, 2, 7102)
                """))
        {
            statement.executeUpdate();
        }

        Map<String,Object> second = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY + "&from_ms=9000&to_ms=15001" +
            "&actions=CALL,DENIAL&after_id=" + firstCursor + "&watermark_id=" + watermark + "&limit=2"));
        assertEquals(List.of(13_000L, 14_000L), activityTimes(second));
        assertFalse((Boolean)second.get("has_more"));
        assertEquals(watermark, number(second.get("next_after_id")),
            "A completed snapshot advances over nonmatching rows through its watermark");

        Map<String,Object> nextPoll = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY + "&from_ms=9000&to_ms=15001" +
            "&actions=CALL,DENIAL&after_id=" + watermark + "&limit=2"));
        assertEquals(List.of(12_500L), activityTimes(nextPoll));
        assertFalse((Boolean)nextPoll.get("has_more"));
        assertTrue(number(nextPoll.get("watermark_id")) > watermark);
        assertEquals(nextPoll.get("watermark_id"), nextPoll.get("next_after_id"));
    }

    @Test
    void activityOwnerForwardWindowSignalsDatabaseReset()
    {
        boolean[] queried = new boolean[1];
        Map<String,Object> empty = mDatabase.activity(request(
            "/?radio_system_key=" + RADIO_SYSTEM_KEY + "&from_ms=1000000000000&to_ms=1000000001000&actions=JOIN,DENIAL&after_id=0"),
            ignored -> queried[0] = true);
        assertTrue(queried[0]);
        assertTrue(rows(empty).isEmpty());
        assertFalse((Boolean)empty.get("has_more"));
        assertFalse((Boolean)empty.get("reset_required"));
        long currentMaximum = number(empty.get("watermark_id"));
        assertEquals(currentMaximum, number(empty.get("next_after_id")));

        Map<String,Object> reset = mDatabase.activity(request(
            "/?radio_system_key=" + RADIO_SYSTEM_KEY + "&from_ms=1&to_ms=1000&actions=JOIN&after_id=" + (currentMaximum + 1)));
        assertTrue(rows(reset).isEmpty());
        assertFalse((Boolean)reset.get("has_more"));
        assertTrue((Boolean)reset.get("reset_required"));
        assertEquals(currentMaximum, number(reset.get("watermark_id")));
        assertEquals(currentMaximum, number(reset.get("next_after_id")));
    }

    @Test
    void p25HistoryFiltersBeforePagingAndPreservesOwnedLabelsAndCanonicalIdentities() throws Exception
    {
        seedActivityFilterRows();
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
                "VALUES(9901,0x12345,0x123,777)");
            statement.executeUpdate("UPDATE radio_system_identity_summary SET p25_subscriber_identity_id=9901," +
                "last_talker_alias='Unit OTA',last_talker_alias_seen_ms=13000 WHERE id=7102");
            statement.executeUpdate("UPDATE receiver_activity_event SET source_observed_working_id=202 " +
                "WHERE source_identity_summary_id=7102");
            statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(9901,71,'North Unit','RADIO_ID','APCO25',202)," +
                "(9902,72,'South Unit','RADIO_ID','APCO25',202)");
            statement.executeUpdate("UPDATE configuration_channel SET decoder_type='P25_CONVENTIONAL' " +
                "WHERE configuration_id='" + ANALOG_CHANNEL + "'");
            statement.executeUpdate("UPDATE configuration_channel SET decoder_type='P25_PHASE2' " +
                "WHERE configuration_id='" + P25_CHANNEL_B + "'");
            statement.executeUpdate("UPDATE receiver_channel SET radio_system_id=NULL," +
                "radio_system_assigned_at_ms=NULL WHERE id=71");
            statement.executeUpdate("INSERT INTO radio_system(id,system_key,configuration_id,protocol_code," +
                "address_domain_code,first_seen_ms,last_seen_ms) VALUES(99,'dmr:channel:" + DMR_CHANNEL +
                "','" + DMR_CHANNEL + "',3,0,1000,15000)");
            // Interleave unrelated conventional/P25 and DMR observations ahead of the relevant page boundary.
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code)
                WITH RECURSIVE rows(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM rows WHERE n<2000)
                SELECT 10000+n,CASE WHEN n%2=0 THEN 73 ELSE 74 END,71,12500,4 FROM rows
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,
                    source_observed_local_id,target_observed_local_id,target_kind_code,
                    source_identity_summary_id,target_identity_summary_id,source_observed_working_id)
                VALUES(12001,72,71,14500,4,202,101,1,7102,7101,202)
                """);
            statement.executeUpdate("INSERT INTO receiver_activity_event(id,channel_id,radio_system_id," +
                "observed_at_ms,action_code) VALUES(12002,71,99,14501,4)");
            // A much newer unrelated protocol history must not be walked to find sparse P25 facts.
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code)
                WITH RECURSIVE rows(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM rows WHERE n<100000)
                SELECT 500000+n,71,99,900000+n,4 FROM rows
                """);
            statement.executeUpdate("ANALYZE");
        }
        StatsWebDatabase.ActivityQuery[] query = new StatsWebDatabase.ActivityQuery[1];
        Map<String,Object> page = mDatabase.activity(request("/?projection=p25_visualizer&from_ms=9000&to_ms=15002" +
            "&actions=CALL,DENIAL,GRANT&after_id=0&limit=500"), value -> query[0] = value);
        List<ObjectNode> actual = StatsApiV1Payload.p25VisualizerRows(StatsApiV1Payload.source(rows(page)));
        assertFalse(actual.isEmpty());
        assertFalse(actual.stream().anyMatch(row -> row.path("id").asLong() > 10000 && row.path("id").asLong() < 12001));
        assertFalse(actual.stream().anyMatch(row -> row.path("id").asLong() == 12002));
        ObjectNode north = actual.stream().filter(row -> row.path("observed_at_ms").asLong() == 10000).findFirst().orElseThrow();
        ObjectNode south = actual.stream().filter(row -> row.path("id").asLong() == 12001).findFirst().orElseThrow();
        assertEquals("North Unit", north.path("source_alias_name").textValue());
        assertEquals(RADIO_SYSTEM_KEY, north.path("radio_system_key").textValue());
        assertEquals("South Unit", south.path("source_alias_name").textValue());
        assertEquals("Fireground", south.path("target_alias_name").textValue());
        assertEquals(777, StatsApiV1Payload.present(north).at("/source_canonical_identity/subscriber_id").intValue());
        assertFalse(north.has("frequency_hz"));
        assertFalse(north.has("event_type"));
        assertEquals(actual, StatsApiV1Payload.p25VisualizerRows(StatsApiV1Payload.source(rows(mDatabase.activity(
            request("/?projection=p25_visualizer&from_ms=9000&to_ms=15002&actions=CALL,DENIAL,GRANT&after_id=0&limit=500"))))),
            "Repeated history reads use the same compact projection");
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            List<String> plan = explain(connection, query[0].sql(), query[0].parameters().toArray());
            assertTrue(plan.stream().anyMatch(detail -> detail.contains("INTEGER PRIMARY KEY")),
                () -> "Forward history must seek by saved row ID: " + plan);
            assertFalse(query[0].sql().contains("activity.frequency_hz"));
            assertTrue(query[0].sql().indexOf("candidate.action_code IN") < query[0].sql().indexOf("LIMIT ?"));
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code)
                WITH RECURSIVE rows(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM rows WHERE n<501)
                SELECT 20000+n,71,71,20000+n,4 FROM rows
                """);
        }
        Map<String,Object> noteworthy = mDatabase.activity(request("/?projection=p25_visualizer&from_ms=9000&to_ms=22000" +
            "&actions=DENIAL&after_id=0&limit=500"));
        assertEquals(List.of(12_000L), activityTimes(noteworthy),
            "More than500 newer routine calls cannot evict earlier matching history");
    }

    @Test
    void currentP25AffiliationsUseSkinnyCurrentTablesAndTypedChannelOwnedPresentation() throws Exception
    {
        seedActivityFilterRows();
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM trunked_radio_affiliation");
            statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(9901,71,'Current Unit','RADIO_ID','APCO25',202)");
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation(radio_system_id,radio_identity_id,talkgroup_identity_id,
                    channel_id,radio_observed_local_id,talkgroup_observed_local_id,confirmed_at_ms,radio_observed_working_id)
                VALUES(71,7102,7101,71,202,101,10000,202)
                """);
            List<String> plan = explain(connection,StatsWebDatabase.P25_AFFILIATION_CORE_SQL);
            assertTrue(plan.stream().anyMatch(detail -> detail.contains("idx_trunked_radio_affiliation_retention")));
            assertFalse(plan.stream().anyMatch(detail -> detail.contains("receiver_activity_event")));
        }
        List<Map<String,Object>> core = mDatabase.p25RawSnapshot().affiliations();
        assertEquals(1,core.size());
        ObjectNode row = mDatabase.presentP25Affiliations(core).getFirst();
        assertEquals(RADIO_SYSTEM_KEY,row.path("radio_system_key").textValue());
        assertEquals("v1-r-bee00-49f-202",row.path("radio_identity_key").textValue());
        assertEquals("v1-g-bee00-49f-101",row.path("talkgroup_identity_key").textValue());
        assertEquals(P25_CHANNEL_A,row.path("configuration_id").textValue());
        assertEquals(10000,row.path("confirmed_at_ms").asLong());
        assertEquals("Current Unit",row.path("radio_alias_name").textValue());
        assertEquals("p25",row.path("protocol").textValue());
        assertFalse(row.has("id"));
        assertFalse(row.has("alias_list_id"));
        assertFalse(row.has("action"),"current state must not impersonate an Activity event");
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
                "VALUES(9901,0x12345,0x123,777)");
            statement.executeUpdate("UPDATE radio_system_identity_summary SET p25_subscriber_identity_id=9901 WHERE id=7102");
        }
        List<Map<String,Object>> corrected = mDatabase.p25RawSnapshot().affiliations();
        assertFalse(core.equals(corrected),"same current radio key must notice canonical corrections");
        assertEquals(777,mDatabase.presentP25Affiliations(corrected).getFirst().at("/radio_canonical_identity/subscriber_id").asInt());
        // A first subscriber presents the entire current table, not a single 500-row Activity page.
        List<Map<String,Object>> bulk = new ArrayList<>();
        for(int index = 0; index < 10_001; index++)
        {
            Map<String,Object> value = new LinkedHashMap<>(corrected.getFirst());
            int radio = 100_000 + index;
            value.put("source_identity_key", "v1-r-bee00-49f-" + radio);
            value.put("source_native_id", radio);
            value.put("source_radio_id", radio);
            value.put("source_observed_working_id", radio);
            value.put("source_p25_subscriber_identity_id", (long)radio);
            value.put("source_canonical_wacn", 0xBEE00);
            value.put("source_canonical_system_id", 0x49F);
            value.put("source_canonical_subscriber_id", radio);
            bulk.add(value);
        }
        List<ObjectNode> presented = mDatabase.presentP25Affiliations(bulk);
        assertEquals(10_001, presented.size());
        assertEquals("v1-r-bee00-49f-100000", presented.getFirst().path("radio_identity_key").textValue());
        assertEquals("v1-r-bee00-49f-110000", presented.getLast().path("radio_identity_key").textValue());
        assertEquals(row.get("talkgroup_alias_name"), presented.getLast().get("talkgroup_alias_name"));
        assertEquals(110_000, presented.getLast().at("/radio_canonical_identity/subscriber_id").asInt());
        assertFalse(bulk.getFirst().containsKey("source_alias_name"), "presentation must not mutate native reader rows");
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        { statement.executeUpdate("DELETE FROM trunked_radio_affiliation"); }
        assertTrue(mDatabase.p25RawSnapshot().affiliations().isEmpty(),"logout/retention/site-clear absence is authoritative");
    }

    @Test
    void activityGlobalWindowAndForwardParametersStayBounded()
    {
        StatsApiException missingActions = assertThrows(StatsApiException.class, () -> mDatabase.activity(
            request("/?from_ms=1&to_ms=1000&after_id=0")));
        assertEquals("radio_system_key", missingActions.field());
        StatsApiException oversizedWindow = assertThrows(StatsApiException.class, () -> mDatabase.activity(
            request("/?from_ms=1&to_ms=86400002&actions=JOIN&after_id=0")));
        assertEquals("radio_system_key", oversizedWindow.field());
        StatsApiException duplicateAction = assertThrows(StatsApiException.class, () -> mDatabase.activity(
            request("/?from_ms=1&to_ms=1000&actions=JOIN,join&after_id=0")));
        assertEquals("actions", duplicateAction.field());
        StatsApiException conflictingAction = assertThrows(StatsApiException.class, () -> mDatabase.activity(
            request("/?radio_system_key=" + RADIO_SYSTEM_KEY + "&action=JOIN&actions=DENIAL")));
        assertEquals("actions", conflictingAction.field());
        StatsApiException conflictingCursor = assertThrows(StatsApiException.class, () -> mDatabase.activity(
            request("/?radio_system_key=" + RADIO_SYSTEM_KEY + "&before_id=1&after_id=0")));
        assertEquals("after_id", conflictingCursor.field());
        StatsApiException orphanedWatermark = assertThrows(StatsApiException.class, () -> mDatabase.activity(
            request("/?radio_system_key=" + RADIO_SYSTEM_KEY + "&watermark_id=1")));
        assertEquals("watermark_id", orphanedWatermark.field());
        StatsApiException oversizedPage = assertThrows(StatsApiException.class, () -> mDatabase.activity(
            request("/?radio_system_key=" + RADIO_SYSTEM_KEY + "&from_ms=1&to_ms=1000&actions=JOIN&after_id=0&limit=501")));
        assertEquals("limit", oversizedPage.field());

    }

    @Test
    void encryptedOwnerHistoryUsesSparseIndexesWithoutPlannerStatistics() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            try(ResultSet resultSet = statement.executeQuery(
                "SELECT count(*) FROM sqlite_stat1"))
            {
                assertTrue(resultSet.next());
                assertEquals(0, resultSet.getInt(1),
                    "Fresh retention creation can create sqlite_stat1; this fixture has no planner statistics");
            }

            statement.executeUpdate("""
                WITH digits(value) AS (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)),
                ten_thousand(value) AS (
                    SELECT a.value + 10*b.value + 100*c.value + 1000*d.value
                    FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d
                ),
                events(value) AS (
                    SELECT value FROM ten_thousand
                    UNION ALL
                    SELECT value + 10000 FROM ten_thousand
                )
                INSERT INTO receiver_activity_event (
                    channel_id, radio_system_id, observed_at_ms, action_code,
                    source_observed_local_id, encrypted)
                SELECT 71, 71, 20000 + value, 23, value % 500,
                       CASE WHEN value % 100 = 0 THEN 1 ELSE 0 END
                FROM events
                """);
        }

        StatsWebDatabase.ActivityQuery[] channelEncrypted = new StatsWebDatabase.ActivityQuery[1];
        Map<String,Object> channelPage = mDatabase.activity(request("/?configuration_id=" + P25_CHANNEL_A +
            "&encryption=encrypted&hide_grants=true&limit=200"),
            query -> channelEncrypted[0] = query);
        assertEquals(200, rows(channelPage).size());
        assertTrue(channelEncrypted[0].sql().contains(
            "receiver_activity_event AS candidate INDEXED BY " +
                "idx_receiver_activity_event_channel_encrypted_time"));
        assertTrue(channelEncrypted[0].sql().contains("candidate.encrypted = 1"));
        assertFalse(channelEncrypted[0].sql().contains("candidate.encrypted = ?"));

        StatsWebDatabase.ActivityQuery[] systemEncrypted = new StatsWebDatabase.ActivityQuery[1];
        mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&encryption=encrypted&hide_grants=true&limit=200"),
            query -> systemEncrypted[0] = query);
        assertTrue(systemEncrypted[0].sql().contains(
            "receiver_activity_event AS candidate INDEXED BY " +
                "idx_receiver_activity_event_system_encrypted_time"));

        StatsWebDatabase.ActivityQuery[] clear = new StatsWebDatabase.ActivityQuery[1];
        mDatabase.activity(request("/?configuration_id=" + P25_CHANNEL_A +
            "&encryption=clear&limit=1"), query -> clear[0] = query);
        assertFalse(clear[0].sql().contains("INDEXED BY"));
        assertTrue(clear[0].sql().contains("candidate.encrypted = ?"));

        StatsWebDatabase.ActivityQuery[] unfiltered = new StatsWebDatabase.ActivityQuery[1];
        mDatabase.activity(request("/?configuration_id=" + P25_CHANNEL_A + "&limit=1"),
            query -> unfiltered[0] = query);
        assertFalse(unfiltered[0].sql().contains("INDEXED BY"));

        StatsWebDatabase.ActivityQuery[] identitySpecific = new StatsWebDatabase.ActivityQuery[1];
        mDatabase.activity(request("/?configuration_id=" + P25_CHANNEL_A +
            "&encryption=encrypted&source_id=1&limit=1"), query -> identitySpecific[0] = query);
        assertFalse(identitySpecific[0].sql().contains("INDEXED BY"));
        assertTrue(identitySpecific[0].sql().contains("candidate.encrypted = ?"));

        StatsWebDatabase.ActivityQuery[] forward = new StatsWebDatabase.ActivityQuery[1];
        mDatabase.activity(request("/?configuration_id=" + P25_CHANNEL_A +
            "&encryption=encrypted&after_id=0&limit=200"), query -> forward[0] = query);
        assertFalse(forward[0].sql().contains("INDEXED BY"));
        assertTrue(forward[0].sql().contains("candidate.encrypted = ?"));

        try(Connection connection = openFixture())
        {
            List<String> channelPlan = explain(connection, channelEncrypted[0].sql(),
                channelEncrypted[0].parameters().toArray());
            assertTrue(channelPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_channel_encrypted_time")),
                () -> "Expected the sparse channel/encrypted/time index without stats, plan was: " + channelPlan);
            List<String> systemPlan = explain(connection, systemEncrypted[0].sql(),
                systemEncrypted[0].parameters().toArray());
            assertTrue(systemPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_system_encrypted_time")),
                () -> "Expected the sparse system/encrypted/time index without stats, plan was: " + systemPlan);
        }
    }

    @Test
    void radioHistoryKeepsBroadPagesOnIdentityIndexesAndSelectiveActionPaths() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms, logical_call_count)
                VALUES (7105, 71, 2, 0xBEE00, 0x49F, 303, 1000, 2000000, 1),
                       (7106, 71, 2, 0xBEE00, 0x49F, 404, 1000, 2000000, 1)
                """);
            statement.executeUpdate("""
                WITH digits(value) AS (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)),
                events(value) AS (
                    SELECT a.value + 10*b.value + 100*c.value + 1000*d.value + 10000*e.value
                    FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d CROSS JOIN digits e
                )
                INSERT INTO receiver_activity_event (
                    channel_id, radio_system_id, observed_at_ms, action_code,
                    source_identity_summary_id, source_identity_kind_code, source_observed_local_id,
                    target_identity_summary_id, target_kind_code, target_observed_local_id)
                SELECT 71, 71, 1000000 + value, 20, 7105, 2, 303, 7105, 2, 303
                FROM events
                """);
            statement.executeUpdate("""
                WITH digits(value) AS (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)),
                events(value) AS (
                    SELECT a.value + 10*b.value + 100*c.value + 1000*d.value
                    FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d
                )
                INSERT INTO receiver_activity_event (
                    channel_id, radio_system_id, observed_at_ms, action_code,
                    source_identity_summary_id, source_identity_kind_code, source_observed_local_id,
                    target_identity_summary_id, target_kind_code, target_observed_local_id)
                SELECT 71, 71, 2000000 + value, 20, 7105, 2, 303, 7106, 2, 404
                FROM events WHERE value <= 1000
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event (
                    id, channel_id, radio_system_id, observed_at_ms, action_code,
                    source_identity_summary_id, source_identity_kind_code, source_observed_local_id,
                    target_identity_summary_id, target_kind_code, target_observed_local_id)
                VALUES (500001, 71, 71, 1000, 20, 7102, 2, 202, 7105, 2, 303),
                       (500002, 71, 71, 2000, 20, 7105, 2, 303, 7102, 2, 202),
                       (500003, 71, 71, 3000, 20, 7102, 2, 202, 7102, 2, 202),
                       (500004, 71, 71, 4000, 4, 7102, 2, 202, 7105, 2, 303),
                       (500005, 71, 71, 5000, 20, 7102, 2, 202, 7105, 2, 303),
                       (500006, 71, 71, 5000, 20, 7105, 2, 303, 7102, 2, 202),
                       (500007, 72, 71, 6000, 20, 7102, 2, 202, 7105, 2, 303),
                       (500008, 71, 71, 7000, 4, 7106, 2, 404, 7105, 2, 303)
                """);
            statement.executeUpdate("ANALYZE");
            // Production averages tied even though this particular radio had only a few events. Keep that skew
            // explicit so the plan test does not depend on the small fixture's naturally favorable averages.
            statement.executeUpdate("""
                UPDATE sqlite_stat1 SET stat = '100007 1000 1'
                WHERE idx IN ('idx_receiver_activity_event_source_time', 'idx_receiver_activity_event_target_time')
                """);
            statement.executeUpdate("""
                UPDATE sqlite_stat1 SET stat = '100007 1000 1000 1 1'
                WHERE idx = 'idx_receiver_activity_event_system_action_time'
                """);
            statement.execute("ANALYZE sqlite_schema");
        }

        String broadScope = "/?radio_system_key=" + RADIO_SYSTEM_KEY + "&radio_identity_key=" +
            p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 303) + "&hide_grants=true&limit=2";
        StatsWebDatabase.ActivityQuery[] broadQuery = new StatsWebDatabase.ActivityQuery[1];
        Map<String,Object> broad = mDatabase.activity(request(broadScope), query -> broadQuery[0] = query);
        assertEquals(List.of(2_001_000L, 2_000_999L), activityTimes(broad));
        assertTrue((Boolean)broad.get("has_more"));
        assertTrue(broadQuery[0].sql().contains("INDEXED BY idx_receiver_activity_event_source_time"));
        assertTrue(broadQuery[0].sql().contains("INDEXED BY idx_receiver_activity_event_target_time"),
            "Busy default history must not switch to system-wide scanning after 1,000 role events");
        Map<String,Object> broadNext = mDatabase.activity(request(broadScope + "&before_id=" +
            broad.get("next_before_id")));
        assertEquals(List.of(2_000_998L, 2_000_997L), activityTimes(broadNext));
        assertEquals(List.of(1_099_999L, 1_099_998L), activityTimes(mDatabase.activity(
            request(broadScope + "&radio_role=target"))));

        String identity303 = p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 303);
        String ownerScope = "/?radio_system_key=" + RADIO_SYSTEM_KEY + "&hide_grants=true&limit=2";
        StatsWebDatabase.ActivityQuery[] sourceFilter = new StatsWebDatabase.ActivityQuery[1];
        assertEquals(List.of(2_001_000L, 2_000_999L), activityTimes(mDatabase.activity(
            request(ownerScope + "&source_identity_key=" + identity303), query -> sourceFilter[0] = query)));
        assertTrue(sourceFilter[0].sql().contains("INDEXED BY idx_receiver_activity_event_source_time"));
        StatsWebDatabase.ActivityQuery[] targetFilter = new StatsWebDatabase.ActivityQuery[1];
        assertEquals(List.of(1_099_999L, 1_099_998L), activityTimes(mDatabase.activity(
            request(ownerScope + "&target_identity_key=" + identity303), query -> targetFilter[0] = query)));
        assertTrue(targetFilter[0].sql().contains("INDEXED BY idx_receiver_activity_event_target_time"),
            "System Activity target drilldowns need the same identity seek as the radio's target history");
        StatsWebDatabase.ActivityQuery[] conjunction = new StatsWebDatabase.ActivityQuery[1];
        assertEquals(List.of(1_099_999L, 1_099_998L), activityTimes(mDatabase.activity(
            request(ownerScope + "&source_identity_key=" + identity303 + "&target_identity_key=" + identity303),
            query -> conjunction[0] = query)));
        assertFalse(conjunction[0].sql().contains("INDEXED BY"),
            "Multiple identity constraints retain the planner's choice of the more selective side");

        String scope = "/?radio_system_key=" + RADIO_SYSTEM_KEY + "&radio_identity_key=" +
            p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 202) + "&action=REGISTER&hide_grants=true";
        StatsWebDatabase.ActivityQuery[] anyQuery = new StatsWebDatabase.ActivityQuery[1];
        Map<String,Object> any = mDatabase.activity(request(scope + "&radio_role=any&limit=200"),
            query -> anyQuery[0] = query);
        List<Long> expected = List.of(500007L, 500006L, 500005L, 500003L, 500002L, 500001L);
        assertEquals(expected, rows(any).stream().map(row -> number(row.get("id"))).toList(),
            "Source/target matches deduplicate while retaining timestamp/ID ordering");
        assertFalse((Boolean)any.get("has_more"));
        assertTrue(rows(any).stream().allMatch(row -> "REGISTER".equals(row.get("action"))));
        assertTrue(anyQuery[0].sql().contains("INDEXED BY idx_receiver_activity_event_source_time"));
        assertTrue(anyQuery[0].sql().contains("INDEXED BY idx_receiver_activity_event_target_time"));

        StatsWebDatabase.ActivityQuery[] sourceQuery = new StatsWebDatabase.ActivityQuery[1];
        Map<String,Object> source = mDatabase.activity(request(scope + "&radio_role=source&limit=200"),
            query -> sourceQuery[0] = query);
        assertEquals(List.of(6000L, 5000L, 3000L, 1000L), activityTimes(source));
        assertTrue(sourceQuery[0].sql().contains("INDEXED BY idx_receiver_activity_event_source_time"));
        assertFalse(sourceQuery[0].sql().contains("INDEXED BY idx_receiver_activity_event_target_time"));
        StatsWebDatabase.ActivityQuery[] targetQuery = new StatsWebDatabase.ActivityQuery[1];
        Map<String,Object> target = mDatabase.activity(request(scope + "&radio_role=target&limit=200"),
            query -> targetQuery[0] = query);
        assertEquals(List.of(5000L, 3000L, 2000L), activityTimes(target));
        assertTrue(targetQuery[0].sql().contains("INDEXED BY idx_receiver_activity_event_target_time"));
        assertFalse(targetQuery[0].sql().contains("INDEXED BY idx_receiver_activity_event_source_time"));

        Map<String,Object> first = mDatabase.activity(request(scope + "&limit=2"));
        assertEquals(List.of(6000L, 5000L), activityTimes(first));
        assertTrue((Boolean)first.get("has_more"));
        Map<String,Object> second = mDatabase.activity(request(scope + "&limit=2&before_id=" +
            first.get("next_before_id")));
        assertEquals(List.of(5000L, 3000L), activityTimes(second),
            "Backward pagination must retain the remaining event at the same timestamp");
        Map<String,Object> bounded = mDatabase.activity(request(scope + "&configuration_id=" + P25_CHANNEL_A +
            "&from_ms=2000&to_ms=6000&limit=200"));
        assertEquals(List.of(5000L, 5000L, 3000L, 2000L), activityTimes(bounded));

        StatsWebDatabase.ActivityQuery[] forwardQuery = new StatsWebDatabase.ActivityQuery[1];
        Map<String,Object> forward = mDatabase.activity(request(scope + "&after_id=500000&limit=200"),
            query -> forwardQuery[0] = query);
        assertEquals(expected.reversed(), rows(forward).stream().map(row -> number(row.get("id"))).toList());
        assertFalse(forwardQuery[0].sql().contains("INDEXED BY"),
            "Forward polling needs planner freedom to order by event ID");
        assertTrue(forwardQuery[0].sql().contains("ORDER BY candidate.id ASC"));

        String busyScope = "/?radio_system_key=" + RADIO_SYSTEM_KEY + "&radio_identity_key=" +
            p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 303) + "&action=CALL&limit=200";
        StatsWebDatabase.ActivityQuery[] busyAnyQuery = new StatsWebDatabase.ActivityQuery[1];
        mDatabase.activity(request(busyScope), query -> busyAnyQuery[0] = query);
        assertFalse(busyAnyQuery[0].sql().contains("INDEXED BY"),
            "Both busy roles retain planner freedom");
        StatsWebDatabase.ActivityQuery[] busyQuery = new StatsWebDatabase.ActivityQuery[1];
        Map<String,Object> busy = mDatabase.activity(request(busyScope + "&radio_role=target"),
            query -> busyQuery[0] = query);
        assertEquals(List.of(7000L, 4000L), activityTimes(busy));
        assertFalse(busyQuery[0].sql().contains("INDEXED BY"),
            "A busy radio with a rare action must retain the selective action-index access path");
        StatsWebDatabase.ActivityQuery[] mixedQuery = new StatsWebDatabase.ActivityQuery[1];
        Map<String,Object> mixed = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&radio_identity_key=" + p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 404) +
            "&action=CALL&limit=200"), query -> mixedQuery[0] = query);
        assertEquals(List.of(7000L), activityTimes(mixed));
        assertTrue(mixedQuery[0].sql().contains("INDEXED BY idx_receiver_activity_event_source_time"));
        assertFalse(mixedQuery[0].sql().contains("INDEXED BY idx_receiver_activity_event_target_time"),
            "The target role has 1,001 entries and must make its own decision independently of the sparse source");

        try(Connection connection = openFixture())
        {
            List<String> plan = explain(connection, anyQuery[0].sql(), anyQuery[0].parameters().toArray());
            assertTrue(plan.stream().anyMatch(detail -> detail.contains(
                "SEARCH candidate USING INDEX idx_receiver_activity_event_source_time")), plan.toString());
            assertTrue(plan.stream().anyMatch(detail -> detail.contains(
                "SEARCH candidate USING INDEX idx_receiver_activity_event_target_time")), plan.toString());
            List<String> broadPlan = explain(connection, broadQuery[0].sql(),
                broadQuery[0].parameters().toArray());
            assertTrue(broadPlan.stream().noneMatch(detail -> detail.contains(
                "SEARCH candidate USING INDEX idx_receiver_activity_event_system_time")), broadPlan.toString());
            List<String> busyPlan = explain(connection, busyQuery[0].sql(), busyQuery[0].parameters().toArray());
            assertTrue(busyPlan.stream().anyMatch(detail -> detail.contains(
                "SEARCH candidate USING INDEX idx_receiver_activity_event_system_action_time")), busyPlan.toString());
            AtomicInteger progressCalls = new AtomicInteger();
            ProgressHandler.setHandler(connection, 1_000, new ProgressHandler()
            {
                @Override
                protected int progress()
                {
                    return progressCalls.incrementAndGet() >= 100 ? 1 : 0;
                }
            });
            try
            {
                for(boolean sourceRole : List.of(true, false))
                {
                    String probe = StatsWebDatabase.activityRadioIndexProbeSql(sourceRole);
                    List<String> probePlan = explain(connection, probe, 7105L, 1001);
                    assertTrue(probePlan.stream().anyMatch(detail -> detail.contains(
                        "USING COVERING INDEX idx_receiver_activity_event_" +
                            (sourceRole ? "source" : "target") + "_time")), probePlan.toString());
                    try(PreparedStatement statement = connection.prepareStatement(probe))
                    {
                        statement.setLong(1, 7105);
                        statement.setInt(2, 1001);
                        try(ResultSet resultSet = statement.executeQuery())
                        {
                            assertTrue(resultSet.next());
                            assertEquals(1001, resultSet.getInt(1),
                                "The population probe must stop at its cap rather than counting the busy history");
                        }
                    }
                }
                for(StatsWebDatabase.ActivityQuery query : List.of(anyQuery[0], busyQuery[0], broadQuery[0],
                    sourceFilter[0], targetFilter[0]))
                {
                    try(PreparedStatement statement = connection.prepareStatement(query.sql()))
                    {
                        for(int index = 0; index < query.parameters().size(); index++)
                        {
                            statement.setObject(index + 1, query.parameters().get(index));
                        }
                        try(ResultSet resultSet = statement.executeQuery())
                        {
                            int matches = 0;
                            while(resultSet.next())
                            {
                                matches++;
                            }
                            assertEquals(query == anyQuery[0] ? 6 : query == busyQuery[0] ? 2 : 3, matches);
                        }
                    }
                }
            }
            finally
            {
                ProgressHandler.clearHandler(connection);
            }
            assertTrue(progressCalls.get() < 100,
                "Bounded probes plus rare-radio history must not revisit unrelated REGISTER events; callbacks=" +
                    progressCalls);
        }
    }

    @Test
    void activityUnionCursorIsStableAndScopedActionEventQueriesUseFormat24Indexes() throws Exception
    {
        seedActivityFilterRows();
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH digits(value) AS (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)),
                events(value) AS (
                    SELECT a.value + 10*b.value + 100*c.value + 1000*d.value
                    FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d
                )
                INSERT INTO receiver_activity_event (
                    channel_id, observed_at_ms, action_code, event_type_code,
                    source_observed_local_id, target_observed_local_id, target_kind_code)
                SELECT 74, 20000 + value, 4, 7, value % 500, (value + 17) % 500, 1
                FROM events
                """);
            statement.executeUpdate("ANALYZE");
        }
        String radio202 = p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 202);
        Map<String,Object> first = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&radio_identity_key=" + radio202 + "&radio_role=any&limit=1"));
        assertEquals(List.of(13_000L), activityTimes(first));
        assertTrue((Boolean)first.get("has_more"));
        Map<String,Object> second = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&radio_identity_key=" + radio202 + "&radio_role=any&limit=1&before_id=" +
            first.get("next_before_id")));
        assertEquals(List.of(12_000L), activityTimes(second));

        StatsWebDatabase.ActivityQuery[] systemAction = new StatsWebDatabase.ActivityQuery[1];
        mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY + "&action=DENIAL&limit=20"),
            query -> systemAction[0] = query);
        StatsWebDatabase.ActivityQuery[] channelEvent = new StatsWebDatabase.ActivityQuery[1];
        mDatabase.activity(request("/?configuration_id=" + P25_CHANNEL_A + "&event_type=DENIAL&limit=20"),
            query -> channelEvent[0] = query);
        StatsWebDatabase.ActivityQuery[] radioAny = new StatsWebDatabase.ActivityQuery[1];
        mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY + "&radio_identity_key=" + radio202 +
            "&radio_role=any&limit=20"), query -> radioAny[0] = query);
        StatsWebDatabase.ActivityQuery[] groupAll = new StatsWebDatabase.ActivityQuery[1];
        Map<String,Object> groupHistory = mDatabase.activity(request("/?radio_system_key=" + RADIO_SYSTEM_KEY +
            "&group_identity_key=" + p25IdentityKey(RadioSystemIdentityKey.KIND_TALKGROUP, 101) +
            "&group_match=all&hide_grants=true&limit=20"),
            query -> groupAll[0] = query);
        assertEquals(List.of(10_000L), activityTimes(groupHistory),
            "Direct and patch-member matches still deduplicate, and hidden grants stay excluded");
        assertTrue(groupAll[0].sql().contains("INDEXED BY idx_receiver_activity_event_target_time"));
        assertEquals(List.of(11_000L, 10_000L), activityTimes(mDatabase.activity(request(
            "/?radio_system_key=" + RADIO_SYSTEM_KEY + "&group_identity_key=" +
                p25IdentityKey(RadioSystemIdentityKey.KIND_TALKGROUP, 101) +
                "&group_match=via_patch&hide_grants=false&limit=20"))));
        StatsWebDatabase.ActivityQuery[] groupSource = new StatsWebDatabase.ActivityQuery[1];
        assertEquals(List.of(10_000L), activityTimes(mDatabase.activity(request(
            "/?radio_system_key=" + RADIO_SYSTEM_KEY + "&group_identity_key=" +
                p25IdentityKey(RadioSystemIdentityKey.KIND_TALKGROUP, 101) +
                "&source_identity_key=" + radio202 + "&hide_grants=true&limit=20"),
            query -> groupSource[0] = query)));
        assertFalse(groupSource[0].sql().contains("INDEXED BY"),
            "A group with an additional source constraint retains selective query planning");
        StatsWebDatabase.ActivityQuery[] channelSource = new StatsWebDatabase.ActivityQuery[1];
        mDatabase.activity(request("/?configuration_id=" + DMR_CHANNEL + "&source_id=17&limit=20"),
            query -> channelSource[0] = query);
        StatsWebDatabase.ActivityQuery[] channelTarget = new StatsWebDatabase.ActivityQuery[1];
        mDatabase.activity(request("/?configuration_id=" + DMR_CHANNEL + "&target_id=17&limit=20"),
            query -> channelTarget[0] = query);

        try(Connection connection = openFixture())
        {
            List<String> actionPlan = explain(connection, systemAction[0].sql(),
                systemAction[0].parameters().toArray());
            assertTrue(actionPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_system_action_time")),
                () -> "Expected the system/action/time index, plan was: " + actionPlan);
            List<String> eventPlan = explain(connection, channelEvent[0].sql(),
                channelEvent[0].parameters().toArray());
            assertTrue(eventPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_channel_event_type_time")),
                () -> "Expected the channel/event-type/time index, plan was: " + eventPlan);
            List<String> radioPlan = explain(connection, radioAny[0].sql(),
                radioAny[0].parameters().toArray());
            assertTrue(radioPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_source_time")),
                () -> "Expected an indexed source branch, plan was: " + radioPlan);
            assertTrue(radioPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_target_time")),
                () -> "Expected an indexed target branch, plan was: " + radioPlan);
            List<String> groupPlan = explain(connection, groupAll[0].sql(),
                groupAll[0].parameters().toArray());
            assertTrue(groupPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_target_time")),
                () -> "Expected an indexed direct-target branch, plan was: " + groupPlan);
            assertTrue(groupPlan.stream().anyMatch(detail ->
                    detail.contains("idx_activity_event_member_identity_event")),
                () -> "Expected an indexed patch-member branch, plan was: " + groupPlan);
            List<String> sourceIdPlan = explain(connection, channelSource[0].sql(),
                channelSource[0].parameters().toArray());
            assertTrue(sourceIdPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_channel_source_id_time")),
                () -> "Expected the channel/source-ID/time index, plan was: " + sourceIdPlan);
            List<String> targetIdPlan = explain(connection, channelTarget[0].sql(),
                channelTarget[0].parameters().toArray());
            assertTrue(targetIdPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_channel_target_id_time")),
                () -> "Expected the channel/target-ID/time index, plan was: " + targetIdPlan);
        }
    }

    @Test
    void activityAliasDiscoveryAndRadioDirectoryUseCurrentBoundedIndexes() throws Exception
    {
        StatsWebDatabase.ObservedGroupIdentityQuery[] observedQuery = new StatsWebDatabase.ObservedGroupIdentityQuery[1];
        mDatabase.observedGroupIdentities(71, request("/?limit=25"), query -> observedQuery[0] = query);

        try(Connection connection = openFixture())
        {
            List<String> activityPlan = explain(connection, StatsWebDatabase.ACTIVITY_SELECT_SQL +
                " AND activity.channel_id = ?" + StatsWebDatabase.ACTIVITY_ORDER_SQL, 71, 201);
            assertTrue(activityPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_channel_time")),
                () -> "Expected a channel/time activity lookup, plan was: " + activityPlan);
            assertTrue(activityPlan.stream().noneMatch(detail -> detail.contains("USE TEMP B-TREE")),
                () -> "Expected index-ordered activity paging, plan was: " + activityPlan);

            assertTrue(observedQuery[0] != null, "The observed-identity query must be reported");
            List<String> aliasPlan = explain(connection, observedQuery[0].sql(),
                observedQuery[0].parameters().toArray());
            assertTrue(aliasPlan.stream().anyMatch(detail ->
                    detail.contains("SEARCH calls") &&
                        detail.contains("idx_p25_site_call_identity_channel_time") &&
                        detail.contains("channel_id=?")),
                () -> "Expected discovery to seek selected-channel call buckets, plan was: " + aliasPlan);
            assertTrue(aliasPlan.stream().anyMatch(detail ->
                    detail.contains("SEARCH identity USING INTEGER PRIMARY KEY") ||
                        detail.contains("SEARCH identity USING INDEX idx_radio_system_identity")),
                () -> "Expected indexed radio-system identity lookup, plan was: " + aliasPlan);
            assertTrue(aliasPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_channel_radio_system") ||
                        detail.contains("sqlite_autoindex_receiver_channel_1")),
                () -> "Expected indexed channel ownership lookup, plan was: " + aliasPlan);
            assertTrue(aliasPlan.stream().noneMatch(detail -> detail.contains("receiver_activity_event")),
                () -> "Alias discovery must not scan detailed activity, plan was: " + aliasPlan);

            StatsSystemNameResolver.installCatalog(connection);
            String directorySql = "WITH radio_systems AS (" + StatsWebDatabase.radioSystemSummarySelect() +
                ") SELECT * FROM radio_systems ORDER BY last_seen_ms DESC, radio_system_key LIMIT ? OFFSET ?";
            List<String> directoryPlan = explain(connection, directorySql, 26, 0);
            assertTrue(directoryPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_channel_radio_system")),
                () -> "Expected indexed saved-channel ownership lookup, plan was: " + directoryPlan);
            assertTrue(directoryPlan.stream().noneMatch(detail -> detail.contains("receiver_activity_event")),
                () -> "The radio directory must use summaries, not detailed activity, plan was: " + directoryPlan);
        }
    }

    @Test
    void systemActivityAndPatchMemberPagingUseNormalizedIdsAndBoundedIndexes() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH digits(value) AS (
                    VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)
                ), events(value) AS (
                    SELECT a.value + 10*b.value + 100*c.value + 1000*d.value + 10000*e.value
                    FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d CROSS JOIN digits e
                )
                INSERT INTO receiver_activity_event(channel_id, radio_system_id, observed_at_ms, action_code)
                SELECT 71, 71, 1000 + value, 23 FROM events
                """);
            statement.executeUpdate("""
                INSERT INTO activity_event_identity_member(
                    event_id, radio_system_id, identity_summary_id, identity_kind_code, observed_local_id, channel_id)
                SELECT max(id), 71, 7101, 1, 101, 71 FROM receiver_activity_event
                """);
            statement.executeUpdate("ANALYZE");

            List<String> firstPagePlan = explain(connection, StatsWebDatabase.ACTIVITY_SELECT_SQL +
                " AND activity.radio_system_id = ?" + StatsWebDatabase.ACTIVITY_ORDER_SQL, 71, 201);
            assertTrue(firstPagePlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_system_time")),
                () -> "Expected indexed system Activity paging, plan was: " + firstPagePlan);
            assertTrue(firstPagePlan.stream().noneMatch(detail -> detail.contains("USE TEMP B-TREE")),
                () -> "System Activity first page must preserve index order: " + firstPagePlan);

            List<String> cursorPlan = explain(connection, StatsWebDatabase.ACTIVITY_SELECT_SQL +
                " AND activity.radio_system_id = ? AND (activity.observed_at_ms < ? OR " +
                "(activity.observed_at_ms = ? AND activity.id < ?))" + StatsWebDatabase.ACTIVITY_ORDER_SQL,
                71, 50_000, 50_000, 50_000, 201);
            assertTrue(cursorPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_system_time")),
                () -> "Expected indexed system Activity cursor paging, plan was: " + cursorPlan);
            assertTrue(cursorPlan.stream().noneMatch(detail -> detail.contains("USE TEMP B-TREE")),
                () -> "System Activity cursor page must preserve index order: " + cursorPlan);

            List<String> channelPlan = explain(connection, StatsWebDatabase.ACTIVITY_SELECT_SQL +
                " AND activity.channel_id = ?" + StatsWebDatabase.ACTIVITY_ORDER_SQL, 71, 201);
            assertTrue(channelPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_activity_event_channel_time")),
                () -> "Expected indexed saved-channel Activity paging, plan was: " + channelPlan);
            assertTrue(channelPlan.stream().noneMatch(detail -> detail.contains("USE TEMP B-TREE")),
                () -> "Saved-channel Activity must preserve index order: " + channelPlan);

            List<String> memberPlan = explain(connection, StatsWebDatabase.ACTIVITY_SELECT_SQL + """
                 AND activity.radio_system_id = ?
                 AND (activity.target_identity_summary_id = ? OR EXISTS (
                     SELECT 1 FROM activity_event_identity_member member
                     WHERE member.event_id = activity.id AND member.identity_summary_id = ?
                 ))
                """ + StatsWebDatabase.ACTIVITY_ORDER_SQL, 71, 7101, 7101, 201);
            assertTrue(memberPlan.stream().noneMatch(detail -> detail.contains("USE TEMP B-TREE")),
                () -> "Patch-member Activity must preserve system/time order: " + memberPlan);

            List<String> identityFirstMemberPlan = explain(connection, """
                SELECT event_id
                FROM activity_event_identity_member
                WHERE identity_summary_id = ?
                ORDER BY event_id DESC
                LIMIT ?
                """, 7101, 201);
            assertTrue(identityFirstMemberPlan.stream().anyMatch(detail ->
                    detail.contains("idx_activity_event_member_identity_event")),
                () -> "Expected indexed identity-first patch lookup, plan was: " + identityFirstMemberPlan);
            assertTrue(identityFirstMemberPlan.stream().noneMatch(detail -> detail.contains("USE TEMP B-TREE")),
                () -> "Identity-first patch lookup must preserve index order: " + identityFirstMemberPlan);
        }

        Map<String,Object> memberActivity = mDatabase.activity(request("/?radio_system_key=" +
            RADIO_SYSTEM_KEY + "&group_identity_key=" +
            p25IdentityKey(RadioSystemIdentityKey.KIND_TALKGROUP, 101) + "&limit=10"));
        assertEquals(1, rows(memberActivity).size());
    }

    @Test
    void csvDatasetsUseCurrentResourceNamesAndSnakeCaseFields()
    {
        StatsCsvExport channels = mDatabase.csvExport("channels", request("/"));
        String channelCsv = new String(channels.content(), StandardCharsets.UTF_8);
        assertTrue(channelCsv.contains("configuration_id"));
        assertTrue(channelCsv.contains("radio_system_key"));
        assertFalse(channelCsv.contains("scope_token"));
        assertFalse(channelCsv.contains("site_guid"));

        StatsCsvExport groups = mDatabase.csvExport("radio-system-group-identities",
            request("/?radio_system_key=" + RADIO_SYSTEM_KEY));
        assertTrue(groups.fileName().startsWith("sdrtrunk-radio-system-group-identities-"));
    }

    @Test
    void talkerAliasCsvExportsOnlyNonblankAliases() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE radio_system_identity_summary
                SET last_talker_alias = 'Unit 202 OTA', last_talker_alias_seen_ms = 6000,
                    encrypted_logical_call_count = 1
                WHERE id = 7102
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms, logical_call_count
                ) VALUES (7104, 71, 2, 0xBEE00, 0x49F, 204, 1000, 4000, 1)
                """);
        }

        StatsCsvExport export = mDatabase.csvExport("radio-system-talker-aliases",
            request("/?radio_system_key=" + RADIO_SYSTEM_KEY));
        String csv = new String(export.content(), StandardCharsets.UTF_8);
        assertEquals(1, export.rowCount());
        assertTrue(export.fileName().startsWith("sdrtrunk-radio-system-talker-aliases-"));
        assertTrue(csv.contains("Unit 202 OTA"));
        assertTrue(csv.contains("talker_alias_seen_utc"));
        assertFalse(csv.contains("v1-r-bee00-49f-204"));
    }

    private void assertQualityChannel(String configurationId, String radioSystemKey, String protocol,
                                      long expectedHealth)
    {
        Map<String,Object> response = mDatabase.channelQuality(configurationId,
            request("/?range=1h&points=60"));
        Map<String,Object> channel = rowsFrom(response, "channels").getFirst();
        assertEquals(configurationId, channel.get("configuration_id"));
        assertEquals(radioSystemKey, channel.get("radio_system_key"));
        assertEquals(protocol, channel.get("protocol"));
        assertEquals(expectedHealth, number(channel.get("decode_health_pct")));
        assertEquals(1, rowsFrom(channel, "series").size());
        assertFalse(channel.containsKey("channel_id"));
        assertFalse(channel.containsKey("radio_system_id"));
    }

    private static void seedTrunkedDmrAndNxdn(Statement statement) throws Exception
    {
        statement.executeUpdate("""
            INSERT INTO alias_list (id, name, family)
            VALUES (78, 'DMR Trunked', 'DMR'), (79, 'NXDN Trunked', 'NXDN')
            """);
        statement.executeUpdate("""
            INSERT INTO configuration_channel (
                configuration_id, channel_kind, sort_order, system_name, site_name, name,
                alias_list_id, decoder_type, address_domain_code, primary_frequency_hz, config_json
            ) VALUES
                ('%1$s', 'TRUNKED', 78, 'Metro DMR', 'Central', 'DMR Control',
                    78, 'DMR', 0, 451012500,
                    '{"decodeConfiguration":{"channelMode":"TRUNKED"}}'),
                ('%2$s', 'TRUNKED', 79, 'Regional NXDN', 'West', 'NXDN Control',
                    79, 'NXDN', 1, 155012500,
                    '{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
            """.formatted(DMR_TRUNKED_CHANNEL, NXDN_TRUNKED_CHANNEL));
        statement.executeUpdate("""
            INSERT INTO radio_system (
                id, system_key, configuration_id, protocol_code, address_domain_code,
                p25_wacn, p25_system_id, first_seen_ms, last_seen_ms
            ) VALUES
                (78, 'dmr:channel:%1$s', '%1$s', 3, 0, NULL, NULL, 1000, 4000),
                (79, 'nxdn-c:channel:%2$s', '%2$s', 4, 1, NULL, NULL, 1000, 4000)
            """.formatted(DMR_TRUNKED_CHANNEL, NXDN_TRUNKED_CHANNEL));
        statement.executeUpdate("""
            INSERT INTO receiver_channel (
                id, configuration_id, first_seen_ms, last_seen_ms,
                radio_system_id, radio_system_assigned_at_ms
            ) VALUES (78, '%1$s', 1000, 4000, 78, 1000),
                     (79, '%2$s', 1000, 4000, 79, 1000)
            """.formatted(DMR_TRUNKED_CHANNEL, NXDN_TRUNKED_CHANNEL));
        statement.executeUpdate("""
            INSERT INTO radio_system_identity_summary (
                id, radio_system_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms)
            VALUES (7801, 78, 1, 7, 1000, 4000),
                   (7802, 78, 1, 8, 1000, 4000),
                   (7803, 78, 2, 501, 1000, 4000),
                   (7804, 78, 2, 502, 1000, 4000),
                   (7901, 79, 1, 9, 1000, 4000),
                   (7902, 79, 1, 10, 1000, 4000),
                   (7903, 79, 2, 601, 1000, 4000),
                   (7904, 79, 2, 602, 1000, 4000)
            """);
        statement.executeUpdate("""
            INSERT INTO trunked_site_snapshot (
                channel_id, snapshot_hash, protocol_code, variant_code, observed_location_category_code,
                observed_network_id, observed_system_id, observed_site_id, observed_ran, observed_model_code,
                brand_code, mode_code,
                channel_type_code, color_code_ts1, color_code_ts2, current_repeater,
                service_flags, failure_code, primary_frequency_hz, current_control_hz,
                first_seen_ms, last_seen_ms, observation_count
            ) VALUES
                /* Incomplete native tuples intentionally keep these systems channel-scoped. */
                (78, '%1$s', 3, 1, 0, NULL, NULL, 20, NULL, 1, 1, 1, 1, 2, 3, NULL,
                    0, NULL, 451012500, 451012500, 1000, 4000, 4),
                (79, '%2$s', 4, 1, 1, 7, NULL, 9, 5, NULL, NULL, 1, NULL, NULL, NULL, 9,
                    16, NULL, 155012500, 155012500, 1000, 4000, 4)
            """.formatted("a".repeat(64), "b".repeat(64)));
    }

    private void seedCurrentRadioModel() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO alias_list (id, name, family) VALUES " +
                "(71, 'County A', 'P25'), (72, 'County B', 'P25'), " +
                "(73, 'Analog County', 'NBFM'), (74, 'DMR County', 'DMR')");
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    alias_list_id, radioresolve_id, decoder_type, primary_frequency_hz, config_json
                ) VALUES
                    ('%1$s', 'TRUNKED', 71, 'Shared P25', 'North', 'North Control', 71,
                     '10000000-0000-0000-0000-000000000071', 'P25_PHASE1', 851012500, '{}'),
                    ('%2$s', 'TRUNKED', 72, 'Shared P25', 'South', 'South Control', 72,
                     '10000000-0000-0000-0000-000000000072', 'P25_PHASE1', 852012500, '{}'),
                    ('%3$s', 'CONVENTIONAL', 73, 'County Fire', '', 'County Fire Dispatch', 73,
                     '10000000-0000-0000-0000-000000000073', 'NBFM', 154310000, '{}'),
                    ('%4$s', 'CONVENTIONAL', 74, 'DMR County', '', 'DMR Dispatch Repeater', 74,
                     '10000000-0000-0000-0000-000000000074', 'DMR', 460012500,
                     '{"decodeConfiguration":{"channelMode":"CONVENTIONAL"}}')
                """.formatted(P25_CHANNEL_A, P25_CHANNEL_B, ANALOG_CHANNEL, DMR_CHANNEL));
            statement.executeUpdate("""
                INSERT INTO radio_system (
                    id, system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                    first_seen_ms, last_seen_ms
                ) VALUES (71, '%s', 1, 0, 0xBEE00, 0x49F, 1000, 4000)
                """.formatted(RADIO_SYSTEM_KEY));
            statement.executeUpdate("""
                INSERT INTO receiver_channel (
                    id, configuration_id, first_seen_ms, last_seen_ms,
                    radio_system_id, radio_system_assigned_at_ms
                ) VALUES (71, '%1$s', 1000, 4000, 71, 1000),
                         (72, '%2$s', 1000, 4000, 71, 1000),
                         (73, '%3$s', 1000, 4000, NULL, NULL),
                         (74, '%4$s', 1000, 4000, NULL, NULL)
                """.formatted(P25_CHANNEL_A, P25_CHANNEL_B, ANALOG_CHANNEL, DMR_CHANNEL));
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (
                    channel_id, first_seen_ms, last_seen_ms, observation_count, protocol,
                    nac, rfss, site, primary_frequency_hz, current_control_hz
                ) VALUES (71, 1000, 4000, 4, 'APCO25', 0x49F, 1, 1, 851012500, 851012500),
                         (72, 1000, 4000, 4, 'APCO25', 0x49F, 1, 2, 852012500, 852012500)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms,
                    logical_call_count
                ) VALUES (7101, 71, 1, 0xBEE00, 0x49F, 101, 1000, 4000, 3),
                         (7102, 71, 2, 0xBEE00, 0x49F, 202, 1000, 4000, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, group_name, matcher_type, protocol, value)
                VALUES (7101, 71, 'Dispatch', 'Operations', 'TALKGROUP', 'APCO25', 101),
                       (7201, 72, 'Fireground', 'Operations', 'TALKGROUP', 'APCO25', 101),
                       (7401, 74, 'DMR Dispatch', 'Operations', 'TALKGROUP', 'DMR', 7)
                """);
            statement.executeUpdate("""
                INSERT INTO conventional_activity_summary (
                    channel_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, call_count
                ) VALUES (73, 154310000, -1, 1000, 4000, 5)
                """);
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_talkgroup_summary (
                    channel_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms,
                    call_count, encrypted_count
                ) VALUES (74, 460012500, 2, 7, 1000, 4000, 3, 0)
                """);
        }
    }

    private void seedActivityFilterRows() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms, logical_call_count
                ) VALUES (7103, 71, 1, 0xBEE00, 0x49F, 102, 1000, 14000, 1),
                         (7104, 71, 3, 0xBEE00, 0x49F, 900, 1000, 14000, 1),
                         (7105, 71, 2, 0xBEE00, 0x49F, 303, 1000, 14000, 1)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event (
                    channel_id, radio_system_id, observed_at_ms, action_code, event_type_code,
                    source_observed_local_id, target_observed_local_id, target_kind_code,
                    source_identity_summary_id, source_identity_kind_code, target_identity_summary_id,
                    frequency_hz, lcn_band, lcn_number, timeslot, encrypted)
                VALUES
                    (71, 71, 10000, 4, 7, 202, 101, 1, 7102, 2, 7101,
                        851012500, 1, 2, 1, 0),
                    (71, 71, 11000, 12, 7, 303, 900, 3, 7105, 2, 7104,
                        852012500, 3, 4, 2, 1),
                    (71, 71, 12000, 9, 57, 303, 202, 2, 7105, 2, 7102,
                        853012500, NULL, NULL, 1, 0),
                    (71, 71, 13000, 4, 19, 202, 303, 2, 7102, 2, 7105,
                        851012500, NULL, NULL, 2, 1),
                    (72, 71, 14000, 4, 7, 303, 102, 1, 7105, 2, 7103,
                        854012500, 5, 6, 1, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO activity_event_identity_member (
                    event_id, radio_system_id, identity_summary_id, identity_kind_code, observed_local_id, channel_id)
                SELECT id, 71, 7101, 1, 101, channel_id
                FROM receiver_activity_event
                WHERE observed_at_ms IN (10000, 11000)
                """);
            statement.executeUpdate("ANALYZE");
        }
    }

    private static StatsRequest request(String uri)
    {
        return StatsRequest.from(URI.create(uri));
    }

    private static String p25IdentityKey(int kind, int identifier)
    {
        return RadioSystemIdentityKey.format(kind, 0xBEE00, 0x49F, identifier);
    }

    private static String p25LocalRadioKey(int identifier)
    {
        return RadioSystemIdentityKey.format(RadioSystemIdentityKey.KIND_RADIO,
            RadioSystemIdentityKey.NO_HOME, RadioSystemIdentityKey.NO_HOME, identifier);
    }

    private static void assertDmrNativeIdentity(Map<String,Object> row)
    {
        assertEquals("dmr:tier3:small:42", row.get("radio_system_key"));
        assertEquals(2, number(row.get("dmr_model_code")));
        assertEquals(42, number(row.get("network_id")));
        assertEquals("TIER_III", row.get("variant"));

        if(row.containsKey("site_network_id"))
        {
            assertEquals(42, number(row.get("site_network_id")));
            assertEquals(2, number(row.get("site_model_code")));
        }
    }

    private static void assertNxdnNativeIdentity(Map<String,Object> row)
    {
        assertEquals("nxdn-c:local:303", row.get("radio_system_key"));
        assertEquals(3, number(row.get("nxdn_location_category_code")));
        assertEquals(303, number(row.get("system_id")));
        assertEquals("TYPE_C", row.get("variant"));

        if(row.containsKey("site_system_id"))
        {
            assertEquals(303, number(row.get("site_system_id")));
            assertEquals(3, number(row.get("site_location_category_code")));
        }
    }

    private static void insertP25ChannelIdentity(PreparedStatement statement, long bucket, int learnedSiteId,
                                                  int channelId, int role, int kind, int summaryId,
                                                  int observedLocalId, Integer observedWorkingId,
                                                  int calls) throws Exception
    {
        statement.setInt(1, 71);
        statement.setInt(2, learnedSiteId);
        statement.setInt(3, channelId);
        statement.setLong(4, bucket);
        statement.setInt(5, role);
        statement.setInt(6, kind);
        statement.setInt(7, summaryId);
        statement.setInt(8, observedLocalId);
        statement.setLong(9, bucket + 1);
        statement.setInt(10, calls);
        if(observedWorkingId != null)
        {
            statement.setInt(11, observedWorkingId);
        }
        else
        {
            statement.setNull(11, java.sql.Types.INTEGER);
        }
        statement.executeUpdate();
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

    @SuppressWarnings("unchecked")
    private static List<String> aliasListNames(Map<String,Object> radioSystem)
    {
        return ((List<Map<String,Object>>)radioSystem.get("alias_lists")).stream()
            .map(row -> String.valueOf(row.get("name"))).toList();
    }

    private static Map<String,Object> rowWith(List<Map<String,Object>> rows, String field, Object value)
    {
        return rows.stream().filter(row -> java.util.Objects.equals(value, row.get(field))).findFirst().orElseThrow();
    }

    private static long number(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static List<Long> activityTimes(Map<String,Object> page)
    {
        return rows(page).stream().map(row -> number(row.get("observed_at_ms"))).toList();
    }

    private static List<String> explain(Connection connection, String sql, Object... parameters) throws Exception
    {
        StatsSystemNameResolver.installCatalog(connection);
        try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql))
        {
            for(int index = 0; index < parameters.length; index++)
            {
                statement.setObject(index + 1, parameters[index]);
            }

            try(ResultSet resultSet = statement.executeQuery())
            {
                java.util.ArrayList<String> details = new java.util.ArrayList<>();

                while(resultSet.next())
                {
                    details.add(resultSet.getString("detail"));
                }

                return List.copyOf(details);
            }
        }
    }


    @Test
    void issiReadModelSeparatesCanonicalSubscribersFromWorkingAssignmentsAndBands() throws Exception
    {
        long now = System.currentTimeMillis();
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_subscriber_identity (
                    id, home_wacn, home_system_id, subscriber_id)
                VALUES (9001, 0x92498, 0x926, 34006),
                       (9002, 0xBEE00, 0x348, 9601699),
                       (9003, 0x92498, 0x926, 34007),
                       (9004, 0x90000, 0x002, 2),
                       (9005, 0x90000, 0x002, 10),
                       (9006, 0x90000, 0x00A, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms, logical_call_count, encrypted_logical_call_count,
                    p25_subscriber_identity_id)
                VALUES (7103, 71, 2, 0x92498, 0x926, 34006, %1$d, %4$d, 4, 1, 9001),
                       (7104, 71, 2, 0xBEE00, 0x348, 9601699, %1$d, %3$d, 2, 0, 9002),
                       (7105, 71, 2, 0x92498, 0x926, 34007, %1$d, %2$d, 0, 0, 9003),
                       (7106, 71, 2, 0x90000, 0x002, 2, %1$d, %3$d, 0, 0, 9004),
                       (7107, 71, 2, 0x90000, 0x002, 10, %1$d, %3$d, 0, 0, 9005),
                       (7108, 71, 2, 0x90000, 0x00A, 3, %1$d, %3$d, 0, 0, 9006)
                """.formatted(now - 5_000, now - 1_000, now - 500, now - 100));
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event (
                    channel_id, radio_system_id, observed_at_ms, action_code,
                    source_observed_local_id, source_identity_summary_id)
                VALUES (71, 71, %1$d, 9, 130004, 7106),
                       (71, 71, %1$d, 21, 130005, 7107)
                """.formatted(now - 300));
            statement.executeUpdate("""
                INSERT INTO trunked_radio_group_summary (
                    radio_system_id, radio_identity_id, group_identity_id, group_kind_code,
                    first_seen_ms, last_seen_ms, logical_call_count)
                VALUES (71, 7103, 7101, 1, %1$d, %2$d, 1)
                """.formatted(now - 5_000, now - 100));
            statement.executeUpdate("""
                INSERT INTO p25_wuid_assignment_observation_summary (
                    radio_system_id, working_id, p25_subscriber_identity_id,
                    first_observed_ms, last_observed_ms, last_registration_ms, last_affiliation_ms,
                    registration_count, affiliation_count, last_evidence_code,
                    last_channel_id)
                VALUES (71, 130001, 9001, %1$d, %2$d, %1$d, %2$d, 2, 1, 2, 71),
                       (71, 130002, 9001, %3$d, %4$d, %4$d, NULL, 1, 0, 1, 72),
                       (71, 130003, 9003, %3$d, %5$d, %5$d, NULL, 1, 0, 1, 72)
                """.formatted(now - 5_000, now - 1_000, now - 8_000, now - 4_000, now - 5_000));
            statement.executeUpdate("""
                INSERT INTO p25_foreign_system_band_summary (
                    channel_id, foreign_wacn, foreign_system_id, band, channel_type,
                    base_hz, spacing_hz, transmit_offset_hz, first_seen_ms, last_seen_ms,
                    observation_count)
                VALUES (71, 0x92498, 0x926, 1, 1, 851000000, 12500, -45000000, %1$d, %2$d, 2),
                       (72, 0x92498, 0x926, 2, 4, 762000000, 12500, 30000000, %1$d, %2$d, 3),
                       (71, 0xBEE00, 0x49F, 3, 1, 851000000, 12500, -45000000, %1$d, %2$d, 1)
                """.formatted(now - 5_000, now - 500));
            statement.executeUpdate("""
                INSERT INTO p25_foreign_system_band (
                    channel_id, foreign_wacn, foreign_system_id, band, channel_type,
                    base_hz, spacing_hz, transmit_offset_hz, confirmed_at_ms)
                VALUES (71, 0x92498, 0x926, 1, 1, 851000000, 12500, -45000000, %1$d),
                       (72, 0x92498, 0x926, 2, 4, 762000000, 12500, 30000000, %1$d)
                """.formatted(now - 500));
        }

        Map<String,Object> overview = mDatabase.radioSystemIssiOverview(RADIO_SYSTEM_KEY);
        assertEquals(6, number(overview.get("observed_subscriber_count")));
        assertEquals(2, number(overview.get("wuid_observed_subscriber_count")));
        assertEquals(3, number(overview.get("observed_assignment_pair_count")));
        assertEquals(4, number(overview.get("foreign_system_count")));
        assertEquals(6, number(overview.get("logical_call_count")));
        assertEquals(2, number(overview.get("advertised_band_count")));
        assertEquals(now - 100, number(overview.get("last_subscriber_observed_ms")),
            "later explicit call/directory evidence must advance the ISSI observation time");

        Map<String,Object> directoryRadio = rowWith(rows(mDatabase.radioSystemRadios(
            RADIO_SYSTEM_KEY, request("/?limit=20"))), "canonical_subscriber_id", 34006);
        assertNull(directoryRadio.get("working_subscriber_id"));
        String canonicalRadioKey = RadioSystemIdentityKey.format(
            RadioSystemIdentityKey.KIND_RADIO, 0x92498, 0x926, 34006);
        Map<String,Object> radioDetail = map(mDatabase.radio(RADIO_SYSTEM_KEY, canonicalRadioKey), "radio");
        assertNull(radioDetail.get("working_subscriber_id"));
        Map<String,Object> relationship = rows(mDatabase.radioSystemRelationships(
            RADIO_SYSTEM_KEY, request("/?radio_identity_key=" + canonicalRadioKey))).getFirst();
        assertNull(relationship.get("radio_working_subscriber_id"));

        List<Map<String,Object>> subscribers = rows(mDatabase.radioSystemIssiSubscribers(
            RADIO_SYSTEM_KEY, request("/?limit=20")));
        assertEquals(6, subscribers.size(), "WUID reassignments must not duplicate the canonical subscriber");
        Map<String,Object> current = rowWith(subscribers, "last_observed_working_id", 130001);
        assertEquals(0x92498, number(current.get("canonical_wacn")));
        assertEquals(0x926, number(current.get("canonical_system_id")));
        assertEquals(34006, number(current.get("canonical_subscriber_id")));
        assertEquals(P25_CHANNEL_A, map(current, "observed_on").get("configuration_id"));
        assertEquals("affiliation", current.get("last_assignment_evidence"));
        assertEquals(3, number(current.get("registration_observation_count")));
        assertEquals(1, number(current.get("affiliation_observation_count")));
        assertEquals(now - 8_000, number(current.get("first_seen_ms")));
        assertEquals(now - 100, number(current.get("last_seen_ms")));
        assertEquals(now - 1_000, number(current.get("last_mapping_observed_ms")));
        assertFalse(subscribers.stream().anyMatch(row -> number(row.get("last_observed_working_id")) == 130002),
            "the prior WUID remains in history without becoming a second subscriber row");

        Map<String,Object> historical = rowWith(subscribers, "last_observed_working_id", 130003);
        assertEquals("registration", historical.get("last_assignment_evidence"));
        Map<String,Object> callOnly = rowWith(subscribers, "canonical_subscriber_id", 9601699);
        assertNull(callOnly.get("last_observed_working_id"));
        assertEquals(2, number(callOnly.get("logical_call_count")));
        Map<String,Object> deniedOnly = rowWith(subscribers, "canonical_subscriber_id", 2);
        assertNull(deniedOnly.get("last_observed_working_id"));
        assertEquals(0, number(deniedOnly.get("logical_call_count")));

        List<Map<String,Object>> canonicalOrder = rows(mDatabase.radioSystemIssiSubscribers(
            RADIO_SYSTEM_KEY, request("/?sort=canonical_identity&direction=asc&limit=20")));
        assertEquals(List.of(2L, 10L), canonicalOrder.subList(0, 2).stream()
            .map(row -> number(row.get("canonical_subscriber_id"))).toList());

        List<Map<String,Object>> foreignSystems = rows(mDatabase.radioSystemIssiForeignSystems(
            RADIO_SYSTEM_KEY, request("/?limit=20")));
        assertEquals(4, foreignSystems.size(),
            "malformed band evidence naming the serving system must not create a foreign-system row");
        assertFalse(foreignSystems.stream().anyMatch(row -> number(row.get("foreign_wacn")) == 0xBEE00 &&
            number(row.get("foreign_system_id")) == 0x49F));
        Map<String,Object> advertised = rowWith(foreignSystems, "foreign_system_id", 0x926);
        assertEquals(2, number(advertised.get("subscriber_count")));
        assertEquals(2, number(advertised.get("wuid_observed_subscriber_count")));
        assertEquals(2, number(advertised.get("band_count")));
        List<Map<String,Object>> foreignIdentityOrder = rows(mDatabase.radioSystemIssiForeignSystems(
            RADIO_SYSTEM_KEY, request("/?sort=identity&direction=asc&limit=20")));
        assertEquals(List.of(2L, 10L), foreignIdentityOrder.subList(0, 2).stream()
            .map(row -> number(row.get("foreign_system_id"))).toList());

        List<Map<String,Object>> bands = rows(mDatabase.radioSystemIssiFrequencyBands(
            RADIO_SYSTEM_KEY, request("/?sort=identity&direction=asc&limit=20")));
        assertEquals(2, bands.size());
        assertEquals(List.of(P25_CHANNEL_A, P25_CHANNEL_B), bands.stream()
            .map(row -> String.valueOf(map(row, "observed_on").get("configuration_id"))).toList());
        assertTrue(rows(mDatabase.channelBands(P25_CHANNEL_A, request("/"))).isEmpty(),
            "Foreign ISSI band plans belong only to the radio-system ISSI page");
        assertTrue(rows(mDatabase.channelNeighbors(P25_CHANNEL_A, request("/"))).isEmpty(),
            "Foreign ISSI systems are not synthetic site neighbors");
    }



    @Test
    void foreignGroupHomesAppearWithoutSubscriberRowsOrDuplicateRadioCallCounts() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,
                    home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms,logical_call_count)
                VALUES (7201,71,1,0xD1234,0x789,501,1000,5000,100),
                       (7202,71,3,0xD1234,0x789,701,2000,6000,200),
                       (7203,71,1,0xE2345,0x801,501,3000,7000,300),
                       (7205,71,1,-1,-1,502,1000,8000,400)
                """);
            statement.executeUpdate("INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
                "VALUES(9201,0xD1234,0x789,7654321)");
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,
                    home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms,logical_call_count,
                    p25_subscriber_identity_id)
                VALUES(7204,71,2,0xD1234,0x789,7654321,1500,5500,2,9201)
                """);
        }
        Map<String,Object> overview = mDatabase.radioSystemIssiOverview(RADIO_SYSTEM_KEY);
        assertEquals(2, number(overview.get("foreign_system_count")));
        assertEquals(2, number(overview.get("observed_talkgroup_count")));
        assertEquals(1, number(overview.get("observed_patch_group_count")));
        assertEquals(2, number(overview.get("logical_call_count")),
            "radio and group participation cannot be summed into unique logical calls");
        assertEquals(7000, number(overview.get("last_group_observed_ms")));
        var systems = rows(mDatabase.radioSystemIssiForeignSystems(RADIO_SYSTEM_KEY, request("/?limit=20")));
        assertEquals(2, systems.size(), "legacy -1 unknown homes are not foreign systems");
        var first = rowWith(systems, "foreign_wacn", 0xD1234);
        assertEquals(1, number(first.get("subscriber_count")));
        assertEquals(1, number(first.get("talkgroup_count")));
        assertEquals(1, number(first.get("patch_group_count")));
        assertEquals(2, number(first.get("logical_call_count")));
        assertEquals(1000, number(first.get("first_seen_ms")));
        assertEquals(6000, number(first.get("last_seen_ms")));
        var groupOnly = rowWith(systems, "foreign_wacn", 0xE2345);
        assertEquals(0, number(groupOnly.get("subscriber_count")));
        assertEquals(1, number(groupOnly.get("talkgroup_count")));
        assertEquals(0, number(groupOnly.get("logical_call_count")));
        StatsWebDatabase.IdentityDirectoryQuery[] selected = new StatsWebDatabase.IdentityDirectoryQuery[1];
        var groups = rows(mDatabase.radioSystemGroupIdentities(RADIO_SYSTEM_KEY, request("/?limit=20"),
            query -> selected[0] = query));
        var row = rowWith(groups, "identity_summary_id", 7203);
        var payload = StatsApiV1Payload.present(row);
        assertEquals(0xE2345, payload.at("/canonical_group_identity/wacn").intValue());
        assertTrue(payload.path("observed_local_id").isNull(), "retained home rows supply no local mapping proof");
        assertFalse(selected[0].sql().contains("JOIN p25_subscriber_identity"),
            "group facts remain direct existing directory columns");
        try(Connection connection = openFixture())
        {
            List<String> plan = explain(connection, selected[0].sql(), selected[0].parameters().toArray());
            assertTrue(plan.stream().anyMatch(detail -> detail.contains("idx_radio_system_identity_last_seen") ||
                detail.contains("sqlite_autoindex_radio_system_identity_summary_1")), plan.toString());
            assertFalse(plan.stream().anyMatch(detail -> detail.contains("p25_subscriber_identity")), plan.toString());
        }
    }

    @Test
    void currentGroupAssociationUsesObservedLocalAliasAndExistingCanonicalRoute() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,
                    home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms)
                VALUES(7201,71,1,0xD1234,0x789,501,1000,5000)
                """);
            statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(7199,71,'Home ID must not match','TALKGROUP','APCO25',501)");
        }
        Map<String,Object> observed = new LinkedHashMap<>();
        observed.put("canonical_group_identity", Map.of("wacn", 0xD1234, "system_id", 0x789, "talkgroup_id", 501));
        observed.put("home_wacn", 0xD1234);
        observed.put("home_system_id", 0x789);
        observed.put("observed_local_id", 101);
        observed.put("configuration_id", P25_CHANNEL_A);
        observed.put("serving_system_key", RADIO_SYSTEM_KEY);
        observed.put("observed_on", Map.of("configuration_id", P25_CHANNEL_A));
        var named = mDatabase.enrichCurrentP25Assignments(RADIO_SYSTEM_KEY, List.of(observed)).getFirst();
        assertEquals("Dispatch", named.get("alias_name"));
        assertEquals("v1-g-d1234-789-501", named.get("identity_key"));
        assertEquals("talkgroup", map(named, "entity_ref").get("kind"));
        assertTrue(map(named, "observed_on").get("name") instanceof String);
        assertEquals(101, StatsApiV1Payload.present(named).path("observed_local_id").intValue());
        assertFalse(named.containsKey("p25_subscriber_identity_id"));
    }

    @Test
    void assignmentOnlyIssiSubscribersUseOnlyTheirExactChannelAliasList() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) VALUES
                    (9301,0x92498,0x926,34031),
                    (9302,0x92498,0x926,34032),
                    (9303,0x92498,0x926,34033),
                    (9304,0x92498,0x926,140004)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_wuid_assignment_observation_summary(
                    radio_system_id,working_id,p25_subscriber_identity_id,
                    first_observed_ms,last_observed_ms,last_registration_ms,last_affiliation_ms,
                    registration_count,affiliation_count,last_evidence_code,last_channel_id)
                VALUES
                    (71,140001,9301,1000,2000,2000,NULL,1,0,1,71),
                    (71,140002,9302,1000,2000,2000,NULL,1,0,1,72),
                    (71,140003,9303,1000,2000,2000,NULL,1,0,1,71),
                    (71,140004,9304,1000,2000,2000,NULL,1,0,1,71)
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) VALUES
                    (9301,71,'North Working ID','RADIO_ID','APCO25',140001),
                    (9302,72,'Canonical Subscriber','P25_SUBSCRIBER_IDENTITY',NULL,NULL),
                    (9303,72,'Working ID Must Lose','RADIO_ID','APCO25',140002),
                    (9304,72,'Wrong County Working ID','RADIO_ID','APCO25',140003),
                    (9305,71,'Equal Working ID','RADIO_ID','APCO25',140004)
                """);
            seedConfiguredSubscribers(statement);
            statement.executeUpdate("""
                INSERT INTO alias_p25_subscriber_identity(alias_id,p25_subscriber_identity_id)
                VALUES(9302,9302)
                """);
        }

        List<Map<String,Object>> subscribers = rows(mDatabase.radioSystemIssiSubscribers(
            RADIO_SYSTEM_KEY, request("/?limit=20")));
        assertTrue(subscribers.stream().noneMatch(row -> row.containsKey("entity_ref")),
            "assignment-only subscribers must not link to a radio detail row that does not exist");
        Map<String,Object> localFallback = rowWith(subscribers, "canonical_subscriber_id", 34031);
        assertEquals("North Working ID", localFallback.get("alias_name"));
        assertEquals(P25_CHANNEL_A, map(localFallback, "observed_on").get("configuration_id"));

        Map<String,Object> canonical = rowWith(subscribers, "canonical_subscriber_id", 34032);
        assertEquals("Canonical Subscriber", canonical.get("alias_name"),
            "the exact canonical Alias must win over an ordinary Working-ID Alias");

        Map<String,Object> wrongList = rowWith(subscribers, "canonical_subscriber_id", 34033);
        assertNull(wrongList.get("alias_name"),
            "an assignment must not borrow a Working-ID Alias from another saved channel");

        Map<String,Object> equalWorking = rowWith(subscribers, "canonical_subscriber_id", 140004);
        assertEquals(140004, number(equalWorking.get("last_observed_working_id")));
        assertEquals("Equal Working ID", equalWorking.get("alias_name"),
            "an explicit equal-valued Working ID retains its assignment provenance");
        assertTrue(subscribers.stream().noneMatch(row -> row.containsKey("observation_alias_list_id")));

        List<Map<String,Object>> searched = rows(mDatabase.radioSystemIssiSubscribers(
            RADIO_SYSTEM_KEY, request("/?q=140001&limit=20")));
        assertEquals(1, searched.size());
        assertEquals("North Working ID", searched.getFirst().get("alias_name"),
            "the exact-channel fallback must survive Working-ID filtering before enrichment");
    }



    @Test
    void activityLabelsOnlyExplicitCanonicalLocalAddressesAsWorkingIds() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_subscriber_identity (
                    id, home_wacn, home_system_id, subscriber_id)
                VALUES (9201, 0x92498, 0x926, 9601699)
                """);
            statement.executeUpdate("""
                UPDATE radio_system_identity_summary
                SET home_wacn = 0x92498, home_system_id = 0x926, identity_id = 9601699,
                    p25_subscriber_identity_id = 9201
                WHERE id = 7102
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms, logical_call_count)
                VALUES (7103, 71, 2, 0xBEE00, 0x49F, 203, 1000, 4000, 1)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event (
                    channel_id, radio_system_id, observed_at_ms, action_code,
                    source_observed_local_id, source_observed_working_id,
                    source_identity_summary_id, source_identity_kind_code)
                VALUES (71, 71, 5000, 23, 202, 202, 7102, 2),
                       (71, 71, 4000, 23, 203, NULL, 7103, 2)
                """);
        }

        List<Map<String,Object>> activity = rows(mDatabase.activity(request(
            "/?configuration_id=" + P25_CHANNEL_A + "&limit=10")));
        assertEquals(2, activity.size());
        Map<String,Object> canonical = activity.getFirst();
        assertEquals(0x92498, number(canonical.get("source_canonical_wacn")));
        assertEquals(0x926, number(canonical.get("source_canonical_system_id")));
        assertEquals(9_601_699, number(canonical.get("source_canonical_subscriber_id")));
        assertEquals(202, number(canonical.get("source_observed_working_id")));

        Map<String,Object> ordinary = activity.get(1);
        assertNull(ordinary.get("source_canonical_subscriber_id"));
        assertNull(ordinary.get("source_observed_working_id"),
            "an ordinary local P25 radio is not proven to use a roaming Working ID");
    }

    @Test
    void liveIssiLabelsSurviveMissingHistoryAndUseTheObservingChannelList() throws Exception
    {
        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id)
                VALUES (9991,0x92498,0x926,34031)
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value)
                VALUES (9911,71,'Local Working Alias','RADIO_ID','APCO25',140001),
                       (9912,72,'Wrong Channel Alias','RADIO_ID','APCO25',140001)
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type)
                VALUES (9913,71,'Permanent Subscriber Alias','P25_SUBSCRIBER_IDENTITY')
                """);
            seedConfiguredSubscribers(statement);
            statement.executeUpdate("INSERT INTO alias_p25_subscriber_identity VALUES (9913,9991)");
        }
        List<Map<String,Object>> input = List.of(new LinkedHashMap<>(Map.of(
            "canonical_wacn", 0x92498, "canonical_system_id", 0x926, "canonical_subscriber_id", 34031,
            "observed_working_id", 140001, "configuration_id", P25_CHANNEL_A,
            "observed_on", Map.of("configuration_id", P25_CHANNEL_A, "rfss", 1, "site_id", 1))));
        Map<String,Object> row = mDatabase.enrichCurrentP25Assignments(RADIO_SYSTEM_KEY, input).getFirst();
        assertEquals("Permanent Subscriber Alias", row.get("alias_name"));
        assertEquals("p25_subscriber_identity", row.get("alias_matcher_type"));
        assertEquals(71, number(row.get("alias_list_id")));
        assertNull(row.get("entity_ref"), "No persisted directory row means no fabricated radio route");
        assertEquals(140001, number(row.get("observed_working_id")));
        assertNull(row.get("last_talker_alias"));

        try(Connection connection = openFixture();
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,
                    home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms,
                    p25_subscriber_identity_id,last_talker_alias,last_talker_alias_seen_ms)
                VALUES(9991,71,2,0x92498,0x926,34031,1000,4000,9991,'Foreign unit OTA',4000),
                      (9992,71,2,0xBEE00,0x49F,34031,1000,4000,NULL,'Different home OTA',4000)
                """);
        }
        row = mDatabase.enrichCurrentP25Assignments(RADIO_SYSTEM_KEY, input).getFirst();
        assertEquals("Foreign unit OTA", row.get("last_talker_alias"));
        assertEquals(4000, number(row.get("last_talker_alias_seen_ms")));
        assertEquals("v1-r-92498-926-34031", row.get("identity_key"));
        List<Map<String,Object>> history = rows(mDatabase.radioSystemIssiSubscribers(RADIO_SYSTEM_KEY,
            request("/?q=Foreign%20unit%20OTA")));
        assertEquals(1, history.size(), "Radio History must search the displayed OTA alias before paging");
        assertEquals("Foreign unit OTA", history.getFirst().get("last_talker_alias"));
        assertEquals("v1-r-92498-926-34031", history.getFirst().get("identity_key"));

        input.getFirst().put("canonical_subscriber_id", 34032);
        row = mDatabase.enrichCurrentP25Assignments(RADIO_SYSTEM_KEY, input).getFirst();
        assertEquals("Local Working Alias", row.get("alias_name"));
        assertNull(row.get("last_talker_alias"));
        assertEquals("radio_id", row.get("alias_matcher_type"));
        input.getFirst().put("configuration_id", P25_CHANNEL_B);
        row = mDatabase.enrichCurrentP25Assignments(RADIO_SYSTEM_KEY, input).getFirst();
        assertEquals("Wrong Channel Alias", row.get("alias_name"));
    }

}
