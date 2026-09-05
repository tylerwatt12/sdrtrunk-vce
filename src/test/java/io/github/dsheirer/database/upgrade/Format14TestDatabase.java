package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Exact populated format 14, derived only through the immutable preceding migration. */
public final class Format14TestDatabase
{
    public static Path create(Path database) throws Exception
    {
        Format13TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            new Format13To14DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 14);
        }
        return database;
    }
}
