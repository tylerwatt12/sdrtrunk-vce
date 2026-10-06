/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
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

/**
 * Frozen format-20 schema created by published Nightly 34930803451 (09bfa0417dc2a597eb32da42e9e8a2d56780865f).
 * The image contains only synthetic configuration from the preceding populated fixtures. Its DDL is independent
 * of the current migration chain so release tests cannot manufacture their own expected legacy schema.
 */
public final class Format20TestDatabase
{
    private static final String RESOURCE =
        "/io/github/dsheirer/database/upgrade/format20-populated.sqlite.gz.b64";

    private Format20TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        if(Files.exists(database))
        {
            throw new IllegalArgumentException("Refusing to overwrite format-20 fixture target: " + database);
        }

        Files.createDirectories(database.toAbsolutePath().normalize().getParent());
        try(InputStream encoded = Format20TestDatabase.class.getResourceAsStream(RESOURCE))
        {
            if(encoded == null)
            {
                throw new IllegalStateException("Missing immutable format-20 fixture resource: " + RESOURCE);
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
            if(detected.version() != 20 ||
                !DatabaseFormatCatalog.requireVersion(20).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 20 fixture fingerprint mismatch: " + fingerprint);
            }
        }
        return database;
    }
}
