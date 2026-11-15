/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-75 configuration with its matching Activity companion. */
public final class Format75TestDatabase
{
    private Format75TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format74TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
        {
            attach.setString(1, io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(database).toString());
            attach.execute();
            connection.setAutoCommit(false);
            new Format74To75DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 75);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 75)
                throw new AssertionError("Frozen format-75 fixture changed");
        }
        return database;
    }
}
