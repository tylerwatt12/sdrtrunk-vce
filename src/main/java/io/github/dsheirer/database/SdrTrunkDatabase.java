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
        if(!java.nio.file.Files.isRegularFile(databasePath))
        {
            throw new IOException("SDRTrunk SQLite database schema is missing: " + databasePath +
                ". Startup schema preparation must run before opening SQLite stores.");
        }

        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);

        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLISECONDS);
            statement.execute("PRAGMA foreign_keys=ON");
        }

        return connection;
    }
}
