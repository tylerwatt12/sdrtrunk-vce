/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Populated fixture retaining exact prior-format content and the new default notification configuration. */
public final class Format47TestDatabase
{
    private Format47TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format46TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try { new Format46To47DatabaseMigration().migrate(connection); DatabaseFormatCatalog.stamp(connection, 47); connection.commit(); }
            catch(Exception exception) { connection.rollback(); throw exception; }
            DatabaseFormatCatalog.requireCurrent(connection);
        }
        return database;
    }
}
