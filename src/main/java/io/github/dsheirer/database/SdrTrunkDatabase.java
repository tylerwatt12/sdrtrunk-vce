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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens the global SDRTrunk SQLite database.
 */
public final class SdrTrunkDatabase
{
    public static final int BUSY_TIMEOUT_MILLISECONDS = 30000;
    private static final Set<Path> INITIALIZED_DATABASES = ConcurrentHashMap.newKeySet();

    private SdrTrunkDatabase()
    {
    }

    public static Connection open(Path databasePath) throws IOException, SQLException
    {
        Files.createDirectories(databasePath.getParent());

        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);

        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLISECONDS);
            statement.execute("PRAGMA foreign_keys=ON");
        }

        ensureSchemaInitialized(databasePath, connection);
        return connection;
    }

    private static void ensureSchemaInitialized(Path databasePath, Connection connection) throws SQLException
    {
        Path normalizedPath = databasePath.toAbsolutePath().normalize();

        if(INITIALIZED_DATABASES.contains(normalizedPath))
        {
            return;
        }

        synchronized(SdrTrunkDatabase.class)
        {
            if(!INITIALIZED_DATABASES.contains(normalizedPath))
            {
                SdrTrunkDatabaseSchema.initialize(connection);
                INITIALIZED_DATABASES.add(normalizedPath);
            }
        }
    }
}
