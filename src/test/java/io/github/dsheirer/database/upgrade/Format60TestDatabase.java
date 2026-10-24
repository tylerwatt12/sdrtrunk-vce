/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-60 fixture retains historical unknown message subtypes and shared definitions. */
public final class Format60TestDatabase
{
    private Format60TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format59TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format59To60DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 60);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 60)
                throw new IllegalStateException("The frozen format-60 fixture changed");
        }
        return database;
    }
}
