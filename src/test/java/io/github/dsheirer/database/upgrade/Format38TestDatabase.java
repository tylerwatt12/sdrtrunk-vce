/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-35 fixture produced only by the adjacent format-34 migration. */
public final class Format38TestDatabase
{
    private Format38TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format34TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format37To38DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 35);
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
            if(DatabaseFormatCatalog.inspect(connection).version() != 35 ||
                !DatabaseFormatCatalog.requireVersion(35).fingerprint().equals(
                    SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 35 fixture signature mismatch");
            }
        }
        return database;
    }
}
