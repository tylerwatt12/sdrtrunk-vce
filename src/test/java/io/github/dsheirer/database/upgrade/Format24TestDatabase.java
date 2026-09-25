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

/** Exact populated format-24 fixture produced only by the adjacent format-23 migration. */
public final class Format24TestDatabase
{
    private Format24TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format23TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format23To24DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 24);
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
            if(!DatabaseFormatCatalog.requireVersion(24).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 24 fixture fingerprint mismatch: " + fingerprint);
            }
            if(DatabaseFormatCatalog.requireCurrent(connection).version() != 24)
            {
                throw new IllegalStateException("Global format 24 fixture marker mismatch");
            }
        }
        return database;
    }
}
