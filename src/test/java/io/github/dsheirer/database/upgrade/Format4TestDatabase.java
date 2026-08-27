/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

/** Exact populated format-4 fixture decoded from an immutable checked-in SQLite image. */
public final class Format4TestDatabase
{
    private static final String RESOURCE =
        "/io/github/dsheirer/database/upgrade/format4-populated.sqlite.gz.b64";

    private Format4TestDatabase()
    {
    }

    public static Path create(Path database) throws Exception
    {
        if(Files.exists(database))
        {
            throw new IllegalArgumentException("Refusing to overwrite format-4 fixture target: " + database);
        }

        Files.createDirectories(database.toAbsolutePath().normalize().getParent());
        try(InputStream encoded = Format4TestDatabase.class.getResourceAsStream(RESOURCE))
        {
            if(encoded == null)
            {
                throw new IllegalStateException("Missing immutable format-4 fixture resource: " + RESOURCE);
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

            if(detected.version() != 4 || !detected.markerPresent() ||
                !DatabaseFormatCatalog.requireVersion(4).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 4 fixture fingerprint mismatch: " + fingerprint);
            }
        }

        return database;
    }

    /**
     * Creates the exact format-4 schema without the populated migration inventory.  Earlier-format fixture
     * builders use this as their independent schema source and then add only the rows required by their test.
     */
    static Path createSchemaBaseline(Path database) throws Exception
    {
        create(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            connection.setAutoCommit(false);

            try
            {
                statement.executeUpdate("DELETE FROM configuration_broadcast_stream");
                statement.executeUpdate("DELETE FROM configuration_channel");
                statement.executeUpdate("DELETE FROM application_settings");
                statement.executeUpdate("""
                    UPDATE database_metadata SET value='required'
                    WHERE key='initial_admin_setup'
                    """);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);

            if(detected.version() != 4 || !detected.markerPresent() ||
                !DatabaseFormatCatalog.requireVersion(4).fingerprint().equals(fingerprint))
            {
                throw new IllegalStateException("Global format 4 schema baseline mismatch: " + fingerprint);
            }
        }

        return database;
    }
}
