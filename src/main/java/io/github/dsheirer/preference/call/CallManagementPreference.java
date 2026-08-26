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
import java.util.prefs.Preferences;

/** User preferences for completed-call routing. */
public class CallManagementPreference extends Preference
{
    private static final String PREFERENCE_KEY_PATCHGROUP_STREAMING = "patchgroup.streaming";
    private final Preferences mPreferences = Preferences.userNodeForPackage(CallManagementPreference.class);
    private PatchGroupStreamingOption mPatchGroupStreamingOption;

    public CallManagementPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
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
}
