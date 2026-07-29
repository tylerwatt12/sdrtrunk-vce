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
    private static final String PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS =
        "traffic.grant.age.out.milliseconds";
    private static final String PREFERENCE_KEY_SHOW_CONTROL_DECODE_QUALITY = "show.control.decode.quality";
    private static final String PREFERENCE_KEY_SHOW_VOICE_DECODE_QUALITY = "show.voice.decode.quality";
    private static final String PREFERENCE_KEY_CLEAR_VOICE_DECODE_QUALITY_ON_CALL_END =
        "clear.voice.decode.quality.on.call.end";
    private static final String PREFERENCE_KEY_DECODE_QUALITY_DISPLAY_MODE = "decode.quality.display.mode";

    public static final int MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 100;
    public static final int MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 15000;
    public static final int DEFAULT_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 1000;

    private final Preferences mPreferences = Preferences.userNodeForPackage(NowPlayingPreference.class);
    private Boolean mRetainIdleCallDetails;
    private Boolean mAdvancedP25EncryptionStatus;
    private Integer mTrafficGrantAgeOutMilliseconds;
    private Boolean mShowControlDecodeQuality;
    private Boolean mShowVoiceDecodeQuality;
    private Boolean mClearVoiceDecodeQualityOnCallEnd;
    private DecodeQualityDisplayMode mDecodeQualityDisplayMode;

    public enum DecodeQualityDisplayMode
    {
        PERCENTAGE("Percentage"),
        DETAILED("Detailed");

        private final String mLabel;

        DecodeQualityDisplayMode(String label)
        {
            mLabel = label;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }

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

    public boolean isShowControlDecodeQuality()
    {
        if(mShowControlDecodeQuality == null)
        {
            mShowControlDecodeQuality = mPreferences.getBoolean(PREFERENCE_KEY_SHOW_CONTROL_DECODE_QUALITY, true);
        }

        return mShowControlDecodeQuality;
    }

    public void setShowControlDecodeQuality(boolean show)
    {
        mShowControlDecodeQuality = show;
        mPreferences.putBoolean(PREFERENCE_KEY_SHOW_CONTROL_DECODE_QUALITY, show);
        notifyPreferenceUpdated();
    }

    public boolean isShowVoiceDecodeQuality()
    {
        if(mShowVoiceDecodeQuality == null)
        {
            mShowVoiceDecodeQuality = mPreferences.getBoolean(PREFERENCE_KEY_SHOW_VOICE_DECODE_QUALITY, true);
        }

        return mShowVoiceDecodeQuality;
    }

    public void setShowVoiceDecodeQuality(boolean show)
    {
        mShowVoiceDecodeQuality = show;
        mPreferences.putBoolean(PREFERENCE_KEY_SHOW_VOICE_DECODE_QUALITY, show);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if the transient voice-channel decode quality should be cleared when its call completes.
     */
    public boolean isClearVoiceDecodeQualityOnCallEnd()
    {
        if(mClearVoiceDecodeQualityOnCallEnd == null)
        {
            mClearVoiceDecodeQualityOnCallEnd = mPreferences.getBoolean(
                PREFERENCE_KEY_CLEAR_VOICE_DECODE_QUALITY_ON_CALL_END, false);
        }

        return mClearVoiceDecodeQualityOnCallEnd;
    }

    /**
     * Sets whether voice-channel decode quality is cleared when its call completes.
     */
    public void setClearVoiceDecodeQualityOnCallEnd(boolean clear)
    {
        mClearVoiceDecodeQualityOnCallEnd = clear;
        mPreferences.putBoolean(PREFERENCE_KEY_CLEAR_VOICE_DECODE_QUALITY_ON_CALL_END, clear);
        notifyPreferenceUpdated();
    }

    public DecodeQualityDisplayMode getDecodeQualityDisplayMode()
    {
        if(mDecodeQualityDisplayMode == null)
        {
            try
            {
                mDecodeQualityDisplayMode = DecodeQualityDisplayMode.valueOf(mPreferences.get(
                    PREFERENCE_KEY_DECODE_QUALITY_DISPLAY_MODE, DecodeQualityDisplayMode.PERCENTAGE.name()));
            }
            catch(IllegalArgumentException _)
            {
                mDecodeQualityDisplayMode = DecodeQualityDisplayMode.PERCENTAGE;
            }
        }

        return mDecodeQualityDisplayMode;
    }

    public void setDecodeQualityDisplayMode(DecodeQualityDisplayMode mode)
    {
        mDecodeQualityDisplayMode = mode != null ? mode : DecodeQualityDisplayMode.PERCENTAGE;
        mPreferences.put(PREFERENCE_KEY_DECODE_QUALITY_DISPLAY_MODE, mDecodeQualityDisplayMode.name());
        notifyPreferenceUpdated();
    }

    private int clamp(int value, int minimum, int maximum)
    {
        return Math.min(maximum, Math.max(minimum, value));
    }
}
