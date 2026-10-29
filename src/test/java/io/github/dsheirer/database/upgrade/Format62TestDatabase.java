/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Populated current fixture preserves account-owned tour history and typed automatic-guide choices. */
public final class Format62TestDatabase
{
    private static final DatabaseFixtureTemplate TEMPLATE =
        new DatabaseFixtureTemplate(62, Format62TestDatabase::build);

    private Format62TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        return TEMPLATE.create(database);
    }

    private static Path build(Path database) throws Exception
    {
        Format61TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format61To62DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 62);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version()!=62)
                throw new AssertionError("Frozen format-62 fixture changed");
        }
        return database;
    }
}
