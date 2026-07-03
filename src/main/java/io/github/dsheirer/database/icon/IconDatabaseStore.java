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

package io.github.dsheirer.database.icon;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.icon.IconSet;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SQLite persistence for icon metadata.
 */
public class IconDatabaseStore
{
    private static final String ICONS_KEY = "default";
    private static final String ICONS_INITIALIZED_KEY = "icon_config_initialized";
    private static final String TRUE = "true";

    private final Path mDatabasePath;
    private final ObjectMapper mObjectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(SerializationFeature.INDENT_OUTPUT);

    public IconDatabaseStore(Path databasePath)
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
            return TRUE.equalsIgnoreCase(getMetadata(connection, ICONS_INITIALIZED_KEY)) || hasIcons(connection);
        }
    }

    public IconSet loadIcons() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement("""
                SELECT icons_json FROM application_icons WHERE key = ?
                """))
        {
            statement.setString(1, ICONS_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return mObjectMapper.readValue(resultSet.getString("icons_json"), IconSet.class);
                }
            }
        }

        return new IconSet();
    }

    public void replaceIcons(IconSet iconSet) throws IOException, SQLException
    {
        if(iconSet == null)
        {
            iconSet = new IconSet();
        }

        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try
            {
                try(PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO application_icons (key, icons_json, updated_at_ms)
                    VALUES (?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET
                        icons_json = excluded.icons_json,
                        updated_at_ms = excluded.updated_at_ms
                    """))
                {
                    statement.setString(1, ICONS_KEY);
                    statement.setString(2, mObjectMapper.writeValueAsString(iconSet));
                    statement.setLong(3, System.currentTimeMillis());
                    statement.executeUpdate();
                }

                updateMetadata(connection, ICONS_INITIALIZED_KEY, TRUE);
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

    private boolean hasIcons(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM application_icons WHERE key = ?
            """))
        {
            statement.setString(1, ICONS_KEY);

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
