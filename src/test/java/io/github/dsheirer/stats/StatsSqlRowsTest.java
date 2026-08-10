/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class StatsSqlRowsTest
{
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
