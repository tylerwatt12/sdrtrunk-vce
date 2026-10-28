/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.preference.call;

import io.github.dsheirer.audio.broadcast.PatchGroupStreamingOption;
import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.util.Objects;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** User preferences for completed-call routing. */
public class CallManagementPreference extends Preference
{
    /** Keep the surviving patch-group setting on its supported historical portable-preferences node. */
    static final String PREFERENCE_NODE_PATH = "/io/github/dsheirer/preference/duplicate";
    private static final String PREFERENCE_KEY_PATCHGROUP_STREAMING = "patchgroup.streaming";
    private static final String PREFERENCE_KEY_CALL_FILTERING_ENABLED = "call.filtering.enabled";
    private static final String PREFERENCE_KEY_MINIMUM_USABLE_VOICE_PERCENT = "call.filtering.minimum.usable.voice.percent";
    private final Preferences mPreferences;
    private PatchGroupStreamingOption mPatchGroupStreamingOption;
    private volatile CallFilteringSettings mCallFilteringSettings;

    public CallManagementPreference(Listener<PreferenceType> updateListener)
    {
        this(updateListener, Preferences.userRoot().node(PREFERENCE_NODE_PATH));
    }

    CallManagementPreference(Listener<PreferenceType> updateListener, Preferences preferences)
    {
        super(updateListener);
        mPreferences = Objects.requireNonNull(preferences, "preferences cannot be null");
        String minimum = mPreferences.get(PREFERENCE_KEY_MINIMUM_USABLE_VOICE_PERCENT, null);
        try
        {
            mCallFilteringSettings = new CallFilteringSettings(
                mPreferences.getBoolean(PREFERENCE_KEY_CALL_FILTERING_ENABLED, false),
                minimum != null ? Integer.valueOf(minimum) : null);
        }
        catch(IllegalArgumentException exception)
        {
            mCallFilteringSettings = CallFilteringSettings.DISABLED;
        }
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.CALL_MANAGEMENT;
    }

    public PatchGroupStreamingOption getPatchGroupStreamingOption()
    {
        if(mPatchGroupStreamingOption == null)
        {
            String option = mPreferences.get(PREFERENCE_KEY_PATCHGROUP_STREAMING,
                PatchGroupStreamingOption.PATCH_GROUP.name());

            try
            {
                mPatchGroupStreamingOption = PatchGroupStreamingOption.valueOf(option);
            }
            catch(IllegalArgumentException _)
            {
                mPatchGroupStreamingOption = PatchGroupStreamingOption.PATCH_GROUP;
            }
        }

        return mPatchGroupStreamingOption;
    }

    public void setPatchGroupStreamingOption(PatchGroupStreamingOption patchGroupStreamingOption)
    {
        if(patchGroupStreamingOption != null)
        {
            mPreferences.put(PREFERENCE_KEY_PATCHGROUP_STREAMING, patchGroupStreamingOption.name());
            mPatchGroupStreamingOption = patchGroupStreamingOption;
            notifyPreferenceUpdated();
        }
    }

    /** Decoder and call workers read one already-loaded snapshot without preference-store access. */
    public CallFilteringSettings getCallFilteringSettings()
    {
        return mCallFilteringSettings;
    }

    /** Persists both values before publishing one complete policy to call workers. */
    public synchronized void setCallFilteringSettings(CallFilteringSettings settings) throws BackingStoreException
    {
        Objects.requireNonNull(settings, "Call filtering settings cannot be null");
        CallFilteringSettings previous = mCallFilteringSettings;
        if(previous.equals(settings)) return;
        try
        {
            writeCallFilteringSettings(settings);
            mPreferences.flush();
            mCallFilteringSettings = settings;
        }
        catch(BackingStoreException | RuntimeException exception)
        {
            try
            {
                writeCallFilteringSettings(previous);
                mPreferences.flush();
            }
            catch(BackingStoreException | RuntimeException rollbackException)
            {
                exception.addSuppressed(rollbackException);
            }
            throw exception;
        }
        notifyPreferenceUpdated();
    }

    private void writeCallFilteringSettings(CallFilteringSettings settings)
    {
        if(settings.minimumUsableVoicePercent() == null)
        {
            mPreferences.remove(PREFERENCE_KEY_MINIMUM_USABLE_VOICE_PERCENT);
        }
        else
        {
            mPreferences.putInt(PREFERENCE_KEY_MINIMUM_USABLE_VOICE_PERCENT, settings.minimumUsableVoicePercent());
        }
        mPreferences.putBoolean(PREFERENCE_KEY_CALL_FILTERING_ENABLED, settings.enabled());
    }
}
