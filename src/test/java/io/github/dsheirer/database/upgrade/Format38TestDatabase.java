/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-38 fixture produced by the adjacent two-index format-37 migration. */
public final class Format38TestDatabase
{
    private Format38TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format37TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format37To38DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 38);
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
            if(DatabaseFormatCatalog.inspect(connection).version() != 38 ||
                !DatabaseFormatCatalog.requireVersion(38).fingerprint().equals(
                    SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 38 fixture signature mismatch");
            }
        }
        return database;
    }
}
