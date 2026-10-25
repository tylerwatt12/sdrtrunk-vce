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

import io.github.dsheirer.database.upgrade.DatabaseFormatCatalog;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultSchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.web.settings.SpectrumSnapSettings;
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
    private SdrTrunkDatabaseStartup()
    {
    }

    public static void createGlobalDatabase(Path databasePath) throws IOException, SQLException
    {
        Path normalized = databasePath.toAbsolutePath().normalize();
        Path activity = ActivityDatabasePath.forConfiguration(normalized);
        if(Files.exists(normalized) || Files.exists(activity))
        {
            throw new IOException("Refusing to overwrite an existing configuration/activity database");
        }
        Files.createDirectories(normalized.getParent());
        String pairId = java.util.UUID.randomUUID().toString();
        try(Connection configuration = DriverManager.getConnection("jdbc:sqlite:" + normalized))
        {
            configure(configuration);
            try(Statement statement = configuration.createStatement())
            {
                statement.execute("PRAGMA foreign_keys=OFF");
            }
            configuration.setAutoCommit(false);
            ConfigurationHistorySchema.createConfiguration(configuration);
            SdrTrunkDatabaseSchema.seedDefaultAliasLists(configuration);
            InitialAdminSetup.markRequired(configuration);
            io.github.dsheirer.gui.setup.SetupProgress.write(configuration,
                new io.github.dsheirer.gui.setup.SetupProgress(false, false));
            SpectrumSnapSettings.write(configuration, SpectrumSnapSettings.defaults());
            io.github.dsheirer.database.settings.ReceiverSettingsSchema.initializeMissing(configuration);
            ConfigurationHistorySchema.setIdentity(configuration, ConfigurationHistorySchema.CONFIGURATION_ROLE, pairId);
            setMetadata(configuration, DatabaseFormatCatalog.FORMAT_VERSION_KEY, "63");
            DatabaseFormatCatalog.requireCurrent(configuration);
            configuration.commit();
        }
        try(Connection history = DriverManager.getConnection("jdbc:sqlite:" + activity))
        {
            configure(history);
            try(Statement statement = history.createStatement())
            {
                statement.execute("PRAGMA foreign_keys=OFF");
            }
            history.setAutoCommit(false);
            ConfigurationHistorySchema.createHistory(history);
            ConfigurationHistorySchema.setIdentity(history, ConfigurationHistorySchema.HISTORY_ROLE, pairId);
            setMetadata(history, DatabaseFormatCatalog.FORMAT_VERSION_KEY, "63");
            ConfigurationHistoryDatabase.validateRole(history, ConfigurationHistorySchema.HISTORY_ROLE);
            history.commit();
        }
        ConfigurationHistoryDatabase.validatePair(normalized);
    }

    public static void validateGlobalDatabase(Path databasePath) throws IOException, SQLException
    {
        validateGlobalDatabaseForStartup(databasePath);
        for(Path path: List.of(databasePath, ActivityDatabasePath.forConfiguration(databasePath)))
        {
            try(Connection connection = openReadOnly(path))
            {
                try(Statement query = connection.createStatement();
                    ResultSet rows = query.executeQuery("PRAGMA foreign_key_check"))
                {
                    if(rows.next())
                    {
                        throw new SQLException("The configuration/activity database contains an invalid relationship");
                    }
                }
                if(path.equals(ActivityDatabasePath.forConfiguration(databasePath)))
                {
                    ReceiverActivitySchema.validateRetainedHistoryData(connection);
                }
            }
        }
    }

    /** Exact pair admission is read-only; operational journal changes follow successful admission of both files. */
    public static void validateGlobalDatabaseForStartup(Path databasePath) throws IOException, SQLException
    {
        Path normalized = requireDatabase(databasePath, "VCE");
        ConfigurationHistoryDatabase.validatePair(normalized);
        try(Connection configuration = DriverManager.getConnection("jdbc:sqlite:" + normalized);
            Connection activity = DriverManager.getConnection("jdbc:sqlite:" + ActivityDatabasePath.forConfiguration(normalized)))
        {
            configure(configuration);
            configure(activity);
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
                SELECT substr(type, 1, 16) || ':' || substr(name, 1, 128)
                FROM sqlite_master
                WHERE lower(name) GLOB 'recorded_call*'
                   OR lower(name) GLOB 'idx_recorded_call*'
                LIMIT 1
                """))
        {
            while(resultSet.next())
            {
                footprints.add(resultSet.getString(1));
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT 'metadata:' || substr(CAST(key AS TEXT), 1, 128)
                FROM database_metadata
                WHERE lower(substr(CAST(key AS TEXT), 1, 13)) = 'recorded_call'
                LIMIT 1
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
            // The main file is opened read-only. Explicit semantic validation may create its own TEMP view.
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
