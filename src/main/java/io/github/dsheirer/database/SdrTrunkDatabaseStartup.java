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
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.sqlite.SQLiteConfig;

/**
 * Single startup owner for SDRTrunk SQLite schema creation and validation.
 */
public final class SdrTrunkDatabaseStartup
{
    private static final String CURRENT_GLOBAL_SCHEMA_FINGERPRINT =
        "391c6787c5754e92c0efc6983c759c56b5279c6ace9c86d3e01ba163ba2ee0ad";
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
            SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
            P25ActivityLogSchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
            InitialAdminSetup.markRequired(connection);
            requireMainTrackDatabase(connection);
            SdrTrunkDatabaseSchema.validate(connection);
            P25ActivityLogSchema.validate(connection);
            DmrActivitySchema.validate(connection);
            TrunkedSiteSchema.validate(connection);
            requireCurrentSchemaFingerprint(connection);
        }
    }

    public static void validateGlobalDatabase(Path databasePath) throws IOException, SQLException
    {
        Path normalized = requireDatabase(databasePath, "SDRTrunk");

        try(Connection connection = openReadOnly(normalized))
        {
            requireMainTrackDatabase(connection);
            SdrTrunkDatabaseSchema.validate(connection);
            P25ActivityLogSchema.validate(connection);
            DmrActivitySchema.validate(connection);
            TrunkedSiteSchema.validate(connection);
            requireCurrentSchemaFingerprint(connection);
        }

        //Only a database proven to be the current main-track schema may be opened read/write and placed in the
        //operational WAL mode. A wrong-track or malformed database is rejected above without being changed.
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalized))
        {
            configure(connection);
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

        try(Connection connection = openReadOnly(normalized))
        {
            EncryptionKeyVaultSchema.validate(connection);
        }

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalized))
        {
            configure(connection);
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

    /**
     * Main and webfirst intentionally have incompatible recording products. Reject a webfirst catalog even when all
     * shared schema versions happen to match, so normal startup cannot open the wrong portable data directory.
     */
    public static void requireMainTrackDatabase(Connection connection) throws SQLException
    {
        List<String> footprints = new ArrayList<>();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT type || ':' || name
                FROM sqlite_master
                WHERE lower(name) GLOB 'recorded_call*'
                   OR lower(name) GLOB 'idx_recorded_call*'
                ORDER BY type, name
                """))
        {
            while(resultSet.next())
            {
                footprints.add(resultSet.getString(1));
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT 'metadata:' || key
                FROM database_metadata
                WHERE lower(key) GLOB 'recorded_call*'
                ORDER BY key
                """))
        {
            while(resultSet.next())
            {
                footprints.add(resultSet.getString(1));
            }
        }

        if(!footprints.isEmpty())
        {
            throw new SQLException("This main release cannot open a webfirst managed-recording database; found " +
                footprints + ". Use separate portable data folders for main and webfirst.");
        }
    }

    public static void requireCurrentSchemaFingerprint(Connection connection) throws SQLException
    {
        String actual = SqliteSchemaValidator.fingerprint(connection);
        if(!CURRENT_GLOBAL_SCHEMA_FINGERPRINT.equals(actual))
        {
            throw new SQLException("SQLite database is not the exact current main schema layout (" + actual + ")");
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

    private static Connection openReadOnly(Path databasePath) throws SQLException
    {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath, config.toProperties());
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
            statement.execute("PRAGMA foreign_keys=ON");
        }
        catch(SQLException e)
        {
            connection.close();
            throw e;
        }
        return connection;
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
