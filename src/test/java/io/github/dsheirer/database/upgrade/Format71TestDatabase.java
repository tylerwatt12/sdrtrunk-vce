/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-71 pair derived through its frozen format-70 predecessor and adjacent retirement. */
public final class Format71TestDatabase
{
    private Format71TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format70TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
        {
            attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString());
            attach.execute();
            connection.setAutoCommit(false);
            new Format70To71DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 71);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 71)
                throw new AssertionError("Frozen format-71 fixture changed");
        }
        return database;
    }
}
