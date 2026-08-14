/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.preference.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.stats.WebCallConfiguration;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class ApplicationPreferenceWebCallConfigurationTest
{
    @Test
    void persistsAllWebCallCapacityFieldsAndPublishesOnePreferenceChange() throws Exception
    {
        Preferences node = Preferences.userRoot().node("/sdrtrunk-vce-tests/web-call-" + UUID.randomUUID());
        Preferences parent = node.parent();

        try
        {
            AtomicInteger changes = new AtomicInteger();
            ApplicationPreference preference = new ApplicationPreference(ignored -> changes.incrementAndGet());
            usePreferences(preference, node);
            assertEquals(WebCallConfiguration.defaults(), preference.getWebCallConfiguration());

            WebCallConfiguration configured = new WebCallConfiguration(48, 12, 80, 768, 256);
            preference.setWebCallConfiguration(configured);
            node.flush();
            assertEquals(1, changes.get());

            AtomicInteger reloadedChanges = new AtomicInteger();
            ApplicationPreference reloaded = new ApplicationPreference(
                ignored -> reloadedChanges.incrementAndGet());
            usePreferences(reloaded, node);
            assertEquals(configured, reloaded.getWebCallConfiguration());

            reloaded.setWebCallConfiguration(null);
            assertEquals(WebCallConfiguration.defaults(), reloaded.getWebCallConfiguration());
            assertEquals(1, reloadedChanges.get());
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
