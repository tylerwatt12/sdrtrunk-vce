/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-64 configuration with its exact unchanged format-63 activity companion. */
public final class Format64TestDatabase
{
    private Format64TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format63TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format63To64DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 64);
            connection.commit();
        }
        return database;
    }
}
