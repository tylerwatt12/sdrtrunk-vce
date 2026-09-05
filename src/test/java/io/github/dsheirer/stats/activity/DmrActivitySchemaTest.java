/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Current conventional-DMR aggregation, bounds, retention and integrity behavior. */
class DmrActivitySchemaTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void aggregatesGroupAndPrivateCallsBySavedChannelCarrierAndNativeTimeslot() throws Exception
    {
        try(Connection connection = open("calls.sqlite"))
        {
            insertChannel(connection, "site-a");
            insertChannel(connection, "site-b");
            record(connection, groupCall(1_000, 2_000, "site-a", 461_125_000L, 1, 91, 101, false));
            record(connection, groupCall(3_000, 4_000, "site-a", 461_125_000L, 1, 91, 101, false));
            record(connection, groupCall(5_000, 6_000, "site-a", 461_125_000L, 1, 91, 102, true));
            record(connection, groupCall(7_000, 8_000, "site-a", 461_125_000L, 2, 91, 101, false));
            record(connection, privateCall(9_000, 10_000, "site-a", 461_125_000L, 1, 101, 202, true));
            record(connection, groupCall(11_000, 12_000, "site-b", 462_125_000L, 1, 91, 101, false));

            int channelA = channelId(connection, "site-a");
            int channelB = channelId(connection, "site-b");
            assertEquals(3, scalar(connection, """
                SELECT call_count FROM dmr_conventional_talkgroup_summary
                WHERE channel_id=%d AND frequency_hz=461125000 AND timeslot=1 AND talkgroup_id=91
                """.formatted(channelA)));
            assertEquals(1, scalar(connection, """
                SELECT encrypted_count FROM dmr_conventional_talkgroup_summary
                WHERE channel_id=%d AND timeslot=1 AND talkgroup_id=91
                """.formatted(channelA)));
            assertEquals(1, scalar(connection, """
                SELECT call_count FROM dmr_conventional_talkgroup_summary
                WHERE channel_id=%d AND timeslot=2 AND talkgroup_id=91
                """.formatted(channelA)));
            assertEquals(3, scalar(connection, """
                SELECT call_count FROM dmr_conventional_radio_summary
                WHERE channel_id=%d AND timeslot=1 AND radio_id=101
                """.formatted(channelA)));
            assertEquals(1, scalar(connection, """
                SELECT target_call_count FROM dmr_conventional_radio_summary
                WHERE channel_id=%d AND timeslot=1 AND radio_id=202
                """.formatted(channelA)));
            assertEquals(4, scalar(connection, """
                SELECT call_count FROM conventional_activity_summary
                WHERE channel_id=%d AND frequency_hz=461125000 AND timeslot=1
                """.formatted(channelA)));
            assertEquals(1, scalar(connection, """
                SELECT call_count FROM dmr_conventional_talkgroup_summary
                WHERE channel_id=%d AND talkgroup_id=91
                """.formatted(channelB)));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(configurationId("site-a"), text(connection,
                "SELECT configuration_id FROM receiver_channel WHERE id=" + channelA));
        }
    }

    @Test
    void optionallyStoresOneResolvedDetailedCallWithoutInflatingSummaries() throws Exception
    {
        try(Connection connection = open("detailed.sqlite"))
        {
            insertChannel(connection, "detailed");
            Long activityId = ReceiverActivitySchema.recordDmrConventionalCall(connection,
                groupCall(1_000, 2_000, "detailed", 461_125_000L, 1, 91, 101, true), true);
            assertEquals(Long.valueOf(1L), activityId);
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT call_count FROM conventional_activity_summary"));
            assertEquals(1, scalar(connection, "SELECT call_count FROM dmr_conventional_talkgroup_summary"));
            assertEquals(1, scalar(connection, "SELECT call_count FROM dmr_conventional_radio_summary"));
            assertEquals(2, scalar(connection, "SELECT SUM(call_count) FROM conventional_call_identity_bucket"));
            assertEquals("CONVENTIONAL_DMR|DMR|CALL|CALL_GROUP_ENCRYPTED|1", text(connection, """
                SELECT channel_kind || '|' || protocol || '|' || action || '|' || event_type || '|' || timeslot
                FROM receiver_activity_event_resolved
                """));
        }
    }

    @Test
    void outOfOrderCallsExpandTheWindowWithoutReplacingNewerLastSeenFacts() throws Exception
    {
        try(Connection connection = open("out-of-order.sqlite"))
        {
            insertChannel(connection, "ordered");
            record(connection, groupCall(5_000, 6_000, "ordered", 461_125_000L, 1, 91, 101, false));
            record(connection, groupCall(1_000, 2_000, "ordered", 461_125_000L, 1, 91, 102, false));
            assertEquals("1000|6000|2|101", text(connection, """
                SELECT first_seen_ms || '|' || last_seen_ms || '|' || call_count || '|' || last_source_radio_id
                FROM dmr_conventional_talkgroup_summary
                """));
        }
    }

    @Test
    void rejectsInvalidCallsAndExactDdlValuesWithoutWriting() throws Exception
    {
        try(Connection connection = open("invalid.sqlite"))
        {
            insertChannel(connection, "invalid");
            ReceiverActivityRecords.DmrConventionalCall backwards =
                new ReceiverActivityRecords.DmrConventionalCall(2_000, 1_000, configurationId("invalid"),
                    461_125_000L, 1, ReceiverActivityRecords.DmrTargetKind.GROUP, 1, 2, null, false);
            assertThrows(SQLException.class,
                () -> ReceiverActivitySchema.recordDmrConventionalCall(connection, backwards));
            assertThrows(SQLException.class, () -> record(connection,
                groupCall(1_000, 2_000, "invalid", 461_125_000L, 0, 91, 2, false)));
            assertThrows(SQLException.class, () -> record(connection,
                groupCall(1_000, 2_000, "invalid", 461_125_000L, 1,
                    DmrActivitySchema.MAXIMUM_DMR_ID + 1, 2, false)));

            int channel = seedReceiverChannel(connection, "invalid", 1_000);
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO dmr_conventional_talkgroup_summary(
                    channel_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms, call_count)
                VALUES (%d, 461125000, 3, 91, 1000, 1000, 1)
                """.formatted(channel)));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO dmr_conventional_radio_summary(
                    channel_id, frequency_hz, timeslot, radio_id, first_seen_ms, last_seen_ms, call_count)
                VALUES (%d, 461125000, 1, 101, 0, 1000, 1)
                """.formatted(channel)));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO dmr_conventional_talkgroup_summary(
                    channel_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms,
                    call_count, encrypted_count)
                VALUES (%d, 461125000, 1, 91, 1000, 1000, 1, 2)
                """.formatted(channel)));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO dmr_conventional_radio_summary(
                    channel_id, frequency_hz, timeslot, radio_id, first_seen_ms, last_seen_ms,
                    call_count, source_call_count, group_call_count, private_call_count)
                VALUES (%d, 461125000, 1, 101, 1000, 1000, 1, 2, 1, 1)
                """.formatted(channel)));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM dmr_conventional_talkgroup_summary"));
        }
    }

    @Test
    void retentionChannelClearAndResetHaveBoundedOwnership() throws Exception
    {
        try(Connection connection = open("maintenance.sqlite"))
        {
            insertChannel(connection, "old");
            insertChannel(connection, "keep");
            insertChannel(connection, "reset");
            record(connection, groupCall(1_000, 2_000, "old", 461_125_000L, 1, 91, 101, false));
            record(connection, groupCall(9_000, 10_000, "keep", 462_125_000L, 2, 92, 102, false));

            DmrActivitySchema.CleanupResult cleanup = DmrActivitySchema.deleteOlderThan(connection, 5_000);
            assertEquals(1, cleanup.talkgroups());
            assertEquals(1, cleanup.radios());
            assertEquals(2, DmrActivitySchema.clearChannelStats(connection, configurationId("keep")));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM dmr_conventional_talkgroup_summary"));

            record(connection, privateCall(11_000, 12_000, "reset", 463_125_000L, 1, 103, 203, false));
            assertEquals(2, DmrActivitySchema.resetStats(connection));
        }
    }

    @Test
    void admissionCapKeepsExistingRowsWritableAndIndexesServeBoundedQueries() throws Exception
    {
        try(Connection connection = open("cap.sqlite"))
        {
            insertChannel(connection, "cap");
            record(connection, groupCall(1_000, 2_000, "cap", 461_125_000L, 1, 1, 101, false));
            int channel = channelId(connection, "cap");
            execute(connection, """
                WITH RECURSIVE identities(value) AS (
                    VALUES(2) UNION ALL SELECT value + 1 FROM identities WHERE value < 4096)
                INSERT INTO dmr_conventional_talkgroup_summary(
                    channel_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms, call_count)
                SELECT %d, 461125000, 1, value, 1000, 2000, 1 FROM identities
                """.formatted(channel));

            record(connection, groupCall(3_000, 4_000, "cap", 461_125_000L, 1, 4_097, 102, false));
            assertEquals(DmrActivitySchema.MAXIMUM_TALKGROUPS_PER_CHANNEL, scalar(connection,
                "SELECT COUNT(*) FROM dmr_conventional_talkgroup_summary WHERE channel_id=" + channel));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM dmr_conventional_talkgroup_summary WHERE talkgroup_id=4097"));
            record(connection, groupCall(5_000, 6_000, "cap", 461_125_000L, 1, 1, 103, false));
            assertEquals(2, scalar(connection,
                "SELECT call_count FROM dmr_conventional_talkgroup_summary WHERE talkgroup_id=1"));

            assertPlanUses(connection, """
                EXPLAIN QUERY PLAN SELECT channel_id FROM dmr_conventional_talkgroup_summary
                WHERE last_seen_ms < 5000 ORDER BY last_seen_ms LIMIT 1000
                """, DmrActivitySchema.TALKGROUP_RETENTION_INDEX);
            assertPlanUses(connection, """
                EXPLAIN QUERY PLAN SELECT talkgroup_id FROM dmr_conventional_talkgroup_summary
                WHERE channel_id=%d ORDER BY last_seen_ms DESC LIMIT 100
                """.formatted(channel), DmrActivitySchema.TALKGROUP_RECEIVER_INDEX);
        }
    }

    private Connection open(String name) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mTemporaryFolder.resolve(name));
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        SdrTrunkDatabaseSchema.create(connection);
        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        DmrActivitySchema.validate(connection);
        return connection;
    }

    private static void insertChannel(Connection connection, String fixture) throws SQLException
    {
        execute(connection, """
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name,
                radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json)
            VALUES ('%s', 'CONVENTIONAL', 0, 'DMR', 'Repeater', '%s', NULL, 0, 'DMR', 461125000, '{}')
            """.formatted(configurationId(fixture), fixture));
    }

    private static void record(Connection connection, ReceiverActivityRecords.DmrConventionalCall call)
        throws SQLException
    {
        ReceiverActivitySchema.recordDmrConventionalCall(connection, call);
    }

    private static int channelId(Connection connection, String fixture) throws SQLException
    {
        return scalar(connection, "SELECT id FROM receiver_channel WHERE configuration_id='" +
            configurationId(fixture) + "'");
    }

    private static int seedReceiverChannel(Connection connection, String fixture, long observedAt) throws SQLException
    {
        execute(connection, """
            INSERT OR IGNORE INTO receiver_channel(configuration_id, first_seen_ms, last_seen_ms)
            VALUES ('%s', %d, %d)
            """.formatted(configurationId(fixture), observedAt, observedAt));
        return channelId(connection, fixture);
    }

    private static ReceiverActivityRecords.DmrConventionalCall groupCall(long start, long end, String fixture,
                                                                        long frequency, int timeslot, int talkgroup,
                                                                        int source, boolean encrypted)
    {
        return new ReceiverActivityRecords.DmrConventionalCall(start, end, configurationId(fixture), frequency,
            timeslot, ReceiverActivityRecords.DmrTargetKind.GROUP, talkgroup, source, null, encrypted);
    }

    private static ReceiverActivityRecords.DmrConventionalCall privateCall(long start, long end, String fixture,
                                                                          long frequency, int timeslot, int source,
                                                                          int target, boolean encrypted)
    {
        return new ReceiverActivityRecords.DmrConventionalCall(start, end, configurationId(fixture), frequency,
            timeslot, ReceiverActivityRecords.DmrTargetKind.PRIVATE, null, source, target, encrypted);
    }

    private static String configurationId(String fixture)
    {
        return UUID.nameUUIDFromBytes(("configuration:" + fixture).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void execute(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
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

    private static void assertPlanUses(Connection connection, String sql, String index) throws SQLException
    {
        StringBuilder plan = new StringBuilder();
        try(PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                plan.append(resultSet.getString("detail")).append('\n');
            }
        }
        assertTrue(plan.toString().contains(index), plan.toString());
    }
}
