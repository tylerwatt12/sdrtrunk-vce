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

package io.github.dsheirer.database.upgrade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

/**
 * Creates a consistent, standalone SQLite snapshot, including committed records that are still in a WAL file.
 */
public final class SqliteDatabaseSnapshot
{
    private static final int SQLITE_OK = 0;
    private static final int BUSY_TIMEOUT_MILLISECONDS = 10_000;

    private SqliteDatabaseSnapshot()
    {
    }

    /**
     * Creates a new snapshot without changing or replacing the source database.
     */
    public static void create(Path source, Path destination) throws IOException, SQLException
    {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();

        if(!Files.isRegularFile(normalizedSource))
        {
            throw new IOException("SQLite database does not exist: " + normalizedSource);
        }

        if(Files.exists(normalizedDestination))
        {
            throw new IOException("Refusing to overwrite an existing SQLite snapshot: " + normalizedDestination);
        }

        if(normalizedDestination.getParent() == null)
        {
            throw new IOException("SQLite snapshot has no parent directory: " + normalizedDestination);
        }

        Files.createDirectories(normalizedDestination.getParent());
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLISECONDS);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalizedSource,
            config.toProperties()))
        {
            if(!(connection instanceof SQLiteConnection sqliteConnection))
            {
                throw new SQLException("The configured JDBC driver is not the SQLite driver.");
            }

            int result = sqliteConnection.getDatabase().backup("main", normalizedDestination.toString(), null);

            if(result != SQLITE_OK)
            {
                throw new SQLException("SQLite backup returned status " + result + ".");
            }
        }
        catch(SQLException | RuntimeException e)
        {
            try
            {
                Files.deleteIfExists(normalizedDestination);
            }
            catch(IOException cleanupFailure)
            {
                e.addSuppressed(cleanupFailure);
            }

            throw e;
        }
    }
}
