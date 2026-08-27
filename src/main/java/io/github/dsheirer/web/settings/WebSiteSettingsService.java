/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.settings;

import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import java.util.Objects;
import java.util.prefs.BackingStoreException;

/** Receiver-wide settings service over their sole authoritative preference owner. */
public final class WebSiteSettingsService
{
    private final NowPlayingPreference mNowPlaying;

    public WebSiteSettingsService(NowPlayingPreference nowPlaying)
    {
        mNowPlaying = Objects.requireNonNull(nowPlaying, "Now-playing preference cannot be null");
    }

    public Snapshot snapshot()
    {
        NowPlayingPreference.SiteSettingsSnapshot snapshot = mNowPlaying.getSiteSettingsSnapshot();
        return new Snapshot(snapshot.revision(), Settings.from(snapshot.settings()));
    }

    /** Replaces all site settings only when the caller still owns the current positive revision. */
    public synchronized ReplaceResult replace(long expectedRevision, Settings settings) throws BackingStoreException
    {
        Objects.requireNonNull(settings, "Site settings cannot be null");
        if(expectedRevision < 1)
        {
            throw new IllegalArgumentException("Expected site-settings revision must be positive");
        }
        NowPlayingPreference.SiteSettingsUpdate result =
            mNowPlaying.replaceSiteSettings(expectedRevision, settings.toPreference());
        NowPlayingPreference.SiteSettingsSnapshot snapshot = result.snapshot();
        return new ReplaceResult(result.updated(),
            new Snapshot(snapshot.revision(), Settings.from(snapshot.settings())));
    }

    public record Snapshot(long revision, Settings settings)
    {
        public Snapshot
        {
            if(revision < 1)
            {
                throw new IllegalArgumentException("Site-settings revision must be positive");
            }
            Objects.requireNonNull(settings, "Site settings cannot be null");
        }
    }

    public record ReplaceResult(boolean updated, Snapshot snapshot)
    {
        public ReplaceResult
        {
            Objects.requireNonNull(snapshot, "Site-settings snapshot cannot be null");
        }
    }

    public record Settings(boolean retainIdleCallDetails, boolean clearVoiceDecodeQualityOnCallEnd,
                           int trafficGrantAgeOutMilliseconds)
    {
        public Settings
        {
            new NowPlayingPreference.SiteSettings(retainIdleCallDetails, clearVoiceDecodeQualityOnCallEnd,
                trafficGrantAgeOutMilliseconds);
        }

        private static Settings from(NowPlayingPreference.SiteSettings settings)
        {
            return new Settings(settings.retainIdleCallDetails(), settings.clearVoiceDecodeQualityOnCallEnd(),
                settings.trafficGrantAgeOutMilliseconds());
        }

        private NowPlayingPreference.SiteSettings toPreference()
        {
            return new NowPlayingPreference.SiteSettings(retainIdleCallDetails, clearVoiceDecodeQualityOnCallEnd,
                trafficGrantAgeOutMilliseconds);
        }
    }
}
