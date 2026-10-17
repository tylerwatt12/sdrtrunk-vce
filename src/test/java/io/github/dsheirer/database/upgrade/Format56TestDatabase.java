/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated frozen format-56 fixture retaining active settings and history after retired audio options are removed. */
public final class Format56TestDatabase
{
    private Format56TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format55TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format55To56DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 56);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 56)
                throw new IllegalStateException("Format-56 fixture was not preserved");
        }
        return database;
    }
}
