/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.preference.portable;

import io.github.dsheirer.database.SdrTrunkTestDatabase;
import java.nio.file.Files;
import java.util.prefs.Preferences;

/** Installs the receiver's real preference backend on a disposable profile before any test preference access. */
public final class PortablePreferencesTestSupport
{
    private static boolean installed;

    private PortablePreferencesTestSupport() {}

    public static synchronized void install() throws Exception
    {
        if(!installed)
        {
            var database = Files.createTempDirectory("portable-test-preferences-").resolve("sdrtrunk.sqlite");
            SdrTrunkTestDatabase.create(database);
            SqlitePreferencesFactory.install(database);
            if(!Preferences.userRoot().getClass().getName().contains("SqlitePreferencesFactory"))
                throw new IllegalStateException("Portable preferences must be installed before first test access");
            installed = true;
        }
    }
}
