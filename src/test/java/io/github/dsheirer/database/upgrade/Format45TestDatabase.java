/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-45 fixture produced only through the adjacent format-44 migration. */
public final class Format45TestDatabase
{
    private Format45TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format44TestDatabase.createWithHomeRadioSplits(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format44To45DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 45);
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
            if(DatabaseFormatCatalog.inspect(connection).version() != 45 ||
                !DatabaseFormatCatalog.requireVersion(45).fingerprint().equals(SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 45 fixture signature mismatch");
            }
        }
        return database;
    }
}
