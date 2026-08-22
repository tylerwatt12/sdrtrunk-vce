/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WebCallConfigurationTest
{
    @Test
    void exposesStableDefaultsAndConvertsTheAudioBudgetWithoutOverflow()
    {
        WebCallConfiguration defaults = WebCallConfiguration.defaults();

        assertEquals(WebCallConfiguration.DEFAULT_MAXIMUM_LISTENERS, defaults.maximumListeners());
        assertEquals(WebCallConfiguration.DEFAULT_MAXIMUM_SELECTED_SCAN_LISTS,
            defaults.maximumSelectedScanLists());
        assertEquals(WebCallConfiguration.DEFAULT_WAITING_CALLS_PER_LISTENER,
            defaults.waitingCallsPerListener());
        assertEquals(WebCallConfiguration.DEFAULT_MAXIMUM_CACHED_CALLS, defaults.maximumCachedCalls());
        assertEquals(128L * 1024L * 1024L, defaults.maximumCachedAudioBytes());
        assertEquals(1024L * 1024L * 1024L,
            new WebCallConfiguration(1, 1, 1, 16, 1024).maximumCachedAudioBytes());
    }

    @Test
    void clampsCorruptPersistedValuesAtEveryOwnedLimit()
    {
        WebCallConfiguration low = new WebCallConfiguration(Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        assertEquals(WebCallConfiguration.MINIMUM_LISTENERS, low.maximumListeners());
        assertEquals(WebCallConfiguration.MINIMUM_SELECTED_SCAN_LISTS, low.maximumSelectedScanLists());
        assertEquals(WebCallConfiguration.MINIMUM_WAITING_CALLS_PER_LISTENER, low.waitingCallsPerListener());
        assertEquals(WebCallConfiguration.MINIMUM_CACHED_CALLS, low.maximumCachedCalls());
        assertEquals(WebCallConfiguration.MINIMUM_CACHED_AUDIO_MIB, low.maximumCachedAudioMiB());

        WebCallConfiguration high = new WebCallConfiguration(Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(WebCallConfiguration.MAXIMUM_LISTENERS, high.maximumListeners());
        assertEquals(WebCallConfiguration.MAXIMUM_SELECTED_SCAN_LISTS, high.maximumSelectedScanLists());
        assertEquals(WebCallConfiguration.MAXIMUM_WAITING_CALLS_PER_LISTENER, high.waitingCallsPerListener());
        assertEquals(WebCallConfiguration.MAXIMUM_CACHED_CALLS, high.maximumCachedCalls());
        assertEquals(WebCallConfiguration.MAXIMUM_CACHED_AUDIO_MIB, high.maximumCachedAudioMiB());
    }
}
