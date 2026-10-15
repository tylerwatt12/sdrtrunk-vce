/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated frozen format-53 fixture retaining receiver evidence and explicit settings rows. */
public final class Format53TestDatabase
{
    private Format53TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format52TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format52To53DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 53);
                connection.commit();
            }
            catch(Exception failure)
            {
                connection.rollback();
                throw failure;
            }
            if(DatabaseFormatCatalog.inspect(connection).version() != 53)
                throw new IllegalStateException("Format-53 fixture was not preserved");
        }
        return database;
    }
}
