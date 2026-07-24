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

package io.github.dsheirer.database;

import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultSchema;
import io.github.dsheirer.record.RecordedCallCatalogSchema;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Single startup owner for SDRTrunk SQLite schema creation and validation.
 */
public final class SdrTrunkDatabaseStartup
{
    private SdrTrunkDatabaseStartup()
    {
    }

    public static void createGlobalDatabase(Path databasePath) throws IOException, SQLException
    {
        Path normalized = databasePath.toAbsolutePath().normalize();

        if(Files.exists(normalized))
        {
            throw new IOException("Refusing to overwrite existing SDRTrunk SQLite database: " + normalized);
        }

        Files.createDirectories(normalized.getParent());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalized))
        {
            configure(connection);
            SdrTrunkDatabaseSchema.create(connection);
            P25ActivityLogSchema.create(connection);
            TrunkedSiteSchema.create(connection);
            RecordedCallCatalogSchema.create(connection);
            SdrTrunkDatabaseSchema.validate(connection);
            P25ActivityLogSchema.validate(connection);
            TrunkedSiteSchema.validate(connection);
            RecordedCallCatalogSchema.validate(connection);
        }
    }

    public static void validateGlobalDatabase(Path databasePath) throws IOException, SQLException
    {
        Path normalized = requireDatabase(databasePath, "SDRTrunk");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalized))
        {
            configure(connection);
            SdrTrunkDatabaseSchema.validate(connection);
            P25ActivityLogSchema.validate(connection);
            TrunkedSiteSchema.validate(connection);
            RecordedCallCatalogSchema.validate(connection);
        }
    }

    public static void createVaultDatabase(Path vaultPath) throws IOException, SQLException
    {
        Path normalized = vaultPath.toAbsolutePath().normalize();

        if(Files.exists(normalized))
        {
            throw new IOException("Refusing to overwrite existing encryption vault database: " + normalized);
        }

        Files.createDirectories(normalized.getParent());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalized))
        {
            configure(connection);
            EncryptionKeyVaultSchema.create(connection);
            EncryptionKeyVaultSchema.validate(connection);
        }
    }

    public static void validateVaultDatabase(Path vaultPath) throws IOException, SQLException
    {
        Path normalized = requireDatabase(vaultPath, "Encryption vault");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalized))
        {
            configure(connection);
            EncryptionKeyVaultSchema.validate(connection);
        }
    }

    public static void setMetadata(Connection connection, String key, String value) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata (key, value, updated_at_ms)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET
                value = excluded.value,
                updated_at_ms = excluded.updated_at_ms
            """))
        {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private static void configure(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    private static Path requireDatabase(Path databasePath, String label) throws IOException
    {
        Path normalized = databasePath.toAbsolutePath().normalize();

        if(!Files.isRegularFile(normalized))
        {
            throw new IOException(label + " SQLite database does not exist: " + normalized);
        }

        return normalized;
    }
}
