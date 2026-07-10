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
