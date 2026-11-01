/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-69 pair derived only through its frozen binary-encryption predecessor and adjacent step. */
public final class Format69TestDatabase
{
    private Format69TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format68TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            try(var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
            {
                attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString());
                attach.execute();
            }
            connection.setAutoCommit(false);
            new Format68To69DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 69);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 69)
                throw new AssertionError("Frozen format-69 fixture changed");
        }
        return database;
    }
}
