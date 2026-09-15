/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasActivityRepresentativeTestDatabaseTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void createsExactAndOverThresholdDatabasesWithRepresentativeRetainedActivity() throws Exception
    {
        Path exactPath = mTemporaryFolder.resolve("exact-100000.sqlite");
        AliasActivityRepresentativeTestDatabase.Fixture exact =
            AliasActivityRepresentativeTestDatabase.createExact(exactPath);
        assertEquals(100_000, exact.aliasCount());
        assertRepresentative(exact);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(exactPath);

        Path overPath = mTemporaryFolder.resolve("over-100000.sqlite");
        AliasActivityRepresentativeTestDatabase.Fixture over =
            AliasActivityRepresentativeTestDatabase.createMoreThan(overPath);
        assertEquals(100_001, over.aliasCount());
        assertRepresentative(over);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(overPath);
    }

    private static void assertRepresentative(AliasActivityRepresentativeTestDatabase.Fixture fixture)
        throws Exception
    {
        assertTrue(fixture.retainedActivityRows() >= 75_000);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
            Statement statement = connection.createStatement())
        {
            assertEquals(fixture.aliasCount(), number(statement,
                "SELECT count(*) FROM alias WHERE alias_list_id=" + fixture.aliasListId()));
            assertEquals(fixture.aliasCount(), number(statement,
                "SELECT count(*) FROM alias_activity_summary WHERE alias_list_id=" + fixture.aliasListId()));
            assertTrue(number(statement, """
                SELECT count(DISTINCT matcher_type) FROM alias WHERE alias_list_id=9901
                """) >= 5);
            assertTrue(number(statement, """
                SELECT count(DISTINCT metrics_state) FROM alias_activity_summary WHERE alias_list_id=9901
                """) >= 3);
            assertTrue(number(statement, """
                SELECT count(DISTINCT logical_call_count) FROM alias_activity_summary WHERE alias_list_id=9901
                """) > 100);
            assertTrue(number(statement, """
                SELECT count(DISTINCT signaling_observation_count)
                FROM alias_activity_summary WHERE alias_list_id=9901
                """) > 25);
            assertTrue(number(statement, """
                SELECT max(last_evidence_ms)-min(last_evidence_ms)
                FROM alias_activity_summary WHERE alias_list_id=9901
                """) > 60_000_000);
            assertTrue(number(statement, """
                SELECT count(*) FROM alias_activity_summary
                WHERE alias_list_id=9901 AND metrics_state='observed'
                  AND logical_call_count=0 AND signaling_observation_count=0
                """) > 5_000);
        }
    }

    private static long number(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }
}
