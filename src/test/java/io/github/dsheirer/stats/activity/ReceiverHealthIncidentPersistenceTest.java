/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceiverHealthIncidentPersistenceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void upsertsLifecycleAndKeepsOnlyTheNewestTwoHundredOccurrences() throws Exception
    {
        Path database = mTemporaryFolder.resolve("bounded-health.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            for(int occurrence = 1; occurrence <= 205; occurrence++)
            {
                ReceiverActivitySchema.recordReceiverHealthIncident(connection,
                    incident(10_000, occurrence, "warning", 0, "opened " + occurrence));
            }
            ReceiverActivitySchema.recordReceiverHealthIncident(connection,
                incident(10_000, 205, "critical", 20_500, "resolved 205"));
            connection.commit();

            assertEquals(SdrTrunkDatabaseSchema.MAXIMUM_RECEIVER_HEALTH_INCIDENTS,
                number(connection, "SELECT COUNT(*) FROM receiver_health_incident"));
            assertEquals(6, number(connection,
                "SELECT MIN(occurrence_id) FROM receiver_health_incident"));
            assertEquals(205, number(connection,
                "SELECT MAX(occurrence_id) FROM receiver_health_incident"));
            assertEquals(1, number(connection, """
                SELECT COUNT(*)
                FROM receiver_health_incident
                WHERE process_started_at_ms=10000 AND occurrence_id=205
                  AND severity='critical' AND resolved_at_ms=20500
                  AND observed='resolved 205'
                """));

            StringBuilder plan = new StringBuilder();
            try(Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                    EXPLAIN QUERY PLAN
                    SELECT * FROM receiver_health_incident ORDER BY id DESC LIMIT 200
                    """))
            {
                while(rows.next())
                {
                    plan.append(rows.getString("detail"));
                }
            }
            assertFalse(plan.toString().contains("USE TEMP B-TREE"));
        }

        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    @Test
    void backgroundWriterCommitsHealthIncidentWithoutWaitingForLongBatchDeadline() throws Exception
    {
        Path database = mTemporaryFolder.resolve("prompt-health.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, false, 16, 100,
            TimeUnit.MINUTES.toMillis(1));
        writer.start();
        assertTrue(writer.enqueue(incident(30_000, 1, "warning", 0, "opened")));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while(writer.getWrittenRecords() < 1 && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
        assertEquals(1, writer.getWrittenRecords());
        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(1, number(connection, """
                SELECT COUNT(*) FROM receiver_health_incident
                WHERE process_started_at_ms=30000 AND occurrence_id=1 AND resolved_at_ms=0
                """));
        }
    }

    private static ReceiverHealthIncidentRecord incident(long processStartedAtMs, long occurrenceId,
                                                          String severity, long resolvedAtMs, String observed)
    {
        long openedAtMs = processStartedAtMs + occurrenceId;
        return new ReceiverHealthIncidentRecord(processStartedAtMs, occurrenceId, "receiver-iq-drop", severity,
            "Radio data was lost before decoding", "Test tuner", openedAtMs, openedAtMs, resolvedAtMs,
            occurrenceId, observed, "Receiver processing could not keep up", "Radio data was lost",
            "Check the incoming radio-data backlog");
    }

    private static long number(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
