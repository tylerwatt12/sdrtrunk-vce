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

package io.github.dsheirer.database;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.sqlite.SQLiteConfig;

/**
 * Opens configuration and independently owned receiver activity stores.
 */
public final class SdrTrunkDatabase
{
    public static final int BUSY_TIMEOUT_MILLISECONDS = 30000;
    private SdrTrunkDatabase()
    {
    }

    public static Connection open(Path databasePath) throws IOException, SQLException
    {
        requireDatabase(databasePath);

        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);

        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLISECONDS);
            statement.execute("PRAGMA foreign_keys=ON");
        }

        return connection;
    }

    /** The activity companion belongs to the same portable profile as its configuration. */
    public static Path historyPath(Path configurationPath)
    {
        return ActivityDatabasePath.forConfiguration(configurationPath);
    }

    /**
     * Opens the activity writer with configuration attached read-only. Observations can consult committed matching
     * and routing policy, but this connection cannot write configuration or reserve its database writer.
     */
    public static Connection openHistory(Path configurationPath) throws IOException, SQLException
    {
        return openHistory(configurationPath, false);
    }

    /** A history reader may enrich its results with current configuration without owning either write path. */
    public static Connection openHistoryRead(Path configurationPath) throws IOException, SQLException
    {
        return openHistory(configurationPath, true);
    }

    private static Connection openHistory(Path configurationPath, boolean readOnly) throws IOException, SQLException
    {
        Path configuration = configurationPath.toAbsolutePath().normalize();
        Path activity = historyPath(configuration);
        requireDatabase(configuration);
        requireDatabase(activity);
        SQLiteConfig settings = new SQLiteConfig();
        settings.setReadOnly(readOnly);
        settings.setBusyTimeout(BUSY_TIMEOUT_MILLISECONDS);
        settings.enforceForeignKeys(true);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + activity, settings.toProperties());
        try
        {
            try(var attach = connection.prepareStatement("ATTACH DATABASE ? AS configuration"))
            {
                attach.setString(1, configuration.toUri().toASCIIString() + "?mode=ro");
                attach.execute();
            }
            io.github.dsheirer.stats.StatsHistoryContext.installSubscriberContext(connection);
            if(readOnly) io.github.dsheirer.stats.StatsHistoryContext.installHistoricalChannels(connection);
            return connection;
        }
        catch(SQLException | RuntimeException failure)
        {
            try { connection.close(); }
            catch(SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /**
     * Opens a caller-owned immediate write transaction. Reserving the SQLite write slot before the caller reads
     * prevents a concurrent writer from invalidating that read snapshot and causing an immediate BUSY_SNAPSHOT when
     * the caller later performs its first write.
     */
    public static Connection openWriteTransaction(Path databasePath) throws IOException, SQLException
    {
        requireDatabase(databasePath);
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(BUSY_TIMEOUT_MILLISECONDS);
        config.enforceForeignKeys(true);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath,
            config.toProperties());

        try
        {
            connection.setAutoCommit(false);
            return connection;
        }
        catch(SQLException exception)
        {
            try
            {
                connection.close();
            }
            catch(SQLException closeException)
            {
                exception.addSuppressed(closeException);
            }

            throw exception;
        }
    }

    /**
     * Commits and completes a caller-owned one-shot write transaction.
     *
     * <p>The SQLite JDBC driver's {@link Connection#commit()} immediately starts another transaction using the
     * configured transaction mode.  For an {@link SQLiteConfig.TransactionMode#IMMEDIATE} connection that would
     * reserve the database writer again after the intended write is already durable.  Returning to auto-commit mode
     * commits without opening that replacement transaction.</p>
     */
    public static void commitWriteTransaction(Connection connection) throws SQLException
    {
        connection.setAutoCommit(true);
    }

    private static void requireDatabase(Path databasePath) throws IOException
    {
        if(!java.nio.file.Files.isRegularFile(databasePath))
        {
            throw new IOException("VCE SQLite database schema is missing: " + databasePath +
                ". Startup schema preparation must run before opening SQLite stores.");
        }
    }
}
