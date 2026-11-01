/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-68 configuration derived through the frozen format-67 adjacent transition. */
public final class Format68TestDatabase
{
    private Format68TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format67TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format67To68DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 68);
            connection.commit();
        }
        return database;
    }
}
