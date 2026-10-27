/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ScopedData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.ProgressHandler;

/**
 * SQLite maintenance actions for the sdrtrunk-vce stats database.
 */
public final class ReceiverActivityMaintenance
{
    private static final Logger LOG = LoggerFactory.getLogger(ReceiverActivityMaintenance.class);
    private static final long FREE_SPACE_RESERVE = 5L * 1024 * 1024 * 1024;
    private static final long CHECK_MAXIMUM_WAL_GROWTH = 1024L * 1024 * 1024;
    private static final long RESET_MAXIMUM_PENDING_WAL = 256L * 1024 * 1024;
    private ReceiverActivityMaintenance()
    {
    }

    public enum Operation
    {
        MAINTAIN,
        SHRINK,
        CHECK,
        RESET_STATS,
        CLEAR_CHANNEL_STATS,
        DELETE_RETAINED_STATS
    }

    public enum DeletionOutcome
    {
        NOT_APPLICABLE,
        DELETED,
        NOT_FOUND,
        STALE_SITE,
        TOO_LARGE,
        INTERRUPTED
    }

    /** Read-only preview. Counts describe directly matched rows; effects describe FK cascades. */
    public record Preview(DeletionOutcome outcome, Map<String,Integer> countsByPart, int rowsTotal,
                          List<String> effects)
    {
    }

    public static Preview preview(Connection connection, ScopedData target) throws SQLException
    {
        ReceiverActivityScopedDeletion.Preview preview = ReceiverActivityScopedDeletion.preview(connection, target);
        return new Preview(preview.outcome(), preview.countsByPart(), preview.rowsTotal(), preview.effects());
    }

    public record Result(Operation operation, int rowsDeleted, String checkResult, long databaseBytesBefore,
                         long databaseBytesAfter, long walBytesBefore, long walBytesAfter,
                         DeletionOutcome deletionOutcome, List<String> warnings)
    {
        public Result
        {
            warnings = List.copyOf(warnings);
        }

        public Result(Operation operation, int rowsDeleted, String checkResult, long databaseBytesBefore,
                      long databaseBytesAfter, long walBytesBefore, long walBytesAfter,
                      DeletionOutcome deletionOutcome)
        {
            this(operation, rowsDeleted, checkResult, databaseBytesBefore, databaseBytesAfter, walBytesBefore,
                walBytesAfter, deletionOutcome, List.of());
        }
        public Result(Operation operation, int rowsDeleted, String checkResult, long databaseBytesBefore,
                      long databaseBytesAfter, long walBytesBefore, long walBytesAfter)
        {
            this(operation, rowsDeleted, checkResult, databaseBytesBefore, databaseBytesAfter, walBytesBefore,
                walBytesAfter, DeletionOutcome.NOT_APPLICABLE);
        }

        public boolean checkOk()
        {
            return checkResult == null || "ok".equalsIgnoreCase(checkResult);
        }

        public String summary()
        {
            StringBuilder sb = new StringBuilder();

            switch(operation)
            {
                case MAINTAIN -> sb.append("Maintenance complete");
                case SHRINK -> sb.append("Shrink complete");
                case CHECK -> sb.append(deletionOutcome == DeletionOutcome.INTERRUPTED ?
                    "Database check stopped without a final result" : checkOk() ?
                    "Database check passed" : "Database check failed");
                case RESET_STATS -> sb.append(deletionOutcome == DeletionOutcome.INTERRUPTED ?
                    "Reset stopped; completed batches remain removed" : "Lifetime stats reset");
                case CLEAR_CHANNEL_STATS -> sb.append(deletionOutcome == DeletionOutcome.INTERRUPTED ?
                    "Channel cleanup stopped; completed batches remain removed" : "Channel statistics cleared");
                case DELETE_RETAINED_STATS -> sb.append(switch(deletionOutcome)
                {
                    case NOT_FOUND -> "Target is no longer available";
                    case STALE_SITE -> "Selected site changed; choose it again";
                    case TOO_LARGE -> "Selection is too large for live removal; narrow the scope";
                    case INTERRUPTED -> "Cleanup stopped; completed batches remain deleted";
                    default -> "Retained statistics removed";
                });
            }

            if(operation == Operation.DELETE_RETAINED_STATS &&
                (deletionOutcome == DeletionOutcome.DELETED || deletionOutcome == DeletionOutcome.INTERRUPTED))
            {
                sb.append(". Removed ").append(rowsDeleted).append(" directly matched row(s)");
            }
            else if(operation != Operation.CHECK && deletionOutcome != DeletionOutcome.NOT_FOUND &&
                deletionOutcome != DeletionOutcome.STALE_SITE && deletionOutcome != DeletionOutcome.TOO_LARGE)
            {
                sb.append(operation == Operation.RESET_STATS ? ". Reset " :
                    operation == Operation.CLEAR_CHANNEL_STATS ? ". Cleared " : ". Deleted ").append(rowsDeleted).append(
                    operation == Operation.RESET_STATS || operation == Operation.CLEAR_CHANNEL_STATS ||
                        operation == Operation.DELETE_RETAINED_STATS ?
                    " stats row(s)" : " expired row(s)");
            }

            sb.append(". DB ").append(size(databaseBytesBefore)).append(" -> ").append(size(databaseBytesAfter));
            sb.append(", WAL ").append(size(walBytesBefore)).append(" -> ").append(size(walBytesAfter));

            if(checkResult != null)
            {
                sb.append(". Check: ").append(checkResult);
            }

            warnings.forEach(warning -> sb.append(". ").append(warning));

            return sb.toString();
        }

        private static String size(long bytes)
        {
            return FileUtils.byteCountToDisplaySize(bytes);
        }
    }

    public static Result run(Path databasePath, int retentionDays, Operation operation) throws IOException, SQLException
    {
        if(operation == Operation.CHECK)
        {
            return check(databasePath, () -> false);
        }
        try(Connection connection = SdrTrunkDatabase.openHistory(databasePath))
        {
            return run(connection, databasePath, retentionDays, operation);
        }
    }

    /**
     * Runs a maintenance operation on the caller-owned connection. Runtime callers use this overload so that all
     * mutations remain serialized on the single statistics database writer.
     */
    static Result run(Connection connection, Path databasePath, int retentionDays, Operation operation)
        throws IOException, SQLException
    {
        return run(connection, databasePath, retentionDays, operation, null);
    }

    static Result run(Connection connection, Path databasePath, int retentionDays, Operation operation,
                      AtomicLong historyGeneration) throws IOException, SQLException
    {
        long databaseBytesBefore = size(activityPath(databasePath));
        long walBytesBefore = size(walPath(activityPath(databasePath)));
        int rowsDeleted = 0;
        String checkResult = null;
        List<String> warnings = new ArrayList<>();

        switch(operation)
        {
            case MAINTAIN -> rowsDeleted = runLightMaintenancePass(connection, retentionDays, historyGeneration).deletedRows();
            case SHRINK ->
            {
                requireShrinkCapacity(connection, databasePath);
                rowsDeleted = runLightMaintenancePass(connection, retentionDays, historyGeneration).deletedRows();
                vacuum(connection);
                checkpointAfterCommit(connection, warnings);
                statusAfterCommit(connection, "last_shrink_ms", warnings);
            }
            case CHECK -> { return recordCheck(connection, check(databasePath, () -> false)); }
            case RESET_STATS ->
            {
                return runBatchesToCompletion(connection, databasePath,
                    StatsDatabaseMaintenanceRequest.forOperation(Operation.RESET_STATS), historyGeneration);
            }
            case CLEAR_CHANNEL_STATS -> throw new IllegalArgumentException(
                "CLEAR_CHANNEL_STATS requires a channel configuration ID");
            case DELETE_RETAINED_STATS -> throw new IllegalArgumentException(
                "DELETE_RETAINED_STATS requires a deletion target");
        }

        return new Result(operation, rowsDeleted, checkResult, databaseBytesBefore, size(activityPath(databasePath)), walBytesBefore,
            size(walPath(activityPath(databasePath))), DeletionOutcome.NOT_APPLICABLE, warnings);
    }

    /**
     * Clears statistics and history owned by one saved channel without changing its configuration or system-wide
     * summaries that may be shared by other channels.
     */
    public static Result clearChannelStats(Path databasePath, String configurationId) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.openHistory(databasePath))
        {
            return clearChannelStats(connection, databasePath, configurationId);
        }
    }

    /**
     * Clears one saved channel's statistics on the caller-owned writer connection.
     */
    static Result clearChannelStats(Connection connection, Path databasePath, String configurationId)
        throws IOException, SQLException
    {
        return clearChannelStats(connection, databasePath, configurationId, null);
    }

    static Result clearChannelStats(Connection connection, Path databasePath, String configurationId,
                                    AtomicLong historyGeneration) throws IOException, SQLException
    {
        if(configurationId == null || configurationId.isBlank())
        {
            throw new IllegalArgumentException("Channel configuration ID is required");
        }

        return runBatchesToCompletion(connection, databasePath,
            StatsDatabaseMaintenanceRequest.clearChannel(configurationId), historyGeneration);
    }

    /** Offline callers use the same bounded deletion engine; the live service yields between these passes. */
    private static Result runBatchesToCompletion(Connection connection, Path databasePath,
        StatsDatabaseMaintenanceRequest request, AtomicLong historyGeneration) throws IOException, SQLException
    {
        Result result;
        do { result = deleteRetainedStatsPass(connection, databasePath, request, 0, historyGeneration); }
        while(result == null);
        return result;
    }

    /** Deletes one selected target on the single writer connection after re-resolving its ownership. */
    static Result deleteRetainedStats(Connection connection, Path databasePath,
                                      StatsDatabaseMaintenanceRequest.DeletionTarget target)
        throws IOException, SQLException
    {
        return deleteRetainedStats(connection, databasePath, target, null);
    }

    static Result deleteRetainedStats(Connection connection, Path databasePath,
                                      StatsDatabaseMaintenanceRequest.DeletionTarget target,
                                      AtomicLong historyGeneration) throws IOException, SQLException
    {
        if(target == null)
        {
            throw new IllegalArgumentException("Deletion target is required");
        }

        long databaseBytesBefore = size(activityPath(databasePath));
        long walBytesBefore = size(walPath(activityPath(databasePath)));
        ReceiverActivityDeletion.Result deleted = ReceiverActivityDeletion.legacySelectionTooLarge(connection, target) ?
            ReceiverActivityDeletion.Result.tooLarge() : inTransaction(connection,
                () -> ReceiverActivityDeletion.delete(connection, target), historyGeneration,
                ReceiverActivityDeletion.Result::rowsDeleted);
        return new Result(Operation.DELETE_RETAINED_STATS, deleted.rowsDeleted(), null, databaseBytesBefore,
            size(activityPath(databasePath)), walBytesBefore, size(walPath(activityPath(databasePath))),
            deleted.outcome());
    }

    /** Executes exactly one bounded scoped-deletion transaction; null means more passes remain. */
    static Result deleteRetainedStatsPass(Connection connection, Path databasePath,
                                          StatsDatabaseMaintenanceRequest request, long droppedRecords)
        throws IOException, SQLException
    {
        return deleteRetainedStatsPass(connection, databasePath, request, droppedRecords, null);
    }

    static Result deleteRetainedStatsPass(Connection connection, Path databasePath,
                                          StatsDatabaseMaintenanceRequest request, long droppedRecords,
                                          AtomicLong historyGeneration) throws IOException, SQLException
    {
        boolean reset = request.operation() == Operation.RESET_STATS;
        String channel = request.operation() == Operation.CLEAR_CHANNEL_STATS ? request.configurationId() :
            request.deletionTarget() instanceof StatsDatabaseMaintenanceRequest.Channel target ?
                target.configurationId() : null;
        ScopedData scoped = request.deletionTarget() instanceof ScopedData target ? target : null;
        if(!reset && channel == null && scoped == null)
            throw new IllegalArgumentException("Batched cleanup requires a retained-data target or reset");
        long before = size(activityPath(databasePath));
        long walBefore = size(walPath(activityPath(databasePath)));
        if(request.cancelled())
        {
            request.setResumable(!reset);
            return deletionResult(request, databasePath, before, walBefore, DeletionOutcome.INTERRUPTED);
        }
        if(reset && request.resetResourceCheckDue())
            request.resetWaitingForReaders(!resetResourcesReady(connection, databasePath));
        if(reset && request.resetWaitingForReaders()) return null;
        ReceiverActivityScopedDeletion.Batch batch = request.deletionBatch();
        if(batch == null)
        {
            long preparationStart = System.nanoTime();
            if(request.progress().cutoffMs() == 0)
                request.initializeProgress(0, System.currentTimeMillis(), droppedRecords);
            try
            {
                batch = reset ? ReceiverActivityScopedDeletion.Batch.startAll(connection, request.progress().cutoffMs()) :
                    channel != null ? ReceiverActivityScopedDeletion.Batch.startChannel(connection, channel,
                        request.progress().cutoffMs()) : ReceiverActivityScopedDeletion.Batch.start(connection,
                        scoped, request.progress().cutoffMs());
                request.deletionBatch(batch);
                //Maintenance factories deliberately avoid a full-history count on the live writer.
                request.matchedRows(reset || channel != null ? 0 : batch.rowsTotal());
            }
            finally
            {
                request.preparationDuration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - preparationStart));
            }
        }
        if(!batch.done())
        {
            ReceiverActivityScopedDeletion.Batch.Position position = batch.position();
            long started = System.nanoTime();
            try
            {
                ReceiverActivityScopedDeletion.Batch active = batch;
                int changed = inTransaction(connection, () -> active.runPass(connection), historyGeneration,
                    Integer::longValue);
                request.committedBatch(changed, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            catch(SQLException | RuntimeException failure)
            {
                batch.restore(position);
                request.preparationDuration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                request.setResumable(!reset);
                throw failure;
            }
        }
        if(batch.done())
        {
            if(reset || channel != null) request.matchedRows(request.progress().rowsDeleted());
            request.setResumable(false);
            if(reset)
            {
                inTransaction(connection, () -> { completeReset(connection); return 0; }, null, Integer::longValue);
            }
            return deletionResult(request, databasePath, before, walBefore, batch.outcome());
        }
        return null;
    }

    static Result interruptRetainedStats(Path databasePath, StatsDatabaseMaintenanceRequest request)
        throws IOException
    {
        request.setResumable(request.operation() != Operation.RESET_STATS);
        return deletionResult(request, databasePath, size(activityPath(databasePath)), size(walPath(activityPath(databasePath))),
            DeletionOutcome.INTERRUPTED);
    }

    private static Result deletionResult(StatsDatabaseMaintenanceRequest request, Path databasePath,
                                          long before, long walBefore, DeletionOutcome outcome) throws IOException
    {
        return new Result(request.operation(), Math.toIntExact(request.progress().rowsDeleted()), null,
            before, size(activityPath(databasePath)), walBefore, size(walPath(activityPath(databasePath))), outcome);
    }

    static int runLightMaintenance(Connection connection, int retentionDays) throws SQLException
    {
        return runLightMaintenancePass(connection, retentionDays).deletedRows();
    }

    static RetentionResult runLightMaintenancePass(Connection connection, int retentionDays) throws SQLException
    {
        return runLightMaintenancePass(connection, retentionDays, null);
    }

    private static RetentionResult runLightMaintenancePass(Connection connection, int retentionDays,
                                                           AtomicLong historyGeneration) throws SQLException
    {
        RetentionResult retention = cleanupRetentionPass(connection, retentionDays, historyGeneration);
        int deleted = retention.deletedRows();
        updateStatus(connection, "last_maintenance_ms");
        ReceiverActivitySchema.updateStatus(connection, "last_maintenance_deleted_rows", Integer.toString(deleted));
        return retention;
    }

    static RetentionResult cleanupRetentionPass(Connection connection, int retentionDays) throws SQLException
    {
        return cleanupRetentionPass(connection, retentionDays, null);
    }

    static RetentionResult cleanupRetentionPass(Connection connection, int retentionDays,
                                                 AtomicLong historyGeneration) throws SQLException
    {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Math.max(1, retentionDays));
        ReceiverActivityRetention.Pass pass = ReceiverActivityRetention.runPass(connection, cutoff, historyGeneration);
        int deleted = pass.deletedRows();

        ReceiverActivitySchema.updateStatus(connection, "retention_days", Integer.toString(Math.max(1, retentionDays)));
        ReceiverActivitySchema.updateStatus(connection, "last_retention_cleanup_ms",
            Long.toString(System.currentTimeMillis()));
        ReceiverActivitySchema.updateStatus(connection, "last_retention_deleted_rows", Integer.toString(deleted));
        return new RetentionResult(deleted, pass.moreWorkLikely());
    }

    record RetentionResult(int deletedRows, boolean moreWorkLikely)
    {
    }

    private static <T> T inTransaction(Connection connection, SqlOperation<T> operation, AtomicLong historyGeneration,
                                        ToLongFunction<T> deletedRows) throws SQLException
    {
        boolean previousAutoCommit = connection.getAutoCommit();

        if(!previousAutoCommit)
        {
            throw new SQLException("Statistics maintenance requires an idle database writer connection");
        }

        connection.setAutoCommit(false);

        try
        {
            T result = operation.run();
            connection.commit();
            if(historyGeneration != null && deletedRows.applyAsLong(result) > 0)
            {
                historyGeneration.incrementAndGet();
            }
            return result;
        }
        catch(SQLException | RuntimeException e)
        {
            try
            {
                connection.rollback();
            }
            catch(SQLException rollbackException)
            {
                e.addSuppressed(rollbackException);
            }

            throw e;
        }
        finally
        {
            connection.setAutoCommit(true);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T>
    {
        T run() throws SQLException;
    }

    private static void checkpointAfterCommit(Connection connection, List<String> warnings)
    {
        try
        {
            checkpoint(connection);
        }
        catch(SQLException exception)
        {
            warnings.add("Data changes completed; checkpoint will be retried later");
            LOG.debug("Statistics changes committed; optional checkpoint was deferred", exception);
        }
    }

    private static void checkpoint(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA main.wal_checkpoint(PASSIVE)");
        }
    }

    private static void vacuum(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("VACUUM");
        }
    }

    private static String quickCheck(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA main.quick_check"))
        {
            List<String> findings = new ArrayList<>();
            while(resultSet.next()) findings.add(resultSet.getString(1));
            return findings.isEmpty() ? "no result" : String.join("; ", findings);
        }
    }

    /** A full SQLite check on a WAL-aware read connection; never owns the observation writer. */
    public static Result check(Path databasePath, BooleanSupplier cancelled) throws IOException, SQLException
    {
        Path activity = activityPath(databasePath);
        long before = size(activity);
        long walBefore = size(walPath(activity));
        String[] stopped = {null};
        long[] nextSpaceCheck = {0};
        try(Connection connection = SdrTrunkDatabase.openHistoryRead(databasePath))
        {
            //Full checks revisit B-tree and overflow pages. The default ~2MiB cache amplified reads on a31GiB
            //history file; cap this diagnostic reader at64MiB without changing the observation writer's cache.
            try(Statement statement = connection.createStatement()) { statement.execute("PRAGMA main.cache_size=-65536"); }
            ProgressHandler.setHandler(connection, 20_000, new ProgressHandler()
            {
                @Override protected int progress()
                {
                    if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                        stopped[0] = "Check cancelled; run it again when ready";
                    else if(System.nanoTime() >= nextSpaceCheck[0])
                    {
                        nextSpaceCheck[0] = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                        try
                        {
                            if(Files.getFileStore(activity).getUsableSpace() < FREE_SPACE_RESERVE)
                                stopped[0] = "Check stopped to preserve free disk space; free space and try again";
                            else if(size(walPath(activity)) - walBefore > CHECK_MAXIMUM_WAL_GROWTH)
                                stopped[0] = "Check stopped because incoming activity needs a checkpoint; try again during a quieter period";
                        }
                        catch(IOException exception)
                        {
                            stopped[0] = "Check stopped because available disk space could not be verified";
                        }
                    }
                    return stopped[0] == null ? 0 : 1;
                }
            });
            String result;
            try
            {
                if(cancelled.getAsBoolean()) stopped[0] = "Check cancelled; run it again when ready";
                result = stopped[0] == null ? quickCheck(connection) : null;
            }
            catch(SQLException exception)
            {
                if(stopped[0] == null) throw exception;
                result = null;
            }
            finally
            {
                ProgressHandler.clearHandler(connection);
            }
            return new Result(Operation.CHECK, 0, stopped[0] != null ? stopped[0] : result, before,
                size(activity), walBefore, size(walPath(activity)), stopped[0] != null ?
                DeletionOutcome.INTERRUPTED : DeletionOutcome.NOT_APPLICABLE);
        }
    }

    /** Records a completed read-side check in one short writer action. A failed status write does not undo its result. */
    static Result recordCheck(Connection connection, Result checked)
    {
        if(checked.operation() != Operation.CHECK) throw new IllegalArgumentException("Database check result required");
        if(checked.deletionOutcome() == DeletionOutcome.INTERRUPTED) return checked;
        List<String> warnings = new ArrayList<>(checked.warnings());
        try
        {
            ReceiverActivitySchema.updateStatus(connection, "last_integrity_check_ms",
                Long.toString(System.currentTimeMillis()));
            ReceiverActivitySchema.updateStatus(connection, "last_integrity_check_result", checked.checkResult());
        }
        catch(SQLException exception)
        {
            warnings.add("The check finished; its saved status could not be updated");
            LOG.debug("Completed statistics check status could not be saved", exception);
        }
        return new Result(checked.operation(), checked.rowsDeleted(), checked.checkResult(),
            checked.databaseBytesBefore(), checked.databaseBytesAfter(), checked.walBytesBefore(),
            checked.walBytesAfter(), checked.deletionOutcome(), warnings);
    }

    public record ShrinkCapacity(long requiredBytes, long availableBytes)
    {
        public boolean sufficient() { return availableBytes >= requiredBytes; }
    }

    static ShrinkCapacity shrinkCapacity(long databasePages, long pageSize, long availableBytes)
    {
        // VACUUM can need both a rebuilt database and its rollback/WAL copy, in addition to normal receiver headroom.
        return new ShrinkCapacity(Math.addExact(Math.multiplyExact(Math.multiplyExact(databasePages, pageSize), 2),
            FREE_SPACE_RESERVE), availableBytes);
    }

    private static void requireShrinkCapacity(Connection connection, Path configuration) throws SQLException, IOException
    {
        long pages = pragma(connection, "page_count");
        Path activity = activityPath(configuration);
        if(activity == null || !Files.isRegularFile(activity))
            throw new IOException("Shrink cannot verify database storage; restart VCE and try again");
        ShrinkCapacity capacity = shrinkCapacity(pages, pragma(connection, "page_size"),
            Files.getFileStore(activity).getUsableSpace());
        if(!capacity.sufficient())
            throw new IOException("Shrink needs " + FileUtils.byteCountToDisplaySize(capacity.requiredBytes()) +
                " free, including temporary workspace and receiver headroom; " +
                FileUtils.byteCountToDisplaySize(capacity.availableBytes()) + " is available");
    }

    private static boolean resetResourcesReady(Connection connection, Path configuration) throws SQLException, IOException
    {
        if(Files.getFileStore(activityPath(configuration)).getUsableSpace() < FREE_SPACE_RESERVE)
            throw new IOException("Reset stopped to preserve free disk space. Completed batches remain removed. " +
                "Free disk space and start a new reset");
        //A separate long-lived reader may pin old history even though statistics admission is paused.
        //Yield before that reader can turn the bounded reset into a database-sized retained WAL.
        long pageSize = pragma(connection, "page_size");
        try(Statement statement = connection.createStatement();
            ResultSet checkpoint = statement.executeQuery("PRAGMA main.wal_checkpoint(PASSIVE)"))
        {
            if(checkpoint.next())
            {
                long pendingPages = Math.max(0L, checkpoint.getLong(2) - checkpoint.getLong(3));
                return pendingPages * pageSize <= RESET_MAXIMUM_PENDING_WAL;
            }
        }
        return true;
    }

    private static long pragma(Connection connection, String name) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA main." + name))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static void statusAfterCommit(Connection connection, String key, List<String> warnings)
    {
        try { updateStatus(connection, key); }
        catch(SQLException exception)
        {
            warnings.add("Data changes completed; their saved status could not be updated");
            LOG.debug("Committed statistics maintenance status could not be saved", exception);
        }
    }

    private static void completeReset(Connection connection) throws SQLException
    {
        updateStatus(connection, "last_stats_reset_ms");
        for(String key: List.of(ReceiverActivitySchema.RADIO_SYSTEM_METRICS_STARTED_AT_KEY,
            ReceiverActivitySchema.TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY,
            ReceiverActivitySchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY))
            io.github.dsheirer.database.SdrTrunkDatabaseStartup.setMetadata(connection, key,
                Long.toString(System.currentTimeMillis()));
    }

    private static void updateStatus(Connection connection, String key) throws SQLException
    {
        ReceiverActivitySchema.updateStatus(connection, key, Long.toString(System.currentTimeMillis()));
    }

    private static long size(Path path) throws IOException
    {
        return path != null && Files.isRegularFile(path) ? Files.size(path) : 0;
    }

    private static Path activityPath(Path configurationPath)
    {
        return configurationPath == null ? null : SdrTrunkDatabase.historyPath(configurationPath);
    }

    private static Path walPath(Path databasePath)
    {
        return databasePath == null ? null : Path.of(databasePath.toString() + "-wal");
    }
}
