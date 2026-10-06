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
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite coverage for retention alone and explicit administrator maintenance. */
class ReceiverActivityMaintenanceTest
{
    private static final String CONFIGURATION_ID = "123e4567-e89b-42d3-a456-426614174000";

    @Test
    void retentionOnlyExpiresRowsWithoutOptimizingOrClaimingAdministratorMaintenance(@TempDir Path temporary)
        throws Exception
    {
        try(Connection connection = openActivityDatabase(temporary.resolve("retention.sqlite")))
        {
            seedRecentActivity(connection);
            seedOptimizationFixture(connection);
            execute(connection,
                "INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code) VALUES (1, 1, 4)");
            ReceiverActivitySchema.updateStatus(connection, "last_maintenance_ms", "123");

            ReceiverActivityMaintenance.RetentionResult result =
                ReceiverActivityMaintenance.cleanupRetentionPass(connection, 30);

            assertEquals(1, result.deletedRows());
            assertEquals(5_000, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE observed_at_ms=1"));
            assertEquals(0, scalar(connection,
                "SELECT count(*) FROM sqlite_stat1 WHERE idx='idx_optimization_fixture'"));
            assertEquals(123, ReceiverActivitySchema.readStatusLong(connection, "last_maintenance_ms"));
            assertTrue(ReceiverActivitySchema.readStatusLong(connection, "last_retention_cleanup_ms") > 0);
            assertTrue(connection.getAutoCommit());

            ReceiverActivityMaintenance.runLightMaintenance(connection, 30);
            assertTrue(ReceiverActivitySchema.readStatusLong(connection, "last_maintenance_ms") > 123);
            assertEquals(1, scalar(connection,
                "SELECT count(*) FROM sqlite_stat1 WHERE idx='idx_optimization_fixture' AND length(stat)>0"),
                "the same analysis candidate must still be optimized by explicit maintenance");
        }
    }

    @Test
    void explicitMaintenanceStillFinishesOptimization(@TempDir Path temporary) throws Exception
    {
        Path database = temporary.resolve("manual.sqlite");
        try(Connection connection = openActivityDatabase(database))
        {
            seedRecentActivity(connection);
            ReceiverActivityMaintenance.Result result = ReceiverActivityMaintenance.run(connection, database, 30,
                ReceiverActivityMaintenance.Operation.MAINTAIN);

            assertEquals(ReceiverActivityMaintenance.Operation.MAINTAIN, result.operation());
            assertTrue(result.checkOk());
            assertTrue(ReceiverActivitySchema.readStatusLong(connection, "last_maintenance_ms") > 0);
            assertEquals(5_000, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, """
                SELECT count(*) FROM sqlite_stat1 WHERE idx='idx_receiver_activity_event_channel_time'
                    AND length(stat)>0
                """));
        }
    }

    @Test
    void explicitShrinkReclaimsFreePagesAndPreservesRecentActivity(@TempDir Path temporary) throws Exception
    {
        Path database = temporary.resolve("shrink.sqlite");
        try(Connection connection = openActivityDatabase(database))
        {
            seedRecentActivity(connection);
            execute(connection, "CREATE TABLE deleted_fixture(payload BLOB)");
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<1000)
                INSERT INTO deleted_fixture SELECT zeroblob(4096) FROM n
                """);
            execute(connection, "DELETE FROM deleted_fixture");
            assertTrue(scalar(connection, "PRAGMA freelist_count") > 0);

            ReceiverActivityMaintenance.Result result = ReceiverActivityMaintenance.run(connection, database, 30,
                ReceiverActivityMaintenance.Operation.SHRINK);

            assertEquals(ReceiverActivityMaintenance.Operation.SHRINK, result.operation());
            assertEquals(0, scalar(connection, "PRAGMA freelist_count"));
            assertEquals(5_000, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertTrue(ReceiverActivitySchema.readStatusLong(connection, "last_shrink_ms") > 0);
            assertEquals(1, scalar(connection, """
                SELECT count(*) FROM sqlite_stat1 WHERE idx='idx_receiver_activity_event_channel_time'
                    AND length(stat)>0
                """));
        }
    }

    @Test
    void explicitCheckPreservesExpiredHistoryAndDoesNotOptimize(@TempDir Path temporary) throws Exception
    {
        Path database = temporary.resolve("check.sqlite");
        try(Connection connection = openActivityDatabase(database))
        {
            seedRecentActivity(connection);
            seedOptimizationFixture(connection);
            execute(connection,
                "INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code) VALUES (1, 1, 4)");

            ReceiverActivityMaintenance.Result result = ReceiverActivityMaintenance.run(connection, database, 30,
                ReceiverActivityMaintenance.Operation.CHECK);

            assertTrue(result.checkOk());
            assertEquals(0, result.rowsDeleted());
            assertEquals(5_001, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertTrue(ReceiverActivitySchema.readStatusLong(connection, "last_integrity_check_ms") > 0);
            assertEquals(0, scalar(connection,
                "SELECT count(*) FROM sqlite_stat1 WHERE idx='idx_optimization_fixture'"));
            assertEquals(0, ReceiverActivitySchema.readStatusLong(connection, "last_maintenance_ms"));
        }
    }

    private static void seedOptimizationFixture(Connection connection) throws Exception
    {
        execute(connection, "CREATE TABLE optimization_fixture(value INTEGER NOT NULL)");
        execute(connection, "CREATE INDEX idx_optimization_fixture ON optimization_fixture(value)");
        execute(connection, """
            WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<5000)
            INSERT INTO optimization_fixture SELECT value FROM n
            """);
        scalar(connection, "SELECT sum(value) FROM optimization_fixture INDEXED BY idx_optimization_fixture");
    }

    private static Connection openActivityDatabase(Path database) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        execute(connection, "PRAGMA foreign_keys=ON");
        execute(connection, "PRAGMA journal_mode=WAL");
        SdrTrunkDatabaseSchema.create(connection);
        SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        return connection;
    }

    private static void seedRecentActivity(Connection connection) throws Exception
    {
        execute(connection, """
            INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,name,
                alias_list_id,auto_start,decoder_type,primary_frequency_hz,config_json)
            VALUES ('%s','CONVENTIONAL',0,'Test','Test','Test',
                (SELECT id FROM alias_list WHERE family='DMR' LIMIT 1),0,'DMR',460000000,
                '{"decodeConfiguration":{"channelMode":"CONVENTIONAL"}}')
            """.formatted(CONFIGURATION_ID));
        execute(connection, """
            INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms)
            VALUES (1,'%s',1,1)
            """.formatted(CONFIGURATION_ID));
        execute(connection, """
            WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<5000)
            INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code)
            SELECT 1,%d+value,4 FROM n
            """.formatted(System.currentTimeMillis()));
    }

    private static void execute(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql))
        {
            return result.next() ? result.getLong(1) : 0;
        }
    }
}
