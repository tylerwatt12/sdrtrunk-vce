/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-74 configuration with its matching Activity companion. */
public final class Format74TestDatabase
{
    private Format74TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format73TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
        {
            attach.setString(1, io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(database).toString());
            attach.execute();
            connection.setAutoCommit(false);
            new Format73To74DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 74);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 74)
                throw new AssertionError("Frozen format-74 fixture changed");
        }
        return database;
    }
}
