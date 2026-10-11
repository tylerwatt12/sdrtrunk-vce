/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-46 fixture produced only through the adjacent format-45 migration. */
public final class Format46TestDatabase
{
    private Format46TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format45TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format45To46DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 46);
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
            if(DatabaseFormatCatalog.inspect(connection).version() != 46 ||
                !DatabaseFormatCatalog.requireVersion(46).fingerprint().equals(SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 46 fixture signature mismatch");
            }
        }
        return database;
    }
}
