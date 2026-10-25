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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SdrTrunkDatabase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlitePreferencesFactoryTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void persistsPortablePreferenceTree() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        SqlitePreferencesFactory.PreferenceStore writer =
            new SqlitePreferencesFactory.PreferenceStore(database);
        writer.put("user/io/github/dsheirer/test", "width", "640");
        writer.put("user/io/github/dsheirer/test/child", "enabled", "true");
        writer.flush();
        writer.close();

        SqlitePreferencesFactory.PreferenceStore reader =
            new SqlitePreferencesFactory.PreferenceStore(database);
        assertEquals("640", reader.get("user/io/github/dsheirer/test", "width"));
        assertEquals("true", reader.get("user/io/github/dsheirer/test/child", "enabled"));

        reader.removeNode("user/io/github/dsheirer/test/child");
        reader.flush();
        assertNull(reader.get("user/io/github/dsheirer/test/child", "enabled"));
        reader.close();
    }

    @Test
    void contendedFlushKeepsReadsAndEditsAvailableAndPersistsInterveningChanges() throws Exception
    {
        Path database = mTemporaryFolder.resolve("contended.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        var store = new SqlitePreferencesFactory.PreferenceStore(database);
        store.put("user/test", "first", "before");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = null;
        try(Connection held = SdrTrunkDatabase.open(database); Statement sql = held.createStatement())
        {
            sql.execute("BEGIN IMMEDIATE");
            writer = Thread.ofPlatform().start(() -> {
                try { store.flush(); } catch(Throwable exception) { failure.set(exception); }
            });
            awaitSqliteWrite(writer);
            CompletableFuture<Void> reads = CompletableFuture.runAsync(() -> {
                assertEquals("before", store.get("user/test", "first"));
                store.put("user/test", "second", "during");
                store.remove("user/test", "first");
                assertEquals(java.util.List.of("second"), java.util.List.of(store.keys("user/test")));
                assertEquals(java.util.List.of("test"), java.util.List.of(store.children("user")));
            });
            try { reads.get(1, TimeUnit.SECONDS); }
            finally { sql.execute("ROLLBACK"); }
        }
        finally
        {
            if(writer != null) writer.join(2000);
            store.flush(); store.close();
        }
        assertNull(failure.get());
        var restored = new SqlitePreferencesFactory.PreferenceStore(database);
        try
        {
            assertNull(restored.get("user/test", "first"));
            assertEquals("during", restored.get("user/test", "second"));
        }
        finally { restored.close(); }
    }

    @Test
    void failedFlushRetainsTheAcceptedChangesForRetry() throws Exception
    {
        Path database = mTemporaryFolder.resolve("retry.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        var store = new SqlitePreferencesFactory.PreferenceStore(database);
        try(Connection connection = SdrTrunkDatabase.open(database); Statement sql = connection.createStatement())
        {
            sql.execute("CREATE TRIGGER reject_preferences BEFORE INSERT ON application_settings WHEN NEW.key='portable_java_preferences_v1' BEGIN SELECT RAISE(ABORT,'fixture rejection'); END");
            store.put("user/test", "accepted", "yes");
            org.junit.jupiter.api.Assertions.assertThrows(java.util.prefs.BackingStoreException.class, store::flush);
            assertEquals("yes", store.get("user/test", "accepted"));
            sql.execute("DROP TRIGGER reject_preferences");
            store.flush();
        }
        finally { store.close(); }
        var restored = new SqlitePreferencesFactory.PreferenceStore(database);
        try { assertEquals("yes", restored.get("user/test", "accepted")); }
        finally { restored.close(); }
    }

    private static void awaitSqliteWrite(Thread writer) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime() < deadline)
        {
            if(java.util.Arrays.stream(writer.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals("org.sqlite.core.NativeDB") && frame.getMethodName().contains("step"))) return;
            Thread.sleep(5);
        }
        throw new AssertionError("The fixture writer did not reach its held SQLite write");
    }

    @Test
    void javaPreferencesApiUsesPortableFactoryInFreshJvm() throws Exception
    {
        Path database = mTemporaryFolder.resolve("factory.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
            PreferenceProcess.class.getName(), database.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);

        SqlitePreferencesFactory.PreferenceStore reader =
            new SqlitePreferencesFactory.PreferenceStore(database);
        assertEquals("true", reader.get("user/portable/smoke", "enabled"));
        reader.close();
    }

    @Test
    void publicPreferencesFlushDoesNotRetainTheSameNodesLockInFreshJvm() throws Exception
    {
        Path database = mTemporaryFolder.resolve("public-contention.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
            PreferenceContentionProcess.class.getName(), database.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    public static class PreferenceContentionProcess
    {
        public static void main(String[] args) throws Exception
        {
            Path database = Path.of(args[0]);
            SqlitePreferencesFactory.install(database);
            Preferences preferences = Preferences.userRoot().node("/portable/same-node");
            preferences.put("first", "before");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread writer = null;
            try(Connection held = SdrTrunkDatabase.open(database); Statement sql = held.createStatement())
            {
                sql.execute("BEGIN IMMEDIATE");
                writer = Thread.ofPlatform().start(() -> {
                    try { preferences.flush(); } catch(Throwable exception) { failure.set(exception); }
                });
                awaitSqliteWrite(writer);
                try
                {
                    CompletableFuture.runAsync(() -> {
                        assertEquals("before", preferences.get("first", null));
                        preferences.put("during", "accepted");
                        preferences.remove("first");
                    }).get(1, TimeUnit.SECONDS);
                }
                finally { sql.execute("ROLLBACK"); }
            }
            finally { if(writer != null) writer.join(2000); }
            assertNull(failure.get());
            preferences.flush();
            preferences.sync();
            assertEquals("accepted", preferences.get("during", null));
            assertNull(preferences.get("first", null));
            preferences.removeNode();
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, preferences::sync);
            preferences.flush();
            SqlitePreferencesFactory.shutdown();
        }
    }

    public static class PreferenceProcess
    {
        public static void main(String[] args) throws Exception
        {
            SqlitePreferencesFactory.install(Path.of(args[0]));
            Preferences preferences = Preferences.userRoot().node("/portable/smoke");
            preferences.putBoolean("enabled", true);
            preferences.flush();
            assertTrue(preferences.getBoolean("enabled", false));
            SqlitePreferencesFactory.shutdown();
        }
    }

}
