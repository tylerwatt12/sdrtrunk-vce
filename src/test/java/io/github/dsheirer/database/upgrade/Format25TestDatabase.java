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

/** Exact populated format-25 fixture produced only by the adjacent format-24 migration. */
public final class Format25TestDatabase
{
    private Format25TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format24TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format24To25DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 25);
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
            if(!DatabaseFormatCatalog.requireVersion(25).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 25 fixture fingerprint mismatch: " + fingerprint);
            }
            if(DatabaseFormatCatalog.requireCurrent(connection).version() != 25)
            {
                throw new IllegalStateException("Global format 25 fixture marker mismatch");
            }
        }
        return database;
    }
}
