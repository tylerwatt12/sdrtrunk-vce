/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-67 configuration, derived through the frozen populated format-66 adjacent transition. */
public final class Format67TestDatabase
{
    private Format67TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format66TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format66To67DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 67);
            connection.commit();
        }
        return database;
    }
}
