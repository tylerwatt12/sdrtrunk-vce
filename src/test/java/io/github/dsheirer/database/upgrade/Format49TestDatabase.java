/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-49 fixture produced only through its adjacent Application Migrator step. */
public final class Format49TestDatabase
{
    private Format49TestDatabase() { }
    public static Path create(Path database) throws Exception
    {
        Format48TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try { new Format48To49DatabaseMigration().migrate(connection); DatabaseFormatCatalog.stamp(connection, 49); connection.commit(); }
            catch(Exception exception) { connection.rollback(); throw exception; }
            DatabaseFormatCatalog.requireCurrent(connection);
        }
        return database;
    }
}
