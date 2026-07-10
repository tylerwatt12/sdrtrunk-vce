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

package io.github.dsheirer.preference.portable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * Stores the existing Java Preferences API in the portable SDRTrunk SQLite database.
 */
public class SqlitePreferencesFactory implements PreferencesFactory
{
    public static final String FACTORY_PROPERTY = "java.util.prefs.PreferencesFactory";
    private static final String SETTINGS_KEY = "portable_java_preferences_v1";
    private static final long WRITE_DELAY_MILLISECONDS = 500;
    private static PreferenceStore sStore;
    private static Preferences sUserRoot;
    private static Preferences sSystemRoot;

    public static synchronized void install(Path databasePath) throws IOException, SQLException
    {
        if(sStore != null)
        {
            return;
        }

        System.setProperty(FACTORY_PROPERTY, SqlitePreferencesFactory.class.getName());
        sStore = new PreferenceStore(databasePath);
        Runtime.getRuntime().addShutdownHook(new Thread(SqlitePreferencesFactory::shutdown,
            "sdrtrunk portable preferences shutdown"));
    }

    public static synchronized void shutdown()
    {
        if(sStore != null)
        {
            sStore.close();
        }
    }

    @Override
    public synchronized Preferences userRoot()
    {
        requireInstalled();

        if(sUserRoot == null)
        {
            sUserRoot = new SqlitePreferences(null, "", "user");
        }

        return sUserRoot;
    }

    @Override
    public synchronized Preferences systemRoot()
    {
        requireInstalled();

        if(sSystemRoot == null)
        {
            sSystemRoot = new SqlitePreferences(null, "", "system");
        }

        return sSystemRoot;
    }

    private static void requireInstalled()
    {
        if(sStore == null)
        {
            throw new IllegalStateException("Portable preferences were not installed before first use");
        }
    }

    private static class SqlitePreferences extends AbstractPreferences
    {
        private final String mRootName;

        private SqlitePreferences(AbstractPreferences parent, String name, String rootName)
        {
            super(parent, name);
            mRootName = rootName;
        }

        @Override
        protected void putSpi(String key, String value)
        {
            sStore.put(path(), key, value);
        }

        @Override
        protected String getSpi(String key)
        {
            return sStore.get(path(), key);
        }

        @Override
        protected void removeSpi(String key)
        {
            sStore.remove(path(), key);
        }

        @Override
        protected void removeNodeSpi()
        {
            sStore.removeNode(path());
        }

        @Override
        protected String[] keysSpi()
        {
            return sStore.keys(path());
        }

        @Override
        protected String[] childrenNamesSpi()
        {
            return sStore.children(path());
        }

        @Override
        protected AbstractPreferences childSpi(String name)
        {
            return new SqlitePreferences(this, name, mRootName);
        }

        @Override
        protected void syncSpi() throws BackingStoreException
        {
            sStore.sync();
        }

        @Override
        protected void flushSpi() throws BackingStoreException
        {
            sStore.flush();
        }

        private String path()
        {
            return mRootName + absolutePath();
        }
    }

    static class PreferenceStore
    {
        private final Path mDatabasePath;
        private final ObjectMapper mObjectMapper = new ObjectMapper();
        private final ScheduledExecutorService mWriter = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sdrtrunk portable preferences writer");
            thread.setDaemon(true);
            return thread;
        });
        private Map<String,Map<String,String>> mValues;
        private ScheduledFuture<?> mPendingWrite;
        private boolean mDirty;
        private boolean mClosed;

        PreferenceStore(Path databasePath) throws IOException, SQLException
        {
            mDatabasePath = databasePath.toAbsolutePath().normalize();
            mValues = read();
        }

        synchronized void put(String path, String key, String value)
        {
            mValues.computeIfAbsent(path, ignored -> new HashMap<>()).put(key, value);
            changed();
        }

        synchronized String get(String path, String key)
        {
            Map<String,String> node = mValues.get(path);
            return node != null ? node.get(key) : null;
        }

        synchronized void remove(String path, String key)
        {
            Map<String,String> node = mValues.get(path);

            if(node != null && node.remove(key) != null)
            {
                if(node.isEmpty())
                {
                    mValues.remove(path);
                }

                changed();
            }
        }

        synchronized void removeNode(String path)
        {
            String childPrefix = path.endsWith("/") ? path : path + "/";
            boolean removed = mValues.keySet().removeIf(candidate -> candidate.equals(path) ||
                candidate.startsWith(childPrefix));

            if(removed)
            {
                changed();
            }
        }

        synchronized String[] keys(String path)
        {
            Map<String,String> node = mValues.get(path);
            return node != null ? node.keySet().stream().sorted().toArray(String[]::new) : new String[0];
        }

        synchronized String[] children(String path)
        {
            String prefix = path.endsWith("/") ? path : path + "/";
            Set<String> children = new java.util.TreeSet<>();

            for(String candidate: mValues.keySet())
            {
                if(candidate.startsWith(prefix))
                {
                    String remainder = candidate.substring(prefix.length());
                    int separator = remainder.indexOf('/');
                    children.add(separator >= 0 ? remainder.substring(0, separator) : remainder);
                }
            }

            children.remove("");
            return children.toArray(String[]::new);
        }

        synchronized void sync() throws BackingStoreException
        {
            flush();

            try
            {
                mValues = read();
            }
            catch(IOException | SQLException e)
            {
                throw new BackingStoreException(e);
            }
        }

        synchronized void flush() throws BackingStoreException
        {
            if(!mDirty)
            {
                return;
            }

            try
            {
                write();
                mDirty = false;
            }
            catch(IOException | SQLException e)
            {
                throw new BackingStoreException(e);
            }
        }

        synchronized void close()
        {
            if(mClosed)
            {
                return;
            }

            mClosed = true;

            if(mPendingWrite != null)
            {
                mPendingWrite.cancel(false);
            }

            try
            {
                flush();
            }
            catch(BackingStoreException e)
            {
                System.err.println("Unable to save portable SDRTrunk preferences: " + e.getMessage());
            }

            mWriter.shutdown();
        }

        private void changed()
        {
            mDirty = true;

            if(!mClosed && (mPendingWrite == null || mPendingWrite.isDone()))
            {
                mPendingWrite = mWriter.schedule(() -> {
                    try
                    {
                        flush();
                    }
                    catch(BackingStoreException e)
                    {
                        System.err.println("Unable to save portable SDRTrunk preferences: " + e.getMessage());
                    }
                }, WRITE_DELAY_MILLISECONDS, TimeUnit.MILLISECONDS);
            }
        }

        private Map<String,Map<String,String>> read() throws IOException, SQLException
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
                        Map<String,Map<String,String>> values = mObjectMapper.readValue(
                            resultSet.getString("settings_json"), new TypeReference<>() {});
                        return values != null ? values : new HashMap<>();
                    }
                }
            }

            return new HashMap<>();
        }

        private void write() throws IOException, SQLException
        {
            String json = mObjectMapper.writeValueAsString(new TreeMap<>(mValues));

            try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
                PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO application_settings (key, settings_json, updated_at_ms)
                    VALUES (?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET
                        settings_json = excluded.settings_json,
                        updated_at_ms = excluded.updated_at_ms
                    """))
            {
                statement.setString(1, SETTINGS_KEY);
                statement.setString(2, json);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }
}
