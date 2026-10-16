/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;
import java.nio.file.Path;
import java.sql.DriverManager;
/** Populated frozen format-55 fixture retaining all prior standalone tone evidence without inferred associations. */
public final class Format55TestDatabase
{
    private Format55TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format54TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format54To55DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 55);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 55)
                throw new IllegalStateException("Format-55 fixture was not preserved");
        }
        return database;
    }
}
