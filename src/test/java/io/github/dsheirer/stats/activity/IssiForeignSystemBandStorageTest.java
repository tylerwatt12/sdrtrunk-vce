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

/** Representative-volume query coverage for retained ISSI foreign-system band facts. */
class IssiForeignSystemBandStorageTest
{
    private static final String CONFIGURATION_ID = "123e4567-e89b-42d3-a456-426614174000";

    @Test
    void denseChannelPageAndRetentionStayOnTheirIndexes() throws Exception
    {
        try(Connection connection = open())
        {
            seedChannel(connection);
            execute(connection, """
                WITH RECURSIVE n(value) AS (
                    VALUES(0) UNION ALL SELECT value + 1 FROM n WHERE value < 65535
                )
                INSERT INTO p25_foreign_system_band(
                    channel_id, foreign_wacn, foreign_system_id, band, channel_type, confirmed_at_ms)
                SELECT 1, 0xABCDE, value / 16, value % 16, 0, 1000 FROM n
                """);
            execute(connection, """
                INSERT INTO p25_foreign_system_band_summary(
                    channel_id, foreign_wacn, foreign_system_id, band, channel_type,
                    first_seen_ms, last_seen_ms, observation_count)
                SELECT channel_id, foreign_wacn, foreign_system_id, band, channel_type,
                    1000, 1000, 1
                FROM p25_foreign_system_band
                """);
            execute(connection, "ANALYZE");

            String pageSql = """
                SELECT summary.foreign_wacn, summary.foreign_system_id, summary.band,
                    current.confirmed_at_ms
                FROM p25_foreign_system_band_summary summary
                LEFT JOIN p25_foreign_system_band current
                  ON current.channel_id = summary.channel_id
                 AND current.foreign_wacn = summary.foreign_wacn
                 AND current.foreign_system_id = summary.foreign_system_id
                 AND current.band = summary.band
                WHERE summary.channel_id = 1
                ORDER BY summary.foreign_wacn, summary.foreign_system_id, summary.band
                LIMIT 26 OFFSET 0
                """;
            String pagePlan = queryPlan(connection, pageSql);
            assertTrue(pagePlan.contains("SEARCH summary USING PRIMARY KEY (channel_id=?)"), pagePlan);
            assertTrue(pagePlan.contains("SEARCH current USING PRIMARY KEY " +
                "(channel_id=? AND foreign_wacn=? AND foreign_system_id=? AND band=?)"), pagePlan);
            assertFalse(pagePlan.contains("USE TEMP B-TREE"), pagePlan);
            assertEquals(26, countRows(connection, pageSql));

            String currentRetentionPlan = queryPlan(connection, """
                SELECT channel_id, foreign_wacn, foreign_system_id, band
                FROM p25_foreign_system_band INDEXED BY idx_p25_foreign_system_band_retention
                WHERE confirmed_at_ms < 2000
                ORDER BY confirmed_at_ms, channel_id, foreign_wacn, foreign_system_id, band
                LIMIT 64
                """);
            assertTrue(currentRetentionPlan.contains("idx_p25_foreign_system_band_retention"),
                currentRetentionPlan);
            assertFalse(currentRetentionPlan.contains("USE TEMP B-TREE"), currentRetentionPlan);

            String summaryRetentionPlan = queryPlan(connection, """
                SELECT channel_id, foreign_wacn, foreign_system_id, band
                FROM p25_foreign_system_band_summary
                    INDEXED BY idx_p25_foreign_system_band_summary_retention
                WHERE last_seen_ms < 2000
                ORDER BY last_seen_ms, channel_id, foreign_wacn, foreign_system_id, band
                LIMIT 64
                """);
            assertTrue(summaryRetentionPlan.contains("idx_p25_foreign_system_band_summary_retention"),
                summaryRetentionPlan);
            assertFalse(summaryRetentionPlan.contains("USE TEMP B-TREE"), summaryRetentionPlan);
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
                auto_start, decoder_type, primary_frequency_hz, config_json)
            VALUES ('%s', 'TRUNKED', 0, 'Test system', 'Test site', 'Control',
                0, 'P25_PHASE1', 851012500, '{}')
            """.formatted(CONFIGURATION_ID));
        execute(connection, """
            INSERT INTO receiver_channel(id, configuration_id, first_seen_ms, last_seen_ms)
            VALUES (1, '%s', 1, 1)
            """.formatted(CONFIGURATION_ID));
        execute(connection, """
            INSERT INTO p25_site_snapshot(channel_id, first_seen_ms, last_seen_ms)
            VALUES (1, 1, 1)
            """);
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }

    private static int countRows(Connection connection, String sql) throws Exception
    {
        int count = 0;
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            while(resultSet.next())
            {
                count++;
            }
        }
        return count;
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
}
