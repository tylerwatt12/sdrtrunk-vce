/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated frozen format-54 fixture retaining every format-53 configuration and receiver-evidence row. */
public final class Format54TestDatabase
{
    private Format54TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format53TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format53To54DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 54);
                connection.commit();
            }
            catch(Exception failure)
            {
                connection.rollback();
                throw failure;
            }
            if(DatabaseFormatCatalog.inspect(connection).version() != 54)
                throw new IllegalStateException("Format-54 fixture was not preserved");
        }
        return database;
    }
}
