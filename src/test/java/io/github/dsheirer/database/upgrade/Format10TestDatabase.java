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

/** Exact current format-10 fixture using the authoritative fresh-database path. */
public final class Format10TestDatabase
{
    private Format10TestDatabase()
    {
    }

    public static Path create(Path database) throws Exception
    {
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.requireCurrent(connection);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            if(detected.version() != 10 ||
                !DatabaseFormatCatalog.requireVersion(10).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 10 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
