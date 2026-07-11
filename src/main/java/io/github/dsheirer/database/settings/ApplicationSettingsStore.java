/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.util.ThreadPool;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Typed, keyed persistence for application settings stored in the global SQLite database.
 */
public class ApplicationSettingsStore
{
    public static final String UI_SETTINGS = "ui.settings";
    public static final String TUNER_SETTINGS = "tuner.settings";
    private static final Logger mLog = LoggerFactory.getLogger(ApplicationSettingsStore.class);
    private static final Map<PendingKey,String> PENDING_WRITES = new ConcurrentHashMap<>();
    private static final AtomicBoolean SAVE_PENDING = new AtomicBoolean();
    private final Path mDatabasePath;
    private final ObjectMapper mObjectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ApplicationSettingsStore(Path databasePath)
    {
        mDatabasePath = databasePath.toAbsolutePath().normalize();
    }

    public Path getDatabasePath()
    {
        return mDatabasePath;
    }

    public boolean contains(String key) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM application_settings WHERE key = ?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    public <T> Optional<T> load(String key, Class<T> type) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement(
                "SELECT settings_json FROM application_settings WHERE key = ?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? Optional.of(mObjectMapper.readValue(resultSet.getString(1), type)) :
                    Optional.empty();
            }
        }
    }

    public void save(String key, Object value) throws IOException, SQLException
    {
        saveJson(mDatabasePath, key, mObjectMapper.writeValueAsString(value));
    }

    public void saveLater(String key, Object value) throws IOException
    {
        PENDING_WRITES.put(new PendingKey(mDatabasePath, key), mObjectMapper.writeValueAsString(value));
        schedulePendingWrites();
    }

    private static void schedulePendingWrites()
    {
        if(SAVE_PENDING.compareAndSet(false, true))
        {
            ThreadPool.SCHEDULED.schedule(ApplicationSettingsStore::flushPendingWrites, 2, TimeUnit.SECONDS);
        }
    }

    private static void flushPendingWrites()
    {
        Map<PendingKey,String> writes = Map.copyOf(PENDING_WRITES);

        for(Map.Entry<PendingKey,String> entry: writes.entrySet())
        {
            PENDING_WRITES.remove(entry.getKey(), entry.getValue());
        }

        for(Map.Entry<PendingKey,String> entry: writes.entrySet())
        {
            try
            {
                saveJson(entry.getKey().databasePath(), entry.getKey().key(), entry.getValue());
            }
            catch(IOException | SQLException e)
            {
                mLog.error("Error saving application setting [{}] to SQLite [{}]", entry.getKey().key(),
                    entry.getKey().databasePath(), e);
            }
        }

        SAVE_PENDING.set(false);

        if(!PENDING_WRITES.isEmpty())
        {
            schedulePendingWrites();
        }
    }

    private static void saveJson(Path databasePath, String key, String json) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(databasePath);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_settings (key, settings_json, updated_at_ms)
                VALUES (?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET
                    settings_json = excluded.settings_json,
                    updated_at_ms = excluded.updated_at_ms
                """))
        {
            statement.setString(1, key);
            statement.setString(2, json);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private record PendingKey(Path databasePath, String key)
    {
    }
}
