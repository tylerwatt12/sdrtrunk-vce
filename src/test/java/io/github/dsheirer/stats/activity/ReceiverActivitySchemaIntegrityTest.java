/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** Focused integrity checks for the current activity schema rather than any retired compatibility shape. */
class ReceiverActivitySchemaIntegrityTest
{
    private static final String CONFIGURATION_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String NATIVE_CONFIGURATION_ID = "223e4567-e89b-42d3-a456-426614174000";

    @Test
    void usesProtocolNeutralNamesStableCodesAndOnlyTheGlobalVersionMarker() throws Exception
    {
        try(Connection connection = open())
        {
            assertEquals(1, count(connection, "sqlite_master",
                "type='table' AND name='receiver_activity_event'"));
            assertEquals(1, count(connection, "sqlite_master",
                "type='view' AND name='receiver_activity_event_resolved'"));
            assertEquals(1, count(connection, "sqlite_master",
                "type='table' AND name='trunked_control_channel_quality'"));
            assertEquals(1, count(connection, "sqlite_master",
                "type='index' AND name='idx_receiver_activity_event_channel_time'"));
            assertEquals(1, count(connection, "sqlite_master",
                "type='index' AND name='idx_trunked_control_quality_channel_time'"));
            assertEquals(0, count(connection, "sqlite_master",
                "name IN ('receiver_context','p25_activity_event','p25_control_channel_quality'," +
                    "'trunked_identity_scope','trunked_identity_summary')"));
            assertEquals(0, count(connection, "database_metadata",
                "key IN ('p25_activity_schema_version','dmr_activity_schema_version'," +
                    "'trunked_site_schema_version')"));

            assertEquals(1, ReceiverActivityRecords.Action.ACKNOWLEDGE.code());
            assertEquals(12, ReceiverActivityRecords.Action.GRANT.code());
            assertEquals(23, ReceiverActivityRecords.Action.UNKNOWN.code());
            assertEquals(1, ReceiverActivityCodes.eventTypeCode(DecodeEventType.AFFILIATE));
            assertEquals(56, ReceiverActivityCodes.eventTypeCode(DecodeEventType.UNKNOWN));
            assertEquals(57, ReceiverActivityCodes.eventTypeCode(DecodeEventType.DENIAL));

            insertConfiguredChannel(connection);
            execute(connection, """
                INSERT INTO receiver_channel(id, configuration_id, first_seen_ms, last_seen_ms)
                VALUES (1, '%s', 1000, 1000)
                """.formatted(CONFIGURATION_ID));
            execute(connection, """
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code, event_type_code)
                VALUES (1, 1000, 12, 57)
                """);
            assertEquals("GRANT|DENIAL", text(connection, """
                SELECT action || '|' || event_type FROM receiver_activity_event_resolved WHERE id=1
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
                VALUES (1, 1001, 24)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code, event_type_code)
                VALUES (1, 1001, 12, 58)
                """));
        }
    }

    @Test
    void configuredChannelOwnsAndCascadesEveryChannelFact() throws Exception
    {
        try(Connection connection = open())
        {
            insertConfiguredChannel(connection);
            ReceiverActivitySchema.insertControlChannelQuality(connection,
                new ReceiverActivityRecords.ControlChannelQuality(1_500, CONFIGURATION_ID, "APCO25",
                    TrunkedIdentityDomain.STANDARD, 851_012_500,
                    -70.0, -71.0, -75.0, -68.0, 95.0, 100, 2, 3, 4, 5, 1_400));
            long channelId = scalar(connection,
                "SELECT id FROM receiver_channel WHERE configuration_id='" + CONFIGURATION_ID + "'");
            execute(connection, """
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
                VALUES (%d, 1600, 12)
                """.formatted(channelId));
            execute(connection, """
                INSERT INTO conventional_activity_summary(
                    channel_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms)
                VALUES (%d, 155000000, -1, 1000, 1000)
                """.formatted(channelId));
            execute(connection, """
                INSERT INTO p25_site_snapshot(
                    channel_id, snapshot_hash, first_seen_ms, last_seen_ms, protocol)
                VALUES (%d, '%s', 1000, 1000, 'APCO25')
                """.formatted(channelId, "a".repeat(64)));
            execute(connection, """
                INSERT INTO p25_site_channel(channel_id, channel_key, downlink_hz, confirmed_at_ms)
                VALUES (%d, '1-1', 851000000, 1000)
                """.formatted(channelId));
            execute(connection, """
                INSERT INTO p25_site_channel_tag(channel_id, channel_key, tag, confirmed_at_ms)
                VALUES (%d, '1-1', 'CONTROL', 1000)
                """.formatted(channelId));

            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
                VALUES (99, 1000, 12)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_channel(channel_id, channel_key, confirmed_at_ms)
                VALUES (99, '1-1', 1000)
                """));

            execute(connection, "DELETE FROM configuration_channel WHERE configuration_id='" +
                CONFIGURATION_ID + "'");
            for(String table: new String[] {"receiver_channel", "receiver_activity_event",
                "conventional_activity_summary", "p25_site_snapshot", "p25_site_channel",
                "p25_site_channel_tag", "trunked_control_channel_quality"})
            {
                assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM " + table), table);
            }
        }
    }

    @Test
    void channelClearRemovesItsFallbackSystemButPreservesNativeSystemHistory() throws Exception
    {
        try(Connection connection = open())
        {
            insertDmrConfiguredChannel(connection, CONFIGURATION_ID, "Fallback");
            insertDmrConfiguredChannel(connection, NATIVE_CONFIGURATION_ID, "Native");
            execute(connection, """
                INSERT INTO radio_system(
                    id, system_key, configuration_id, protocol_code, address_domain_code,
                    first_seen_ms, last_seen_ms)
                VALUES (1, 'dmr:channel:%s', '%s', 3, 0, 1000, 1000)
                """.formatted(CONFIGURATION_ID, CONFIGURATION_ID));
            execute(connection, """
                INSERT INTO radio_system(
                    id, system_key, protocol_code, address_domain_code, dmr_model_code,
                    dmr_network_id, first_seen_ms, last_seen_ms)
                VALUES (2, 'dmr:tier3:small:42', 3, 0, 2, 42, 1000, 1000)
                """);
            execute(connection, """
                INSERT INTO receiver_channel(
                    id, configuration_id, first_seen_ms, last_seen_ms,
                    radio_system_id, radio_system_assigned_at_ms)
                VALUES
                    (1, '%s', 1000, 1000, 1, 1000),
                    (2, '%s', 1000, 1000, 2, 1000)
                """.formatted(CONFIGURATION_ID, NATIVE_CONFIGURATION_ID));
            execute(connection, """
                INSERT INTO radio_system_identity_summary(
                    id, radio_system_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms)
                VALUES (1, 1, 1, 101, 1000, 1000), (2, 2, 1, 202, 1000, 1000)
                """);
            execute(connection, """
                INSERT INTO trunked_logical_call_bucket(radio_system_id, bucket_start_ms, logical_call_count)
                VALUES (1, 0, 1), (2, 0, 1)
                """);

            assertEquals(2, ReceiverActivitySchema.clearChannelStats(connection, CONFIGURATION_ID));
            assertEquals(0, count(connection, "receiver_channel",
                "configuration_id='" + CONFIGURATION_ID + "'"));
            assertEquals(0, count(connection, "radio_system",
                "system_key='dmr:channel:" + CONFIGURATION_ID + "'"));
            assertEquals(0, count(connection, "radio_system_identity_summary", "radio_system_id=1"));
            assertEquals(0, count(connection, "trunked_logical_call_bucket", "radio_system_id=1"));

            assertEquals(1, count(connection, "receiver_channel",
                "configuration_id='" + NATIVE_CONFIGURATION_ID + "' AND radio_system_id=2"));
            assertEquals(1, count(connection, "radio_system", "system_key='dmr:tier3:small:42'"));
            assertEquals(1, count(connection, "radio_system_identity_summary", "radio_system_id=2"));
            assertEquals(1, count(connection, "trunked_logical_call_bucket", "radio_system_id=2"));
        }
    }

    @Test
    void identityRelationshipsEnforceKindsAndRolesAtTheDatabaseBoundary() throws Exception
    {
        try(Connection connection = open())
        {
            insertConfiguredChannel(connection);
            execute(connection, """
                INSERT INTO radio_system(
                    id, system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                    first_seen_ms, last_seen_ms)
                VALUES (10, 'p25:abcde:123', 1, 0, 0xABCDE, 0x123, 1000, 1000)
                """);
            execute(connection, """
                INSERT INTO receiver_channel(
                    id, configuration_id, first_seen_ms, last_seen_ms,
                    radio_system_id, radio_system_assigned_at_ms)
                VALUES (1, '%s', 1000, 1000, 10, 1000)
                """.formatted(CONFIGURATION_ID));
            execute(connection, """
                INSERT INTO p25_learned_site(
                    learned_site_id, radio_system_id, rfss, site, first_seen_ms, last_seen_ms)
                VALUES (100, 10, 1, 1, 1000, 1000)
                """);
            execute(connection, """
                INSERT INTO radio_system_identity_summary(
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id,
                    identity_id, first_seen_ms, last_seen_ms)
                VALUES (101, 10, 1, 0xABCDE, 0x123, 1001, 1000, 1000),
                       (102, 10, 2, 0xABCDE, 0x123, 2002, 1000, 1000),
                       (103, 10, 3, 0xABCDE, 0x123, 3003, 1000, 1000)
                """);

            execute(connection, """
                INSERT INTO receiver_activity_event(
                    id, channel_id, radio_system_id, observed_at_ms, action_code,
                    source_observed_local_id, target_observed_local_id, target_kind_code,
                    source_identity_summary_id, source_identity_kind_code, target_identity_summary_id)
                VALUES (1, 1, 10, 1000, 4, 2002, 1001, 1, 102, 2, 101)
                """);
            execute(connection, """
                INSERT INTO activity_event_identity_member(
                    event_id, radio_system_id, identity_summary_id, identity_kind_code, observed_local_id)
                VALUES (1, 10, 101, 1, 1001)
                """);
            execute(connection, """
                INSERT INTO trunked_logical_call_identity_bucket(
                    radio_system_id, bucket_start_ms, identity_role_code, identity_kind_code,
                    identity_summary_id)
                VALUES (10, 0, 1, 1, 101),
                       (10, 0, 1, 2, 102),
                       (10, 0, 1, 3, 103),
                       (10, 0, 2, 2, 102)
                """);
            execute(connection, """
                INSERT INTO p25_site_call_identity_bucket(
                    radio_system_id, learned_site_id, channel_id, bucket_start_ms,
                    identity_role_code, identity_kind_code, identity_summary_id,
                    observed_local_id, last_observed_at_ms)
                VALUES (10, 100, 1, 0, 1, 1, 101, 1001, 1000),
                       (10, 100, 1, 0, 1, 2, 102, 2002, 1000),
                       (10, 100, 1, 0, 1, 3, 103, 3003, 1000),
                       (10, 100, 1, 0, 2, 2, 102, 2002, 1000)
                """);

            assertEquals(4, scalar(connection,
                "SELECT COUNT(*) FROM trunked_logical_call_identity_bucket"));
            assertEquals(4, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_call_identity_bucket"));

            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO receiver_activity_event(
                    channel_id, radio_system_id, observed_at_ms, action_code,
                    source_identity_summary_id, source_identity_kind_code)
                VALUES (1, 10, 1001, 4, 101, 2)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO receiver_activity_event(
                    channel_id, radio_system_id, observed_at_ms, action_code,
                    source_identity_summary_id, source_identity_kind_code)
                VALUES (1, 10, 1001, 4, 101, 1)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO receiver_activity_event(
                    channel_id, radio_system_id, observed_at_ms, action_code,
                    target_observed_local_id, target_kind_code, target_identity_summary_id)
                VALUES (1, 10, 1001, 4, 1001, 2, 101)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO receiver_activity_event(
                    channel_id, radio_system_id, observed_at_ms, action_code,
                    target_identity_summary_id)
                VALUES (1, 10, 1001, 4, 101)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO activity_event_identity_member(
                    event_id, radio_system_id, identity_summary_id, identity_kind_code)
                VALUES (1, 10, 102, 1)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO activity_event_identity_member(
                    event_id, radio_system_id, identity_summary_id, identity_kind_code)
                VALUES (1, 10, 102, 2)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO trunked_logical_call_identity_bucket(
                    radio_system_id, bucket_start_ms, identity_role_code, identity_kind_code,
                    identity_summary_id)
                VALUES (10, 3600000, 2, 1, 101)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO trunked_logical_call_identity_bucket(
                    radio_system_id, bucket_start_ms, identity_role_code, identity_kind_code,
                    identity_summary_id)
                VALUES (10, 3600000, 2, 2, 101)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_call_identity_bucket(
                    radio_system_id, learned_site_id, channel_id, bucket_start_ms,
                    identity_role_code, identity_kind_code, identity_summary_id,
                    last_observed_at_ms)
                VALUES (10, 100, 1, 3600000, 2, 1, 101, 1001)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_call_identity_bucket(
                    radio_system_id, learned_site_id, channel_id, bucket_start_ms,
                    identity_role_code, identity_kind_code, identity_summary_id,
                    last_observed_at_ms)
                VALUES (10, 100, 1, 3600000, 2, 2, 101, 1001)
                """));
        }
    }

    @Test
    void rejectsInvalidTimesBooleansHashesSlotsFrequenciesAndCounters() throws Exception
    {
        try(Connection connection = open())
        {
            insertConfiguredChannel(connection);
            execute(connection, """
                INSERT INTO receiver_channel(id, configuration_id, first_seen_ms, last_seen_ms)
                VALUES (1, '%s', 1000, 1000)
                """.formatted(CONFIGURATION_ID));

            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO conventional_activity_summary(
                    channel_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, call_count)
                VALUES (1, 155000000, -1, 1000, 1000, -1)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO conventional_activity_summary(
                    channel_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms)
                VALUES (1, 155000000, 0, 1000, 1000)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_snapshot(
                    channel_id, snapshot_hash, first_seen_ms, last_seen_ms, protocol, tdma)
                VALUES (1, '%s', 1000, 1000, 'APCO25', 2)
                """.formatted("a".repeat(64))));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_snapshot(channel_id, snapshot_hash, first_seen_ms, last_seen_ms)
                VALUES (1, 'not-a-canonical-sha256', 1000, 1000)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_snapshot(channel_id, snapshot_hash, first_seen_ms, last_seen_ms)
                VALUES (1, x'%s', 1000, 1000)
                """.formatted("aa".repeat(64))));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_snapshot(
                    channel_id, snapshot_hash, first_seen_ms, last_seen_ms, protocol, nac)
                VALUES (1, '%s', 1000, 1000, 'APCO25', 1.5)
                """.formatted("a".repeat(64))));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_snapshot(
                    channel_id, snapshot_hash, first_seen_ms, last_seen_ms, protocol, micro_slots)
                VALUES (1, '%s', 1000, 1000, 'APCO25', 8000)
                """.formatted("a".repeat(64))));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_snapshot(
                    channel_id, snapshot_hash, first_seen_ms, last_seen_ms, protocol, data_access)
                VALUES (1, '%s', 1000, 1000, 'APCO25', 'Unknown mode')
                """.formatted("a".repeat(64))));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_snapshot(
                    channel_id, snapshot_hash, first_seen_ms, last_seen_ms, protocol, wuid_lease_minutes)
                VALUES (1, '%s', 1000, 1000, 'APCO25', 271)
                """.formatted("a".repeat(64))));
            execute(connection, """
                INSERT INTO p25_site_snapshot(
                    channel_id, snapshot_hash, first_seen_ms, last_seen_ms, protocol)
                VALUES (1, '%s', 1000, 1000, 'APCO25')
                """.formatted("a".repeat(64)));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_channel(
                    channel_id, channel_key, timeslots, confirmed_at_ms)
                VALUES (1, '0-1', 3, 1000)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_channel(channel_id, channel_key, confirmed_at_ms)
                VALUES (1, x'3031', 1000)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_frequency_band(
                    channel_id, band, tdma, base_hz, spacing_hz, timeslots, confirmed_at_ms)
                VALUES (1, 0, 1, 851000000, 12500, 3, 1000)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_foreign_system_band(
                    channel_id, foreign_wacn, foreign_system_id, band, channel_type, confirmed_at_ms)
                VALUES (1, 781824, 937, 0, 6, 1000)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_patch_group(
                    channel_id, local_patch_group_id, version, confirmed_at_ms)
                VALUES (1, 91, 32, 1000)
                """));
            execute(connection, """
                INSERT INTO p25_site_patch_group(
                    channel_id, local_patch_group_id, version, confirmed_at_ms)
                VALUES (1, 91, 31, 1000)
                """);
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM p25_site_patch_group"));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_patch_group(
                    channel_id, local_patch_group_id, version, confirmed_at_ms)
                VALUES (1, 65535, 1, 1000)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO trunked_control_channel_quality(
                    channel_id, frequency_hz, bucket_start_ms, observed_at_ms, decode_health_pct)
                VALUES (1, 851000000, 0, 1000, 101.0)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO statistics_status(key, value, updated_at_ms) VALUES ('bad-time', '1', 0)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO statistics_status(key, value, updated_at_ms) VALUES ('bad-value', x'31', 1000)
                """));
            assertThrows(IllegalArgumentException.class, () -> new ReceiverActivityRecords.ControlChannelQuality(
                1000, CONFIGURATION_ID, "APCO25", TrunkedIdentityDomain.STANDARD, 851000000,
                null, null, null, null, 101.0,
                0, 0, 0, 0, 0, 0));
        }
    }

    private static void insertConfiguredChannel(Connection connection) throws SQLException
    {
        execute(connection, """
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name,
                radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json)
            VALUES ('%s', 'TRUNKED', 0, 'System', 'Site', 'Control',
                'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 0, 'P25_PHASE1', 851000000, '{}')
            """.formatted(CONFIGURATION_ID));
    }

    private static void insertDmrConfiguredChannel(Connection connection, String configurationId, String name)
        throws SQLException
    {
        execute(connection, """
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name,
                radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json)
            VALUES ('%s', 'TRUNKED', 0, 'DMR System', 'DMR Site', '%s',
                NULL, 0, 'DMR', 451000000,
                '{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
            """.formatted(configurationId, name));
    }

    private static Connection open() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        SdrTrunkDatabaseSchema.create(connection);
        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        ReceiverActivitySchema.validate(connection);
        return connection;
    }

    private static void execute(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }

    private static int count(Connection connection, String table, String predicate) throws SQLException
    {
        return scalar(connection, "SELECT COUNT(*) FROM " + table + " WHERE " + predicate);
    }

    private static int scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static String text(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
