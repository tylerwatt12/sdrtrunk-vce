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
 * Opens the global SDRTrunk SQLite database.
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

    private static void requireDatabase(Path databasePath) throws IOException
    {
        if(!java.nio.file.Files.isRegularFile(databasePath))
        {
            throw new IOException("SDRTrunk SQLite database schema is missing: " + databasePath +
                ". Startup schema preparation must run before opening SQLite stores.");
        }
    }
}
