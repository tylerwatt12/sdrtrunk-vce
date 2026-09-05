/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** Regression coverage for the single bounded routine-retention path. */
class ReceiverActivityRetentionTest
{
    private static final String CONFIGURATION_ID = "123e4567-e89b-42d3-a456-426614174000";

    @Test
    void passHasOneGlobalCapRotatesAndEventuallyDrainsEveryBacklog() throws Exception
    {
        try(Connection connection = open())
        {
            seedChannel(connection);
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 300)
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
                SELECT 1, value, 4 FROM n
                """);
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 300)
                INSERT INTO conventional_activity_bucket(channel_id, frequency_hz, timeslot, bucket_start_ms)
                SELECT 1, 450000000 + value, 1, value FROM n
                """);
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 300)
                INSERT INTO dmr_conventional_talkgroup_summary(
                    channel_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms, call_count)
                SELECT 1, 460000000 + value, 1, value, 1, 1, 1 FROM n
                """);
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 300)
                INSERT INTO dmr_conventional_radio_summary(
                    channel_id, frequency_hz, timeslot, radio_id, first_seen_ms, last_seen_ms,
                    call_count, source_call_count)
                SELECT 1, 470000000 + value, 1, value, 1, 1, 1, 1 FROM n
                """);

            ReceiverActivityRetention.Pass first = ReceiverActivityRetention.runPass(connection, 3_700_000);
            assertEquals(ReceiverActivityRetention.MAXIMUM_ROWS_PER_PASS, first.deletedRows());
            assertTrue(first.moreWorkLikely());
            assertTrue(first.tasksVisited() < ReceiverActivityRetention.taskCount());
            assertEquals(first.nextCursor(), ReceiverActivitySchema.readStatusLong(connection,
                ReceiverActivityRetention.CURSOR_STATUS_KEY));
            assertTrue(scalar(connection, "SELECT count(*) FROM receiver_activity_event") < 300);
            assertTrue(scalar(connection, "SELECT count(*) FROM conventional_activity_bucket") < 300);
            assertTrue(scalar(connection, "SELECT count(*) FROM dmr_conventional_talkgroup_summary") < 300);
            assertTrue(scalar(connection, "SELECT count(*) FROM dmr_conventional_radio_summary") < 300);

            ReceiverActivityRetention.Pass second = ReceiverActivityRetention.runPass(connection, 3_700_000);
            assertTrue(second.deletedRows() > 0);
            ReceiverActivityRetention.Pass third = ReceiverActivityRetention.runPass(connection, 3_700_000);
            assertEquals(0, third.deletedRows());
            assertFalse(third.moreWorkLikely());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM conventional_activity_bucket"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM dmr_conventional_talkgroup_summary"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM dmr_conventional_radio_summary"));
        }
    }

    @Test
    void expiredP25ParentSurvivesWhileAnyRetainedChildIsCurrent() throws Exception
    {
        try(Connection connection = open())
        {
            seedChannel(connection);
            execute(connection, """
                INSERT INTO p25_site_snapshot(channel_id, first_seen_ms, last_seen_ms)
                VALUES (1, 1, 1)
                """);
            execute(connection, """
                INSERT INTO p25_site_channel_summary(
                    channel_id, channel_key, first_seen_ms, last_seen_ms, observation_count)
                VALUES (1, '1-1', 1, 10000, 1)
                """);

            ReceiverActivityRetention.runPass(connection, 5_000);
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_site_snapshot"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_site_channel_summary"));

            execute(connection, "UPDATE p25_site_channel_summary SET last_seen_ms=1");
            ReceiverActivityRetention.runPass(connection, 5_000);
            assertEquals(0, scalar(connection, "SELECT count(*) FROM p25_site_channel_summary"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM p25_site_snapshot"));
        }
    }

    @Test
    void boundedFollowUpPassesConvergeWhileExpiredRowsContinueArriving() throws Exception
    {
        try(Connection connection = open())
        {
            seedChannel(connection);
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 1000)
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
                SELECT 1, value, 4 FROM n
                """);

            for(int pass = 0; pass < 8; pass++)
            {
                execute(connection, """
                    WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 10)
                    INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
                    SELECT 1, 1001 + value, 4 FROM n
                    """);
                ReceiverActivityRetention.runPass(connection, 3_700_000);
            }

            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
        }
    }

    @Test
    void orphanAndLearnedSiteProbesAreSystemScopedAndIndexBacked() throws Exception
    {
        try(Connection connection = open())
        {
            seedChannel(connection);
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 1000)
                INSERT INTO radio_system(
                    id, system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                    first_seen_ms, last_seen_ms)
                SELECT value, printf('p25:%05x:%03x', 1, value), 1, 0, 1, value, 1, 1 FROM n
                """);
            execute(connection, """
                INSERT INTO p25_learned_site(
                    learned_site_id, radio_system_id, rfss, site, first_seen_ms, last_seen_ms)
                SELECT id, id, id % 256, (id / 256) % 256, 1, 1 FROM radio_system
                """);
            execute(connection, """
                INSERT INTO receiver_activity_event(channel_id, radio_system_id, observed_at_ms, action_code)
                SELECT 1, id, id, 4 FROM radio_system
                """);
            execute(connection, """
                INSERT INTO trunked_signaling_activity_bucket(channel_id, radio_system_id, bucket_start_ms)
                SELECT 1, id, id FROM radio_system
                """);
            execute(connection, "ANALYZE");
            String orphanPlan = queryPlan(connection, """
                SELECT system.id
                FROM radio_system system
                WHERE NOT EXISTS (SELECT 1 FROM receiver_channel WHERE radio_system_id = system.id)
                  AND NOT EXISTS (SELECT 1 FROM receiver_activity_event WHERE radio_system_id = system.id)
                  AND NOT EXISTS (
                      SELECT 1 FROM trunked_signaling_activity_bucket WHERE radio_system_id = system.id)
                ORDER BY system.id LIMIT 64
                """);
            assertTrue(orphanPlan.contains("idx_receiver_channel_radio_system"), orphanPlan);
            assertTrue(orphanPlan.contains("idx_receiver_activity_event_radio_system"), orphanPlan);
            assertTrue(orphanPlan.contains("idx_trunked_signaling_activity_system"), orphanPlan);

            String learnedPlan = queryPlan(connection, """
                SELECT site.learned_site_id
                FROM p25_learned_site site INDEXED BY idx_p25_learned_site_retention
                WHERE site.last_seen_ms < 5000
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_call_bucket fact
                      WHERE fact.radio_system_id = site.radio_system_id
                        AND fact.learned_site_id = site.learned_site_id)
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_call_identity_bucket fact
                      WHERE fact.radio_system_id = site.radio_system_id
                        AND fact.learned_site_id = site.learned_site_id)
                ORDER BY site.last_seen_ms, site.radio_system_id, site.learned_site_id LIMIT 64
                """);
            assertTrue(learnedPlan.contains("idx_p25_learned_site_retention"), learnedPlan);
            assertEquals(2, occurrences(learnedPlan, "SEARCH fact"), learnedPlan);
            assertTrue(learnedPlan.contains("radio_system_id=? AND learned_site_id=?"), learnedPlan);
            assertTrue(learnedPlan.contains("learned_site_id=? AND radio_system_id=?"), learnedPlan);
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
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        return connection;
    }

    private static void seedChannel(Connection connection) throws Exception
    {
        execute(connection, """
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name,
                radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json)
            VALUES ('%s', 'CONVENTIONAL', 0, 'Test', 'Test', 'Test', NULL, 0, 'DMR', 460000000, '{}')
            """.formatted(CONFIGURATION_ID));
        execute(connection, """
            INSERT INTO receiver_channel(id, configuration_id, first_seen_ms, last_seen_ms)
            VALUES (1, '%s', 1, 1)
            """.formatted(CONFIGURATION_ID));
    }

    private static String queryPlan(Connection connection, String sql) throws Exception
    {
        StringBuilder plan = new StringBuilder();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("EXPLAIN QUERY PLAN " + sql))
        {
            while(resultSet.next())
            {
                plan.append(resultSet.getString("detail")).append('\n');
            }
        }
        return plan.toString();
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static int occurrences(String value, String search)
    {
        return (value.length() - value.replace(search, "").length()) / search.length();
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }
}
