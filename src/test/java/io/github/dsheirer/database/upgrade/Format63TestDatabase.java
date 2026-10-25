/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;

/** Populated format-63 pair, derived only through its frozen populated predecessor and adjacent transition. */
public final class Format63TestDatabase
{
    private Format63TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format62TestDatabase.create(database);
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement=connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false);
            new Format62To63DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection,63);
            connection.commit();
        }
        Format62To63DatabaseMigration.recover(database);
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            if(DatabaseFormatCatalog.inspect(connection).version()!=63)
                throw new AssertionError("Frozen format-63 fixture changed");
        }
        return database;
    }
}
