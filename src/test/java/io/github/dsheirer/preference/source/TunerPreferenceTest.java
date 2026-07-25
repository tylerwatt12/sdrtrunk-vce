/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.preference.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class TunerPreferenceTest
{
    @Test
    void channelizerSelectionRoundTripsThroughPreferences() throws Exception
    {
        Preferences preferences = Preferences.userRoot().node(
            "/io/github/dsheirer/test/" + UUID.randomUUID());

        try
        {
            preferences.put(TunerPreference.PREFERENCE_KEY_CHANNELIZER_TYPE, "HETERODYNE");

            TunerPreference tunerPreference = new TunerPreference(ignored -> {}, preferences);
            assertEquals(ChannelizerType.HETERODYNE, tunerPreference.getChannelizerType());

            tunerPreference.setChannelizerType(ChannelizerType.POLYPHASE);
            assertEquals("POLYPHASE",
                preferences.get(TunerPreference.PREFERENCE_KEY_CHANNELIZER_TYPE, null));
        }
        finally
        {
            preferences.removeNode();
        }
    }
}
