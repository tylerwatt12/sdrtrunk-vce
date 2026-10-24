/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Frozen populated format-61 fixture has independent tour versions for two retained accounts. */
public final class Format61TestDatabase
{
    private Format61TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format60TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement())
        {
            connection.setAutoCommit(false);
            new Format60To61DatabaseMigration().migrate(connection);
            statement.executeUpdate("INSERT INTO web_user_tour_dismissal(user_id,guide_id,guide_version) " +
                "SELECT id,'welcome',1 FROM web_user");
            statement.executeUpdate("INSERT INTO web_user_tour_dismissal(user_id,guide_id,guide_version) " +
                "SELECT id,'admin',3 FROM web_user WHERE primary_admin=1");
            DatabaseFormatCatalog.stamp(connection, 61);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 61)
                throw new IllegalStateException("The frozen format-61 fixture changed");
        }
        return database;
    }
}
