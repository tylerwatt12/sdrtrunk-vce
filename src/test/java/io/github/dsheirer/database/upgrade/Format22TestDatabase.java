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

/** Exact populated format-22 fixture produced only by the adjacent format-21 migration. */
public final class Format22TestDatabase
{
    private Format22TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format21TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format21To22DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 22);
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

            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            if(!DatabaseFormatCatalog.requireVersion(22).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 22 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
