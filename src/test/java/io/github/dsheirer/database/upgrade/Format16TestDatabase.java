/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-16 fixture produced only by the adjacent format-15 migration. */
public final class Format16TestDatabase
{
    private Format16TestDatabase()
    {
    }

    public static Path create(Path database) throws Exception
    {
        Format15TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format15To16DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 16);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            if(detected.version() != 16 ||
                !DatabaseFormatCatalog.requireVersion(16).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 16 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
