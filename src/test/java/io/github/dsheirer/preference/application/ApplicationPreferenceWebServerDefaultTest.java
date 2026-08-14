/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.preference.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class ApplicationPreferenceWebServerDefaultTest
{
    @Test
    void enablesFreshWebServerAndPreservesExplicitDisable() throws Exception
    {
        Preferences node = Preferences.userRoot().node("/sdrtrunk-vce-tests/web-server-" + UUID.randomUUID());
        Preferences parent = node.parent();

        try
        {
            ApplicationPreference fresh = new ApplicationPreference(ignored -> {});
            usePreferences(fresh, node);
            assertTrue(fresh.isStatsWebServerEnabled());

            fresh.setStatsWebServerEnabled(false);
            node.flush();

            ApplicationPreference reloaded = new ApplicationPreference(ignored -> {});
            usePreferences(reloaded, node);
            assertFalse(reloaded.isStatsWebServerEnabled());
        }
        finally
        {
            node.removeNode();
            parent.flush();
        }
    }

    private static void usePreferences(ApplicationPreference preference, Preferences preferences)
        throws ReflectiveOperationException
    {
        Field field = ApplicationPreference.class.getDeclaredField("mPreferences");
        field.setAccessible(true);
        field.set(preference, preferences);
    }
}
