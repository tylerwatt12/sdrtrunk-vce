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

/** Exact populated format-20 fixture produced only by the adjacent format-19 migration. */
public final class Format20TestDatabase
{
    private Format20TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format19TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format19To20DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 20);
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
            if(detected.version() != 20 ||
                !DatabaseFormatCatalog.requireVersion(20).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 20 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
