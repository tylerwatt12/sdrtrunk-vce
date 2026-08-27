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

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact format 3 fixture factory; delegates only to authoritative fresh-current startup creation. */
public final class Format3TestDatabase
{
    private Format3TestDatabase()
    {
    }

    public static Path create(Path database) throws Exception
    {
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.requireCurrent(connection);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);

            if(detected.version() != 3 ||
                !DatabaseFormatCatalog.requireVersion(3).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 3 fixture fingerprint mismatch: " + fingerprint);
            }
        }

        return database;
    }
}
