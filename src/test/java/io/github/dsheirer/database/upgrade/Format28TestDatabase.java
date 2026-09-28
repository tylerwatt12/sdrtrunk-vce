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

/** Exact populated format-28 fixture produced only by the adjacent format-27 migration. */
public final class Format28TestDatabase
{
    private Format28TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format27TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format27To28DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 28);
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
            if(!DatabaseFormatCatalog.requireVersion(28).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 28 fixture fingerprint mismatch: " + fingerprint);
            }
            if(DatabaseFormatCatalog.requireCurrent(connection).version() != 28)
            {
                throw new IllegalStateException("Global format 28 fixture marker mismatch");
            }
        }
        return database;
    }
}
