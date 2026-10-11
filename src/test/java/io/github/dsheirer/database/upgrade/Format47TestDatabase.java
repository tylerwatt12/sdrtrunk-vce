/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-47 fixture derived solely through the adjacent format-46 migration. */
public final class Format47TestDatabase
{
    private Format47TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format46TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format46To47DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 47);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            if(DatabaseFormatCatalog.inspect(connection).version() != 47 ||
                !DatabaseFormatCatalog.requireVersion(47).fingerprint().equals(SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 47 fixture signature mismatch");
            }
        }
        return database;
    }
}
