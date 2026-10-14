/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.preference.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.preference.PreferenceType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class AudioOutputPreferenceTest
{
    @Test
    void defaultsOffWithoutWritingAndPersistsAnIndependentChoice() throws Exception
    {
        Preferences node = Preferences.userRoot().node("/sdrtrunk-vce-tests/audio-output-" + UUID.randomUUID());
        Preferences parent = node.parent();
        List<PreferenceType> changes = new ArrayList<>();
        try
        {
            AudioOutputPreference preference = new AudioOutputPreference(changes::add, node);
            assertFalse(preference.isContinuousAgcEnabled());
            assertNull(node.get("audio.output.continuous.agc.enabled", null));
            preference.setContinuousAgcEnabled(false);
            assertTrue(changes.isEmpty());

            preference.setContinuousAgcEnabled(true);
            assertEquals(List.of(PreferenceType.AUDIO_OUTPUT), changes);
            assertTrue(new AudioOutputPreference(ignored -> {}, node).isContinuousAgcEnabled());
            assertNull(node.get("audio.mp3.normalize.before.encode", null));

            // A worker read uses the cached value rather than querying preference storage again.
            node.putBoolean("audio.output.continuous.agc.enabled", false);
            assertTrue(preference.isContinuousAgcEnabled());
            assertFalse(new AudioOutputPreference(ignored -> {}, node).isContinuousAgcEnabled());

            preference.setContinuousAgcEnabled(false);
            assertFalse(preference.isContinuousAgcEnabled());
            assertFalse(new AudioOutputPreference(ignored -> {}, node).isContinuousAgcEnabled());
            assertEquals(2, changes.size());
        }
        finally
        {
            node.removeNode();
            parent.flush();
        }
    }
}
