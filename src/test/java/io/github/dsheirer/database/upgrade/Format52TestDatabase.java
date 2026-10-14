/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Frozen populated predecessor carried forward without relabeling its raw or encoded tone evidence. */
public final class Format52TestDatabase
{
    private Format52TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format51TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try { new Format51To52DatabaseMigration().migrate(connection); DatabaseFormatCatalog.stamp(connection, 52); connection.commit(); }
            catch(Exception error) { connection.rollback(); throw error; }
            DatabaseFormatCatalog.requireCurrent(connection);
        }
        return database;
    }
}
