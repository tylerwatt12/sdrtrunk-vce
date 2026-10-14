/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Exact populated predecessor plus prospective analog Alias activity. */
public final class Format51TestDatabase
{
    private Format51TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format50TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try { new Format50To51DatabaseMigration().migrate(connection); DatabaseFormatCatalog.stamp(connection, 51); connection.commit(); }
            catch(Exception error) { connection.rollback(); throw error; }
            DatabaseFormatCatalog.requireCurrent(connection);
        }
        return database;
    }
}
