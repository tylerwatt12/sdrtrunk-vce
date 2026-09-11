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

/** Exact populated format-18 fixture produced only by the adjacent format-17 migration. */
public final class Format18TestDatabase
{
    private Format18TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format17TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format17To18DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 18);
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
            if(detected.version() != 18 ||
                !DatabaseFormatCatalog.requireVersion(18).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 18 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
