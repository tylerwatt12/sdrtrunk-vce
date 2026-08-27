/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
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

/**
 * Production-shaped navigation regressions for configured entities whose retained activity is empty or optional.
 */
class StatsWebProductionNavigationRegressionTest
{
    private static final String P25_SCOPE = "p25:BEE00:49F:alias-list:1";
    private static final String P25_CONFIGURATION_ID = "4b75217f-2555-4c38-aafc-5d17bc0faf71";
    private static final String P25_SITE_GUID = "4b75217f-2555-4c38-aafc-5d17bc0faf72";
    private static final int ALIAS_ONLY_TALKGROUP = 56_735;
    private static final String CONVENTIONAL_CONFIGURATION_ID =
        "dcc948ac-6812-444c-a257-b9b350bb6f8f";
    private static final String CONVENTIONAL_RADIORESOLVE_GUID =
        "728d2d66-de4e-476b-a696-919f32dd4d12";

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
    void preservesTheFullP25ScopeAndReturnsAnAliasOnlyTalkgroupWithZeroActivity()
    {
        Map<String,Object> system = map(mDatabase.system(request("/api/system?scope=" + P25_SCOPE)), "system");
        assertEquals(P25_SCOPE, system.get("scope_token"));
        assertEquals(Map.of("kind", "system", "key", P25_SCOPE), system.get("entity_ref"));

        Map<String,Object> talkgroup = map(mDatabase.talkgroup(request(
            "/api/talkgroup?scope=" + P25_SCOPE + "&talkgroup_id=" + ALIAS_ONLY_TALKGROUP)),
            "group_identity");
        assertEquals(P25_SCOPE, talkgroup.get("scope_token"));
        assertEquals(ALIAS_ONLY_TALKGROUP, number(talkgroup.get("talkgroup_id")));
        assertEquals("CuyCO Jail 35", talkgroup.get("alias_name"));
        assertEquals(Map.of("kind", "talkgroup", "scope", P25_SCOPE, "id", ALIAS_ONLY_TALKGROUP),
            talkgroup.get("entity_ref"));
        assertEquals(Map.of("kind", "system", "key", P25_SCOPE), talkgroup.get("system_entity_ref"));

        for(String counter: List.of("logical_call_count", "source_logical_call_count",
            "target_logical_call_count", "encrypted_logical_call_count", "recorded_logical_call_count",
            "stream_submitted_logical_call_count", "signaling_observation_count", "radios",
            "affiliated_radios", "affiliated_sites", "site_observation_count"))
        {
            assertEquals(0, number(talkgroup.get(counter)), counter);
        }
    }

    @Test
    void usesConfigurationIdentityForAConventionalChannelWithNoReceiverContext() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list (id, name, family) VALUES (2, 'Lake County', 'NBFM')");
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, name, alias_list_name,
                    radres_guid, decoder_type, primary_frequency_hz, config_json
                ) VALUES ('%1$s', 'CONVENTIONAL', 2, 'Lake County', 'LCSO TAC3', 'Lake County',
                    '%2$s', 'NBFM', 155730000, '{}')
                """.formatted(CONVENTIONAL_CONFIGURATION_ID, CONVENTIONAL_RADIORESOLVE_GUID));
        }

        List<Map<String,Object>> channels = rows(mDatabase.conventional(request("/api/conventional")));
        assertEquals(1, channels.size());
        Map<String,Object> listed = channels.getFirst();
        assertEquals(CONVENTIONAL_CONFIGURATION_ID, listed.get("configuration_id"));
        assertEquals(CONVENTIONAL_RADIORESOLVE_GUID, listed.get("guid"));
        assertEquals(Map.of("kind", "conventional", "key", CONVENTIONAL_CONFIGURATION_ID),
            listed.get("entity_ref"));
        assertNull(listed.get("context_key"));

        Map<String,Object> detail = mDatabase.conventionalDetail(request(
            "/api/conventional/detail?configuration_id=" + CONVENTIONAL_CONFIGURATION_ID));
        Map<String,Object> channel = map(detail, "channel");
        assertEquals("LCSO TAC3", channel.get("channel_name"));
        assertEquals(CONVENTIONAL_CONFIGURATION_ID, channel.get("configuration_id"));
        assertEquals(CONVENTIONAL_RADIORESOLVE_GUID, channel.get("guid"));
        assertEquals(Map.of("kind", "conventional", "key", CONVENTIONAL_CONFIGURATION_ID),
            channel.get("entity_ref"));
        assertNull(channel.get("context_key"));
        assertTrue(rowsFrom(detail, "summaries").isEmpty());

        WebEntityNavigationCatalog.Snapshot navigation = mDatabase.webEntityNavigationSnapshot();
        assertEquals(CONVENTIONAL_CONFIGURATION_ID,
            navigation.channel(CONVENTIONAL_CONFIGURATION_ID, null).entityRef().key());
        assertNull(navigation.channel(null, CONVENTIONAL_RADIORESOLVE_GUID),
            "RadioResolve correlation GUIDs must never become conventional navigation identities");
    }

    @Test
    void exposesConfiguredP25SiteLabelsWithoutAppendingTheAliasListDatabaseId()
    {
        Map<String,Object> system = map(mDatabase.system(request("/api/system?scope=" + P25_SCOPE)), "system");
        assertEquals("GCRCN", system.get("configured_system"));
        assertEquals("GCRCN", system.get("alias_list_name"));
        assertEquals(1, number(system.get("alias_list_id")));
        assertEquals("GCRCNSimul", system.get("site_names"));

        List<Map<String,Object>> sites = rows(mDatabase.systemSites(request(
            "/api/system/sites?scope=" + P25_SCOPE)));
        assertEquals(1, sites.size());
        Map<String,Object> site = sites.getFirst();
        assertEquals("GCRCNSimul", site.get("configured_site"));
        assertEquals("GCRCN Control", site.get("configured_name"));
        assertEquals("GCRCN Control", site.get("channel_name"));
        assertEquals(1, number(site.get("site_id")));
        assertEquals(Map.of("kind", "site", "key", P25_SITE_GUID), site.get("entity_ref"));

        for(Object label: List.of(system.get("configured_system"), system.get("alias_list_name"),
            system.get("site_names"), site.get("configured_site"), site.get("configured_name"),
            site.get("channel_name")))
        {
            assertFalse(String.valueOf(label).contains("(#"), String.valueOf(label));
        }
    }

    private void seedConfiguredP25System() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM alias_list_unmatched_talkgroup_scan_list_membership");
            statement.executeUpdate("DELETE FROM alias_list");
            statement.executeUpdate("INSERT INTO alias_list (id, name, family) VALUES (1, 'GCRCN', 'P25')");
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    alias_list_name, radres_guid, decoder_type, primary_frequency_hz, config_json
                ) VALUES ('%1$s', 'TRUNKED', 1, 'GCRCN', 'GCRCNSimul', 'GCRCN Control',
                    'GCRCN', '%2$s', 'P25_PHASE1', 856137500, '{}')
                """.formatted(P25_CONFIGURATION_ID, P25_SITE_GUID));
            statement.executeUpdate("INSERT INTO p25_system VALUES (1, 0xBEE00, 0x49F, 1000, 2000)");
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name,
                    decoder, first_seen_ms, last_seen_ms, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz
                ) VALUES (1, 'GUID:%1$s', '%1$s', 1, 1, 'GCRCN Control', 'GCRCN',
                    'P25-1', 1000, 2000, 1, 0x49F, 1, 1, 856137500, 856137500)
                """.formatted(P25_SITE_GUID));
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (
                    guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count, protocol,
                    channel_name, alias_list_name, decoder, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz
                ) VALUES ('%1$s', 'gcrcn-hash', 1000, 2000, 10, 'APCO25', 'GCRCN Control',
                    'GCRCN', 'P25-1', 1, 0x49F, 1, 1, 856137500, 856137500)
                """.formatted(P25_SITE_GUID));
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope (
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    alias_list_id, p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES (1, '%s', 1, 1, 0, 1, 1, 1000, 2000)
                """.formatted(P25_SCOPE));
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context (context_id, scope_id, first_seen_ms, last_seen_ms)
                VALUES (1, 1, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, group_name, matcher_type, protocol, value)
                VALUES (1, 1, 'CuyCO Jail 35', 'Corrections', 'TALKGROUP', 'APCO25', %d)
                """.formatted(ALIAS_ONLY_TALKGROUP));
        }
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
}
