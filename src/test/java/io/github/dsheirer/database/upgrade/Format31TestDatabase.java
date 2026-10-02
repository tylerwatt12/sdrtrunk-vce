/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Exact populated format-31 fixture produced by the adjacent format-30 migration. */
public final class Format31TestDatabase
{
    private Format31TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format30TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format30To31DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 31);
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
            if(!DatabaseFormatCatalog.requireVersion(31).fingerprint().equals(
                SqliteSchemaValidator.fingerprint(connection)) ||
                DatabaseFormatCatalog.requireCurrent(connection).version() != 31)
            {
                throw new IllegalStateException("Global format 31 fixture signature mismatch");
            }
        }
        return database;
    }
}
