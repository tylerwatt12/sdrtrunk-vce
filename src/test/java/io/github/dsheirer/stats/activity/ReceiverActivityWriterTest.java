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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.audio.call.SourceCallReference;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end coverage for the writer boundary using the current saved-channel identity model. */
class ReceiverActivityWriterTest
{
    @Test
    void failedForeignKeyRestorationClosesWriterBeforeCompletingResetAndPreservesCommittedProgress() throws Exception
    {
        assertUnsafeResetClosesWriter("restore");
    }

    @Test
    void failedResetRollbackCannotCommitUnknownStateOrResumeTheWriter() throws Exception
    {
        assertUnsafeResetClosesWriter("rollback");
    }

    @Test
    void failedFinalForeignKeyVerificationClosesWriterBeforeReleasingReset() throws Exception
    {
        assertUnsafeResetClosesWriter("verify");
    }

    private void assertUnsafeResetClosesWriter(String failureMode) throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("reset-unsafe-" + failureMode + ".sqlite"));
        insertConfiguredChannel(database);
        try(Connection real = ActivityTestDatabase.openPair(database))
        {
            try(Statement seed = real.createStatement()) { seedExpiredActivity(seed, 2_000, 1); seed.execute("PRAGMA foreign_keys=ON"); }
            AtomicBoolean injected = new AtomicBoolean(), closedBeforeCompletion = new AtomicBoolean();
            AtomicBoolean rollbackFailed = new AtomicBoolean(), committedAfterFailedRollback = new AtomicBoolean();
            var rowsAtFailedDelete = new java.util.concurrent.atomic.AtomicLong();
            Connection guarded = (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    try
                    {
                        if(failureMode.equals("rollback") && method.getName().equals("rollback") && injected.get())
                        {
                            rollbackFailed.set(true);
                            throw new java.sql.SQLException("controlled reset rollback failure");
                        }
                        if(method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0]) && rollbackFailed.get())
                            committedAfterFailedRollback.set(true);
                        Object result = method.invoke(real, arguments);
                        if(result instanceof Statement statement && method.getName().equals("createStatement"))
                            return java.lang.reflect.Proxy.newProxyInstance(Statement.class.getClassLoader(),
                                new Class<?>[]{Statement.class}, (ignored, call, values) -> {
                                    if(failureMode.equals("restore") && call.getName().equals("execute") && values != null &&
                                        "PRAGMA main.foreign_keys=ON".equals(values[0]) &&
                                        scalar(real, "SELECT changes()") > 512 && injected.compareAndSet(false, true))
                                        throw new java.sql.SQLException("controlled foreign-key restoration failure");
                                    if(failureMode.equals("rollback") && call.getName().equals("executeUpdate") &&
                                        values != null && "DELETE FROM receiver_activity_event".equals(values[0]))
                                    {
                                        rowsAtFailedDelete.set(scalar(real, "SELECT count(*) FROM receiver_activity_event"));
                                        injected.set(true);
                                        throw new java.sql.SQLException("controlled reset deletion failure");
                                    }
                                    if(failureMode.equals("verify") && call.getName().equals("executeQuery") &&
                                        values != null && "PRAGMA main.foreign_key_check".equals(values[0]))
                                    {
                                        injected.set(true);
                                        return statement.executeQuery("SELECT 'reported_violation',1,'parent',0");
                                    }
                                    try { return call.invoke(statement, values); }
                                    catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                                });
                        return result;
                    }
                    catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true);
            //Exercise the actual failure handler deterministically without starting a competing writer thread.
            var running = ReceiverActivityWriter.class.getDeclaredField("mRunning"); running.setAccessible(true);
            ((AtomicBoolean)running.get(writer)).set(true);
            var pending = ReceiverActivityWriter.class.getDeclaredField("mDeletionJobs"); pending.setAccessible(true);
            var nextPass = ReceiverActivityWriter.class.getDeclaredField("mNextDeletionPassNanos"); nextPass.setAccessible(true);
            var process = ReceiverActivityWriter.class.getDeclaredMethod("processDeletionPass", Connection.class);
            process.setAccessible(true);
            var request = StatsDatabaseMaintenanceRequest.forOperation(ReceiverActivityMaintenance.Operation.RESET_STATS);
            request.result().whenComplete((result, failure) -> {
                try { closedBeforeCompletion.set(real.isClosed()); }
                catch(java.sql.SQLException error) { throw new RuntimeException(error); }
            });
            ((java.util.Deque<StatsDatabaseMaintenanceRequest>)pending.get(writer)).add(request);
            for(int pass = 0; pass < 1_000 && !request.result().isDone(); pass++)
            {
                nextPass.setLong(writer, 0);
                process.invoke(writer, guarded);
            }
            assertTrue(injected.get(), "The fixture must reach its intended SQLite failure");
            assertThrows(ExecutionException.class, () -> request.result().get(2, TimeUnit.SECONDS));
            assertTrue(closedBeforeCompletion.get(), "The unsafe connection must close before reset admission is released");
            assertEquals(ReceiverActivityStatus.State.FAILED, writer.getStatus().state());
            assertFalse(writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, System.currentTimeMillis())));
            assertFalse(committedAfterFailedRollback.get(), "A failed rollback must never be followed by an implicit COMMIT");
            if(!failureMode.equals("rollback"))
                assertTrue(request.progress().rowsDeleted() >= 2_000, "Committed table changes must still be reported");
            try(Connection verified = ActivityTestDatabase.openPair(database))
            {
                assertEquals(failureMode.equals("rollback") ? rowsAtFailedDelete.get() : 0,
                    scalar(verified, "SELECT count(*) FROM receiver_activity_event"));
                assertEquals(1, scalar(verified, "SELECT count(*) FROM configuration_channel"));
            }
        }
    }
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
        try(Connection connection = ActivityTestDatabase.openPair(database))
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
    void defaultStatisticsCadenceCommitsLowRateActivityWithoutViewerDemand() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("live-cadence.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true);
        writer.start();
        try
        {
            writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, System.currentTimeMillis()));
            awaitWritten(writer, 1, 3);
            assertEquals(0, writer.historyGeneration(), "ordinary inserts preserve the current history seed");
            try(Connection connection = ActivityTestDatabase.openPair(database))
            {
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            }
        }
        finally { writer.close(); }
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

        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(1, scalar(connection, "SELECT denial_count FROM trunked_signaling_activity_bucket"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
        }
    }

    @Test
    void nonPositiveOptionalFrequenciesDoNotStopTheWriter() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("unknown-frequency.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 2,
            TimeUnit.SECONDS.toMillis(10));
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.REGISTER, 1_700_000_000_200L, 0L));
        writer.enqueue(activity(ReceiverActivityRecords.Action.REGISTER, 1_700_000_000_201L, -1L));

        awaitWritten(writer, 2);
        assertEquals(2, writer.getWrittenRecords());
        assertEquals(ReceiverActivityStatus.State.RUNNING, writer.getStatus().state());
        writer.close();

        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(2, scalar(connection,
                "SELECT COUNT(*) FROM receiver_activity_event WHERE frequency_hz IS NULL"));
        }
    }

    @Test
    void constraintRejectedRecordDoesNotStopWriterOrDiscardValidNeighbors() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("constraint-isolation.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 3,
            TimeUnit.SECONDS.toMillis(10));
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_000_210L));
        writer.enqueue(activityWithEncryptionAlgorithm(1_700_000_000_211L, -1));
        writer.enqueue(activity(ReceiverActivityRecords.Action.DENIAL, 1_700_000_000_212L));

        awaitWritten(writer, 2);
        assertEquals(2, writer.getWrittenRecords());
        assertEquals(1, writer.getDroppedRecords());
        assertEquals(ReceiverActivityStatus.State.RUNNING, writer.getStatus().state());
        assertNull(writer.getStatus().lastError());
        assertEquals(1, writer.getStatus().dropDiagnostics().constraintRejection());
        assertEquals(1, writer.getStatus().dropDiagnostics().writerRecordCategories().get("signaling"));
        assertEquals(0, writer.getStatus().dropDiagnostics().writerQueueOverflow());
        assertTrue(writer.getStatus().dropDiagnostics().lastConstraintRejectionMs() > 0);
        writer.close();

        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(2, scalar(connection,
                "SELECT grant_count + denial_count FROM trunked_signaling_activity_bucket"));
            assertEquals(4, scalar(connection,
                "SELECT sum(grant_count + denial_count) FROM radio_system_identity_summary"));
            assertEquals(2, scalar(connection,
                "SELECT grant_count + denial_count FROM trunked_radio_group_summary"));
            assertEquals(1, scalar(connection,
                "SELECT value FROM statistics_status WHERE key='records_dropped'"));
            assertEquals(1, scalar(connection,
                "SELECT value FROM statistics_status WHERE key='constraint_rejection_drops'"));
        }
        ReceiverActivityWriter restarted = new ReceiverActivityWriter(database, 30, true);
        restarted.start();
        restarted.enqueue(activity(ReceiverActivityRecords.Action.DENIAL, 1_700_000_000_213L));
        awaitWritten(restarted, 3);
        assertEquals(1, restarted.getStatus().dropDiagnostics().constraintRejection());
        assertEquals(1, restarted.getStatus().dropDiagnostics().writerRecordCategories().get("signaling"));
        assertNull(restarted.getStatus().lastError());
        restarted.close();
    }

    @Test
    void rejectedRecordDiagnosticsWaitForAConcurrentConfigurationWriteAndQualityResumes() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("constraint-busy-diagnostics.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 1, 0, 1, 5_000);
        CountDownLatch locked = new CountDownLatch(1);
        AtomicReference<Thread> writerThread = new AtomicReference<>();
        AtomicReference<Exception> lockFailure = new AtomicReference<>();
        Logger logger = (Logger)LoggerFactory.getLogger(ReceiverActivityWriter.class);
        try(Connection blocker = ActivityTestDatabase.openPair(database))
        {
            blocker.setAutoCommit(false);
            AppenderBase<ILoggingEvent> appender = new AppenderBase<>()
            {
                @Override protected void append(ILoggingEvent event)
                {
                    if(!event.getFormattedMessage().startsWith("Discarded invalid statistics record") ||
                        !writerThread.compareAndSet(null, Thread.currentThread())) return;
                    try(Statement statement = blocker.createStatement())
                    {
                        // Acquire the second connection's write lock exactly after the rejected transaction rolled back.
                        statement.executeUpdate("INSERT INTO statistics_status(key,value,updated_at_ms) VALUES ('busy_fixture','1',1)");
                    }
                    catch(Exception failure) { lockFailure.set(failure); }
                    finally { locked.countDown(); }
                }
            };
            appender.start(); logger.addAppender(appender);
            try
            {
                writer.start(); awaitState(writer, ReceiverActivityStatus.State.RUNNING);
                writer.enqueue(activityWithEncryptionAlgorithm(System.currentTimeMillis(), -1));
                assertTrue(locked.await(5, TimeUnit.SECONDS)); assertNull(lockFailure.get());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean retrying = false;
                while(System.nanoTime() < deadline && writer.getStatus().state() == ReceiverActivityStatus.State.RUNNING)
                {
                    retrying = java.util.Arrays.stream(writerThread.get().getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals(ReceiverActivityWriter.class.getName()) &&
                            frame.getMethodName().equals("pauseBeforeDatabaseBusyRetry"));
                    if(retrying) break;
                    Thread.sleep(10);
                }
                assertEquals(ReceiverActivityStatus.State.RUNNING, writer.getStatus().state());
                assertTrue(retrying, "diagnostics must use the existing busy retry while the real write lock is held");
                long observed = System.currentTimeMillis();
                writer.enqueue(new ReceiverActivityRecords.ControlChannelQuality(observed, CONFIGURATION_ID, "APCO25",
                    TrunkedIdentityDomain.STANDARD, 853_000_000, -45.0, -45.0, -46.0, -44.0, 100.0,
                    10, 0, 0, 0, 0, observed));
                blocker.commit(); awaitWritten(writer, 1);
                assertEquals(ReceiverActivityStatus.State.RUNNING, writer.getStatus().state());
                assertNull(writer.getStatus().lastError());
                assertEquals(1, writer.getStatus().dropDiagnostics().constraintRejection());
                try(Connection reader = ActivityTestDatabase.openPair(database))
                {
                    assertEquals(observed, scalar(reader, "SELECT max(observed_at_ms) FROM trunked_control_channel_quality"));
                    assertEquals(1, scalar(reader, "SELECT value FROM statistics_status WHERE key='records_dropped'"));
                    assertEquals(1, scalar(reader, "SELECT value FROM statistics_status WHERE key='constraint_rejection_drops'"));
                }
            }
            finally
            {
                blocker.rollback(); writer.close(); logger.detachAppender(appender); appender.stop();
            }
        }
    }

    @Test
    void zeroP25SiteUplinkIsStoredAsNullWithoutStoppingTheWriter() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("unknown-site-uplink.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 1,
            TimeUnit.SECONDS.toMillis(10));
        long timestamp = 1_700_000_000_300L;
        P25NetworkConfigurationSnapshot.Channel channel = new P25NetworkConfigurationSnapshot.Channel(
            "primary_control", "1-2053", 876_675_000L, 0L, false, 1);
        ReceiverActivityRecords.SiteSnapshot site = new ReceiverActivityRecords.SiteSnapshot(timestamp,
            CONFIGURATION_ID, ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "a".repeat(64), "APCO25",
            0xBEE00, 0x3A9, 0x293, 2, 1, null, null, false, null, 876_675_000L, 876_675_000L,
            876_675_000L, List.of(channel), List.of(), List.of(), List.of(), List.of());

        writer.start();
        writer.enqueue(site);

        awaitWritten(writer, 1);
        assertEquals(1, writer.getWrittenRecords());
        assertEquals(ReceiverActivityStatus.State.RUNNING, writer.getStatus().state());
        assertNull(writer.getStatus().lastError());
        writer.close();

        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM p25_site_channel_summary"));
            assertEquals(1, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_channel_summary WHERE uplink_hz IS NULL"));
            assertEquals(1, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_channel WHERE uplink_hz IS NULL"));
            assertEquals(876_675_000L, scalar(connection,
                "SELECT downlink_hz FROM p25_site_channel_summary"));
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
    void coalescesHotSummaryUpdatesWithoutLosingHistoryOrCounts() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("coalesced-writes.sqlite"));
        insertConfiguredChannel(database);
        long first = System.currentTimeMillis();
        first -= Math.floorMod(first, TimeUnit.HOURS.toMillis(1));
        first += TimeUnit.MINUTES.toMillis(1);
        long primedAt = first + 1_000;
        long last = first + 2_000;
        int batchSize = 32;

        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            ReceiverActivitySchema.recordActivity(connection,
                activity(ReceiverActivityRecords.Action.GRANT, primedAt), true);
        }

        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 64, batchSize,
            TimeUnit.SECONDS.toMillis(10));
        writer.start();
        awaitState(writer, ReceiverActivityStatus.State.RUNNING);
        installWriteAudit(database);

        for(int index = 0; index < batchSize; index++)
        {
            long observedAt = index == 0 ? last : index == 1 ? first : primedAt + index;
            ReceiverActivityRecords.Action action = index % 2 == 0 ?
                ReceiverActivityRecords.Action.GRANT : ReceiverActivityRecords.Action.DENIAL;
            writer.enqueue(activity(action, observedAt));
        }

        awaitWritten(writer, batchSize);
        writer.close();

        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(batchSize + 1, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(batchSize / 2 + 1, scalar(connection,
                "SELECT sum(grant_count) FROM trunked_signaling_activity_bucket"));
            assertEquals(batchSize / 2, scalar(connection,
                "SELECT sum(denial_count) FROM trunked_signaling_activity_bucket"));
            assertEquals(2L * (batchSize / 2 + 1), scalar(connection,
                "SELECT sum(grant_count) FROM radio_system_identity_summary"));
            assertEquals(batchSize, scalar(connection,
                "SELECT sum(denial_count) FROM radio_system_identity_summary"));
            assertEquals(batchSize / 2 + 1, scalar(connection,
                "SELECT grant_count FROM trunked_radio_group_summary"));
            assertEquals(batchSize / 2, scalar(connection,
                "SELECT denial_count FROM trunked_radio_group_summary"));
            assertEquals(first, scalar(connection, "SELECT first_seen_ms FROM receiver_channel"));
            assertEquals(last, scalar(connection, "SELECT last_seen_ms FROM receiver_channel"));
            assertEquals(first, scalar(connection, "SELECT first_seen_ms FROM radio_system"));
            assertEquals(last, scalar(connection, "SELECT last_seen_ms FROM radio_system"));
            assertEquals(2, scalar(connection, """
                SELECT count(*) FROM radio_system_identity_summary
                WHERE first_seen_ms=%d AND last_seen_ms=%d
                """.formatted(first, last)));
            assertEquals(1, scalar(connection,
                "SELECT count(*) FROM test_update_audit WHERE table_name='receiver_channel'"));
            assertEquals(2, scalar(connection,
                "SELECT count(*) FROM test_update_audit WHERE table_name='radio_system'"));
            assertEquals(4, scalar(connection,
                "SELECT count(*) FROM test_update_audit WHERE table_name='radio_system_identity_summary'"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void flushesIdentityBoundsBeforeApplyingStaleEncryptionMetadata() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("identity-metadata-order.sqlite"));
        insertConfiguredChannel(database);
        long first = System.currentTimeMillis();

        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            ReceiverActivitySchema.recordActivity(connection,
                activity(ReceiverActivityRecords.Action.GRANT, first), true);
        }

        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 3,
            TimeUnit.SECONDS.toMillis(10));
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, first + 100));
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, first + 300));
        writer.enqueue(activityWithEncryptionAlgorithm(first + 200, 7));
        awaitWritten(writer, 3);
        writer.close();

        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(2, scalar(connection, """
                SELECT count(*) FROM radio_system_identity_summary
                WHERE last_seen_ms=%d AND last_encryption_algorithm_id IS NULL
                  AND last_encryption_key_id IS NULL
                """.formatted(first + 300)));
            assertEquals(1, scalar(connection, """
                SELECT count(*) FROM trunked_radio_group_summary
                WHERE last_seen_ms=%d AND last_encryption_algorithm_id IS NULL
                  AND last_encryption_key_id IS NULL
                """.formatted(first + 300)));
            assertEquals(4, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
        }
    }

    @Test
    void p25SourceEnrichmentPreservesExistingHistoryWhenNewRowsCommitTogether() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("live-source-enrichment.sqlite"));
        insertConfiguredChannel(database);
        var writer = new ReceiverActivityWriter(database, 30, true);
        long now = System.currentTimeMillis();
        var initial = p25Call("late", now, null);
        var known = p25Call("late", now, 202);
        var update = new ReceiverActivityRecords.P25CallObservation("late", known.activity(), false, false);
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            writeBatch(writer, connection, List.of(initial));
            writeBatch(writer, connection, List.of(update, activity(ReceiverActivityRecords.Action.REGISTER, now + 1)));
            assertEquals(202, scalar(connection,
                "SELECT source_observed_local_id FROM receiver_activity_event WHERE source_call_uuid IS NOT NULL"));
            writeBatch(writer, connection, List.of(update));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM receiver_activity_event"),
                "Repeating enrichment does not insert or lose saved events");
        }
    }

    @Test
    void resolvedExactReferenceEnrichesOnlyItsRetainedSavedSource() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("live-resolved-source.sqlite"));
        insertConfiguredChannel(database);
        var writer = new ReceiverActivityWriter(database, 30, true);
        long now = System.currentTimeMillis();
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            writeBatch(writer, connection, List.of(p25Call("exact", now, null)));
            writeBatch(writer, connection, List.of(resolved(1, now, "exact")));
            assertEquals(202, scalar(connection, """
                SELECT identity.identity_id FROM receiver_activity_event event
                JOIN radio_system_identity_summary identity ON identity.id = event.source_identity_summary_id
                """));
            writeBatch(writer, connection, List.of(resolved(2, now, "exact")));
            writeBatch(writer, connection, List.of(resolved(3, now, "unrelated")));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(202, scalar(connection, """
                SELECT identity.identity_id FROM receiver_activity_event event
                JOIN radio_system_identity_summary identity ON identity.id = event.source_identity_summary_id
                """));
        }
    }

    @Test
    void p25CompletionEnrichesTheSameRowWithoutLosingTimingOnLaterIdentityUpdates() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("p25-call-duration.sqlite"));
        insertConfiguredChannel(database);
        var writer = new ReceiverActivityWriter(database, 30, true);
        long now = System.currentTimeMillis();
        var initial = p25Call("duration", now, null);
        var finalTiming = new ReceiverActivityRecords.P25CallObservation("duration",
            initial.activity().withDurationMilliseconds(2_345L), false, true);
        var lateSource = new ReceiverActivityRecords.P25CallObservation("duration",
            p25Call("duration", now, 202).activity(), false, false);
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            writeBatch(writer, connection, List.of(initial));
            long id = scalar(connection, "SELECT id FROM receiver_activity_event");
            writeBatch(writer, connection, List.of(finalTiming));
            writeBatch(writer, connection, List.of(lateSource));
            writeBatch(writer, connection, List.of(finalTiming));
            //P25 native audio has no timing authority; its null duration must preserve the tracker span.
            writeBatch(writer, connection, List.of(resolved(1, now, "duration")));
            assertEquals(id, scalar(connection, "SELECT id FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(2_345L, scalar(connection, "SELECT duration_ms FROM receiver_activity_event"));
            assertEquals(202, scalar(connection, "SELECT source_observed_local_id FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT sum(logical_call_count) FROM trunked_logical_call_bucket"));
        }
    }

    @Test
    void completionTimingUpdatesOnlyItsExactRetainedCallAndConfiguredOwner() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("exact-call-duration.sqlite"));
        insertConfiguredChannel(database);
        var writer = new ReceiverActivityWriter(database, 30, true);
        long now = System.currentTimeMillis();
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            writeBatch(writer, connection, List.of(p25Call("first", now, 202), p25Call("next", now, 202)));
            writeBatch(writer, connection, List.of(new ReceiverActivityRecords.ActivityCallTiming(
                "first", "99999999-9999-4999-8999-999999999999", now + 100, 100L)));
            writeBatch(writer, connection, List.of(new ReceiverActivityRecords.ActivityCallTiming(
                "absent", CONFIGURATION_ID, now + 100, 100L)));
            writeBatch(writer, connection, List.of(new ReceiverActivityRecords.ActivityCallTiming(
                "first", CONFIGURATION_ID, now + 2_500, 2_500L)));
            writeBatch(writer, connection, List.of(new ReceiverActivityRecords.ActivityCallTiming(
                "first", CONFIGURATION_ID, now + 2_500, 2_500L)));
            writeBatch(writer, connection, List.of(new ReceiverActivityRecords.ActivityCallTiming(
                "first", CONFIGURATION_ID, now + 2_500, null)));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE duration_ms=2500"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE duration_ms IS NULL"));
            assertEquals(2_500L, scalar(connection, "SELECT duration_ms FROM receiver_activity_event WHERE source_call_uuid='" +
                SourceCallReference.fromToken("first") + "'"));
        }
    }

    private static ReceiverActivityRecords.ResolvedLogicalCall resolved(long sequence, long timestamp, String token)
    {
        return new ReceiverActivityRecords.ResolvedLogicalCall(new LogicalCallId(1, sequence), timestamp,
            CONFIGURATION_ID, "APCO25", TrunkedIdentityDomain.STANDARD, 0xBEE00, 0x3A9,
            56138, "TALKGROUP", List.of(), 202, false, null, null,
            ReceiverActivityRecords.P25Identity.ORDINARY, ReceiverActivityRecords.P25Identity.ORDINARY,
            List.of(), List.of(), "p25:bee00:3a9", null, null, List.of(SourceCallReference.fromToken(token)));
    }

    private static ReceiverActivityRecords.P25CallObservation p25Call(String token, long timestamp, Integer source)
    {
        List<Identifier> identifiers = new ArrayList<>(List.of(APCO25Talkgroup.create(56138),
            APCO25Wacn.create(0xBEE00), APCO25System.create(0x3A9)));
        if(source != null) identifiers.add(APCO25RadioIdentifier.createFrom(source));
        return (ReceiverActivityRecords.P25CallObservation)new ReceiverActivityMapper().mapCallStart(
            new P25CallStartEvent(CONFIGURATION_ID, DecoderType.P25_PHASE1, DecodeEventType.CALL_GROUP,
                timestamp, identifiers, 854_187_500L, 0, 509, 1, false, false, "p25:bee00:3a9", token));
    }

    private static void writeBatch(ReceiverActivityWriter writer, Connection connection,
                                    List<ReceiverActivityRecord> records) throws Exception
    {
        var method = ReceiverActivityWriter.class.getDeclaredMethod("writeBatch", Connection.class, List.class);
        method.setAccessible(true);
        method.invoke(writer, connection, records);
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
        try(Connection connection = ActivityTestDatabase.openPair(database))
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
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM receiver_channel"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM trunked_signaling_activity_bucket"));
        }
    }

    @Test
    void restoresObservationOverflowAcrossWriterRestartWithoutCountingTheSameIngressDropsTwice() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("observer-drops.sqlite"));
        insertConfiguredChannel(database);
        java.util.concurrent.atomic.AtomicLong drops = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong lastDrop = new java.util.concurrent.atomic.AtomicLong();
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, false);
        writer.setObservationDropProviders(drops::get, lastDrop::get);
        writer.start();
        drops.set(3);
        lastDrop.set(System.currentTimeMillis());
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, System.currentTimeMillis()));
        awaitWritten(writer, 1);
        writer.close();
        assertEquals(3, writer.getStatus().recordsDropped());
        assertEquals(3, writer.getStatus().dropDiagnostics().observationQueueOverflow());

        ReceiverActivityWriter restarted = new ReceiverActivityWriter(database, 30, false);
        restarted.setObservationDropProviders(drops::get, lastDrop::get);
        restarted.start();
        drops.incrementAndGet();
        lastDrop.incrementAndGet();
        restarted.enqueue(activity(ReceiverActivityRecords.Action.GRANT, System.currentTimeMillis()));
        awaitWritten(restarted, 2);
        restarted.close();
        assertEquals(4, restarted.getStatus().recordsDropped());
        assertEquals(4, restarted.getStatus().dropDiagnostics().observationQueueOverflow());
        assertEquals(lastDrop.get(), restarted.getStatus().dropDiagnostics().lastObservationQueueOverflowMs());
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(4, scalar(connection,
                "SELECT value FROM statistics_status WHERE key='observation_queue_overflow_drops'"));
        }
        ReceiverActivityWriter newProcess = new ReceiverActivityWriter(database, 30, false);
        newProcess.start();
        newProcess.enqueue(activity(ReceiverActivityRecords.Action.GRANT, System.currentTimeMillis()));
        awaitWritten(newProcess, 3);
        newProcess.close();
        assertEquals(4, newProcess.getStatus().dropDiagnostics().observationQueueOverflow());
    }

    @Test
    void restoresLegacyDropsWithoutInventingTheirCauseOrCategory() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("legacy-drops.sqlite"));
        insertConfiguredChannel(database);
        try(Connection connection = ActivityTestDatabase.openPair(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO statistics_status(key,value,updated_at_ms) VALUES('records_dropped','42300',1000) " +
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value");
        }
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, false);
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, System.currentTimeMillis()));
        awaitWritten(writer, 1);
        assertEquals(42300, writer.getDroppedRecords());
        assertEquals(42300, writer.getStatus().dropDiagnostics().unclassified());
        assertEquals(42300, writer.getStatus().dropDiagnostics().writerRecordCategories().get("unclassified"));
        assertEquals(0, writer.getStatus().dropDiagnostics().constraintRejection());
        assertEquals(0, writer.getStatus().dropDiagnostics().writerQueueOverflow());
        assertNull(writer.getStatus().lastError());
        writer.close();
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
        assertEquals(writer.getDroppedRecords(), writer.getStatus().dropDiagnostics().writerQueueOverflow());
        assertEquals(writer.getDroppedRecords(),
            writer.getStatus().dropDiagnostics().writerRecordCategories().get("signaling"));
        assertEquals(0, writer.getStatus().dropDiagnostics().constraintRejection());
        assertTrue(writer.getStatus().dropDiagnostics().lastWriterQueueOverflowMs() > 0);
        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(2), "producer handoff must remain nonblocking");
    }

    @Test
    void startupWritesObservationsBeforeAnyRetentionEvenWhenPersistedCleanupIsOverdue() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("startup-retention.sqlite"));
        insertConfiguredChannel(database);
        try(Connection connection = ActivityTestDatabase.openPair(database);
            Statement statement = connection.createStatement())
        {
            seedExpiredActivity(statement, 5_000, 1);
            ReceiverActivitySchema.updateStatus(connection, "last_retention_cleanup_ms", "1");
            ReceiverActivitySchema.updateStatus(connection, "last_maintenance_ms", "123");
            statement.executeUpdate("CREATE TABLE test_retention_write_order(written_records INTEGER NOT NULL)");
            statement.executeUpdate("""
                CREATE TRIGGER test_retention_after_write AFTER DELETE ON receiver_activity_event
                BEGIN
                    INSERT INTO test_retention_write_order VALUES (
                        coalesce((SELECT CAST(value AS INTEGER) FROM statistics_status
                            WHERE key='records_written'), 0));
                END
                """);
        }

        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 200, 100, 0);
        try
        {
            writer.start();
            awaitState(writer, ReceiverActivityStatus.State.RUNNING);
            try(Connection connection = ActivityTestDatabase.openPair(database))
            {
                assertEquals(5_000, scalar(connection, "SELECT count(*) FROM receiver_activity_event"),
                    "an initially idle writer must not run startup retention");
            }
            long now = System.currentTimeMillis();
            for(int index = 0; index < 100; index++)
            {
                assertTrue(writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, now + index)));
            }
            awaitWritten(writer, 100, 30);
            try(Connection connection = ActivityTestDatabase.openPair(database))
            {
                assertEquals(5_000, scalar(connection,
                    "SELECT count(*) FROM receiver_activity_event WHERE observed_at_ms < 10000"));
                assertEquals(1, ReceiverActivitySchema.readStatusLong(connection, "last_retention_cleanup_ms"));
                assertEquals(123, ReceiverActivitySchema.readStatusLong(connection, "last_maintenance_ms"));
                assertEquals(0, scalar(connection, "SELECT count(*) FROM test_retention_write_order"));
            }

            //Make the existing hourly timer due without a production clock seam or a one-hour test wait.
            setLastRetentionCleanup(writer, System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1) - 1_000);
            awaitRetentionCleanup(database);
            try(Connection connection = ActivityTestDatabase.openPair(database))
            {
                assertTrue(scalar(connection, "SELECT count(*) FROM test_retention_write_order") > 0);
                assertTrue(scalar(connection, "SELECT min(written_records) FROM test_retention_write_order") >= 100,
                    "routine expiry must begin only after the first observations have committed");
                assertEquals(123, ReceiverActivitySchema.readStatusLong(connection, "last_maintenance_ms"),
                    "scheduled retention must not become automatic optimization");
            }
            assertEquals(0, writer.getDroppedRecords());
            assertNull(writer.getStatus().lastError());
        }
        finally
        {
            writer.close();
        }
    }

    @Test
    void scheduledRetentionKeepsHourlyAndFiveSecondCatchupAndSkipsShutdownCleanup() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("scheduled-retention.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true);
        try(Connection connection = ActivityTestDatabase.openPair(database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            seedExpiredActivity(statement, 5_000, 1);
            ReceiverActivitySchema.updateStatus(connection, "last_maintenance_ms", "123");
            setWriterRunning(writer, true);
            setLastRetentionCleanup(writer, System.currentTimeMillis());
            runScheduledRetention(writer, connection);
            assertEquals(5_000, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0, writer.historyGeneration());

            setLastRetentionCleanup(writer, System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1) - 1_000);
            runScheduledRetention(writer, connection);
            long afterHourly = scalar(connection, "SELECT count(*) FROM receiver_activity_event");
            assertTrue(afterHourly < 5_000 && afterHourly >= 4_000, "an hourly pass must stay bounded");
            long hourlyGeneration = writer.historyGeneration();
            assertTrue(hourlyGeneration > 0);
            runScheduledRetention(writer, connection);
            assertEquals(afterHourly, scalar(connection, "SELECT count(*) FROM receiver_activity_event"),
                "a backlog must wait before its next pass");
            assertEquals(hourlyGeneration, writer.historyGeneration());

            setLastRetentionCleanup(writer, System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(6));
            runScheduledRetention(writer, connection);
            long afterCatchup = scalar(connection, "SELECT count(*) FROM receiver_activity_event");
            assertTrue(afterCatchup < afterHourly && afterCatchup >= afterHourly - 1_000,
                "the five-second backlog schedule must continue expiry without waiting another hour");
            assertTrue(writer.historyGeneration() > hourlyGeneration);
            assertEquals(123, ReceiverActivitySchema.readStatusLong(connection, "last_maintenance_ms"));
            assertEquals(0, scalar(connection, """
                SELECT count(*) FROM sqlite_stat1 WHERE idx='idx_receiver_activity_event_channel_time'
                """), "scheduled retention must leave eligible planner statistics untouched");

            setWriterRunning(writer, false);
            setLastRetentionCleanup(writer, 0);
            runScheduledRetention(writer, connection);
            assertEquals(afterCatchup, scalar(connection, "SELECT count(*) FROM receiver_activity_event"),
                "shutdown must finish observations without starting routine housekeeping");
        }
        finally
        {
            setWriterRunning(writer, false);
        }
    }

    @Test
    void unrelatedAutomaticRetentionDoesNotInvalidateDetailedHistory() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("summary-only-history-generation.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true);
        try(Connection connection = ActivityTestDatabase.openPair(database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms)
                VALUES (1,'%s',1,1)
                """.formatted(CONFIGURATION_ID));
            statement.executeUpdate("""
                INSERT INTO trunked_control_channel_quality(channel_id,frequency_hz,bucket_start_ms,observed_at_ms)
                VALUES (1,854187500,0,1)
                """);
            setWriterRunning(writer, true);
            setLastRetentionCleanup(writer, 0);
            runScheduledRetention(writer, connection);
            assertEquals(0, scalar(connection, "SELECT count(*) FROM trunked_control_channel_quality"));
            assertEquals(0, writer.historyGeneration(), "expiring unrelated measurements must not reload Activity");
        }
        finally { setWriterRunning(writer, false); }
    }

    @Test
    void reducingRetentionKeepsPromptCatchupWithoutStartupMaintenance() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("reduced-retention.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true);
        try(Connection connection = ActivityTestDatabase.openPair(database);
            Statement statement = connection.createStatement())
        {
            seedExpiredActivity(statement, 50, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2));
            statement.executeUpdate("INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code) " +
                "VALUES (1, " + System.currentTimeMillis() + ", 4)");
            setWriterRunning(writer, true);
            setLastRetentionCleanup(writer, System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(6));
            runScheduledRetention(writer, connection);
            assertEquals(51, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));

            writer.setRetentionDays(1);
            runScheduledRetention(writer, connection);
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(1, ReceiverActivitySchema.readStatusLong(connection, "retention_days"));
        }
        finally
        {
            setWriterRunning(writer, false);
        }
    }

    private static void seedExpiredActivity(Statement statement, int rows, long observedAt) throws Exception
    {
        statement.executeUpdate("""
            INSERT INTO receiver_channel(id, configuration_id, first_seen_ms, last_seen_ms)
            VALUES (1, '%s', 1, 1)
            """.formatted(CONFIGURATION_ID));
        statement.executeUpdate("""
            WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<%d)
            INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
            SELECT 1, %d+value, 4 FROM n
            """.formatted(rows, observedAt));
    }

    private static void setLastRetentionCleanup(ReceiverActivityWriter writer, long time) throws Exception
    {
        var field = ReceiverActivityWriter.class.getDeclaredField("mLastRetentionCleanup");
        field.setAccessible(true);
        field.setLong(writer, time);
    }

    private static void setWriterRunning(ReceiverActivityWriter writer, boolean running) throws Exception
    {
        var field = ReceiverActivityWriter.class.getDeclaredField("mRunning");
        field.setAccessible(true);
        ((AtomicBoolean)field.get(writer)).set(running);
    }

    private static void runScheduledRetention(ReceiverActivityWriter writer, Connection connection) throws Exception
    {
        var method = ReceiverActivityWriter.class.getDeclaredMethod("runScheduledRetention", Connection.class);
        method.setAccessible(true);
        method.invoke(writer, connection);
    }

    private static void awaitRetentionCleanup(Path database) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            while(ReceiverActivitySchema.readStatusLong(connection, "last_retention_cleanup_ms") <= 1 &&
                System.nanoTime() < deadline)
            {
                Thread.sleep(10);
            }
            assertTrue(ReceiverActivitySchema.readStatusLong(connection, "last_retention_cleanup_ms") > 1);
        }
    }

    @Test
    void scopedInteriorDeletionInvalidatesHistoryWithoutAnAppendOrChangingMaximumId() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("interior-history-generation.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 1, 0);
        writer.start();
        try
        {
            long first = System.currentTimeMillis();
            writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, first));
            writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, first + 1_000));
            awaitWritten(writer, 2);
            assertEquals(0, writer.historyGeneration());
            StatsDatabaseMaintenanceRequest.ScopedData target = new StatsDatabaseMaintenanceRequest.ScopedData(
                "radio_system", "p25:bee00:3a9", null, null, "detailed_events", null, List.of("events"),
                first, first + 1, null);
            try(Connection connection = ActivityTestDatabase.openPair(database))
            {
                long maximum = scalar(connection, "SELECT MAX(id) FROM receiver_activity_event");
                StatsDatabaseMaintenanceRequest deletion = StatsDatabaseMaintenanceRequest.delete(target);
                writer.submitMaintenance(deletion);
                assertTrue(deletion.result().get(5, TimeUnit.SECONDS).rowsDeleted() > 0);
                assertTrue(writer.historyGeneration() > 0);
                assertEquals(maximum, scalar(connection, "SELECT MAX(id) FROM receiver_activity_event"));
                assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
                long generation = writer.historyGeneration();
                StatsDatabaseMaintenanceRequest missing = StatsDatabaseMaintenanceRequest.delete(target);
                writer.submitMaintenance(missing);
                assertEquals(0, missing.result().get(5, TimeUnit.SECONDS).rowsDeleted());
                assertEquals(generation, writer.historyGeneration());
            }
        }
        finally { writer.close(); }
    }

    @Test
    void rolledBackFirstClearPassDoesNotInvalidateHistoryUntilADeletionCommits() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("rollback-history-generation.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 1, 0);
        writer.start();
        try
        {
            writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, System.currentTimeMillis()));
            awaitWritten(writer, 1);
            try(Connection connection = ActivityTestDatabase.openPair(database);
                Statement statement = connection.createStatement())
            {
                statement.execute("""
                    CREATE TRIGGER prevent_history_delete BEFORE DELETE ON receiver_activity_event
                    BEGIN SELECT RAISE(ABORT,'history generation rollback fixture'); END
                    """);
                StatsDatabaseMaintenanceRequest rollback = StatsDatabaseMaintenanceRequest.clearChannel(CONFIGURATION_ID);
                writer.submitMaintenance(rollback);
                assertThrows(ExecutionException.class, () -> rollback.result().get(5, TimeUnit.SECONDS));
                long partialGeneration = writer.historyGeneration();
                assertEquals(0, partialGeneration, "The failing first transaction must roll back its reset fence too");
                assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_channel_statistics_reset"));
                assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
                statement.execute("DROP TRIGGER prevent_history_delete");
                StatsDatabaseMaintenanceRequest deletion = StatsDatabaseMaintenanceRequest.clearChannel(CONFIGURATION_ID);
                writer.submitMaintenance(deletion);
                assertTrue(deletion.result().get(5, TimeUnit.SECONDS).rowsDeleted() > 0);
                assertTrue(writer.historyGeneration() > partialGeneration);
                assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            }
        }
        finally { writer.close(); }
    }

    @Test
    void committedRetentionStillInvalidatesHistoryWhenLaterMaintenanceStatusFails() throws Exception
    {
        for(String statusKey: List.of(ReceiverActivityRetention.CURSOR_STATUS_KEY, "last_maintenance_ms"))
        {
            Path database = createDatabase(mTemporaryFolder.resolve(statusKey + "-history-generation.sqlite"));
            insertConfiguredChannel(database);
            try(Connection connection = ActivityTestDatabase.openPair(database);
                Statement statement = connection.createStatement())
            {
                seedExpiredActivity(statement, 50, 1);
                statement.execute("""
                    CREATE TRIGGER fail_after_history_commit BEFORE INSERT ON statistics_status
                    WHEN NEW.key='%s'
                    BEGIN SELECT RAISE(ABORT,'postcommit history generation fixture'); END
                    """.formatted(statusKey));
            }
            ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true);
            writer.start();
            try
            {
                StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.forOperation(
                    ReceiverActivityMaintenance.Operation.MAINTAIN);
                writer.submitMaintenance(request);
                assertThrows(ExecutionException.class, () -> request.result().get(5, TimeUnit.SECONDS));
                assertTrue(writer.historyGeneration() > 0,
                    "a postcommit status failure must not hide already durable history deletions");
                try(Connection connection = ActivityTestDatabase.openPair(database))
                {
                    assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
                }
            }
            finally { writer.close(); }
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

        ReceiverActivityMaintenance.Result result = request.result().get(5, TimeUnit.SECONDS);
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, after));
        assertEquals(ReceiverActivityMaintenance.Operation.CLEAR_CHANNEL_STATS, result.operation());
        writer.close();
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(after, scalar(connection, "SELECT observed_at_ms FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM configuration_channel"));
        }
    }

    @Test
    void selectedChannelDeletionIsOrderedAndReportsMissingTargets() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("selected-clear-order.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 32, 1_250,
            TimeUnit.SECONDS.toMillis(10));
        writer.start();
        long before = 1_700_000_004_100L;
        long after = before + 1;
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, before));
        StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.delete(
            new StatsDatabaseMaintenanceRequest.Channel(CONFIGURATION_ID));
        writer.submitMaintenance(request);

        ReceiverActivityMaintenance.Result result = request.result().get(5, TimeUnit.SECONDS);
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, after));
        assertEquals(ReceiverActivityMaintenance.DeletionOutcome.DELETED, result.deletionOutcome());
        StatsDatabaseMaintenanceRequest missing = StatsDatabaseMaintenanceRequest.delete(
            new StatsDatabaseMaintenanceRequest.Channel("223e4567-e89b-42d3-a456-426614174000"));
        writer.submitMaintenance(missing);
        assertEquals(ReceiverActivityMaintenance.DeletionOutcome.NOT_FOUND,
            missing.result().get(5, TimeUnit.SECONDS).deletionOutcome());
        writer.close();

        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(after, scalar(connection, "SELECT observed_at_ms FROM receiver_activity_event"));
        }
    }

    @Test
    void resetIsOrderedBetweenEarlierAndLaterObservations() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("reset-order.sqlite"));
        insertConfiguredChannel(database);
        try(Connection connection = ActivityTestDatabase.openPair(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE database_metadata SET value='101' WHERE key='" +
                ReceiverActivitySchema.RADIO_SYSTEM_METRICS_STARTED_AT_KEY + "'");
            statement.executeUpdate("UPDATE database_metadata SET value='102' WHERE key='" +
                ReceiverActivitySchema.TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY + "'");
            statement.executeUpdate("UPDATE database_metadata SET value='103' WHERE key='" +
                ReceiverActivitySchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY + "'");
        }
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 32, 1_250,
            TimeUnit.SECONDS.toMillis(10));
        writer.start();
        long before = 1_700_000_005_000L;
        long after = before + 1;
        writer.enqueue(activity(ReceiverActivityRecords.Action.DENIAL, before));
        StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.forOperation(
            ReceiverActivityMaintenance.Operation.RESET_STATS);
        writer.submitMaintenance(request);

        ReceiverActivityMaintenance.Result result = request.result().get(5, TimeUnit.SECONDS);
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, after));
        assertEquals(ReceiverActivityMaintenance.Operation.RESET_STATS, result.operation());
        writer.close();
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(after, scalar(connection, "SELECT observed_at_ms FROM receiver_activity_event"));
            assertEquals(0, scalar(connection,
                "SELECT coalesce(sum(denial_count),0) FROM trunked_signaling_activity_bucket"));
            assertEquals(1, scalar(connection,
                "SELECT coalesce(sum(grant_count),0) FROM trunked_signaling_activity_bucket"));
            assertTrue(metadataTimestamp(connection,
                ReceiverActivitySchema.RADIO_SYSTEM_METRICS_STARTED_AT_KEY) > 103);
            assertTrue(metadataTimestamp(connection,
                ReceiverActivitySchema.TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY) > 103);
            assertTrue(metadataTimestamp(connection,
                ReceiverActivitySchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY) > 103);
        }
    }

    @Test
    void globalResetClearsAliasActivityCountersAndReseedsConfiguredAliases() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("reset-alias-activity.sqlite"));
        insertObservedAliasSummary(database);

        ReceiverActivityMaintenance.Result result = ReceiverActivityMaintenance.run(database, 30,
            ReceiverActivityMaintenance.Operation.RESET_STATS);

        assertEquals(ReceiverActivityMaintenance.Operation.RESET_STATS, result.operation());
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM alias"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM alias_activity_summary"));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM alias_activity_summary
                WHERE protocol_code=1 AND metrics_state='not_collected'
                  AND logical_call_count IS NULL
                  AND signaling_observation_count IS NULL
                  AND first_evidence_ms IS NULL AND last_evidence_ms IS NULL
                """));
        }
    }

    @Test
    void channelClearLeavesDurableAliasActivityCountersAlone() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("clear-channel-alias-activity.sqlite"));
        insertConfiguredChannel(database);
        insertObservedAliasSummary(database);

        ReceiverActivityMaintenance.Result result =
            ReceiverActivityMaintenance.clearChannelStats(database, CONFIGURATION_ID);

        assertEquals(ReceiverActivityMaintenance.Operation.CLEAR_CHANNEL_STATS, result.operation());
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(7, scalar(connection,
                "SELECT logical_call_count FROM alias_activity_summary WHERE metrics_state='observed'"));
            assertEquals(9, scalar(connection,
                "SELECT signaling_observation_count FROM alias_activity_summary WHERE metrics_state='observed'"));
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

        try(Connection blocker = ActivityTestDatabase.openPair(database);
            Statement statement = blocker.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value)
                SELECT id,'Projected target','TALKGROUP','APCO25',56138
                FROM alias_list WHERE family='P25' LIMIT 1
                """);
            statement.execute("BEGIN IMMEDIATE");
            long enqueueStarted = System.nanoTime();
            writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_006_000L));
            assertTrue(System.nanoTime() - enqueueStarted < TimeUnit.MILLISECONDS.toNanos(100),
                "database-backed Alias Activity projection must remain on the statistics writer");
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
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(1, scalar(connection, """
                SELECT grant_observation_count FROM alias_activity_summary
                WHERE alias_id=(SELECT id FROM alias WHERE name='Projected target')
                """));
        }
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

        try(Connection blocker = ActivityTestDatabase.openPair(database);
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
    void largeCleanupYieldsToIncomingObservationsAndContinuationKeepsTheCutoff() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("cleanup-fairness.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 1000, 1250, 10_000);
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, System.currentTimeMillis()));
        StatsDatabaseMaintenanceRequest barrier = StatsDatabaseMaintenanceRequest.forOperation(
            ReceiverActivityMaintenance.Operation.MAINTAIN);
        writer.submitMaintenance(barrier);
        barrier.result().get(5, TimeUnit.SECONDS);
        awaitWritten(writer, 1);
        StatsDatabaseMaintenanceRequest request = seedCleanupQuality(database, 100_002);
        writer.submitMaintenance(request);
        awaitCleanupPass(request);
        long cutoff = request.progress().cutoffMs();
        for(int index = 0; index < 100; index++)
            writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, cutoff + 1 + index));
        awaitWritten(writer, 101, 3);
        assertEquals(101, writer.getWrittenRecords());
        assertFalse(request.result().isDone(), "Incoming observations must commit before a large cleanup drains");
        request.cancel();
        assertEquals(ReceiverActivityMaintenance.DeletionOutcome.INTERRUPTED,
            request.result().get(3, TimeUnit.SECONDS).deletionOutcome());
        assertTrue(request.progress().rowsDeleted() > 0);
        assertTrue(request.progress().rowsDeleted() < request.progress().rowsTotal());
        StatsDatabaseMaintenanceRequest continuation = request.resume();
        writer.submitMaintenance(continuation);
        assertEquals(ReceiverActivityMaintenance.DeletionOutcome.DELETED,
            continuation.result().get(30, TimeUnit.SECONDS).deletionOutcome());
        assertEquals(cutoff, continuation.progress().cutoffMs());
        assertEquals(100_002, continuation.progress().rowsDeleted());
        assertEquals(0, continuation.progress().recordsDropped());
        assertTrue(continuation.progress().observationQueueHighWater() > 0);
        writer.close();
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(0, scalar(connection, "SELECT count(*) FROM trunked_control_channel_quality"));
            assertEquals(100, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE observed_at_ms>" + cutoff));
            assertEquals("ok", text(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void contendedCleanupPausesWithProgressAndFullObserverQueueNeverBlocksProducer() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("cleanup-lock.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 1, 1250, 100, 25, 5000);
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, System.currentTimeMillis()));
        awaitWritten(writer, 1);
        StatsDatabaseMaintenanceRequest request = seedCleanupQuality(database, 20_000);
        writer.submitMaintenance(request);
        awaitCleanupPass(request);
        try(Connection blocker = ActivityTestDatabase.openPair(database);
            Statement statement = blocker.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=1000");
            statement.execute("BEGIN IMMEDIATE");
            try
            {
                java.util.concurrent.ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> request.result().get(3, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof java.sql.SQLException);
                assertEquals(1, request.progress().databaseBusyRetries());
                assertTrue(request.progress().resumable());
                assertTrue(request.progress().rowsDeleted() > 0);
                long started = System.nanoTime();
                for(int index = 0; index < 2000; index++)
                    writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, System.currentTimeMillis() + index));
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2),
                    "Decode producers must never wait for a cleanup or a contended database");
                assertTrue(writer.getDroppedRecords() > 0);
            }
            finally { statement.execute("ROLLBACK"); }
        }
        StatsDatabaseMaintenanceRequest continuation = request.resume();
        writer.submitMaintenance(continuation);
        assertEquals(ReceiverActivityMaintenance.DeletionOutcome.DELETED,
            continuation.result().get(15, TimeUnit.SECONDS).deletionOutcome());
        assertEquals(request.progress().cutoffMs(), continuation.progress().cutoffMs());
        assertEquals(20_000, continuation.progress().rowsDeleted());
        assertEquals(1, continuation.progress().databaseBusyRetries());
        assertEquals(ReceiverActivityStatus.State.RUNNING, writer.getStatus().state());
        writer.close();
    }

    private static void awaitCleanupPass(StatsDatabaseMaintenanceRequest request) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while(request.progress().batchesCompleted() == 0 && !request.result().isDone() && System.nanoTime() < deadline)
            Thread.sleep(5);
        assertTrue(request.progress().batchesCompleted() > 0);
    }

    private static StatsDatabaseMaintenanceRequest seedCleanupQuality(Path database, int rows) throws Exception
    {
        try(Connection connection = ActivityTestDatabase.openPair(database);
            Statement statement = connection.createStatement())
        {
            long oldest = System.currentTimeMillis() / 10_000 * 10_000 - rows * 10_000L;
            statement.executeUpdate("WITH RECURSIVE n(value) AS (VALUES(0) UNION ALL SELECT value+1 FROM n WHERE value<" + (rows-1) + ") " +
                "INSERT INTO trunked_control_channel_quality(channel_id,frequency_hz,bucket_start_ms,observed_at_ms) " +
                "SELECT 1,854187500," + oldest + "+value*10000," + oldest + "+value*10000+100 FROM n");
            String site = ReceiverActivityDeletion.savedChannel(connection, CONFIGURATION_ID).siteKey();
            return StatsDatabaseMaintenanceRequest.delete(new StatsDatabaseMaintenanceRequest.ScopedData("radio_system",
                "p25:bee00:3a9", CONFIGURATION_ID, site, "control_quality", null, List.of("buckets")));
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

    private static ReceiverActivityRecords.ActivityEvent activity(ReceiverActivityRecords.Action action,
                                                                   long timestamp, Long frequencyHertz)
    {
        return activity(CONFIGURATION_ID, action, timestamp, frequencyHertz);
    }

    private static ReceiverActivityRecords.ActivityEvent activity(String configurationId,
                                                                   ReceiverActivityRecords.Action action,
                                                                   long timestamp)
    {
        return activity(configurationId, action, timestamp, 854_187_500L);
    }

    private static ReceiverActivityRecords.ActivityEvent activity(String configurationId,
                                                                   ReceiverActivityRecords.Action action,
                                                                   long timestamp, Long frequencyHertz)
    {
        return new ReceiverActivityRecords.ActivityEvent(timestamp, configurationId,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "APCO25", action, "CALL_GROUP", "1811524",
            "56138", "TALKGROUP", List.of(), frequencyHertz, "00-0509", 1, false, null, null,
            0xBEE00, 0x3A9, 0x293, 2, 1, null, action == ReceiverActivityRecords.Action.CALL, null, null,
            TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.ORDINARY,
            ReceiverActivityRecords.P25Identity.ORDINARY, List.of(), null);
    }

    private static ReceiverActivityRecords.ActivityEvent activityWithEncryptionAlgorithm(long timestamp,
                                                                                           int algorithm)
    {
        return new ReceiverActivityRecords.ActivityEvent(timestamp, CONFIGURATION_ID,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "APCO25", ReceiverActivityRecords.Action.GRANT,
            "CALL_GROUP", "1811524", "56138", "TALKGROUP", List.of(), 854_187_500L, "00-0509", 1,
            true, algorithm, 1, 0xBEE00, 0x3A9, 0x293, 2, 1, null, false, null, null,
            TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.ORDINARY,
            ReceiverActivityRecords.P25Identity.ORDINARY, List.of(), null);
    }

    private static void awaitWritten(ReceiverActivityWriter writer, long count) throws Exception
    {
        awaitWritten(writer, count, 5);
    }

    private static void awaitWritten(ReceiverActivityWriter writer, long count, long timeoutSeconds) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
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
        try(Connection connection = ActivityTestDatabase.openPair(database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    alias_list_id, radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json
                ) VALUES (
                    '%s', 'TRUNKED', 0, 'Capture system', 'Capture site', 'Control',
                    (SELECT id FROM alias_list WHERE family='P25' LIMIT 1),
                    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 0, 'P25_PHASE1', 854187500, '{}'
                )
                """.formatted(CONFIGURATION_ID));
        }
    }

    private static void insertObservedAliasSummary(Path database) throws Exception
    {
        try(Connection connection = ActivityTestDatabase.openPair(database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO alias(alias_list_id, name, matcher_type, protocol, value)
                SELECT id, 'Observed Alias', 'TALKGROUP', 'APCO25', 56138
                FROM alias_list WHERE family='P25' LIMIT 1
                """);
            statement.executeUpdate("""
                INSERT INTO alias_activity_summary(
                    alias_id, alias_list_id, protocol_code, metrics_state,
                    logical_call_count, signaling_observation_count,
                    first_evidence_ms, last_evidence_ms, updated_at_ms, activity_identity
                )
                SELECT id, alias_list_id, 1, 'observed', 7, 9, 1000, 2000, 2000, activity_identity
                FROM alias WHERE name='Observed Alias'
                """);
        }
    }

    private static void installWriteAudit(Path database) throws Exception
    {
        try(Connection connection = ActivityTestDatabase.openPair(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE test_update_audit(table_name TEXT NOT NULL)");
            for(String table: List.of("receiver_channel", "radio_system", "radio_system_identity_summary"))
            {
                statement.executeUpdate("""
                    CREATE TRIGGER test_%s_update AFTER UPDATE ON %s
                    BEGIN
                        INSERT INTO test_update_audit(table_name) VALUES ('%s');
                    END
                    """.formatted(table, table, table));
            }
        }
    }

    private static Path createDatabase(Path database) throws Exception
    {
        return io.github.dsheirer.database.SdrTrunkTestDatabase.create(database);
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static long metadataTimestamp(Connection connection, String key) throws Exception
    {
        try(java.sql.PreparedStatement statement = connection.prepareStatement(
            "SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
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
