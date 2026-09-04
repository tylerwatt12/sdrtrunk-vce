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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class ReceiverActivitySchemaIntegrityTest
{
    @Test
    void usesProtocolNeutralNamesAndStableCodes() throws Exception
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
                "type='table' AND name='statistics_status'"));
            assertEquals(1, count(connection, "sqlite_master",
                "type='index' AND name='idx_receiver_activity_event_context_time'"));
            assertEquals(1, count(connection, "sqlite_master",
                "type='index' AND name='idx_trunked_control_quality_guid_time'"));
            assertEquals(0, count(connection, "sqlite_master",
                "name IN ('p25_activity_event', 'p25_activity_event_resolved', " +
                    "'p25_control_channel_quality', 'logger_status')"));
            assertEquals(ReceiverActivitySchema.SCHEMA_VERSION, scalar(connection, """
                SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key='p25_activity_schema_version'
                """));

            assertEquals(1, ReceiverActivityRecords.Action.ACKNOWLEDGE.code());
            assertEquals(12, ReceiverActivityRecords.Action.GRANT.code());
            assertEquals(23, ReceiverActivityRecords.Action.UNKNOWN.code());
            assertEquals(1, ReceiverActivityCodes.eventTypeCode(DecodeEventType.AFFILIATE));
            assertEquals(56, ReceiverActivityCodes.eventTypeCode(DecodeEventType.UNKNOWN));
            assertEquals(57, ReceiverActivityCodes.eventTypeCode(DecodeEventType.DENIAL));
            assertEquals(DecodeEventType.values().length, ReceiverActivityCodes.eventTypeCodes().size());

            execute(connection, """
                INSERT INTO receiver_context(
                    id, context_key, kind_code, protocol_code, first_seen_ms, last_seen_ms
                ) VALUES (1, 'CONFIGURATION:stable-codes', 10, 10, 1000, 1000)
                """);
            execute(connection, """
                INSERT INTO receiver_activity_event(
                    context_id, observed_at_ms, action_code, event_type_code
                ) VALUES (1, 1000, 12, 57)
                """);
            assertEquals("GRANT|DENIAL", text(connection, """
                SELECT action || '|' || event_type FROM receiver_activity_event_resolved WHERE id=1
                """));

            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO receiver_activity_event(context_id, observed_at_ms, action_code)
                VALUES (1, 1001, 24)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO receiver_activity_event(context_id, observed_at_ms, action_code, event_type_code)
                VALUES (1, 1001, 12, 58)
                """));
        }
    }

    @Test
    void enforcesOwnersAndCascadingCleanup() throws Exception
    {
        try(Connection connection = open())
        {
            ReceiverActivitySchema.insertControlChannelQuality(connection,
                new ReceiverActivityRecords.ControlChannelQuality(1500, "quality-first", 851012500,
                    -70.0, -71.0, -75.0, -68.0, 95.0, 100, 2, 3, 4, 5, 1400));
            assertEquals("1|0", text(connection, """
                SELECT kind_code || '|' || protocol_code FROM receiver_context WHERE guid='quality-first'
                """));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM trunked_control_channel_quality WHERE guid='quality-first'
                """));
            execute(connection, "DELETE FROM receiver_context WHERE guid='quality-first'");
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM trunked_control_channel_quality WHERE guid='quality-first'
                """));

            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO receiver_activity_event(context_id, observed_at_ms, action_code)
                VALUES (99, 1000, 12)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO conventional_activity_summary(
                    context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms
                ) VALUES (99, 155000000, -1, 1000, 1000)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_channel(guid, channel_key, confirmed_at_ms)
                VALUES ('missing-site', '1-1', 1000)
                """));

            execute(connection, """
                INSERT INTO alias_list(id, name, family) VALUES (50, 'Integrity Alias', 'P25')
                """);
            execute(connection, """
                INSERT INTO p25_system(system_key, wacn, system_id, first_seen_ms, last_seen_ms)
                VALUES (7, 1, 2, 1000, 1000)
                """);
            execute(connection, """
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, first_seen_ms, last_seen_ms,
                    system_key, alias_list_id
                ) VALUES (1, 'GUID:integrity-site', 'integrity-site', 1, 1, 1000, 1000, 7, 50)
                """);
            execute(connection, """
                INSERT INTO receiver_activity_event(context_id, observed_at_ms, action_code)
                VALUES (1, 1000, 12)
                """);
            execute(connection, """
                INSERT INTO conventional_activity_summary(
                    context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms
                ) VALUES (1, 155000000, -1, 1000, 1000)
                """);
            execute(connection, """
                INSERT INTO p25_site_snapshot(
                    guid, first_seen_ms, last_seen_ms, protocol, system_key
                ) VALUES ('integrity-site', 1000, 1000, 'APCO25', 7)
                """);
            execute(connection, """
                INSERT INTO p25_site_channel(guid, channel_key, downlink_hz, confirmed_at_ms)
                VALUES ('integrity-site', '1-1', 851000000, 1000)
                """);
            execute(connection, """
                INSERT INTO p25_site_channel_tag(guid, channel_key, tag, confirmed_at_ms)
                VALUES ('integrity-site', '1-1', 'CONTROL', 1000)
                """);
            execute(connection, """
                INSERT INTO trunked_control_channel_quality(
                    guid, frequency_hz, bucket_start_ms, observed_at_ms
                ) VALUES ('integrity-site', 851000000, 0, 1000)
                """);

            execute(connection, "DELETE FROM alias_list WHERE id=50");
            assertEquals(0, scalar(connection,
                "SELECT coalesce(alias_list_id, 0) FROM receiver_context WHERE id=1"));
            execute(connection, "DELETE FROM p25_system WHERE system_key=7");
            assertEquals("0|0", text(connection, """
                SELECT coalesce(context.system_key, 0) || '|' || coalesce(site.system_key, 0)
                FROM receiver_context context JOIN p25_site_snapshot site ON site.guid=context.guid
                WHERE context.id=1
                """));

            execute(connection, "DELETE FROM receiver_context WHERE id=1");
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM conventional_activity_summary"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_site_snapshot"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_site_channel"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_site_channel_tag"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_control_channel_quality"));
        }
    }

    @Test
    void rejectsInvalidCodesTimesBooleansAndCounters() throws Exception
    {
        try(Connection connection = open())
        {
            execute(connection, """
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, first_seen_ms, last_seen_ms
                ) VALUES (1, 'GUID:checks', 'checks', 1, 1, 1000, 1000)
                """);

            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO conventional_activity_summary(
                    context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, call_count
                ) VALUES (1, 155000000, -1, 1000, 1000, -1)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_snapshot(
                    guid, first_seen_ms, last_seen_ms, protocol, tdma
                ) VALUES ('checks', 1000, 1000, 'APCO25', 2)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO trunked_control_channel_quality(
                    guid, frequency_hz, bucket_start_ms, observed_at_ms, decode_health_pct
                ) VALUES ('checks', 851000000, 0, 1000, 101.0)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO statistics_status(key, value, updated_at_ms) VALUES ('bad-time', '1', 0)
                """));
            assertThrows(IllegalArgumentException.class, () -> new ReceiverActivityRecords.ControlChannelQuality(
                1000, "checks", 851000000, null, null, null, null, 101.0,
                0, 0, 0, 0, 0, 0));
        }
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
