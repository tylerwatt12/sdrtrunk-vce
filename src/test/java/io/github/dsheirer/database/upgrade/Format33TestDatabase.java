/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-33 fixture produced only by the adjacent format-32 migration. */
public final class Format33TestDatabase
{
    private Format33TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format32TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format32To33DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 33);
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
            if(DatabaseFormatCatalog.inspect(connection).version() != 33 ||
                !DatabaseFormatCatalog.requireVersion(33).fingerprint().equals(
                    SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 33 fixture signature mismatch");
            }
        }
        return database;
    }
}
