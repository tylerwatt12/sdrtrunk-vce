/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-30 fixture produced by the adjacent format-29 migration. */
public final class Format30TestDatabase
{
    private Format30TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format29TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format29To30DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 30);
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
            if(!DatabaseFormatCatalog.requireVersion(30).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 30 fixture fingerprint mismatch: " + fingerprint);
            }
            if(DatabaseFormatCatalog.requireCurrent(connection).version() != 30)
            {
                throw new IllegalStateException("Global format 30 fixture marker mismatch");
            }
        }
        return database;
    }
}
