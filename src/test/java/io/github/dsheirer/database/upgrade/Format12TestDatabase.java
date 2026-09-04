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

/** Exact populated format-12 fixture through its frozen predecessor and adjacent migration. */
public final class Format12TestDatabase
{
    private Format12TestDatabase()
    {
    }

    public static Path create(Path database) throws Exception
    {
        Format11TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            new Format11To12DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 12);
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            if(detected.version() != 12 ||
                !DatabaseFormatCatalog.requireVersion(12).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 12 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
