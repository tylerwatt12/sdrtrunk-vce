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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;

/**
 * SQLite maintenance actions for the sdrtrunk-vce stats database.
 */
public final class P25ActivityLogMaintenance
{
    private P25ActivityLogMaintenance()
    {
    }

    public enum Operation
    {
        MAINTAIN,
        SHRINK,
        CHECK,
        RESET_STATS,
        CLEAR_SITE_STATS
    }

    public record Result(Operation operation, int rowsDeleted, String checkResult, long databaseBytesBefore,
                         long databaseBytesAfter, long walBytesBefore, long walBytesAfter)
    {
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
                case CLEAR_SITE_STATS -> sb.append("Site statistics cleared");
            }

            if(operation != Operation.CHECK)
            {
                sb.append(". Deleted ").append(rowsDeleted).append(
                    operation == Operation.RESET_STATS || operation == Operation.CLEAR_SITE_STATS ?
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
                P25ActivityLogSchema.updateStatus(connection, "last_integrity_check_ms",
                    Long.toString(System.currentTimeMillis()));
                P25ActivityLogSchema.updateStatus(connection, "last_integrity_check_result", checkResult);
            }
            case RESET_STATS ->
            {
                rowsDeleted = resetStats(connection);
                checkpoint(connection);
                optimize(connection);
            }
            case CLEAR_SITE_STATS -> throw new IllegalArgumentException(
                "CLEAR_SITE_STATS requires a site GUID");
        }

        return new Result(operation, rowsDeleted, checkResult, databaseBytesBefore, size(databasePath), walBytesBefore,
            size(walPath(databasePath)));
    }

    /**
     * Clears statistics and history owned by one configured site without changing its channel configuration or
     * system-wide summaries that may be shared by other sites.
     */
    public static Result clearSiteStats(Path databasePath, String guid) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(databasePath))
        {
            return clearSiteStats(connection, databasePath, guid);
        }
    }

    /**
     * Clears one site's statistics on the caller-owned writer connection.
     */
    static Result clearSiteStats(Connection connection, Path databasePath, String guid)
        throws IOException, SQLException
    {
        if(guid == null || guid.isBlank())
        {
            throw new IllegalArgumentException("Site GUID is required");
        }

        long databaseBytesBefore = size(databasePath);
        long walBytesBefore = size(walPath(databasePath));
        int rowsDeleted = clearSiteStats(connection, guid);
        checkpoint(connection);
        optimize(connection);
        return new Result(Operation.CLEAR_SITE_STATS, rowsDeleted, null, databaseBytesBefore, size(databasePath),
            walBytesBefore, size(walPath(databasePath)));
    }

    static int runLightMaintenance(Connection connection, int retentionDays) throws SQLException
    {
        int deleted = cleanupRetention(connection, retentionDays);
        checkpoint(connection);
        optimize(connection);
        updateStatus(connection, "last_maintenance_ms");
        P25ActivityLogSchema.updateStatus(connection, "last_maintenance_deleted_rows", Integer.toString(deleted));
        return deleted;
    }

    static int cleanupRetention(Connection connection, int retentionDays) throws SQLException
    {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Math.max(1, retentionDays));
        int deleted = P25ActivityLogSchema.deleteOlderThan(connection, cutoff) +
            TrunkedSiteSchema.deleteOlderThan(connection, cutoff).total();

        P25ActivityLogSchema.updateStatus(connection, "retention_days", Integer.toString(Math.max(1, retentionDays)));
        P25ActivityLogSchema.updateStatus(connection, "last_retention_cleanup_ms",
            Long.toString(System.currentTimeMillis()));
        P25ActivityLogSchema.updateStatus(connection, "last_retention_deleted_rows", Integer.toString(deleted));
        return deleted;
    }

    private static int resetStats(Connection connection) throws SQLException
    {
        return inTransaction(connection, () -> {
            int deleted = P25ActivityLogSchema.resetStats(connection) + TrunkedSiteSchema.resetStats(connection);
            updateStatus(connection, "last_stats_reset_ms");
            return deleted;
        });
    }

    private static int clearSiteStats(Connection connection, String guid) throws SQLException
    {
        return inTransaction(connection, () -> {
            int deleted = P25ActivityLogSchema.clearSiteStats(connection, guid) +
                TrunkedSiteSchema.clearSiteStats(connection, guid);
            P25ActivityLogSchema.updateStatus(connection, "last_site_stats_clear_ms",
                Long.toString(System.currentTimeMillis()));
            return deleted;
        });
    }

    private static int inTransaction(Connection connection, SqlOperation operation) throws SQLException
    {
        boolean previousAutoCommit = connection.getAutoCommit();

        if(!previousAutoCommit)
        {
            throw new SQLException("Statistics maintenance requires an idle database writer connection");
        }

        connection.setAutoCommit(false);

        try
        {
            int result = operation.run();
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
    private interface SqlOperation
    {
        int run() throws SQLException;
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
        P25ActivityLogSchema.updateStatus(connection, key, Long.toString(System.currentTimeMillis()));
    }

    private static long size(Path path) throws IOException
    {
        return path != null && Files.isRegularFile(path) ? Files.size(path) : 0;
    }

    private static Path walPath(Path databasePath)
    {
        return Path.of(databasePath.toString() + "-wal");
    }
}
