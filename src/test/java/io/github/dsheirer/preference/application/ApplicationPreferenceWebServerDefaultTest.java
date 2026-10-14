/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.preference.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class ApplicationPreferenceWebServerDefaultTest
{
    @Test
    void diagnosticLoggingStartsOffPersistsAndDoesNotNotifyListenerReload() throws Exception
    {
        Preferences node = Preferences.userRoot().node("/sdrtrunk-vce-tests/web-diagnostic-default-" + UUID.randomUUID());
        Preferences parent = node.parent();
        java.util.concurrent.atomic.AtomicInteger updates = new java.util.concurrent.atomic.AtomicInteger();
        try
        {
            ApplicationPreference fresh = new ApplicationPreference(ignored -> updates.incrementAndGet());
            usePreferences(fresh, node);
            assertFalse(fresh.isWebDiagnosticLoggingEnabled());
            fresh.setWebDiagnosticLoggingEnabled(true);
            node.flush();
            ApplicationPreference reloaded = new ApplicationPreference(ignored -> updates.incrementAndGet());
            usePreferences(reloaded, node);
            assertTrue(reloaded.isWebDiagnosticLoggingEnabled());
            reloaded.setWebDiagnosticLoggingEnabled(false);
            assertEquals(0, updates.get());
        }
        finally { node.removeNode(); parent.flush(); }
    }

    @Test
    void acceptsFullPortRangeAndClampsInvalidValues() throws Exception
    {
        Preferences node = Preferences.userRoot().node("/sdrtrunk-vce-tests/web-server-port-" + UUID.randomUUID());
        Preferences parent = node.parent();

        try
        {
            ApplicationPreference preference = new ApplicationPreference(ignored -> {});
            usePreferences(preference, node);

            preference.setStatsWebServerPort(1);
            assertEquals(1, preference.getStatsWebServerPort());

            preference.setStatsWebServerPort(80);
            assertEquals(80, preference.getStatsWebServerPort());

            preference.setStatsWebServerPort(65_535);
            assertEquals(65_535, preference.getStatsWebServerPort());

            preference.setStatsWebServerPort(0);
            assertEquals(ApplicationPreference.MIN_STATS_WEB_SERVER_PORT, preference.getStatsWebServerPort());

            preference.setStatsWebServerPort(65_536);
            assertEquals(ApplicationPreference.MAX_STATS_WEB_SERVER_PORT, preference.getStatsWebServerPort());
        }
        finally
        {
            node.removeNode();
            parent.flush();
        }
    }

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
