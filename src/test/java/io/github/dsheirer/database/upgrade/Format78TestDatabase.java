/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated format-78 pair; saved provider JSON remains exactly as its frozen predecessor supplied it. */
public final class Format78TestDatabase
{
    private Format78TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format77TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
        {
            attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString());
            attach.execute();
            connection.setAutoCommit(false);
            new Format77To78DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stampForMigration(connection, 78);
            DatabaseFormatCatalog.requireCurrent(connection);
            connection.commit();
        }
        return database;
    }
}
