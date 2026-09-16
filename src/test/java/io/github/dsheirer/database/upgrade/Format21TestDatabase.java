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

/** Exact populated format-21 fixture produced only by the adjacent format-20 migration. */
public final class Format21TestDatabase
{
    private Format21TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format20TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format20To21DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 21);
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
            if(!DatabaseFormatCatalog.requireVersion(21).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 21 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
