/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises the actual retention query against dense detailed history and independent descendants. */
class ReceiverActivityIdentityRetentionTest
{
    private static final String CONFIGURATION_ID = "123e4567-e89b-42d3-a456-426614174001";

    @Test
    void identityCleanupSeeksDetailedHistoryAndPreservesEveryReferencedIdentity() throws Exception
    {
        try(Connection connection = open())
        {
            seed(connection);
            String plan = identityTaskPlan(connection);
            assertTrue(plan.contains("SEARCH child USING INDEX idx_receiver_activity_event_source_time " +
                "(source_identity_summary_id=?)"), plan);
            assertTrue(plan.contains("SEARCH child USING INDEX idx_receiver_activity_event_target_time " +
                "(target_identity_summary_id=?)"), plan);
            assertTrue(plan.contains("SEARCH child USING COVERING INDEX idx_trunked_logical_identity_identity " +
                "(identity_summary_id=? AND radio_system_id=?)"), plan);

            ReceiverActivityRetention.runPass(connection, 5_000, () -> 0);
            assertEquals(1, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id=1"),
                "current source events retain their radio");
            assertEquals(1, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id=2"),
                "current target events retain their talkgroup");
            assertEquals(1, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id=3"),
                "a current logical-call bucket independently retains its identity");
            assertEquals(1, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id=4"),
                "an event member independently retains its identity");
            assertEquals(0, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id=5"),
                "an expired identity without descendants is removed");
            assertEquals(2_001, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(10_001, scalar(connection, "SELECT count(*) FROM trunked_logical_call_identity_bucket"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM activity_event_identity_member"));
            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check"))
            {
                assertTrue(!resultSet.next(), "retention must preserve descendant foreign keys");
            }
        }
    }

    private static Connection open() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        execute(connection, "PRAGMA foreign_keys=ON");
        SdrTrunkDatabaseSchema.create(connection);
        SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        return connection;
    }

    private static void seed(Connection connection) throws Exception
    {
        execute(connection, """
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name,
                alias_list_id, auto_start, decoder_type, primary_frequency_hz, config_json)
            VALUES ('%s', 'TRUNKED', 0, 'P25', 'Site', 'Control',
                (SELECT id FROM alias_list WHERE family='P25' LIMIT 1), 0, 'P25_PHASE1', 851012500, '{}')
            """.formatted(CONFIGURATION_ID));
        execute(connection, """
            INSERT INTO radio_system(
                id, system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                first_seen_ms, last_seen_ms)
            VALUES (1, 'p25:bee00:3a9', 1, 0, 0xBEE00, 0x3A9, 1, 10000)
            """);
        execute(connection, """
            INSERT INTO receiver_channel(
                id, configuration_id, first_seen_ms, last_seen_ms, radio_system_id, radio_system_assigned_at_ms)
            VALUES (1, '%s', 1, 10000, 1, 1)
            """.formatted(CONFIGURATION_ID));
        execute(connection, """
            WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<1000)
            INSERT INTO radio_system_identity_summary(
                id, radio_system_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms)
            SELECT value, 1, CASE WHEN value=1 THEN 2 ELSE 1 END, value, 1, 1 FROM n
            """);
        execute(connection, """
            WITH RECURSIVE n(value) AS (VALUES(1001) UNION ALL SELECT value+1 FROM n WHERE value<2000)
            INSERT INTO radio_system_identity_summary(
                id, radio_system_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms)
            SELECT value, 1, 1, value, 1, 10000 FROM n
            """);
        execute(connection, """
            WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<2000)
            INSERT INTO receiver_activity_event(
                channel_id, radio_system_id, observed_at_ms, action_code,
                source_identity_summary_id, target_identity_summary_id, target_kind_code, target_observed_local_id)
            SELECT 1, 1, 10000+value, 4, 1, 2, 1, 2 FROM n
            """);
        execute(connection, """
            INSERT INTO receiver_activity_event(id, channel_id, radio_system_id, observed_at_ms, action_code)
            VALUES (2001, 1, 1, 10000, 4)
            """);
        execute(connection, """
            INSERT INTO activity_event_identity_member(event_id, radio_system_id, identity_summary_id)
            VALUES (2001, 1, 4)
            """);
        execute(connection, """
            INSERT INTO trunked_logical_call_identity_bucket(
                radio_system_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_summary_id)
            VALUES (1, 3600000, 1, 1, 3)
            """);
        execute(connection, """
            WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<10000)
            INSERT INTO trunked_logical_call_identity_bucket(
                radio_system_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_summary_id)
            SELECT 1, 3600000+(value/1000)*3600000, 1, 1, 1001+(value%1000) FROM n
            """);
        execute(connection, "ANALYZE");
    }

    private static String identityTaskPlan(Connection connection) throws Exception
    {
        //Inspect the production statement so this contract cannot pass with a simplified duplicate query.
        Field tasksField = ReceiverActivityRetention.class.getDeclaredField("TASKS");
        tasksField.setAccessible(true);
        for(Object task: (List<?>)tasksField.get(null))
        {
            Method nameMethod = task.getClass().getDeclaredMethod("name");
            nameMethod.setAccessible(true);
            if("radio-system identities".equals(nameMethod.invoke(task)))
            {
                Method sqlMethod = task.getClass().getDeclaredMethod("sql");
                sqlMethod.setAccessible(true);
                StringBuilder plan = new StringBuilder();
                try(PreparedStatement statement = connection.prepareStatement(
                    "EXPLAIN QUERY PLAN " + sqlMethod.invoke(task)))
                {
                    statement.setLong(1, 5_000);
                    statement.setInt(2, ReceiverActivityRetention.MAXIMUM_ROWS_PER_TASK);
                    try(ResultSet resultSet = statement.executeQuery())
                    {
                        while(resultSet.next()) plan.append(resultSet.getString("detail")).append('\n');
                    }
                }
                return plan.toString();
            }
        }
        throw new AssertionError("Radio-system identity retention task is missing");
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }
}
