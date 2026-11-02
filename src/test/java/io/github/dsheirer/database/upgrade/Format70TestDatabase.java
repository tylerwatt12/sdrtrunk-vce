/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-70 pair derived only through its frozen format-69 predecessor and adjacent semantic step. */
public final class Format70TestDatabase
{
    private Format70TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format69TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
        {
            attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString());
            attach.execute();
            connection.setAutoCommit(false);
            new Format69To70DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 70);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 70)
                throw new AssertionError("Frozen format-70 fixture changed");
        }
        return database;
    }
}
