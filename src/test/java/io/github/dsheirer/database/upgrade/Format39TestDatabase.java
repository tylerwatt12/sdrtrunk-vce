/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-39 fixture produced only by the adjacent format-38 migration. */
public final class Format39TestDatabase
{
    private Format39TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format38TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format38To39DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 39);
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
            if(DatabaseFormatCatalog.inspect(connection).version() != 39 ||
                !DatabaseFormatCatalog.requireVersion(39).fingerprint().equals(
                    SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 39 fixture signature mismatch");
            }
        }
        return database;
    }
}
