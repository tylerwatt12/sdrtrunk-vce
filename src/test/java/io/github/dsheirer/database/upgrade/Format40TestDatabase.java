/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-37 fixture produced only by the adjacent format-36 migration. */
public final class Format40TestDatabase
{
    private Format40TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format39TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:"+database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format39To40DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection,37);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally { connection.setAutoCommit(true); }
            if(DatabaseFormatCatalog.inspect(connection).version()!=37 ||
                !DatabaseFormatCatalog.requireVersion(37).fingerprint().equals(SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 37 fixture signature mismatch");
            }
        }
        return database;
    }
}
