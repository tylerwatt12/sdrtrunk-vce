/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.preference.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.preference.PreferenceType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class ReceiverLocationPreferenceTest
{
    @Test
    void validatesAndPersistsOneCompleteCoordinatePair() throws Exception
    {
        Preferences preferences = Preferences.userRoot().node(
            "/io/github/dsheirer/test/receiver-location/" + UUID.randomUUID());

        try
        {
            AtomicInteger changes = new AtomicInteger();
            ReceiverLocationPreference preference = new ReceiverLocationPreference(type -> {
                assertEquals(PreferenceType.RECEIVER_LOCATION, type);
                changes.incrementAndGet();
            }, preferences);
            assertTrue(preference.getReceiverLocation().isEmpty());

            ReceiverLocation location = new ReceiverLocation(41.50481d, -81.69312d);
            preference.setReceiverLocation(location);
            preferences.flush();
            assertEquals(1, changes.get());
            assertEquals(location, preference.getReceiverLocation().orElseThrow());
            assertEquals("41.50481,-81.69312",
                preferences.get(ReceiverLocationPreference.PREFERENCE_KEY_RECEIVER_LOCATION, null));

            ReceiverLocationPreference reloaded = new ReceiverLocationPreference(ignored -> {}, preferences);
            assertEquals(location, reloaded.getReceiverLocation().orElseThrow());
            reloaded.clearReceiverLocation();
            assertTrue(reloaded.getReceiverLocation().isEmpty());
        }
        finally
        {
            preferences.removeNode();
        }
    }

    @Test
    void rejectsInvalidCoordinates()
    {
        assertThrows(IllegalArgumentException.class, () -> new ReceiverLocation(90.0001d, 0.0d));
        assertThrows(IllegalArgumentException.class, () -> new ReceiverLocation(0.0d, -180.0001d));
        assertThrows(IllegalArgumentException.class, () -> new ReceiverLocation(Double.NaN, 0.0d));
        assertThrows(IllegalArgumentException.class,
            () -> new ReceiverLocation(0.0d, Double.POSITIVE_INFINITY));
        assertEquals(new ReceiverLocation(-90.0d, 180.0d), new ReceiverLocation(-90.0d, 180.0d));
    }

    @Test
    void ignoresMalformedStoredCoordinates() throws Exception
    {
        Preferences preferences = Preferences.userRoot().node(
            "/io/github/dsheirer/test/receiver-location-malformed/" + UUID.randomUUID());

        try
        {
            preferences.put(ReceiverLocationPreference.PREFERENCE_KEY_RECEIVER_LOCATION, "91.0,not-a-number");
            assertTrue(new ReceiverLocationPreference(ignored -> {}, preferences).getReceiverLocation().isEmpty());
        }
        finally
        {
            preferences.removeNode();
        }
    }
}
