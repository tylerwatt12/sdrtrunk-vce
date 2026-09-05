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
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /**
     * Builds the current component schemas directly so these read-model tests do not depend on the migration
     * catalog's version stamp or historical fingerprints. Startup and whole-file validation have separate tests.
     */
    private void createCurrentWebTestDatabase() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
            ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
        }
    }

    @Test
    void formatsKnownAndUnknownMfids()
    {
        assertEquals("Motorola (0x90)", StatsWebDatabase.mfidDisplay(0x90));
        assertEquals("0xAB", StatsWebDatabase.mfidDisplay(0xAB));
    }

    @Test
    void radioSystemDirectoryOwnsSystemsWhileSavedChannelsOwnConfiguration()
    {
        Map<String,Object> directory = mDatabase.radioSystemDirectory(request("/"));
        List<Map<String,Object>> systems = rows(directory);
        assertEquals(1, systems.size());
        Map<String,Object> listed = systems.getFirst();
        assertEquals(RADIO_SYSTEM_KEY, listed.get("radio_system_key"));
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
    void nativeDmrAndNxdnSystemFieldsAndObservedGroupsComeFromTheSharedRadioSystem() throws Exception
    {
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
        assertDmrNativeIdentity(map(mDatabase.radio(
            "dmr:tier3:small:42", dmrRadioKey), "radio"));
        assertDmrNativeIdentity(rows(mDatabase.radioSystemRelationships(
            "dmr:tier3:small:42", request("/?group_identity_key=" + dmrGroupKey))).getFirst());
        List<Map<String,Object>> observedDmr = rows(mDatabase.observedGroupIdentities(81, request("/")));
        assertEquals(1, observedDmr.size(),
            "Two channels using the list must not duplicate one native system identity");
        assertDmrNativeIdentity(observedDmr.getFirst());
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

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
                INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (8111, 81, 'Shared Dispatch', 'TALKGROUP', 'DMR', 101),
                       (8112, 81, 'Shared Unit', 'RADIO_ID', 'DMR', 202),
                       (8211, 82, 'Shared Dispatch', 'TALKGROUP', 'DMR', 101),
                       (8212, 82, 'Shared Unit', 'RADIO_ID', 'DMR', 202),
                       (7402, 74, 'Conventional Unit', 'RADIO_ID', 'DMR', 303)
                """);
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
        assertEquals("DMR Dispatch", conventionalDestination.get("alias_name"));
        assertEquals("Shared Unit", trunkedSource.get("alias_name"));
        assertEquals("Conventional Unit", conventionalSource.get("alias_name"));

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
    void historicalChannelScopedSystemAndObservedGroupKeepTheirOwnerAfterNativePromotion() throws Exception
    {
        String configurationId = "90000000-0000-4000-8000-000000000090";
        String fallbackKey = "dmr:channel:" + configurationId;
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
        assertEquals("Old site", historical.get("channel_names"));
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
    void p25PatchTelemetryRemainsUnlinkedLocalEvidence() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
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
        assertFalse(patch.containsKey("entity_ref"));
        assertFalse(talkgroup.containsKey("entity_ref"));
        assertFalse(radio.containsKey("entity_ref"));
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

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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

        String identityKey = p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 202);
        Map<String,Object> radio = map(mDatabase.radio(RADIO_SYSTEM_KEY, identityKey), "radio");
        assertFalse(radio.containsKey("last_group_identity_id"));
        assertEquals(Map.of("kind", "radio", "radio_system_key", RADIO_SYSTEM_KEY,
            "identity_key", identityKey),
            radio.get("entity_ref"));
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
    void talkgroupAndPatchGroupWithTheSameNumberStayDistinct() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
            p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 202)), "radio")
            .get("radio_system_key"));
        assertEquals(secondSystemKey, map(mDatabase.radio(secondSystemKey,
            RadioSystemIdentityKey.format(RadioSystemIdentityKey.KIND_RADIO, 0xBEE00, 0x4A0, 202)), "radio")
            .get("radio_system_key"));
    }

    @Test
    void affiliationAndPresenceFiltersUseTheExactSavedChannel() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
        assertEquals(P25_CHANNEL_A,
            map(map(rows(channelA).getFirst(), "presence"), "channel").get("configuration_id"));

        Map<String,Object> channelB = mDatabase.radioSystemRadios(RADIO_SYSTEM_KEY,
            request("/?configuration_id=" + P25_CHANNEL_B + "&affiliated=true"));
        assertEquals(0, number(channelB.get("total_count")));
        assertTrue(rows(channelB).isEmpty());

        assertEquals(1, number(mDatabase.radioSystemRelationships(RADIO_SYSTEM_KEY,
            request("/?radio_identity_key=" + p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 202) +
                "&configuration_id=" + P25_CHANNEL_A)).get("total_count")));
        assertEquals(0, number(mDatabase.radioSystemRelationships(RADIO_SYSTEM_KEY,
            request("/?radio_identity_key=" + p25IdentityKey(RadioSystemIdentityKey.KIND_RADIO, 202) +
                "&configuration_id=" + P25_CHANNEL_B)).get("total_count")));
    }

    @Test
    void conventionalP25AndNxdnExposeRealChannelOwnedGroupsAndRadios() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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

        Map<String,Object> nxdnGroup = rows(mDatabase.channelGroupIdentities(
            CONVENTIONAL_NXDN_CHANNEL, request("/"))).getFirst();
        assertEquals(43, number(nxdnGroup.get("native_id")));
        assertEquals("NXDN Group", nxdnGroup.get("alias_name"));
        assertEquals(CONVENTIONAL_NXDN_CHANNEL, nxdnGroup.get("configuration_id"));

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

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement calls = connection.prepareStatement("""
                INSERT INTO p25_site_call_identity_bucket (
                    radio_system_id, learned_site_id, channel_id, bucket_start_ms, identity_role_code,
                    identity_kind_code, identity_summary_id, observed_local_id, last_observed_at_ms,
                    observed_call_count, encrypted_observed_call_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
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
                INSERT INTO radio_system_identity_summary (
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms)
                VALUES (7112, 71, 1, 0xBEE00, 0x49F, 102, 1000, 4000),
                       (7113, 71, 1, 0xBEE00, 0x49F, 103, 1000, 4000),
                       (7131, 71, 2, 0xBEE00, 0x49F, 301, 1000, 4000),
                       (7132, 71, 2, 0xBEE00, 0x49F, 302, 1000, 4000),
                       (7191, 71, 1, 0xBEE00, 0x49F, 999, 1000, 4000),
                       (7192, 71, 2, 0xBEE00, 0x49F, 999, 1000, 4000)
                """);
            insertP25ChannelIdentity(calls, bucket, 710, 71, 1, 1, 7112, 102, 2);
            insertP25ChannelIdentity(calls, bucket, 710, 71, 1, 1, 7113, 103, 3);
            insertP25ChannelIdentity(calls, bucket, 710, 71, 2, 2, 7131, 301, 2);
            insertP25ChannelIdentity(calls, bucket, 710, 71, 2, 2, 7132, 302, 3);
            insertP25ChannelIdentity(calls, bucket, 720, 72, 1, 1, 7191, 999, 9);
            insertP25ChannelIdentity(calls, bucket, 720, 72, 2, 2, 7192, 999, 9);
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
        assertEquals(true, firstRadio.get("has_more"));
        assertEquals(1, number(firstRadio.get("next_offset")));
        assertTrue(rows(firstRadio).stream().noneMatch(row -> number(row.get("native_id")) == 999));

        StatsRequest otherSite = request("/?q=999&sort=radio&direction=asc");
        assertTrue(rows(mDatabase.channelRadios(P25_CHANNEL_A, otherSite)).isEmpty());
        otherSite.requireFullyConsumed();
    }

    @Test
    void trunkedDmrAndNxdnChannelIdentityPagesUseTheSameQueryContract() throws Exception
    {
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
    void conventionalDmrAliasDiscoveryKeepsCarrierTimeslotsSeparate() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
    }

    @Test
    void configuredQuietChannelRemainsVisibleWithoutRuntimeFacts() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
    void boundedDirectoryHandlesRepresentativeIdentityVolume() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
    void activityCursorAndGrantFilterApplyBeforeTheBoundedPage() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
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

        long cursor = number(firstPage.get("next_before_id"));
        Map<String,Object> secondPage = mDatabase.activity(request(
            "/?configuration_id=" + P25_CHANNEL_A + "&hide_grants=true&limit=2&before_id=" + cursor));
        assertEquals(1, rows(secondPage).size());
        assertEquals(4000L, number(rows(secondPage).getFirst().get("observed_at_ms")));
        assertFalse((Boolean)secondPage.get("has_more"));
    }

    @Test
    void activityAliasDiscoveryAndRadioDirectoryUseCurrentBoundedIndexes() throws Exception
    {
        StatsWebDatabase.ObservedGroupIdentityQuery[] observedQuery = new StatsWebDatabase.ObservedGroupIdentityQuery[1];
        mDatabase.observedGroupIdentities(71, request("/?limit=25"), query -> observedQuery[0] = query);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
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
                    detail.contains("SEARCH identity USING INTEGER PRIMARY KEY") ||
                        detail.contains("SEARCH identity USING INDEX idx_radio_system_identity")),
                () -> "Expected indexed radio-system identity lookup, plan was: " + aliasPlan);
            assertTrue(aliasPlan.stream().anyMatch(detail ->
                    detail.contains("idx_receiver_channel_radio_system") ||
                        detail.contains("sqlite_autoindex_receiver_channel_1")),
                () -> "Expected indexed channel ownership lookup, plan was: " + aliasPlan);
            assertTrue(aliasPlan.stream().noneMatch(detail -> detail.contains("receiver_activity_event")),
                () -> "Alias discovery must not scan detailed activity, plan was: " + aliasPlan);

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
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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
                    event_id, radio_system_id, identity_summary_id, identity_kind_code, observed_local_id)
                SELECT max(id), 71, 7101, 1, 101 FROM receiver_activity_event
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
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
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

    private static StatsRequest request(String uri)
    {
        return StatsRequest.from(URI.create(uri));
    }

    private static String p25IdentityKey(int kind, int identifier)
    {
        return RadioSystemIdentityKey.format(kind, 0xBEE00, 0x49F, identifier);
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
                                                  int observedLocalId, int calls) throws Exception
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

    private static List<String> explain(Connection connection, String sql, Object... parameters) throws Exception
    {
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
}
