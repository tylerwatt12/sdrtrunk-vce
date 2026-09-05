/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.settings;

import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import java.util.Objects;
import java.util.prefs.BackingStoreException;

/** Receiver-wide traffic-timing service over its sole authoritative preference owner. */
public final class WebReceiverSettingsService
{
    private final NowPlayingPreference mNowPlaying;

    public WebReceiverSettingsService(NowPlayingPreference nowPlaying)
    {
        mNowPlaying = Objects.requireNonNull(nowPlaying, "Now-playing preference cannot be null");
    }

    public Snapshot snapshot()
    {
        NowPlayingPreference.ReceiverSettingsSnapshot snapshot = mNowPlaying.getReceiverSettingsSnapshot();
        return new Snapshot(snapshot.revision(), Settings.from(snapshot.settings()));
    }

    /** Replaces all receiver settings only when the caller still owns the current positive revision. */
    public synchronized ReplaceResult replace(long expectedRevision, Settings settings) throws BackingStoreException
    {
        Objects.requireNonNull(settings, "Receiver settings cannot be null");
        if(expectedRevision < 1)
        {
            throw new IllegalArgumentException("Expected receiver-settings revision must be positive");
        }
        NowPlayingPreference.ReceiverSettingsUpdate result =
            mNowPlaying.replaceReceiverSettings(expectedRevision, settings.toPreference());
        NowPlayingPreference.ReceiverSettingsSnapshot snapshot = result.snapshot();
        return new ReplaceResult(result.updated(),
            new Snapshot(snapshot.revision(), Settings.from(snapshot.settings())));
    }

    public record Snapshot(long revision, Settings settings)
    {
        public Snapshot
        {
            if(revision < 1)
            {
                throw new IllegalArgumentException("Receiver-settings revision must be positive");
            }
            Objects.requireNonNull(settings, "Receiver settings cannot be null");
        }
    }

    public record ReplaceResult(boolean updated, Snapshot snapshot)
    {
        public ReplaceResult
        {
            Objects.requireNonNull(snapshot, "Receiver-settings snapshot cannot be null");
        }
    }

    public record Settings(int trafficGrantAgeOutMilliseconds)
    {
        public Settings
        {
            new NowPlayingPreference.ReceiverSettings(trafficGrantAgeOutMilliseconds);
        }

        private static Settings from(NowPlayingPreference.ReceiverSettings settings)
        {
            return new Settings(settings.trafficGrantAgeOutMilliseconds());
        }

        private NowPlayingPreference.ReceiverSettings toPreference()
        {
            return new NowPlayingPreference.ReceiverSettings(trafficGrantAgeOutMilliseconds);
        }
    }
}
