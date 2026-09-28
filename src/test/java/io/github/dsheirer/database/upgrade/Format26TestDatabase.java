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

/** Exact populated format-26 fixture produced only by the adjacent format-25 migration. */
public final class Format26TestDatabase
{
    private Format26TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format25TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format25To26DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 26);
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
            if(!DatabaseFormatCatalog.requireVersion(26).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 26 fixture fingerprint mismatch: " + fingerprint);
            }
            if(DatabaseFormatCatalog.inspect(connection).version() != 26)
            {
                throw new IllegalStateException("Global format 26 fixture marker mismatch");
            }
            try(var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                    SELECT COUNT(*) FROM web_user
                    WHERE username='operator' AND primary_admin=0 AND tier='ADMIN'
                    """))
            {
                if(!rows.next() || rows.getLong(1) != 1)
                {
                    throw new IllegalStateException("Global format 26 fixture must contain one ordinary ADMIN account");
                }
            }
        }
        return database;
    }
}
