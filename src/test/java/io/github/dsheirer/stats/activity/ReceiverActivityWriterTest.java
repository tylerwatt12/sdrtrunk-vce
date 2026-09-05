/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end coverage for the writer boundary using the current saved-channel identity model. */
class ReceiverActivityWriterTest
{
    private static final String CONFIGURATION_ID = "123e4567-e89b-42d3-a456-426614174000";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void writesThroughConfigurationChannelToTheNativeP25RadioSystem() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("writer.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 1, 0);
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_000_000L));
        writer.close();

        assertEquals(ReceiverActivityStatus.State.STOPPED, writer.getStatus().state());
        assertEquals(1, writer.getWrittenRecords());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(CONFIGURATION_ID, text(connection, """
                SELECT channel.configuration_id
                FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                """));
            assertEquals("p25:bee00:3a9", text(connection, """
                SELECT system.system_key
                FROM receiver_channel channel
                JOIN radio_system system ON system.id=channel.radio_system_id
                """));
            assertEquals(1, scalar(connection, "SELECT grant_count FROM trunked_signaling_activity_bucket"));
        }
    }

    @Test
    void closeDrainsAnAcceptedPartialBatch() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("drain.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, false, 8, 100, 60_000);
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.DENIAL, 1_700_000_000_100L));
        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(1, scalar(connection, "SELECT denial_count FROM trunked_signaling_activity_bucket"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
        }
    }

    @Test
    void configuredBatchCapFlushesBeforeTheLongCollectionDeadline() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("batch-cap.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 3,
            TimeUnit.SECONDS.toMillis(10));
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_000_100L));
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_000_200L));
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_000_300L));

        awaitWritten(writer, 3);
        assertEquals(3, writer.getWrittenRecords());
        writer.close();
    }

    @Test
    void sparseBatchWaitsForItsConfiguredDeadline() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("batch-deadline.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 10, 300);
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_000_400L));

        Thread.sleep(100);
        assertEquals(0, writer.getWrittenRecords());
        awaitWritten(writer, 1);
        assertEquals(1, writer.getWrittenRecords());
        writer.close();
    }

    @Test
    void closeDrainsAcceptedRecordsAcrossMoreThanOneBatch() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("multiple-batches.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 2,
            TimeUnit.SECONDS.toMillis(10));
        writer.start();
        for(int index = 0; index < 5; index++)
        {
            writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_001_000L + index));
        }
        writer.close();

        assertEquals(5, writer.getWrittenRecords());
        assertEquals(ReceiverActivityStatus.State.STOPPED, writer.getStatus().state());
        assertTrue(writer.isWorkerTerminated());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(5, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
        }
    }

    @Test
    void aStaleMissingConfigurationRecordIsDroppedWithoutPoisoningTheBatch() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("rollback.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 2,
            TimeUnit.SECONDS.toMillis(10));
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_002_000L));
        writer.enqueue(activity("99999999-9999-4999-8999-999999999999",
            ReceiverActivityRecords.Action.GRANT, 1_700_000_002_001L));

        awaitWritten(writer, 2);
        writer.close();
        assertEquals(2, writer.getWrittenRecords());
        assertEquals(ReceiverActivityStatus.State.STOPPED, writer.getStatus().state());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM receiver_channel"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM trunked_signaling_activity_bucket"));
        }
    }

    @Test
    void boundedQueueDropsObserverRecordsInsteadOfBlockingTheProducer() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("overflow.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, false, 1, 250, 25);
        writer.start();

        long started = System.nanoTime();
        for(int index = 0; index < 2_000; index++)
        {
            writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_003_000L + index));
        }
        long elapsed = System.nanoTime() - started;
        writer.close();

        assertTrue(writer.getDroppedRecords() > 0);
        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(2), "producer handoff must remain nonblocking");
    }

    @Test
    void startupBacklogUsesOnePassAndLiveObservationsAreWrittenBeforeItDrains() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("startup-retention.sqlite"));
        insertConfiguredChannel(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id, configuration_id, first_seen_ms, last_seen_ms)
                VALUES (1, '%s', 1, 1)
                """.formatted(CONFIGURATION_ID));
            statement.executeUpdate("""
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 5000)
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
                SELECT 1, value, 4 FROM n
                """);
        }

        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 200, 100, 0);
        writer.start();
        long now = System.currentTimeMillis();
        for(int index = 0; index < 100; index++)
        {
            writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, now + index));
        }

        awaitWritten(writer, 100);
        assertEquals(100, writer.getWrittenRecords());
        assertEquals(ReceiverActivityStatus.State.RUNNING, writer.getStatus().state());
        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertTrue(scalar(connection,
                "SELECT count(*) FROM receiver_activity_event WHERE observed_at_ms < 10000") > 0,
                "startup must not drain a large expired backlog before serving the live queue");
        }
    }

    @Test
    void clearChannelIsOrderedBetweenEarlierAndLaterObservations() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("clear-order.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 32, 1_250,
            TimeUnit.SECONDS.toMillis(10));
        writer.start();
        long before = 1_700_000_004_000L;
        long after = before + 1;
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, before));
        StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.clearChannel(CONFIGURATION_ID);
        writer.submitMaintenance(request);
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, after));

        ReceiverActivityMaintenance.Result result = request.result().get(5, TimeUnit.SECONDS);
        assertEquals(ReceiverActivityMaintenance.Operation.CLEAR_CHANNEL_STATS, result.operation());
        writer.close();
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(after, scalar(connection, "SELECT observed_at_ms FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM configuration_channel"));
        }
    }

    @Test
    void resetIsOrderedBetweenEarlierAndLaterObservations() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("reset-order.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 32, 1_250,
            TimeUnit.SECONDS.toMillis(10));
        writer.start();
        long before = 1_700_000_005_000L;
        long after = before + 1;
        writer.enqueue(activity(ReceiverActivityRecords.Action.DENIAL, before));
        StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.forOperation(
            ReceiverActivityMaintenance.Operation.RESET_STATS);
        writer.submitMaintenance(request);
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, after));

        ReceiverActivityMaintenance.Result result = request.result().get(5, TimeUnit.SECONDS);
        assertEquals(ReceiverActivityMaintenance.Operation.RESET_STATS, result.operation());
        writer.close();
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(after, scalar(connection, "SELECT observed_at_ms FROM receiver_activity_event"));
            assertEquals(0, scalar(connection,
                "SELECT coalesce(sum(denial_count),0) FROM trunked_signaling_activity_bucket"));
            assertEquals(1, scalar(connection,
                "SELECT coalesce(sum(grant_count),0) FROM trunked_signaling_activity_bucket"));
        }
    }

    @Test
    void lockedWriteRetriesDuringGracefulClose() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("locked-close.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 1,
            TimeUnit.SECONDS.toMillis(10), 25, 1_000);
        writer.start();
        awaitState(writer, ReceiverActivityStatus.State.RUNNING);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try(Connection blocker = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = blocker.createStatement())
        {
            statement.execute("BEGIN IMMEDIATE");
            writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_006_000L));
            awaitQueueEmpty(writer);
            Thread closeThread = new Thread(() ->
            {
                try
                {
                    writer.close();
                }
                catch(Throwable throwable)
                {
                    failure.set(throwable);
                }
            }, "locked statistics writer close test");
            closeThread.start();
            Thread.sleep(100);
            assertTrue(closeThread.isAlive());
            statement.execute("ROLLBACK");
            closeThread.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(closeThread.isAlive());
        }

        assertNull(failure.get());
        assertEquals(1, writer.getWrittenRecords());
        assertEquals(ReceiverActivityStatus.State.STOPPED, writer.getStatus().state());
        assertTrue(writer.isWorkerTerminated());
    }

    @Test
    void lockedWritePastGraceFailsAndTerminates() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("locked-past-grace.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 1,
            TimeUnit.SECONDS.toMillis(10), 25, 300);
        writer.start();
        awaitState(writer, ReceiverActivityStatus.State.RUNNING);

        try(Connection blocker = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = blocker.createStatement())
        {
            statement.execute("BEGIN IMMEDIATE");
            writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_006_100L));
            awaitQueueEmpty(writer);
            Thread.sleep(75);
            long started = System.nanoTime();
            writer.close();

            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
            assertEquals(ReceiverActivityStatus.State.FAILED, writer.getStatus().state());
            assertTrue(writer.getStatus().lastError() != null && !writer.getStatus().lastError().isBlank());
            assertTrue(writer.isWorkerTerminated());
            assertEquals(0, writer.getWrittenRecords());
            assertEquals(0, scalar(blocker, "SELECT COUNT(*) FROM receiver_activity_event"));
            statement.execute("ROLLBACK");
        }
    }

    @Test
    void maintenanceIsRejectedWhenTheWriterIsNotRunning()
    {
        ReceiverActivityWriter writer = new ReceiverActivityWriter(
            mTemporaryFolder.resolve("not-running.sqlite"), 30, false, 1);
        StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.forOperation(
            ReceiverActivityMaintenance.Operation.RESET_STATS);

        writer.submitMaintenance(request);

        assertTrue(request.result().isCompletedExceptionally());
    }

    private static ReceiverActivityRecords.ActivityEvent activity(ReceiverActivityRecords.Action action,
                                                                   long timestamp)
    {
        return activity(CONFIGURATION_ID, action, timestamp);
    }

    private static ReceiverActivityRecords.ActivityEvent activity(String configurationId,
                                                                   ReceiverActivityRecords.Action action,
                                                                   long timestamp)
    {
        return new ReceiverActivityRecords.ActivityEvent(timestamp, configurationId,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "APCO25", action, "CALL_GROUP", "1811524",
            "56138", "TALKGROUP", List.of(), 854_187_500L, "00-0509", 1, false, null, null,
            0xBEE00, 0x3A9, 0x293, 2, 1, null, action == ReceiverActivityRecords.Action.CALL, null, null,
            TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.ORDINARY,
            ReceiverActivityRecords.P25Identity.ORDINARY, List.of());
    }

    private static void awaitWritten(ReceiverActivityWriter writer, long count) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while(writer.getWrittenRecords() < count && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
    }

    private static void awaitState(ReceiverActivityWriter writer, ReceiverActivityStatus.State state)
        throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while(writer.getStatus().state() != state && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
        assertEquals(state, writer.getStatus().state());
    }

    private static void awaitQueueEmpty(ReceiverActivityWriter writer) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while(writer.getQueuedRecordCountForTest() != 0 && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
        assertEquals(0, writer.getQueuedRecordCountForTest());
    }

    private static void insertConfiguredChannel(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json
                ) VALUES (
                    '%s', 'TRUNKED', 0, 'Capture system', 'Capture site', 'Control',
                    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 0, 'P25_PHASE1', 854187500, '{}'
                )
                """.formatted(CONFIGURATION_ID));
        }
    }

    private static Path createDatabase(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
        }
        return database;
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
