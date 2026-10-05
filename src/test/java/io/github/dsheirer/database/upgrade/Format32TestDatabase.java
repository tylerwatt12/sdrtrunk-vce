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

/** Exact populated format-32 fixture produced only by the adjacent format-31 migration. */
public final class Format32TestDatabase
{
    private Format32TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format31TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format31To32DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 32);
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
            if(detected.version() != 32 ||
                !DatabaseFormatCatalog.requireVersion(32).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 32 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
