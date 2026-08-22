/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Small operator-owned capacity policy for completed-call browser audio.  Per-call and per-connection safety
 * ceilings remain code-owned; these values are the useful sizing controls that vary with server resources.
 */
public record WebCallConfiguration(int maximumListeners, int maximumSelectedScanLists,
                                   int waitingCallsPerListener, int maximumCachedCalls,
                                   @JsonProperty("maximum_cached_audio_mib") int maximumCachedAudioMiB)
{
    public static final int DEFAULT_MAXIMUM_LISTENERS = 32;
    public static final int DEFAULT_MAXIMUM_SELECTED_SCAN_LISTS = 16;
    public static final int DEFAULT_WAITING_CALLS_PER_LISTENER = 100;
    public static final int DEFAULT_MAXIMUM_CACHED_CALLS = 512;
    public static final int DEFAULT_MAXIMUM_CACHED_AUDIO_MIB = 128;

    public static final int MINIMUM_LISTENERS = 1;
    /** The web server has a hard 64-client live-HTTP admission ceiling shared by all SSE streams. */
    public static final int MAXIMUM_LISTENERS = 64;
    public static final int MINIMUM_SELECTED_SCAN_LISTS = 1;
    public static final int MAXIMUM_SELECTED_SCAN_LISTS = 64;
    public static final int MINIMUM_WAITING_CALLS_PER_LISTENER = 1;
    public static final int MAXIMUM_WAITING_CALLS_PER_LISTENER = 500;
    public static final int MINIMUM_CACHED_CALLS = 16;
    public static final int MAXIMUM_CACHED_CALLS = 4096;
    public static final int MINIMUM_CACHED_AUDIO_MIB = 16;
    public static final int MAXIMUM_CACHED_AUDIO_MIB = 1024;

    public WebCallConfiguration
    {
        maximumListeners = clamp(maximumListeners, MINIMUM_LISTENERS, MAXIMUM_LISTENERS);
        maximumSelectedScanLists = clamp(maximumSelectedScanLists, MINIMUM_SELECTED_SCAN_LISTS,
            MAXIMUM_SELECTED_SCAN_LISTS);
        waitingCallsPerListener = clamp(waitingCallsPerListener, MINIMUM_WAITING_CALLS_PER_LISTENER,
            MAXIMUM_WAITING_CALLS_PER_LISTENER);
        maximumCachedCalls = clamp(maximumCachedCalls, MINIMUM_CACHED_CALLS, MAXIMUM_CACHED_CALLS);
        maximumCachedAudioMiB = clamp(maximumCachedAudioMiB, MINIMUM_CACHED_AUDIO_MIB,
            MAXIMUM_CACHED_AUDIO_MIB);
    }

    public static WebCallConfiguration defaults()
    {
        return new WebCallConfiguration(DEFAULT_MAXIMUM_LISTENERS, DEFAULT_MAXIMUM_SELECTED_SCAN_LISTS,
            DEFAULT_WAITING_CALLS_PER_LISTENER, DEFAULT_MAXIMUM_CACHED_CALLS,
            DEFAULT_MAXIMUM_CACHED_AUDIO_MIB);
    }

    public long maximumCachedAudioBytes()
    {
        return (long)maximumCachedAudioMiB * 1024L * 1024L;
    }

    private static int clamp(int value, int minimum, int maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
