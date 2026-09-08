package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;

/** Exact populated format 14, derived only through the immutable preceding migration. */
public final class Format14TestDatabase
{
    private static byte[] sTemplate;

    private Format14TestDatabase()
    {
    }

    public static synchronized Path create(Path database) throws Exception
    {
        Path target = database.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());

        if(sTemplate == null)
        {
            build(target);
            sTemplate = Files.readAllBytes(target);
        }
        else
        {
            Files.write(target, sTemplate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        return target;
    }

    private static void build(Path database) throws Exception
    {
        Format13TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            new Format13To14DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 14);
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            if(detected.version() != 14 ||
                !DatabaseFormatCatalog.requireVersion(14).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 14 fixture fingerprint mismatch: " + fingerprint);
            }
        }
    }
}
