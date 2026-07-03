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

package io.github.dsheirer.database.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.settings.Settings;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SQLite persistence for application settings.
 */
public class SettingsDatabaseStore
{
    private static final String SETTINGS_KEY = "default";
    private static final String SETTINGS_INITIALIZED_KEY = "settings_config_initialized";
    private static final String TRUE = "true";

    private final Path mDatabasePath;
    private final ObjectMapper mObjectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(SerializationFeature.INDENT_OUTPUT);

    public SettingsDatabaseStore(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    public Path getDatabasePath()
    {
        return mDatabasePath;
    }

    public boolean isInitialized() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            return TRUE.equalsIgnoreCase(getMetadata(connection, SETTINGS_INITIALIZED_KEY)) || hasSettings(connection);
        }
    }

    public Settings loadSettings() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement("""
                SELECT settings_json FROM application_settings WHERE key = ?
                """))
        {
            statement.setString(1, SETTINGS_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return mObjectMapper.readValue(resultSet.getString("settings_json"), Settings.class);
                }
            }
        }

        return new Settings();
    }

    public void replaceSettings(Settings settings) throws IOException, SQLException
    {
        if(settings == null)
        {
            settings = new Settings();
        }

        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try
            {
                try(PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO application_settings (key, settings_json, updated_at_ms)
                    VALUES (?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET
                        settings_json = excluded.settings_json,
                        updated_at_ms = excluded.updated_at_ms
                    """))
                {
                    statement.setString(1, SETTINGS_KEY);
                    statement.setString(2, mObjectMapper.writeValueAsString(settings));
                    statement.setLong(3, System.currentTimeMillis());
                    statement.executeUpdate();
                }

                updateMetadata(connection, SETTINGS_INITIALIZED_KEY, TRUE);
                connection.commit();
            }
            catch(SQLException | IOException e)
            {
                connection.rollback();
                throw e;
            }
            finally
            {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private boolean hasSettings(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM application_settings WHERE key = ?
            """))
        {
            statement.setString(1, SETTINGS_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private String getMetadata(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT value FROM database_metadata WHERE key = ?
            """))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return resultSet.getString("value");
                }
            }
        }

        return null;
    }

    private void updateMetadata(Connection connection, String key, String value) throws SQLException
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
}
