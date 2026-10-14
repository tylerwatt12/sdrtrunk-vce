/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated current fixture retaining the frozen format-52 receiver evidence and explicit settings rows. */
public final class Format53TestDatabase
{
    private Format53TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format52TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format52To53DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 53);
                connection.commit();
            }
            catch(Exception failure)
            {
                connection.rollback();
                throw failure;
            }
            DatabaseFormatCatalog.requireCurrent(connection);
        }
        return database;
    }
}
