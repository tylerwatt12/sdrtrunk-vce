/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.preference.playback;

import io.github.dsheirer.preference.PreferenceType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackPreferenceTest
{
    @Test
    void muteStatePersistsAndPublishesPlaybackPreferenceUpdates() throws Exception
    {
        Preferences preferences = isolatedPreferences();

        try
        {
            List<PreferenceType> updates = new ArrayList<>();
            PlaybackPreference first = new PlaybackPreference(updates::add, preferences);
            assertFalse(first.isMuted());

            first.setMuted(true);

            assertTrue(first.isMuted());
            assertEquals(List.of(PreferenceType.PLAYBACK), updates);
            assertTrue(new PlaybackPreference(null, preferences).isMuted());
        }
        finally
        {
            preferences.removeNode();
        }
    }

    @Test
    void playbackBacklogIsBoundedAndPersists() throws Exception
    {
        Preferences preferences = isolatedPreferences();

        try
        {
            PlaybackPreference playbackPreference = new PlaybackPreference(null, preferences);
            playbackPreference.setMaximumBackloggedCalls(-1);
            assertEquals(PlaybackPreference.MINIMUM_BACKLOGGED_CALLS,
                playbackPreference.getMaximumBackloggedCalls());

            playbackPreference.setMaximumBackloggedCalls(PlaybackPreference.MAXIMUM_BACKLOGGED_CALLS + 1);
            assertEquals(PlaybackPreference.MAXIMUM_BACKLOGGED_CALLS,
                playbackPreference.getMaximumBackloggedCalls());

            playbackPreference.setMaximumBackloggedCalls(37);
            assertEquals(37,
                new PlaybackPreference(null, preferences).getMaximumBackloggedCalls());
        }
        finally
        {
            preferences.removeNode();
        }
    }

    private static Preferences isolatedPreferences()
    {
        return Preferences.userRoot().node("/io/github/dsheirer/tests/playback/" + UUID.randomUUID());
    }
}
