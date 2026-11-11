/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-73 configuration with its matching Activity companion. */
public final class Format73TestDatabase
{
    private Format73TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format72TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
        {
            attach.setString(1, io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(database).toString());
            attach.execute();
            connection.setAutoCommit(false);
            new Format72To73DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 73);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 73)
                throw new AssertionError("Frozen format-73 fixture changed");
        }
        return database;
    }
}
