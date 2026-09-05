/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/** Production-shaped navigation regressions for configured entities with optional retained activity. */
class StatsWebProductionNavigationRegressionTest
{
    private static final String RADIO_SYSTEM_KEY = "p25:bee00:49f";
    private static final String P25_CONFIGURATION_ID = "4b75217f-2555-4c38-aafc-5d17bc0faf71";
    private static final int ALIAS_ONLY_TALKGROUP = 56_735;
    private static final String CONVENTIONAL_CONFIGURATION_ID = "dcc948ac-6812-444c-a257-b9b350bb6f8f";
    private static final String RADIORESOLVE_ID = "728d2d66-de4e-476b-a696-919f32dd4d12";

    @TempDir
    Path mTemporaryFolder;
    private Path mDatabasePath;
    private StatsWebDatabase mDatabase;

    @BeforeEach
    void setUp() throws Exception
    {
        mDatabasePath = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(mDatabasePath);
        seedConfiguredP25System();
        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
    }

    @Test
    void returnsAliasOnlyTalkgroupWithZeroActivity()
    {
        Map<String,Object> system = map(mDatabase.radioSystem(RADIO_SYSTEM_KEY), "radio_system");
        assertEquals(RADIO_SYSTEM_KEY, system.get("radio_system_key"));
        assertEquals(Map.of("kind", "radio_system", "key", RADIO_SYSTEM_KEY), system.get("entity_ref"));

        Map<String,Object> groupIdentity = map(mDatabase.radioSystemGroupIdentity(
            RADIO_SYSTEM_KEY, "talkgroup", ALIAS_ONLY_TALKGROUP), "group_identity");
        assertEquals(RADIO_SYSTEM_KEY, groupIdentity.get("radio_system_key"));
        assertEquals(ALIAS_ONLY_TALKGROUP, number(groupIdentity.get("group_identity_id")));
        assertEquals("CuyCO Jail 35", groupIdentity.get("alias_name"));
        assertEquals(Map.of("kind", "talkgroup", "radio_system_key", RADIO_SYSTEM_KEY,
            "id", ALIAS_ONLY_TALKGROUP), groupIdentity.get("entity_ref"));

        for(String counter: List.of("logical_call_count", "source_logical_call_count",
            "target_logical_call_count", "encrypted_logical_call_count", "recorded_logical_call_count",
            "stream_submitted_logical_call_count", "signaling_observation_count", "radios",
            "affiliated_radios", "affiliated_channels", "channel_observation_count"))
        {
            assertEquals(0, number(groupIdentity.get(counter)), counter);
        }
    }

    @Test
    void configuredConventionalChannelNeedsNoObservationRow() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list (id, name, family) VALUES (92, 'Lake County', 'NBFM')");
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, name, alias_list_id,
                    radioresolve_id, decoder_type, primary_frequency_hz, config_json
                ) VALUES ('%1$s', 'CONVENTIONAL', 92, 'Lake County', 'LCSO TAC3', 92,
                    '%2$s', 'NBFM', 155730000, '{}')
                """.formatted(CONVENTIONAL_CONFIGURATION_ID, RADIORESOLVE_ID));
        }

        Map<String,Object> listed = rows(mDatabase.channelDirectory(request("/?type=conventional"))).getFirst();
        assertEquals(CONVENTIONAL_CONFIGURATION_ID, listed.get("configuration_id"));
        assertEquals(Map.of("kind", "channel", "key", CONVENTIONAL_CONFIGURATION_ID),
            listed.get("entity_ref"));

        Map<String,Object> detail = mDatabase.channelDetail(CONVENTIONAL_CONFIGURATION_ID, request("/"));
        Map<String,Object> channel = map(detail, "channel");
        assertEquals("LCSO TAC3", channel.get("name"));
        assertEquals(CONVENTIONAL_CONFIGURATION_ID, channel.get("configuration_id"));
        assertTrue(rowsFrom(detail, "summaries").isEmpty());

        WebEntityNavigationCatalog.Snapshot navigation = mDatabase.webEntityNavigationSnapshot();
        assertEquals(CONVENTIONAL_CONFIGURATION_ID,
            navigation.channel(CONVENTIONAL_CONFIGURATION_ID).entityRef().key());
        assertNull(navigation.channel(RADIORESOLVE_ID));
    }

    @Test
    void namesNeverIncludeDatabaseIds()
    {
        Map<String,Object> system = map(mDatabase.radioSystem(RADIO_SYSTEM_KEY), "radio_system");
        assertEquals("GCRCN", system.get("system_name"));
        assertEquals(List.of("GCRCN"), aliasListNames(system));
        assertEquals("GCRCNSimul", system.get("channel_names"));

        Map<String,Object> channel = rows(mDatabase.radioSystemChannels(RADIO_SYSTEM_KEY, request("/"))).getFirst();
        assertEquals("GCRCNSimul", channel.get("site_name"));
        assertEquals("GCRCN Control", channel.get("name"));
        assertEquals(Map.of("kind", "channel", "key", P25_CONFIGURATION_ID), channel.get("entity_ref"));

        for(Object label: List.of(system.get("system_name"), system.get("channel_names"),
            channel.get("site_name"), channel.get("name")))
        {
            assertFalse(String.valueOf(label).contains("(#"), String.valueOf(label));
        }
    }

    private void seedConfiguredP25System() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO alias_list (id, name, family) VALUES (91, 'GCRCN', 'P25')");
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    alias_list_id, radioresolve_id, decoder_type, primary_frequency_hz, config_json
                ) VALUES ('%1$s', 'TRUNKED', 91, 'GCRCN', 'GCRCNSimul', 'GCRCN Control',
                    91, '4b75217f-2555-4c38-aafc-5d17bc0faf72', 'P25_PHASE1', 856137500, '{}')
                """.formatted(P25_CONFIGURATION_ID));
            statement.executeUpdate("""
                INSERT INTO radio_system (
                    id, system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                    first_seen_ms, last_seen_ms
                ) VALUES (91, '%s', 1, 0, 0xBEE00, 0x49F, 1000, 2000)
                """.formatted(RADIO_SYSTEM_KEY));
            statement.executeUpdate("""
                INSERT INTO receiver_channel (id, configuration_id, first_seen_ms, last_seen_ms, radio_system_id)
                VALUES (91, '%s', 1000, 2000, 91)
                """.formatted(P25_CONFIGURATION_ID));
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (
                    channel_id, first_seen_ms, last_seen_ms, observation_count, protocol,
                    nac, rfss, site, primary_frequency_hz, current_control_hz
                ) VALUES (91, 1000, 2000, 10, 'APCO25', 0x49F, 1, 1, 856137500, 856137500)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, group_name, matcher_type, protocol, value)
                VALUES (9101, 91, 'CuyCO Jail 35', 'Corrections', 'TALKGROUP', 'APCO25', %d)
                """.formatted(ALIAS_ONLY_TALKGROUP));
        }
    }

    private static StatsRequest request(String uri)
    {
        return StatsRequest.from(URI.create(uri));
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Map<String,Object> source, String key)
    {
        return (Map<String,Object>)source.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> source)
    {
        return (List<Map<String,Object>>)source.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rowsFrom(Map<String,Object> source, String key)
    {
        return (List<Map<String,Object>>)source.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> aliasListNames(Map<String,Object> radioSystem)
    {
        return ((List<Map<String,Object>>)radioSystem.get("alias_lists")).stream()
            .map(row -> String.valueOf(row.get("name"))).toList();
    }

    private static long number(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0;
    }
}
