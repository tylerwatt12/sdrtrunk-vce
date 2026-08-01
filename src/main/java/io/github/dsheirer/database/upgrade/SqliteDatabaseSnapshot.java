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
import java.nio.file.LinkOption;
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

        requireSourceUsable(normalizedSource);

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
                deleteDatabaseAndSidecars(normalizedDestination);
            }
            catch(IOException cleanupFailure)
            {
                e.addSuppressed(cleanupFailure);
            }

            throw e;
        }
    }

    static void requireSourceUsable(Path source) throws IOException
    {
        Path normalized = source.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(normalized))
        {
            throw new IOException("Refusing to snapshot a symbolic-link SQLite database: " + normalized);
        }
        if(!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("SQLite database does not exist: " + normalized);
        }
        Path parent = normalized.getParent();
        if(!Files.isReadable(normalized) || !Files.isWritable(normalized) || parent == null ||
            !Files.isWritable(parent))
        {
            throw new IOException("SQLite database and its folder must be writable for a safe locked snapshot: " +
                normalized + ". Copy it to a writable folder or correct its permissions and try again.");
        }
        for(String suffix: java.util.List.of("-journal", "-wal", "-shm"))
        {
            Path sidecar = Path.of(normalized + suffix);
            if(Files.isSymbolicLink(sidecar))
            {
                throw new IOException("Refusing to snapshot a symbolic-link SQLite sidecar: " + sidecar);
            }
            if(Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS) && !Files.isWritable(sidecar))
            {
                throw new IOException("SQLite sidecar is not writable for a safe locked snapshot: " + sidecar);
            }
        }
    }

    private static void deleteDatabaseAndSidecars(Path database) throws IOException
    {
        IOException failure = null;
        for(String suffix: java.util.List.of("-journal", "-wal", "-shm", ""))
        {
            Path path = suffix.isEmpty() ? database : Path.of(database + suffix);
            try
            {
                Files.deleteIfExists(path);
            }
            catch(IOException e)
            {
                if(failure == null)
                {
                    failure = e;
                }
                else
                {
                    failure.addSuppressed(e);
                }
            }
        }
        if(failure != null)
        {
            throw failure;
        }
    }
}
