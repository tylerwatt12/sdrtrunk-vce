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

package io.github.dsheirer.radioresolve.activitylog;

import io.github.dsheirer.database.SdrTrunkDatabase;
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
 * SQLite maintenance actions for the SDRTrunk stats database.
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
        CHECK
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
            }

            if(operation != Operation.CHECK)
            {
                sb.append(". Deleted ").append(rowsDeleted).append(" expired row(s)");
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
        long databaseBytesBefore = size(databasePath);
        long walBytesBefore = size(walPath(databasePath));
        int rowsDeleted = 0;
        String checkResult = null;

        try(Connection connection = SdrTrunkDatabase.open(databasePath))
        {
            P25ActivityLogSchema.validate(connection);

            switch(operation)
            {
                case MAINTAIN ->
                {
                    rowsDeleted = runLightMaintenance(connection, retentionDays);
                }
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
            }
        }

        return new Result(operation, rowsDeleted, checkResult, databaseBytesBefore, size(databasePath), walBytesBefore,
            size(walPath(databasePath)));
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
        int deleted = P25ActivityLogSchema.deleteOlderThan(connection, cutoff);

        P25ActivityLogSchema.updateStatus(connection, "retention_days", Integer.toString(Math.max(1, retentionDays)));
        P25ActivityLogSchema.updateStatus(connection, "last_retention_cleanup_ms",
            Long.toString(System.currentTimeMillis()));
        P25ActivityLogSchema.updateStatus(connection, "last_retention_deleted_rows", Integer.toString(deleted));
        return deleted;
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
