/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-76 configuration with its matching Activity companion. */
public final class Format76TestDatabase
{
    private Format76TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format75TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
        {
            attach.setString(1, io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(database).toString());
            attach.execute();
            connection.setAutoCommit(false);
            new Format75To76DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 76);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 76)
                throw new AssertionError("Frozen format-76 fixture changed");
        }
        return database;
    }
}
