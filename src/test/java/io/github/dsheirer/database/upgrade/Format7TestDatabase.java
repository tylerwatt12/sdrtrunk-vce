/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

/** Exact populated format-7 fixture decoded from an immutable checked-in SQLite image. */
public final class Format7TestDatabase
{
    private static final String RESOURCE =
        "/io/github/dsheirer/database/upgrade/format7-populated.sqlite.gz.b64";

    private Format7TestDatabase()
    {
    }

    public static Path create(Path database) throws Exception
    {
        if(Files.exists(database))
        {
            throw new IllegalArgumentException("Refusing to overwrite format-7 fixture target: " + database);
        }

        Files.createDirectories(database.toAbsolutePath().normalize().getParent());
        try(InputStream encoded = Format7TestDatabase.class.getResourceAsStream(RESOURCE))
        {
            if(encoded == null)
            {
                throw new IllegalStateException("Missing immutable format-7 fixture resource: " + RESOURCE);
            }

            try(InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                InputStream uncompressed = new GZIPInputStream(decoded))
            {
                Files.copy(uncompressed, database);
            }
        }

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            if(detected.version() != 7 || !detected.markerPresent() ||
                !DatabaseFormatCatalog.requireVersion(7).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 7 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
