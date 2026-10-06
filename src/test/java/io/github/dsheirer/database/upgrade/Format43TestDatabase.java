/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-43 fixture produced by the adjacent Live row-density preference migration. */
public final class Format43TestDatabase
{
    private Format43TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format42TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format42To43DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 43);
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
            if(DatabaseFormatCatalog.requireCurrent(connection).version() != 43 ||
                !DatabaseFormatCatalog.requireVersion(43).fingerprint().equals(
                    SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 43 fixture signature mismatch");
            }
        }
        return database;
    }
}
