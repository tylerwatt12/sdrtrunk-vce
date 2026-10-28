/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.preference.portable;

import io.github.dsheirer.database.SdrTrunkTestDatabase;
import java.nio.file.Files;
import java.util.prefs.Preferences;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/** Installs the real disposable preference backend before test discovery can initialize preference users. */
public final class PortablePreferencesTestSupport implements LauncherSessionListener
{
    private static boolean installed;

    public PortablePreferencesTestSupport() {}

    @Override
    public void launcherSessionOpened(LauncherSession session)
    {
        try
        {
            install();
        }
        catch(Exception failure)
        {
            throw new IllegalStateException("Unable to install disposable portable test preferences", failure);
        }
    }

    private static synchronized void install() throws Exception
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
