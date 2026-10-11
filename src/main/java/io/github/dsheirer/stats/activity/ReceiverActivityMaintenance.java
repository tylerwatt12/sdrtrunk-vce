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
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ScopedData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;

/**
 * SQLite maintenance actions for the sdrtrunk-vce stats database.
 */
public final class ReceiverActivityMaintenance
{
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
                         DeletionOutcome deletionOutcome)
    {
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
                case CHECK -> sb.append(checkOk() ? "Database check passed" : "Database check failed");
                case RESET_STATS -> sb.append("Lifetime stats reset");
                case CLEAR_CHANNEL_STATS -> sb.append("Channel statistics cleared");
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
                sb.append(". Deleted ").append(rowsDeleted).append(
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

            return sb.toString();
        }

        private static String size(long bytes)
        {
            return FileUtils.byteCountToDisplaySize(bytes);
        }
    }

    public static Result run(Path databasePath, int retentionDays, Operation operation) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(databasePath))
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
        long databaseBytesBefore = size(databasePath);
        long walBytesBefore = size(walPath(databasePath));
        int rowsDeleted = 0;
        String checkResult = null;

        switch(operation)
        {
            case MAINTAIN -> rowsDeleted = runLightMaintenance(connection, retentionDays);
            case SHRINK ->
            {
                rowsDeleted = runLightMaintenance(connection, retentionDays);
                vacuum(connection);
                checkpoint(connection);
                optimize(connection);
                updateStatus(connection, "last_shrink_ms");
            }
            case CHECK ->
            {
                checkResult = quickCheck(connection);
                ReceiverActivitySchema.updateStatus(connection, "last_integrity_check_ms",
                    Long.toString(System.currentTimeMillis()));
                ReceiverActivitySchema.updateStatus(connection, "last_integrity_check_result", checkResult);
            }
            case RESET_STATS ->
            {
                rowsDeleted = resetStats(connection);
                checkpoint(connection);
                optimize(connection);
            }
            case CLEAR_CHANNEL_STATS -> throw new IllegalArgumentException(
                "CLEAR_CHANNEL_STATS requires a channel configuration ID");
            case DELETE_RETAINED_STATS -> throw new IllegalArgumentException(
                "DELETE_RETAINED_STATS requires a deletion target");
        }

        return new Result(operation, rowsDeleted, checkResult, databaseBytesBefore, size(databasePath), walBytesBefore,
            size(walPath(databasePath)));
    }

    /**
     * Clears statistics and history owned by one saved channel without changing its configuration or system-wide
     * summaries that may be shared by other channels.
     */
    public static Result clearChannelStats(Path databasePath, String configurationId) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(databasePath))
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
        if(configurationId == null || configurationId.isBlank())
        {
            throw new IllegalArgumentException("Channel configuration ID is required");
        }

        long databaseBytesBefore = size(databasePath);
        long walBytesBefore = size(walPath(databasePath));
        int rowsDeleted = clearChannelStats(connection, configurationId);
        checkpoint(connection);
        optimize(connection);
        return new Result(Operation.CLEAR_CHANNEL_STATS, rowsDeleted, null, databaseBytesBefore, size(databasePath),
            walBytesBefore, size(walPath(databasePath)));
    }

    /** Deletes one selected target on the single writer connection after re-resolving its ownership. */
    static Result deleteRetainedStats(Connection connection, Path databasePath,
                                      StatsDatabaseMaintenanceRequest.DeletionTarget target)
        throws IOException, SQLException
    {
        if(target == null)
        {
            throw new IllegalArgumentException("Deletion target is required");
        }

        long databaseBytesBefore = size(databasePath);
        long walBytesBefore = size(walPath(databasePath));
        ReceiverActivityDeletion.Result deleted = ReceiverActivityDeletion.legacySelectionTooLarge(connection, target) ?
            ReceiverActivityDeletion.Result.tooLarge() : inTransaction(connection,
                () -> ReceiverActivityDeletion.delete(connection, target));
        return new Result(Operation.DELETE_RETAINED_STATS, deleted.rowsDeleted(), null, databaseBytesBefore,
            size(databasePath), walBytesBefore, size(walPath(databasePath)),
            deleted.outcome());
    }

    /** Executes exactly one bounded scoped-deletion transaction; null means more passes remain. */
    static Result deleteRetainedStatsPass(Connection connection, Path databasePath,
                                          StatsDatabaseMaintenanceRequest request, long droppedRecords)
        throws IOException, SQLException
    {
        if(!(request.deletionTarget() instanceof ScopedData target))
            throw new IllegalArgumentException("Batched cleanup requires a scoped retained-data target");
        long before = size(databasePath);
        long walBefore = size(walPath(databasePath));
        if(request.cancelled())
        {
            request.setResumable(true);
            return deletionResult(request, databasePath, before, walBefore, DeletionOutcome.INTERRUPTED);
        }
        ReceiverActivityScopedDeletion.Batch batch = request.deletionBatch();
        if(batch == null)
        {
            long preparationStart = System.nanoTime();
            if(request.progress().cutoffMs() == 0)
                request.initializeProgress(0, System.currentTimeMillis(), droppedRecords);
            try
            {
                batch = ReceiverActivityScopedDeletion.Batch.start(connection, target, request.progress().cutoffMs());
                request.deletionBatch(batch);
                request.matchedRows(batch.rowsTotal());
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
                int changed = inTransaction(connection, () -> active.runPass(connection));
                request.committedBatch(changed, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            catch(SQLException | RuntimeException failure)
            {
                batch.restore(position);
                request.preparationDuration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                request.setResumable(true);
                throw failure;
            }
        }
        if(batch.done())
        {
            request.setResumable(false);
            return deletionResult(request, databasePath, before, walBefore, batch.outcome());
        }
        return null;
    }

    static Result interruptRetainedStats(Path databasePath, StatsDatabaseMaintenanceRequest request)
        throws IOException
    {
        request.setResumable(true);
        return deletionResult(request, databasePath, size(databasePath), size(walPath(databasePath)),
            DeletionOutcome.INTERRUPTED);
    }

    private static Result deletionResult(StatsDatabaseMaintenanceRequest request, Path databasePath,
                                          long before, long walBefore, DeletionOutcome outcome) throws IOException
    {
        return new Result(Operation.DELETE_RETAINED_STATS, Math.toIntExact(request.progress().rowsDeleted()), null,
            before, size(databasePath), walBefore, size(walPath(databasePath)), outcome);
    }

    static int runLightMaintenance(Connection connection, int retentionDays) throws SQLException
    {
        return runLightMaintenancePass(connection, retentionDays).deletedRows();
    }

    static RetentionResult runLightMaintenancePass(Connection connection, int retentionDays) throws SQLException
    {
        RetentionResult retention = cleanupRetentionPass(connection, retentionDays);
        int deleted = retention.deletedRows();
        checkpoint(connection);
        optimize(connection);
        updateStatus(connection, "last_maintenance_ms");
        ReceiverActivitySchema.updateStatus(connection, "last_maintenance_deleted_rows", Integer.toString(deleted));
        return retention;
    }

    static RetentionResult cleanupRetentionPass(Connection connection, int retentionDays) throws SQLException
    {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Math.max(1, retentionDays));
        ReceiverActivityRetention.Pass pass = ReceiverActivityRetention.runPass(connection, cutoff);
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

    private static int resetStats(Connection connection) throws SQLException
    {
        return inTransaction(connection, () -> {
            int deleted = DmrActivitySchema.resetStats(connection) + ReceiverActivitySchema.resetStats(connection) +
                TrunkedSiteSchema.resetStats(connection);
            updateStatus(connection, "last_stats_reset_ms");
            return deleted;
        });
    }

    private static int clearChannelStats(Connection connection, String configurationId) throws SQLException
    {
        return inTransaction(connection, () -> {
            int deleted = DmrActivitySchema.clearChannelStats(connection, configurationId) +
                ReceiverActivitySchema.clearChannelStats(connection, configurationId) +
                TrunkedSiteSchema.clearChannelStats(connection, configurationId);
            ReceiverActivitySchema.updateStatus(connection, "last_channel_stats_clear_ms",
                Long.toString(System.currentTimeMillis()));
            return deleted;
        });
    }

    private static <T> T inTransaction(Connection connection, SqlOperation<T> operation) throws SQLException
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

    private static void optimize(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA optimize");
        }
    }

    private static void checkpoint(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
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
            ResultSet resultSet = statement.executeQuery("PRAGMA quick_check"))
        {
            return resultSet.next() ? resultSet.getString(1) : "no result";
        }
    }

    private static void updateStatus(Connection connection, String key) throws SQLException
    {
        ReceiverActivitySchema.updateStatus(connection, key, Long.toString(System.currentTimeMillis()));
    }

    private static long size(Path path) throws IOException
    {
        return path != null && Files.isRegularFile(path) ? Files.size(path) : 0;
    }

    private static Path walPath(Path databasePath)
    {
        return databasePath == null ? null : Path.of(databasePath.toString() + "-wal");
    }
}
