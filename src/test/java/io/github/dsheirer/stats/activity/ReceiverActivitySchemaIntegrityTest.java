/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

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
                new ReceiverActivityRecords.ControlChannelQuality(1_500, CONFIGURATION_ID, 851_012_500,
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
                INSERT INTO trunked_control_channel_quality(
                    channel_id, frequency_hz, bucket_start_ms, observed_at_ms, decode_health_pct)
                VALUES (1, 851000000, 0, 1000, 101.0)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO statistics_status(key, value, updated_at_ms) VALUES ('bad-time', '1', 0)
                """));
            assertThrows(IllegalArgumentException.class, () -> new ReceiverActivityRecords.ControlChannelQuality(
                1000, CONFIGURATION_ID, 851000000, null, null, null, null, 101.0,
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
