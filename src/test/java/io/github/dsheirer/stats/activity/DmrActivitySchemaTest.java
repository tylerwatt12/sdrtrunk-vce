/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DmrActivitySchemaTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void aggregatesGroupAndPrivateCallsByContextCarrierAndTimeslot() throws Exception
    {
        Path database = mTemporaryFolder.resolve("calls.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            DmrActivitySchema.validate(connection);
            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                groupCall(1_000, 2_000, "site-a", 461_125_000L, 1, 91, 101, false));
            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                groupCall(3_000, 4_000, "site-a", 461_125_000L, 1, 91, 101, false));
            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                groupCall(5_000, 6_000, "site-a", 461_125_000L, 1, 91, 102, true));
            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                groupCall(7_000, 8_000, "site-a", 461_125_000L, 2, 91, 101, false));
            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                privateCall(9_000, 10_000, "site-a", 461_125_000L, 1, 101, 202, true));
            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                groupCall(11_000, 12_000, "site-b", 462_125_000L, 1, 91, 101, false));

            assertEquals(3, scalar(connection, """
                SELECT call_count FROM dmr_conventional_talkgroup_summary
                WHERE context_id=(SELECT id FROM receiver_context WHERE guid='site-a')
                  AND frequency_hz=461125000 AND timeslot=1 AND talkgroup_id=91
                """));
            assertEquals(1, scalar(connection, """
                SELECT encrypted_count FROM dmr_conventional_talkgroup_summary
                WHERE frequency_hz=461125000 AND timeslot=1 AND talkgroup_id=91
                """));
            assertEquals(1, scalar(connection, """
                SELECT call_count FROM dmr_conventional_talkgroup_summary
                WHERE frequency_hz=461125000 AND timeslot=2 AND talkgroup_id=91
                """));
            assertEquals(3, scalar(connection, """
                SELECT call_count FROM dmr_conventional_radio_summary
                WHERE context_id=(SELECT id FROM receiver_context WHERE guid='site-a')
                  AND frequency_hz=461125000 AND timeslot=1 AND radio_id=101
                """));
            assertEquals(2, scalar(connection, """
                SELECT group_call_count FROM dmr_conventional_radio_summary
                WHERE context_id=(SELECT id FROM receiver_context WHERE guid='site-a')
                  AND frequency_hz=461125000 AND timeslot=1 AND radio_id=101
                """));
            assertEquals(1, scalar(connection, """
                SELECT private_call_count FROM dmr_conventional_radio_summary
                WHERE frequency_hz=461125000 AND timeslot=1 AND radio_id=202
                """));
            assertEquals(1, scalar(connection, """
                SELECT target_call_count FROM dmr_conventional_radio_summary
                WHERE frequency_hz=461125000 AND timeslot=1 AND radio_id=202
                """));
            assertEquals(4, scalar(connection, """
                SELECT call_count FROM conventional_activity_summary
                WHERE context_id=(SELECT id FROM receiver_context WHERE guid='site-a')
                  AND frequency_hz=461125000 AND timeslot=1
                """));
            assertEquals(1, scalar(connection, """
                SELECT call_count FROM dmr_conventional_talkgroup_summary
                WHERE context_id=(SELECT id FROM receiver_context WHERE guid='site-b')
                  AND frequency_hz=462125000 AND timeslot=1 AND talkgroup_id=91
                """));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_activity_event"));
            assertEquals("County DMR", text(connection,
                "SELECT alias_list_name FROM receiver_context WHERE guid='site-a'"));
            assertEquals(3, scalar(connection,
                "SELECT kind_code FROM receiver_context WHERE guid='site-a'"));
        }
    }

    @Test
    void optionallyStoresOneResolvedDetailedCallWithoutInflatingSummaries() throws Exception
    {
        Path database = mTemporaryFolder.resolve("detailed-call.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            Long activityId = P25ActivityLogSchema.recordDmrConventionalCall(connection,
                groupCall(1_000, 2_000, "detailed", 461_125_000L, 1, 91, 101, true), true);

            assertEquals(Long.valueOf(1L), activityId);
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM p25_activity_event"));
            assertEquals(1, scalar(connection,
                "SELECT call_count FROM conventional_activity_summary"));
            assertEquals(1, scalar(connection,
                "SELECT call_count FROM dmr_conventional_talkgroup_summary"));
            assertEquals(1, scalar(connection,
                "SELECT call_count FROM dmr_conventional_radio_summary"));
            assertEquals(2, scalar(connection,
                "SELECT SUM(call_count) FROM call_identity_bucket"));

            try(ResultSet resultSet = statement.executeQuery("""
                SELECT channel_kind, protocol, action, event_type, source_radio_id, target_id, target_kind,
                       frequency_hz, timeslot, encrypted
                FROM p25_activity_event_resolved
                """))
            {
                assertTrue(resultSet.next());
                assertEquals("CONVENTIONAL_DMR", resultSet.getString("channel_kind"));
                assertEquals("DMR", resultSet.getString("protocol"));
                assertEquals("CALL", resultSet.getString("action"));
                assertEquals("CALL_GROUP_ENCRYPTED", resultSet.getString("event_type"));
                assertEquals(101, resultSet.getInt("source_radio_id"));
                assertEquals(91, resultSet.getInt("target_id"));
                assertEquals("TALKGROUP", resultSet.getString("target_kind"));
                assertEquals(461_125_000L, resultSet.getLong("frequency_hz"));
                assertEquals(1, resultSet.getInt("timeslot"));
                assertEquals(1, resultSet.getInt("encrypted"));
            }
        }
    }

    @Test
    void guidUsesCanonicalReceiverContextIdentity() throws Exception
    {
        P25ActivityLogRecords.DmrConventionalCall call =
            groupCall(1_000, 2_000, "same-guid", 461_125_000L, 1, 91, 101, false);
        assertEquals("GUID:same-guid", call.contextKey());
    }

    @Test
    void reusesCanonicalGuidContextWhenChannelModeChanges() throws Exception
    {
        Path database = mTemporaryFolder.resolve("mode-switch.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    context_key, guid, kind_code, protocol_code, channel_name, decoder, first_seen_ms, last_seen_ms,
                    nac, rfss, site, current_control_hz
                ) VALUES ('GUID:mode-switch', 'mode-switch', 1, 3, 'Old trunked', 'DMR', 500, 500,
                    1, 2, 3, 461125000)
                """);

            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                groupCall(1_000, 2_000, "mode-switch", 461_125_000L, 1, 91, 101, false));

            assertEquals(1, scalar(connection,
                "SELECT COUNT(*) FROM receiver_context WHERE guid='mode-switch'"));
            assertEquals(3, scalar(connection,
                "SELECT kind_code FROM receiver_context WHERE guid='mode-switch'"));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(nac) + COUNT(rfss) + COUNT(site) + COUNT(current_control_hz) " +
                    "FROM receiver_context WHERE guid='mode-switch'"));
            assertEquals(1, scalar(connection,
                "SELECT call_count FROM dmr_conventional_talkgroup_summary"));
        }
    }

    @Test
    void rejectsInvalidCallWithoutWriting() throws Exception
    {
        Path database = mTemporaryFolder.resolve("invalid.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogRecords.DmrConventionalCall invalid = new P25ActivityLogRecords.DmrConventionalCall(
                2_000, 1_000, "GUID:bad", "bad", "Bad", null, 0, 3,
                P25ActivityLogRecords.DmrTargetKind.GROUP, 1, 2, null, false);
            assertThrows(SQLException.class,
                () -> P25ActivityLogSchema.recordDmrConventionalCall(connection, invalid));
            P25ActivityLogRecords.DmrConventionalCall invalidIdentity =
                groupCall(1_000, 2_000, "bad-id", 461_125_000L, 1,
                    DmrActivitySchema.MAXIMUM_DMR_ID + 1, 2, false);
            assertThrows(SQLException.class,
                () -> P25ActivityLogSchema.recordDmrConventionalCall(connection, invalidIdentity));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM dmr_conventional_talkgroup_summary"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM conventional_activity_summary"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM receiver_context"));
        }
    }

    @Test
    void retentionClearAndResetAreBoundedAndContextScoped() throws Exception
    {
        Path database = mTemporaryFolder.resolve("maintenance.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                groupCall(1_000, 2_000, "old", 461_125_000L, 1, 91, 101, false));
            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                groupCall(9_000, 10_000, "keep", 462_125_000L, 2, 92, 102, false));

            DmrActivitySchema.CleanupResult cleanup = DmrActivitySchema.deleteOlderThan(connection, 5_000);
            assertEquals(1, cleanup.talkgroups());
            assertEquals(1, cleanup.radios());
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM dmr_conventional_talkgroup_summary"));

            assertEquals(2, DmrActivitySchema.clearSiteStats(connection, "keep"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM dmr_conventional_talkgroup_summary"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM dmr_conventional_radio_summary"));

            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                privateCall(11_000, 12_000, "reset", 463_125_000L, 1, 103, 203, false));
            assertEquals(2, DmrActivitySchema.resetStats(connection));
        }
    }

    @Test
    void admissionCapKeepsExistingRowsWritableAndRejectsNewIdentities() throws Exception
    {
        Path database = mTemporaryFolder.resolve("cap.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            P25ActivityLogRecords.DmrConventionalCall seed =
                groupCall(1_000, 2_000, "cap", 461_125_000L, 1, 1, 101, false);
            P25ActivityLogSchema.recordDmrConventionalCall(connection, seed);
            int context = scalar(connection,
                "SELECT id FROM receiver_context WHERE context_key='GUID:cap'");
            statement.executeUpdate("""
                WITH RECURSIVE identities(value) AS (
                    VALUES(2) UNION ALL SELECT value + 1 FROM identities WHERE value < 4096
                )
                INSERT INTO dmr_conventional_talkgroup_summary (
                    context_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms, call_count
                )
                SELECT %d, 461125000, 1, value, 1000, 2000, 1 FROM identities
                """.formatted(context));

            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                groupCall(3_000, 4_000, "cap", 461_125_000L, 1, 4_097, 102, false));
            assertEquals(DmrActivitySchema.MAXIMUM_TALKGROUPS_PER_CONTEXT, scalar(connection,
                "SELECT COUNT(*) FROM dmr_conventional_talkgroup_summary WHERE context_id=" + context));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM dmr_conventional_talkgroup_summary WHERE talkgroup_id=4097"));

            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                groupCall(5_000, 6_000, "cap", 461_125_000L, 1, 1, 103, false));
            assertEquals(2, scalar(connection,
                "SELECT call_count FROM dmr_conventional_talkgroup_summary WHERE talkgroup_id=1"));

            statement.executeUpdate("""
                WITH RECURSIVE identities(value) AS (
                    VALUES(1) UNION ALL SELECT value + 1 FROM identities WHERE value < 32768
                )
                INSERT OR IGNORE INTO dmr_conventional_radio_summary (
                    context_id, frequency_hz, timeslot, radio_id, first_seen_ms, last_seen_ms, call_count
                )
                SELECT %d, 461125000, 1, value, 1000, 2000, 1 FROM identities
                """.formatted(context));
            int existingRadioCalls = scalar(connection, """
                SELECT call_count FROM dmr_conventional_radio_summary
                WHERE context_id=%d AND frequency_hz=461125000 AND timeslot=1 AND radio_id=101
                """.formatted(context));

            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                privateCall(7_000, 8_000, "cap", 461_125_000L, 1, 101, 32_769, false));
            assertEquals(DmrActivitySchema.MAXIMUM_RADIOS_PER_CONTEXT, scalar(connection,
                "SELECT COUNT(*) FROM dmr_conventional_radio_summary WHERE context_id=" + context));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM dmr_conventional_radio_summary WHERE radio_id=32769"));
            assertEquals(existingRadioCalls + 1, scalar(connection, """
                SELECT call_count FROM dmr_conventional_radio_summary
                WHERE context_id=%d AND frequency_hz=461125000 AND timeslot=1 AND radio_id=101
                """.formatted(context)));
        }
    }

    @Test
    void indexesSupportRetentionAndRecentContextQueries() throws Exception
    {
        Path database = mTemporaryFolder.resolve("plans.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                groupCall(1_000, 2_000, "plans", 461_125_000L, 1, 1, 1, false));
            int context = scalar(connection,
                "SELECT id FROM receiver_context WHERE context_key='GUID:plans'");
            statement.executeUpdate("""
                WITH RECURSIVE identities(value) AS (
                    VALUES(2) UNION ALL SELECT value + 1 FROM identities WHERE value < 4096
                )
                INSERT INTO dmr_conventional_talkgroup_summary (
                    context_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms, call_count
                )
                SELECT %d, 461125000, 1, value, 1000, 2000 + value, 1 FROM identities
                """.formatted(context));
            statement.executeUpdate("""
                WITH RECURSIVE identities(value) AS (
                    VALUES(2) UNION ALL SELECT value + 1 FROM identities WHERE value < 32768
                )
                INSERT INTO dmr_conventional_radio_summary (
                    context_id, frequency_hz, timeslot, radio_id, first_seen_ms, last_seen_ms, call_count,
                    source_call_count, group_call_count
                )
                SELECT %d, 461125000, 1, value, 1000, 2000 + value, 1, 1, 1 FROM identities
                """.formatted(context));

            assertPlanUses(connection, """
                EXPLAIN QUERY PLAN
                SELECT context_id, frequency_hz, timeslot, talkgroup_id
                FROM dmr_conventional_talkgroup_summary
                WHERE last_seen_ms < 5000 ORDER BY last_seen_ms LIMIT 1000
                """, DmrActivitySchema.TALKGROUP_RETENTION_INDEX);
            assertPlanUses(connection, """
                EXPLAIN QUERY PLAN
                SELECT talkgroup_id, frequency_hz, timeslot
                FROM dmr_conventional_talkgroup_summary
                WHERE context_id=%d ORDER BY last_seen_ms DESC LIMIT 100
                """.formatted(context), DmrActivitySchema.TALKGROUP_CONTEXT_INDEX);
            assertPlanUses(connection, """
                EXPLAIN QUERY PLAN
                SELECT context_id, frequency_hz, timeslot, radio_id
                FROM dmr_conventional_radio_summary
                WHERE last_seen_ms < 5000 ORDER BY last_seen_ms LIMIT 1000
                """, DmrActivitySchema.RADIO_RETENTION_INDEX);
            assertPlanUses(connection, """
                EXPLAIN QUERY PLAN
                SELECT radio_id, frequency_hz, timeslot
                FROM dmr_conventional_radio_summary
                WHERE context_id=%d ORDER BY last_seen_ms DESC LIMIT 100
                """.formatted(context), DmrActivitySchema.RADIO_CONTEXT_INDEX);
        }
    }

    private static P25ActivityLogRecords.DmrConventionalCall groupCall(long start, long end, String guid,
                                                                        long frequency, int timeslot, int talkgroup,
                                                                        int source, boolean encrypted)
    {
        return new P25ActivityLogRecords.DmrConventionalCall(start, end, "GUID:" + guid, guid,
            "Repeater " + guid, "County DMR", frequency, timeslot, P25ActivityLogRecords.DmrTargetKind.GROUP,
            talkgroup, source, null, encrypted);
    }

    private static P25ActivityLogRecords.DmrConventionalCall privateCall(long start, long end, String guid,
                                                                          long frequency, int timeslot, int source,
                                                                          int target, boolean encrypted)
    {
        return new P25ActivityLogRecords.DmrConventionalCall(start, end, "GUID:" + guid, guid,
            "Repeater " + guid, "County DMR", frequency, timeslot, P25ActivityLogRecords.DmrTargetKind.PRIVATE,
            null, source, target, encrypted);
    }

    private static int scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static String text(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
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
