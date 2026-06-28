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
    private static final String PREFERENCE_KEY_SYMBOL_GRAPH_HANG_MILLISECONDS = "symbol.graph.hang.milliseconds";
    private static final String PREFERENCE_KEY_P25_CLASSIFICATION_DELAY_MILLISECONDS =
        "p25.classification.delay.milliseconds";
    private static final String PREFERENCE_KEY_CONTROL_DECODE_HANG_MILLISECONDS = "control.decode.hang.milliseconds";
    private static final String PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS =
        "traffic.grant.age.out.milliseconds";
    private static final String PREFERENCE_KEY_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS =
        "activity.sweeper.interval.milliseconds";

    public static final int MIN_SYMBOL_GRAPH_HANG_MILLISECONDS = 100;
    public static final int MAX_SYMBOL_GRAPH_HANG_MILLISECONDS = 2000;
    public static final int DEFAULT_SYMBOL_GRAPH_HANG_MILLISECONDS = 1000;
    public static final int MIN_P25_CLASSIFICATION_DELAY_MILLISECONDS = 0;
    public static final int MAX_P25_CLASSIFICATION_DELAY_MILLISECONDS = 10000;
    public static final int DEFAULT_P25_CLASSIFICATION_DELAY_MILLISECONDS = 500;
    public static final int MIN_CONTROL_DECODE_HANG_MILLISECONDS = 0;
    public static final int MAX_CONTROL_DECODE_HANG_MILLISECONDS = 60000;
    public static final int DEFAULT_CONTROL_DECODE_HANG_MILLISECONDS = 15000;
    public static final int MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 0;
    public static final int MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 15000;
    public static final int DEFAULT_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 1000;
    public static final int MIN_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS = 25;
    public static final int MAX_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS = 5000;
    public static final int DEFAULT_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS = 250;

    private final Preferences mPreferences = Preferences.userNodeForPackage(NowPlayingPreference.class);
    private Boolean mRetainIdleCallDetails;
    private Boolean mAdvancedP25EncryptionStatus;
    private Integer mSymbolGraphHangMilliseconds;
    private Integer mP25ClassificationDelayMilliseconds;
    private Integer mControlDecodeHangMilliseconds;
    private Integer mTrafficGrantAgeOutMilliseconds;
    private Integer mActivitySweeperIntervalMilliseconds;

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
     * Symbol graph hang time in milliseconds.  This prevents short-lived decoder gaps from blanking the graph.
     */
    public int getSymbolGraphHangMilliseconds()
    {
        if(mSymbolGraphHangMilliseconds == null)
        {
            mSymbolGraphHangMilliseconds = clampSymbolGraphHang(mPreferences.getInt(
                PREFERENCE_KEY_SYMBOL_GRAPH_HANG_MILLISECONDS, DEFAULT_SYMBOL_GRAPH_HANG_MILLISECONDS));
        }

        return mSymbolGraphHangMilliseconds;
    }

    /**
     * Sets symbol graph hang time in milliseconds.
     */
    public void setSymbolGraphHangMilliseconds(int milliseconds)
    {
        mSymbolGraphHangMilliseconds = clampSymbolGraphHang(milliseconds);
        mPreferences.putInt(PREFERENCE_KEY_SYMBOL_GRAPH_HANG_MILLISECONDS, mSymbolGraphHangMilliseconds);
        notifyPreferenceUpdated();
    }

    private int clampSymbolGraphHang(int milliseconds)
    {
        return Math.min(MAX_SYMBOL_GRAPH_HANG_MILLISECONDS,
            Math.max(MIN_SYMBOL_GRAPH_HANG_MILLISECONDS, milliseconds));
    }

    public int getP25ClassificationDelayMilliseconds()
    {
        if(mP25ClassificationDelayMilliseconds == null)
        {
            mP25ClassificationDelayMilliseconds = clamp(mPreferences.getInt(
                PREFERENCE_KEY_P25_CLASSIFICATION_DELAY_MILLISECONDS, DEFAULT_P25_CLASSIFICATION_DELAY_MILLISECONDS),
                MIN_P25_CLASSIFICATION_DELAY_MILLISECONDS, MAX_P25_CLASSIFICATION_DELAY_MILLISECONDS);
        }

        return mP25ClassificationDelayMilliseconds;
    }

    public void setP25ClassificationDelayMilliseconds(int milliseconds)
    {
        mP25ClassificationDelayMilliseconds = clamp(milliseconds, MIN_P25_CLASSIFICATION_DELAY_MILLISECONDS,
            MAX_P25_CLASSIFICATION_DELAY_MILLISECONDS);
        mPreferences.putInt(PREFERENCE_KEY_P25_CLASSIFICATION_DELAY_MILLISECONDS,
            mP25ClassificationDelayMilliseconds);
        notifyPreferenceUpdated();
    }

    public int getControlDecodeHangMilliseconds()
    {
        if(mControlDecodeHangMilliseconds == null)
        {
            mControlDecodeHangMilliseconds = clamp(mPreferences.getInt(PREFERENCE_KEY_CONTROL_DECODE_HANG_MILLISECONDS,
                DEFAULT_CONTROL_DECODE_HANG_MILLISECONDS), MIN_CONTROL_DECODE_HANG_MILLISECONDS,
                MAX_CONTROL_DECODE_HANG_MILLISECONDS);
        }

        return mControlDecodeHangMilliseconds;
    }

    public void setControlDecodeHangMilliseconds(int milliseconds)
    {
        mControlDecodeHangMilliseconds = clamp(milliseconds, MIN_CONTROL_DECODE_HANG_MILLISECONDS,
            MAX_CONTROL_DECODE_HANG_MILLISECONDS);
        mPreferences.putInt(PREFERENCE_KEY_CONTROL_DECODE_HANG_MILLISECONDS, mControlDecodeHangMilliseconds);
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

    public int getActivitySweeperIntervalMilliseconds()
    {
        if(mActivitySweeperIntervalMilliseconds == null)
        {
            mActivitySweeperIntervalMilliseconds = clamp(mPreferences.getInt(
                PREFERENCE_KEY_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS,
                DEFAULT_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS), MIN_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS,
                MAX_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS);
        }

        return mActivitySweeperIntervalMilliseconds;
    }

    public void setActivitySweeperIntervalMilliseconds(int milliseconds)
    {
        mActivitySweeperIntervalMilliseconds = clamp(milliseconds, MIN_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS,
            MAX_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS);
        mPreferences.putInt(PREFERENCE_KEY_ACTIVITY_SWEEPER_INTERVAL_MILLISECONDS,
            mActivitySweeperIntervalMilliseconds);
        notifyPreferenceUpdated();
    }

    private int clamp(int value, int minimum, int maximum)
    {
        return Math.min(maximum, Math.max(minimum, value));
    }
}
