/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-42 fixture produced by the adjacent one-index format-41 migration. */
public final class Format42TestDatabase
{
    private Format42TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format41TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format41To42DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 42);
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
            if(DatabaseFormatCatalog.inspect(connection).version() != 42 ||
                !DatabaseFormatCatalog.requireVersion(42).fingerprint().equals(
                    SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 42 fixture signature mismatch");
            }
        }
        return database;
    }
}
