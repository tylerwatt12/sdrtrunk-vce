/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-65 configuration, derived through the frozen populated format-64 adjacent transition. */
public final class Format65TestDatabase
{
    private Format65TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format64TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format64To65DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 65);
            connection.commit();
        }
        return database;
    }
}
