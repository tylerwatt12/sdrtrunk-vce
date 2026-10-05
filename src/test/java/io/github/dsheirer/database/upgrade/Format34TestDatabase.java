/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-34 fixture produced only by the adjacent format-33 migration. */
public final class Format34TestDatabase
{
    private Format34TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format33TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format33To34DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 34);
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
            if(DatabaseFormatCatalog.inspect(connection).version() != 34 ||
                !DatabaseFormatCatalog.requireVersion(34).fingerprint().equals(
                    SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 34 fixture signature mismatch");
            }
        }
        return database;
    }
}
