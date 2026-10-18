/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Current populated fixture preserves historical rows with unknown call lengths. */
public final class Format59TestDatabase
{
    private Format59TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format58TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format58To59DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 59);
            connection.commit();
            DatabaseFormatCatalog.requireCurrent(connection);
        }
        return database;
    }
}
