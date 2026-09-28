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

/** Exact populated format-27 fixture produced only by the adjacent format-26 migration. */
public final class Format27TestDatabase
{
    private Format27TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format26TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format26To27DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 27);
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
            if(!DatabaseFormatCatalog.requireVersion(27).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 27 fixture fingerprint mismatch: " + fingerprint);
            }
            if(DatabaseFormatCatalog.requireCurrent(connection).version() != 27)
            {
                throw new IllegalStateException("Global format 27 fixture marker mismatch");
            }
        }
        return database;
    }
}
