/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.preference.audio;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.util.Objects;
import java.util.prefs.Preferences;

/** Receiver-wide audio processing preferences shared by browser calls, recordings, and streams. */
public class AudioOutputPreference extends Preference
{
    private static final String CONTINUOUS_AGC_KEY = "audio.output.continuous.agc.enabled";
    private final Preferences mPreferences;
    private volatile boolean mContinuousAgcEnabled;

    public AudioOutputPreference(Listener<PreferenceType> updateListener)
    {
        this(updateListener, Preferences.userNodeForPackage(AudioOutputPreference.class));
    }

    AudioOutputPreference(Listener<PreferenceType> updateListener, Preferences preferences)
    {
        super(updateListener);
        mPreferences = Objects.requireNonNull(preferences);
        mContinuousAgcEnabled = mPreferences.getBoolean(CONTINUOUS_AGC_KEY, false);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.AUDIO_OUTPUT;
    }

    /** Cached at construction and on updates so audio workers never access preference storage. */
    public boolean isContinuousAgcEnabled()
    {
        return mContinuousAgcEnabled;
    }

    public synchronized void setContinuousAgcEnabled(boolean enabled)
    {
        if(enabled != mContinuousAgcEnabled)
        {
            mPreferences.putBoolean(CONTINUOUS_AGC_KEY, enabled);
            mContinuousAgcEnabled = enabled;
            notifyPreferenceUpdated();
        }
    }
}
