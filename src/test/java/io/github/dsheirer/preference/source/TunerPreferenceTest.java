/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.preference.source;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class TunerPreferenceTest
{
    @Test
    void staleChannelizerSelectionIsDiscarded() throws Exception
    {
        Preferences preferences = Preferences.userRoot().node(
            "/io/github/dsheirer/test/" + UUID.randomUUID());

        try
        {
            preferences.put(TunerPreference.RETIRED_PREFERENCE_KEY_CHANNELIZER_TYPE, "HETERODYNE");

            new TunerPreference(ignored -> {}, preferences);

            assertNull(preferences.get(TunerPreference.RETIRED_PREFERENCE_KEY_CHANNELIZER_TYPE, null),
                "A retired channelizer preference must not survive or influence tuner construction");
        }
        finally
        {
            preferences.removeNode();
        }
    }
}
