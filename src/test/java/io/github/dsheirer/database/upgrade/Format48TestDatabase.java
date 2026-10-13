/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated current fixture migrated from the frozen prior contract. */
public final class Format48TestDatabase
{
    private Format48TestDatabase() { }
    public static Path create(Path database) throws Exception
    {
        Format47TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try { new Format47To48DatabaseMigration().migrate(connection); DatabaseFormatCatalog.stamp(connection, 48); connection.commit(); }
            catch(Exception exception) { connection.rollback(); throw exception; }
            DatabaseFormatCatalog.requireCurrent(connection);
        }
        return database;
    }
}
