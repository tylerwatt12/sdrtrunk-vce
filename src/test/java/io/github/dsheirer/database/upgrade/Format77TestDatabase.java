/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-77 pair, retaining its prior configuration and an enabled channel preference. */
public final class Format77TestDatabase
{
    private Format77TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format76TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
        {
            attach.setString(1, io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(database).toString());
            attach.execute();
            connection.setAutoCommit(false);
            new Format76To77DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stampForMigration(connection, 77);
            DatabaseFormatCatalog.requireCurrent(connection);
            try(var statement = connection.createStatement())
            {
                statement.executeUpdate("UPDATE configuration_channel SET use_auto_start_order_for_audio=1 " +
                    "WHERE id=(SELECT min(id) FROM configuration_channel)");
            }
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 77)
                throw new AssertionError("Frozen format-77 fixture changed");
        }
        return database;
    }
}
