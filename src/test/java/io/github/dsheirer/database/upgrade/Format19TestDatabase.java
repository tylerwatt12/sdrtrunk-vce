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

/** Exact populated format-19 fixture produced only by the adjacent format-18 migration. */
public final class Format19TestDatabase
{
    private Format19TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format18TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format18To19DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 19);
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
            if(detected.version() != 19 ||
                !DatabaseFormatCatalog.requireVersion(19).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 19 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
