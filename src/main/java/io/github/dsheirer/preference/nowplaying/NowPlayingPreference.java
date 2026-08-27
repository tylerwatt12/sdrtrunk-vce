/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.preference.nowplaying;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.Objects;

/** Receiver-wide call-lifecycle preferences and optional Java desktop views. */
public class NowPlayingPreference extends Preference
{
    public static final String PORTABLE_PREFERENCE_NODE =
        "user/io/github/dsheirer/preference/nowplaying";
    public static final String PREFERENCE_KEY_RETAIN_IDLE_CALL_DETAILS = "retain.idle.call.details";
    public static final String PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS =
        "traffic.grant.age.out.milliseconds";
    public static final String PREFERENCE_KEY_CLEAR_VOICE_DECODE_QUALITY_ON_CALL_END =
        "clear.voice.decode.quality.on.call.end";
    public static final String PREFERENCE_KEY_SITE_SETTINGS_REVISION = "site.settings.revision";

    public static final int MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 100;
    public static final int MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 15000;
    public static final int DEFAULT_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 1000;

    private final Preferences mPreferences = Preferences.userNodeForPackage(NowPlayingPreference.class);
    private volatile SiteSettingsSnapshot mSiteSettings;

    /** Optional Java desktop views that can be independently shown or hidden. */
    public enum JavaInterfaceView
    {
        MAP("Map", "java.tab.map.visible", false);

        private final String mLabel;
        private final String mPreferenceKey;
        private final boolean mDefaultEnabled;

        JavaInterfaceView(String label, String preferenceKey, boolean defaultEnabled)
        {
            mLabel = label;
            mPreferenceKey = preferenceKey;
            mDefaultEnabled = defaultEnabled;
        }

        public String getLabel()
        {
            return mLabel;
        }
    }

    /** One coherent snapshot of the settings that change receiver behavior for everyone. */
    public record SiteSettings(boolean retainIdleCallDetails, boolean clearVoiceDecodeQualityOnCallEnd,
                               int trafficGrantAgeOutMilliseconds)
    {
        public SiteSettings
        {
            if(trafficGrantAgeOutMilliseconds < MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS ||
                trafficGrantAgeOutMilliseconds > MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS)
            {
                throw new IllegalArgumentException("Traffic grant age-out is outside the supported range");
            }
        }
    }

    /** Revisioned receiver-wide settings state used for optimistic web administration updates. */
    public record SiteSettingsSnapshot(long revision, SiteSettings settings)
    {
        public SiteSettingsSnapshot
        {
            if(revision < 1)
            {
                throw new IllegalArgumentException("Site-settings revision must be positive");
            }
            Objects.requireNonNull(settings, "Site settings cannot be null");
        }
    }

    /** Result of an exact-revision replacement. */
    public record SiteSettingsUpdate(boolean updated, SiteSettingsSnapshot snapshot)
    {
        public SiteSettingsUpdate
        {
            Objects.requireNonNull(snapshot, "Site-settings snapshot cannot be null");
        }
    }

    public NowPlayingPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
        long revision = Math.max(1, mPreferences.getLong(PREFERENCE_KEY_SITE_SETTINGS_REVISION, 1));
        mSiteSettings = new SiteSettingsSnapshot(revision, new SiteSettings(
            mPreferences.getBoolean(PREFERENCE_KEY_RETAIN_IDLE_CALL_DETAILS, false),
            mPreferences.getBoolean(PREFERENCE_KEY_CLEAR_VOICE_DECODE_QUALITY_ON_CALL_END, false),
            clamp(mPreferences.getInt(PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS,
                DEFAULT_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS), MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS,
                MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS)));
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.NOW_PLAYING;
    }

    public boolean isRetainIdleCallDetails()
    {
        return mSiteSettings.settings().retainIdleCallDetails();
    }

    public synchronized void setRetainIdleCallDetails(boolean retain)
    {
        SiteSettings current = mSiteSettings.settings();
        SiteSettings updated = new SiteSettings(retain, current.clearVoiceDecodeQualityOnCallEnd(),
            current.trafficGrantAgeOutMilliseconds());
        publishDesktopSiteSettings(updated);
    }

    public boolean isClearVoiceDecodeQualityOnCallEnd()
    {
        return mSiteSettings.settings().clearVoiceDecodeQualityOnCallEnd();
    }

    public synchronized void setClearVoiceDecodeQualityOnCallEnd(boolean clear)
    {
        SiteSettings current = mSiteSettings.settings();
        SiteSettings updated = new SiteSettings(current.retainIdleCallDetails(), clear,
            current.trafficGrantAgeOutMilliseconds());
        publishDesktopSiteSettings(updated);
    }

    public int getTrafficGrantAgeOutMilliseconds()
    {
        return mSiteSettings.settings().trafficGrantAgeOutMilliseconds();
    }

    public synchronized void setTrafficGrantAgeOutMilliseconds(int milliseconds)
    {
        SiteSettings current = mSiteSettings.settings();
        int ageOut = clamp(milliseconds, MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS,
            MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS);
        SiteSettings updated = new SiteSettings(current.retainIdleCallDetails(),
            current.clearVoiceDecodeQualityOnCallEnd(), ageOut);
        publishDesktopSiteSettings(updated);
    }

    public SiteSettings getSiteSettings()
    {
        return mSiteSettings.settings();
    }

    public SiteSettingsSnapshot getSiteSettingsSnapshot()
    {
        return mSiteSettings;
    }

    /** Persists and publishes one complete snapshot only when the caller has the current revision. */
    public synchronized SiteSettingsUpdate replaceSiteSettings(long expectedRevision, SiteSettings settings)
        throws BackingStoreException
    {
        if(settings == null)
        {
            throw new IllegalArgumentException("Site settings cannot be null");
        }

        SiteSettingsSnapshot previous = mSiteSettings;
        if(expectedRevision != previous.revision())
        {
            return new SiteSettingsUpdate(false, previous);
        }
        SiteSettingsSnapshot updated = new SiteSettingsSnapshot(Math.incrementExact(previous.revision()), settings);
        try
        {
            writeSiteSettings(updated);
            mPreferences.flush();
            mSiteSettings = updated;
            notifyPreferenceUpdated();
            return new SiteSettingsUpdate(true, updated);
        }
        catch(BackingStoreException | RuntimeException exception)
        {
            mSiteSettings = previous;
            try
            {
                writeSiteSettings(previous);
                mPreferences.flush();
            }
            catch(BackingStoreException | RuntimeException rollbackException)
            {
                exception.addSuppressed(rollbackException);
            }
            throw exception;
        }
    }

    private void publishDesktopSiteSettings(SiteSettings settings)
    {
        SiteSettingsSnapshot updated = new SiteSettingsSnapshot(Math.incrementExact(mSiteSettings.revision()), settings);
        writeSiteSettings(updated);
        mSiteSettings = updated;
        notifyPreferenceUpdated();
    }

    private void writeSiteSettings(SiteSettingsSnapshot snapshot)
    {
        SiteSettings settings = snapshot.settings();
        mPreferences.putBoolean(PREFERENCE_KEY_RETAIN_IDLE_CALL_DETAILS, settings.retainIdleCallDetails());
        mPreferences.putBoolean(PREFERENCE_KEY_CLEAR_VOICE_DECODE_QUALITY_ON_CALL_END,
            settings.clearVoiceDecodeQualityOnCallEnd());
        mPreferences.putInt(PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS,
            settings.trafficGrantAgeOutMilliseconds());
        mPreferences.putLong(PREFERENCE_KEY_SITE_SETTINGS_REVISION, snapshot.revision());
    }

    public boolean isJavaInterfaceViewEnabled(JavaInterfaceView view)
    {
        return view != null && mPreferences.getBoolean(view.mPreferenceKey, view.mDefaultEnabled);
    }

    public void setJavaInterfaceViewEnabled(JavaInterfaceView view, boolean enabled)
    {
        if(view != null)
        {
            mPreferences.putBoolean(view.mPreferenceKey, enabled);
            notifyPreferenceUpdated();
        }
    }

    private static int clamp(int value, int minimum, int maximum)
    {
        return Math.min(maximum, Math.max(minimum, value));
    }
}
