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

/** Exact populated format-17 fixture produced only by the adjacent format-16 migration. */
public final class Format17TestDatabase
{
    private Format17TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format16TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format16To17DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 17);
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
            if(detected.version() != 17 ||
                !DatabaseFormatCatalog.requireVersion(17).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 17 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
