/*
 * *****************************************************************************
 *  Copyright (C) 2014-2020 Dennis Sheirer
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

package io.github.dsheirer.preference.record;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.record.RecordFormat;
import io.github.dsheirer.sample.Listener;
import java.util.Objects;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * User preferences for configuration data
 */
public class RecordPreference extends Preference
{
    private static final String PREFERENCE_KEY_AUDIO_RECORD_FORMAT = "audio.record.format";
    private static final String PREFERENCE_KEY_RECORDED_CALL_RETENTION_DAYS =
        "recorded.call.retention.days";
    private static final String PREFERENCE_KEY_RECORDED_CALL_MAXIMUM_RETAINED_MIB =
        "recorded.call.maximum.retained.mib";
    private static final RecordFormat DEFAULT_RECORD_FORMAT = RecordFormat.MP3;
    public static final int MINIMUM_RECORDED_CALL_RETENTION_DAYS = 1;
    public static final int MAXIMUM_RECORDED_CALL_RETENTION_DAYS = 3_650;
    public static final int DEFAULT_RECORDED_CALL_RETENTION_DAYS = 30;
    public static final int MINIMUM_RECORDED_CALL_MAXIMUM_RETAINED_MIB = 1;
    public static final int MAXIMUM_RECORDED_CALL_MAXIMUM_RETAINED_MIB = 16 * 1024 * 1024;
    public static final int DEFAULT_RECORDED_CALL_MAXIMUM_RETAINED_MIB = 2_000;
    private static final long BYTES_PER_MIB = 1024L * 1024L;
    private static final Logger mLog = LoggerFactory.getLogger(RecordPreference.class);
    private final Preferences mPreferences;
    private RecordFormat mAudioRecordFormat;
    private Integer mRecordedCallRetentionDays;
    private Integer mRecordedCallMaximumRetainedMiB;

    /**
     * Constructs this preference with an update listener
     * @param updateListener to receive notifications whenever these preferences change
     */
    public RecordPreference(Listener<PreferenceType> updateListener)
    {
        this(updateListener, Preferences.userNodeForPackage(RecordPreference.class));
    }

    /**
     * Constructs an instance with an isolated preference node for testing.
     */
    RecordPreference(Listener<PreferenceType> updateListener, Preferences preferences)
    {
        super(updateListener);
        mPreferences = Objects.requireNonNull(preferences, "Preferences cannot be null");
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.RECORD;
    }


    /**
     * Audio recording format
     */
    public RecordFormat getAudioRecordFormat()
    {
        if(mAudioRecordFormat == null)
        {
            try
            {
                String format = mPreferences.get(PREFERENCE_KEY_AUDIO_RECORD_FORMAT, DEFAULT_RECORD_FORMAT.name());
                mAudioRecordFormat = RecordFormat.valueOf(format);
            }
            catch(Exception e)
            {
                mLog.error("Error parsing record format preference", e);
            }

            if(mAudioRecordFormat == null)
            {
                mAudioRecordFormat = DEFAULT_RECORD_FORMAT;
            }
        }

        return mAudioRecordFormat;
    }

    /**
     * Sets the audio recording format
     */
    public void setAudioRecordFormat(RecordFormat audioRecordFormat)
    {
        mAudioRecordFormat = audioRecordFormat;
        mPreferences.put(PREFERENCE_KEY_AUDIO_RECORD_FORMAT, audioRecordFormat.name());
        notifyPreferenceUpdated();
    }

    /**
     * Number of days that recorded-call audio and its catalog entry are retained.
     */
    public int getRecordedCallRetentionDays()
    {
        if(mRecordedCallRetentionDays == null)
        {
            mRecordedCallRetentionDays = clamp(mPreferences.getInt(
                PREFERENCE_KEY_RECORDED_CALL_RETENTION_DAYS, DEFAULT_RECORDED_CALL_RETENTION_DAYS),
                MINIMUM_RECORDED_CALL_RETENTION_DAYS, MAXIMUM_RECORDED_CALL_RETENTION_DAYS);
        }

        return mRecordedCallRetentionDays;
    }

    /**
     * Sets the recorded-call retention period independently of activity-history retention.
     */
    public void setRecordedCallRetentionDays(int retentionDays)
    {
        mRecordedCallRetentionDays = clamp(retentionDays, MINIMUM_RECORDED_CALL_RETENTION_DAYS,
            MAXIMUM_RECORDED_CALL_RETENTION_DAYS);
        mPreferences.putInt(PREFERENCE_KEY_RECORDED_CALL_RETENTION_DAYS, mRecordedCallRetentionDays);
        notifyPreferenceUpdated();
    }

    /**
     * Maximum retained recorded-call storage in mebibytes.
     */
    public int getRecordedCallMaximumRetainedMiB()
    {
        if(mRecordedCallMaximumRetainedMiB == null)
        {
            mRecordedCallMaximumRetainedMiB = clamp(mPreferences.getInt(
                PREFERENCE_KEY_RECORDED_CALL_MAXIMUM_RETAINED_MIB,
                DEFAULT_RECORDED_CALL_MAXIMUM_RETAINED_MIB),
                MINIMUM_RECORDED_CALL_MAXIMUM_RETAINED_MIB,
                MAXIMUM_RECORDED_CALL_MAXIMUM_RETAINED_MIB);
        }

        return mRecordedCallMaximumRetainedMiB;
    }

    /**
     * Maximum retained recorded-call storage in bytes.
     */
    public long getRecordedCallMaximumRetainedBytes()
    {
        return getRecordedCallMaximumRetainedMiB() * BYTES_PER_MIB;
    }

    /**
     * Sets the explicit recorded-call storage limit in mebibytes.  This is separate from the recordings-directory
     * warning threshold.
     */
    public void setRecordedCallMaximumRetainedMiB(int maximumRetainedMiB)
    {
        mRecordedCallMaximumRetainedMiB = clamp(maximumRetainedMiB,
            MINIMUM_RECORDED_CALL_MAXIMUM_RETAINED_MIB,
            MAXIMUM_RECORDED_CALL_MAXIMUM_RETAINED_MIB);
        mPreferences.putInt(PREFERENCE_KEY_RECORDED_CALL_MAXIMUM_RETAINED_MIB,
            mRecordedCallMaximumRetainedMiB);
        notifyPreferenceUpdated();
    }

    private static int clamp(int value, int minimum, int maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
