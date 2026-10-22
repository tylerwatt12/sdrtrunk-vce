/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StatsSqlRowsTest
{
    @Test
    void explicitRowConsumerReadsPastBrowseCeilingAndClosesAfterConsumerFailure() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:"))
        {
            String query = """
                WITH RECURSIVE generated(value) AS (
                    SELECT 1 UNION ALL SELECT value+1 FROM generated WHERE value<?
                ) SELECT value, NULL AS optional_value FROM generated ORDER BY value
                """;
            AtomicInteger count = new AtomicInteger();
            StatsSqlRows.forEachRow(connection, query, row -> {
                assertEquals(count.incrementAndGet(), ((Number)row.get("value")).intValue());
                assertTrue(row.containsKey("optional_value"));
                assertEquals(null,row.get("optional_value"));
            }, StatsSqlRows.MAXIMUM_MATERIALIZED_ROWS + 1);
            assertEquals(StatsSqlRows.MAXIMUM_MATERIALIZED_ROWS + 1,count.get());
            assertThrows(IllegalStateException.class, () -> StatsSqlRows.forEachRow(connection, query,
                row -> { throw new IllegalStateException("consumer failed"); }, 2));
            assertEquals(1,StatsSqlRows.queryRows(connection,"SELECT 1 AS value").size(),
                "A failed consumer releases its result set and statement");
        }
    }

    @Test
    void enforcesEmergencyMaterializationCeiling() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:"))
        {
            StatsApiException exception = assertThrows(StatsApiException.class, () -> StatsSqlRows.queryRows(
                connection, """
                    WITH RECURSIVE generated(value) AS (
                        SELECT 1
                        UNION ALL
                        SELECT value + 1 FROM generated WHERE value <= ?
                    )
                    SELECT value FROM generated
                    """, StatsSqlRows.MAXIMUM_MATERIALIZED_ROWS));

            assertEquals(413, exception.status());
            assertEquals("query_result_too_large", exception.code());
        }
    }
}
