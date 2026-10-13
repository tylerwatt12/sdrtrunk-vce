/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Exact populated predecessor plus the empty prospective tone contract. */
public final class Format50TestDatabase
{
    private Format50TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format49TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try { new Format49To50DatabaseMigration().migrate(connection); DatabaseFormatCatalog.stamp(connection, 50); connection.commit(); }
            catch(Exception error) { connection.rollback(); throw error; }
            DatabaseFormatCatalog.requireCurrent(connection);
        }
        return database;
    }
}
