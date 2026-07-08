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

import io.github.dsheirer.database.migration.XmlPlaylistToSqliteMigrator;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultSchema;
import io.github.dsheirer.radioresolve.activitylog.P25ActivityLogSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    public static void prepare(UserPreferences userPreferences) throws IOException, SQLException
    {
        XmlPlaylistToSqliteMigrator.migrateDefaultIfDatabaseMissing(userPreferences);
        prepareGlobalDatabase(SdrTrunkDatabasePath.getDatabasePath(userPreferences));
        prepareVaultDatabase(EncryptionKeyVaultPath.getVaultPath(userPreferences));
    }

    public static void prepareGlobalDatabase(Path databasePath) throws IOException, SQLException
    {
        Files.createDirectories(databasePath.toAbsolutePath().normalize().getParent());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath))
        {
            configure(connection);

            if(isEmpty(connection))
            {
                SdrTrunkDatabaseSchema.create(connection);
                P25ActivityLogSchema.create(connection);
            }

            SdrTrunkDatabaseSchema.validate(connection);
            P25ActivityLogSchema.validate(connection);
        }
    }

    public static void prepareVaultDatabase(Path vaultPath) throws IOException, SQLException
    {
        Files.createDirectories(vaultPath.toAbsolutePath().normalize().getParent());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + vaultPath))
        {
            configure(connection);

            if(isEmpty(connection))
            {
                EncryptionKeyVaultSchema.create(connection);
            }

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

    private static boolean isEmpty(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT COUNT(*)
                FROM sqlite_master
                WHERE type IN ('table', 'index', 'view')
                  AND name NOT LIKE 'sqlite_%'
                """))
        {
            return resultSet.next() && resultSet.getInt(1) == 0;
        }
    }
}
