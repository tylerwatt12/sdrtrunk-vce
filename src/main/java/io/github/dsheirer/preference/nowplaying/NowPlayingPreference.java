/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.preference.nowplaying;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.util.prefs.Preferences;

/**
 * Preferences for the Now Playing activity view.
 */
public class NowPlayingPreference extends Preference
{
    private static final String PREFERENCE_KEY_RETAIN_IDLE_CALL_DETAILS = "retain.idle.call.details";
    private static final String PREFERENCE_KEY_ADVANCED_P25_ENCRYPTION_STATUS = "advanced.p25.encryption.status";
    private static final String PREFERENCE_KEY_RF_METADATA_DEBUG_TAB = "rf.metadata.debug.tab";
    private static final String PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS =
        "traffic.grant.age.out.milliseconds";

    public static final int MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 100;
    public static final int MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 15000;
    public static final int DEFAULT_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 1000;

    private final Preferences mPreferences = Preferences.userNodeForPackage(NowPlayingPreference.class);
    private Boolean mRetainIdleCallDetails;
    private Boolean mAdvancedP25EncryptionStatus;
    private Boolean mRfMetadataDebugTab;
    private Integer mTrafficGrantAgeOutMilliseconds;

    /**
     * Constructs an instance.
     * @param updateListener to receive notifications that a preference has been updated
     */
    public NowPlayingPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.NOW_PLAYING;
    }

    /**
     * Indicates if idle Now Playing rows retain the last call source/target details.
     */
    public boolean isRetainIdleCallDetails()
    {
        if(mRetainIdleCallDetails == null)
        {
            mRetainIdleCallDetails = mPreferences.getBoolean(PREFERENCE_KEY_RETAIN_IDLE_CALL_DETAILS, false);
        }

        return mRetainIdleCallDetails;
    }

    /**
     * Sets idle row call detail retention.
     */
    public void setRetainIdleCallDetails(boolean retain)
    {
        mRetainIdleCallDetails = retain;
        mPreferences.putBoolean(PREFERENCE_KEY_RETAIN_IDLE_CALL_DETAILS, retain);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if advanced P25 encryption details should replace ENCRYPTED status text.
     */
    public boolean isAdvancedP25EncryptionStatus()
    {
        if(mAdvancedP25EncryptionStatus == null)
        {
            mAdvancedP25EncryptionStatus = mPreferences.getBoolean(PREFERENCE_KEY_ADVANCED_P25_ENCRYPTION_STATUS,
                false);
        }

        return mAdvancedP25EncryptionStatus;
    }

    /**
     * Sets advanced P25 encryption status rendering.
     */
    public void setAdvancedP25EncryptionStatus(boolean advanced)
    {
        mAdvancedP25EncryptionStatus = advanced;
        mPreferences.putBoolean(PREFERENCE_KEY_ADVANCED_P25_ENCRYPTION_STATUS, advanced);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if the RF metadata debug tab should be shown in the Now Playing lower panel.
     */
    public boolean isRfMetadataDebugTabEnabled()
    {
        if(mRfMetadataDebugTab == null)
        {
            mRfMetadataDebugTab = mPreferences.getBoolean(PREFERENCE_KEY_RF_METADATA_DEBUG_TAB, false);
        }

        return mRfMetadataDebugTab;
    }

    /**
     * Sets RF metadata debug tab visibility.
     */
    public void setRfMetadataDebugTabEnabled(boolean enabled)
    {
        mRfMetadataDebugTab = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_RF_METADATA_DEBUG_TAB, enabled);
        notifyPreferenceUpdated();
    }

    public int getTrafficGrantAgeOutMilliseconds()
    {
        if(mTrafficGrantAgeOutMilliseconds == null)
        {
            mTrafficGrantAgeOutMilliseconds = clamp(mPreferences.getInt(
                PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS, DEFAULT_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS),
                MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS, MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS);
        }

        return mTrafficGrantAgeOutMilliseconds;
    }

    public void setTrafficGrantAgeOutMilliseconds(int milliseconds)
    {
        mTrafficGrantAgeOutMilliseconds = clamp(milliseconds, MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS,
            MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS);
        mPreferences.putInt(PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS, mTrafficGrantAgeOutMilliseconds);
        notifyPreferenceUpdated();
    }

    private int clamp(int value, int minimum, int maximum)
    {
        return Math.min(maximum, Math.max(minimum, value));
    }
}
