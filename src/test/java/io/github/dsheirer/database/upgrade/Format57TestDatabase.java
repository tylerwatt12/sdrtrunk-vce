/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated current fixture preserving all earlier tone evidence without historical renormalization. */
public final class Format57TestDatabase
{
    private Format57TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format56TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format56To57DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 57);
            connection.commit();
            DatabaseFormatCatalog.requireCurrent(connection);
        }
        return database;
    }
}
