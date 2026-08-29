/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact current format-7 fixture using the authoritative fresh-database path. */
public final class Format7TestDatabase
{
    private Format7TestDatabase()
    {
    }

    public static Path create(Path database) throws Exception
    {
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.requireCurrent(connection);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            if(detected.version() != 7 ||
                !DatabaseFormatCatalog.requireVersion(7).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 7 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
