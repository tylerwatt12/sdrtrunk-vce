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

/** Exact populated format-23 fixture produced only by the adjacent format-22 migration. */
public final class Format23TestDatabase
{
    private Format23TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format22TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format22To23DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 23);
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
            if(!DatabaseFormatCatalog.requireVersion(23).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 23 fixture fingerprint mismatch: " + fingerprint);
            }
            if(DatabaseFormatCatalog.requireCurrent(connection).version() != 23)
            {
                throw new IllegalStateException("Global format 23 fixture marker mismatch");
            }
        }
        return database;
    }
}
