/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-15 fixture produced only by the adjacent format-14 migration. */
public final class Format15TestDatabase
{
    private Format15TestDatabase()
    {
    }

    public static Path create(Path database) throws Exception
    {
        Format14TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format14To15DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 15);
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

            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.requireCurrent(connection);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            if(detected.version() != 15 ||
                !DatabaseFormatCatalog.requireVersion(15).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 15 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
