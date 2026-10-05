/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConnection;

/** Real SQLite coverage for optional automatic optimization and the shared writer connection after it yields. */
class ReceiverActivityMaintenanceTest
{
    private static final String CONFIGURATION_ID = "123e4567-e89b-42d3-a456-426614174000";

    @Test
    void interruptedOptimizationPreservesRowsAndReleasesHandlerAndWriterLock(@TempDir Path temporary)
        throws Exception
    {
        String url = "jdbc:sqlite:" + temporary.resolve("optimization.sqlite");
        try(Connection connection = DriverManager.getConnection(url);
            Connection otherWriter = DriverManager.getConnection(url))
        {
            execute(connection, "PRAGMA journal_mode=WAL");
            seedOptimizationFixture(connection);
            execute(connection, "PRAGMA analysis_limit=3000");
            long originalAnalysisLimit = scalar(connection, "PRAGMA analysis_limit");
            AtomicInteger clockReads = new AtomicInteger();

            assertFalse(ReceiverActivityMaintenance.optimizeAutomatically(connection, expiredClock(clockReads)));
            assertTrue(clockReads.get() > 1, "real ANALYZE execution must reach the progress callback");
            assertTrue(connection.getAutoCommit());
            assertEquals(originalAnalysisLimit, scalar(connection, "PRAGMA analysis_limit"),
                "interrupted nested ANALYZE must restore the connection's original analysis limit");
            assertEquals(5_000, scalar(connection, "SELECT count(*) FROM optimization_fixture"));
            assertEquals(12_502_500, scalar(connection, "SELECT sum(value) FROM optimization_fixture"),
                "the connection must execute a large query after its expired handler has been cleared");

            execute(otherWriter, "INSERT INTO optimization_fixture VALUES (5001)");
            assertEquals(5_001, scalar(connection, "SELECT count(*) FROM optimization_fixture"),
                "interruption must release the SQLite writer lock");
        }
    }

    @Test
    void automaticPassKeepsCommittedRetentionAndDoesNotClaimDeferredOptimizationCompleted(@TempDir Path temporary)
        throws Exception
    {
        try(Connection connection = openActivityDatabase(temporary.resolve("automatic.sqlite")))
        {
            seedRecentActivity(connection);
            execute(connection, """
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code) VALUES (1, 1, 4)
                """);
            ReceiverActivitySchema.updateStatus(connection, "last_maintenance_ms", "123");

            ReceiverActivityMaintenance.AutomaticResult result =
                ReceiverActivityMaintenance.runAutomaticMaintenancePass(connection, 30,
                    expiredClock(new AtomicInteger()));

            assertTrue(result.optimizationDeferred());
            assertTrue(result.deletedRows() > 0);
            assertEquals(0, scalar(connection,
                "SELECT count(*) FROM receiver_activity_event WHERE observed_at_ms=1"));
            assertEquals(5_000, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(123, ReceiverActivitySchema.readStatusLong(connection, "last_maintenance_ms"));
            assertTrue(ReceiverActivitySchema.readStatusLong(connection, "last_retention_cleanup_ms") > 0);
            assertTrue(connection.getAutoCommit());
            assertEquals(0, scalar(connection, "PRAGMA analysis_limit"));
            ReceiverActivityMaintenance.runLightMaintenance(connection, 30);
            assertTrue(ReceiverActivitySchema.readStatusLong(connection, "last_maintenance_ms") > 123,
                "later explicit maintenance must finish on the connection after interrupted optimization");
            assertEquals(5_000, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
        }
    }

    @Test
    void normalAutomaticOptimizationPopulatesPlannerStatisticsWithoutChangingRows() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:"))
        {
            seedOptimizationFixture(connection);
            assertTrue(ReceiverActivityMaintenance.optimizeAutomatically(connection, () -> 0L));
            assertEquals(1, scalar(connection,
                "SELECT count(*) FROM sqlite_stat1 WHERE idx='idx_optimization_fixture' AND length(stat)>0"));
            assertEquals(5_000, scalar(connection, "SELECT count(*) FROM optimization_fixture"));
        }
    }

    @Test
    void busyOptionalOptimizationYieldsWithoutInterruptingTheOtherWriter(@TempDir Path temporary) throws Exception
    {
        String url = "jdbc:sqlite:" + temporary.resolve("busy.sqlite");
        try(Connection connection = DriverManager.getConnection(url);
            Connection otherWriter = DriverManager.getConnection(url))
        {
            execute(connection, "PRAGMA journal_mode=WAL");
            execute(connection, "PRAGMA busy_timeout=1");
            seedOptimizationFixture(connection);
            otherWriter.setAutoCommit(false);
            execute(otherWriter, "INSERT INTO optimization_fixture VALUES (5001)");

            assertFalse(ReceiverActivityMaintenance.optimizeAutomatically(connection, () -> 0L));
            assertFalse(otherWriter.getAutoCommit());
            assertEquals(5_000, scalar(connection, "SELECT count(*) FROM optimization_fixture"));
            otherWriter.commit();
            assertEquals(5_001, scalar(connection, "SELECT count(*) FROM optimization_fixture"));
            assertTrue(ReceiverActivityMaintenance.optimizeAutomatically(connection, () -> 0L));
        }
    }

    @Test
    void unrelatedOptimizationFailureIsPropagatedAndHandlerIsRemoved() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:"))
        {
            seedOptimizationFixture(connection);
            execute(connection, "PRAGMA query_only=ON");
            SQLException failure = assertThrows(SQLException.class,
                () -> ReceiverActivityMaintenance.optimizeAutomatically(connection, () -> 0L));
            assertEquals(8, failure.getErrorCode() & 0xFF); //SQLITE_READONLY, not optional busy work.
            assertEquals(12_502_500, scalar(connection, "SELECT sum(value) FROM optimization_fixture"));
            execute(connection, "PRAGMA query_only=OFF");
            assertTrue(ReceiverActivityMaintenance.optimizeAutomatically(connection, () -> 0L));
        }
    }

    @Test
    void interruptionFromAnotherThreadIsNotMistakenForAnAutomaticDeadline() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            var executor = Executors.newSingleThreadExecutor())
        {
            seedOptimizationFixture(connection);
            CountDownLatch inProgress = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            var cancellation = executor.submit(() -> {
                assertTrue(inProgress.await(5, TimeUnit.SECONDS));
                ((SQLiteConnection)connection).getDatabase().interrupt();
                interrupted.countDown();
                return null;
            });
            AtomicInteger clockReads = new AtomicInteger();
            LongSupplier clock = () -> {
                if(clockReads.getAndIncrement() > 0)
                {
                    inProgress.countDown();
                    try
                    {
                        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                    }
                    catch(InterruptedException failure)
                    {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }
                return 0L; //The automatic deadline never expires; cancellation is external.
            };

            SQLException failure = assertThrows(SQLException.class,
                () -> ReceiverActivityMaintenance.optimizeAutomatically(connection, clock));
            cancellation.get(5, TimeUnit.SECONDS);
            assertEquals(9, failure.getErrorCode() & 0xFF);
            assertEquals(5_000, scalar(connection, "SELECT count(*) FROM optimization_fixture"));
            assertTrue(ReceiverActivityMaintenance.optimizeAutomatically(connection, () -> 0L));
        }
    }

    @Test
    void optimizationRejectsCallerTransactionWithoutCommittingOrDiscardingIt(@TempDir Path temporary)
        throws Exception
    {
        String url = "jdbc:sqlite:" + temporary.resolve("transaction.sqlite");
        try(Connection connection = DriverManager.getConnection(url);
            Connection observer = DriverManager.getConnection(url))
        {
            execute(connection, "PRAGMA journal_mode=WAL");
            seedOptimizationFixture(connection);
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO optimization_fixture VALUES (5001)");

            assertThrows(SQLException.class,
                () -> ReceiverActivityMaintenance.optimizeAutomatically(connection, () -> 0L));
            assertFalse(connection.getAutoCommit());
            assertEquals(5_001, scalar(connection, "SELECT count(*) FROM optimization_fixture"));
            assertEquals(5_000, scalar(observer, "SELECT count(*) FROM optimization_fixture"));
            connection.rollback();
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

    static LongSupplier expiredClock(AtomicInteger reads)
    {
        return () -> reads.getAndIncrement() == 0 ? 0L :
            ReceiverActivityMaintenance.AUTOMATIC_OPTIMIZE_NANOSECONDS + 1;
    }

    private static void seedOptimizationFixture(Connection connection) throws Exception
    {
        execute(connection, "CREATE TABLE optimization_fixture(value INTEGER NOT NULL)");
        execute(connection, "CREATE INDEX idx_optimization_fixture ON optimization_fixture(value)");
        execute(connection, """
            WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<5000)
            INSERT INTO optimization_fixture SELECT value FROM n
            """);
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
