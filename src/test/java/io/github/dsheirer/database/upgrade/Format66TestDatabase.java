/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-66 configuration, derived through the frozen populated format-65 adjacent transition. */
public final class Format66TestDatabase
{
    private Format66TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format65TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format65To66DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 66);
            connection.commit();
        }
        return database;
    }
}
