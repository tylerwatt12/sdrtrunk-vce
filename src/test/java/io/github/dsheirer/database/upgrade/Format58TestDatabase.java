/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated current fixture; historical Activity and alerts retain unknown exact source references. */
public final class Format58TestDatabase
{
    private Format58TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format57TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format57To58DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 58);
            connection.commit();
            DatabaseFormatCatalog.requireCurrent(connection);
        }
        return database;
    }
}
