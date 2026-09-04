package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Exact populated format 13, derived only through the immutable preceding migration. */
public final class Format13TestDatabase
{
    public static Path create(Path database) throws Exception
    {
        Format12TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            new Format12To13DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 13);
        }
        return database;
    }
}
