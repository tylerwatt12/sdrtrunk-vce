/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-44 fixture produced by the adjacent Alias List name-bound migration. */
public final class Format44TestDatabase
{
    private Format44TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format43TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format43To44DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 44);
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
            if(DatabaseFormatCatalog.requireCurrent(connection).version() != 44 ||
                !DatabaseFormatCatalog.current().fingerprint().equals(SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 44 fixture signature mismatch");
            }
        }
        return database;
    }
}
