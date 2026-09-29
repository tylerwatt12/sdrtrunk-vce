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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.prefs.Preferences;

/**
 * User preferences for configuration data
 */
public class RecordPreference extends Preference
{
    private static final String PREFERENCE_KEY_AUDIO_RECORD_FORMAT = "audio.record.format";
    private static final String PREFERENCE_KEY_RECORDING_MODE = "audio.record.mode";
    private static final String PREFERENCE_KEY_MANAGED_RETENTION_DAYS = "audio.record.managed.retention.days";
    public static final int MAX_MANAGED_RETENTION_DAYS = 3650;
    private static final RecordFormat DEFAULT_RECORD_FORMAT = RecordFormat.MP3;
    private static final Logger mLog = LoggerFactory.getLogger(RecordPreference.class);
    private Preferences mPreferences = Preferences.userNodeForPackage(RecordPreference.class);
    private RecordFormat mAudioRecordFormat;
    private volatile RecordingMode mRecordingMode;
    private volatile Integer mManagedRetentionDays;
    private volatile boolean mManagedRetentionLoaded;

    /**
     * Constructs this preference with an update listener
     * @param updateListener to receive notifications whenever these preferences change
     */
    public RecordPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
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
     * Mode for newly completed calls. The value is loaded before the receiver starts, so reading it from the
     * completed-call handoff does not access Java Preferences or the filesystem.
     */
    public RecordingMode getRecordingMode()
    {
        RecordingMode mode = mRecordingMode;

        if(mode == null)
        {
            synchronized(this)
            {
                mode = mRecordingMode;

                if(mode == null)
                {
                    try
                    {
                        mode = RecordingMode.valueOf(mPreferences.get(PREFERENCE_KEY_RECORDING_MODE,
                            RecordingMode.CLASSIC.name()));
                    }
                    catch(IllegalArgumentException exception)
                    {
                        mLog.warn("Unrecognized recording mode preference; using Classic");
                        mode = RecordingMode.CLASSIC;
                    }

                    mRecordingMode = mode;
                }
            }
        }

        return mode;
    }

    /** Changes the destination of calls completed after this update. */
    public synchronized void setRecordingMode(RecordingMode mode)
    {
        if(mode == null)
        {
            throw new IllegalArgumentException("Recording mode is required");
        }

        mPreferences.put(PREFERENCE_KEY_RECORDING_MODE, mode.name());
        mRecordingMode = mode;
        notifyPreferenceUpdated();
    }

    /** Maximum age for cataloged managed calls, or null when automatic age pruning is disabled. */
    public Integer getManagedRetentionDays()
    {
        if(!mManagedRetentionLoaded)
        {
            synchronized(this)
            {
                if(!mManagedRetentionLoaded)
                {
                    int days = mPreferences.getInt(PREFERENCE_KEY_MANAGED_RETENTION_DAYS, 0);
                    mManagedRetentionDays = days >= 1 && days <= MAX_MANAGED_RETENTION_DAYS ? days : null;
                    mManagedRetentionLoaded = true;
                }
            }
        }

        return mManagedRetentionDays;
    }

    /** Retention continues in Classic mode for calls previously saved in Managed mode. */
    public synchronized void setManagedRetentionDays(Integer days)
    {
        if(days != null && (days < 1 || days > MAX_MANAGED_RETENTION_DAYS))
        {
            throw new IllegalArgumentException("Managed recording retention must be between 1 and 3650 days");
        }

        if(days == null)
        {
            mPreferences.remove(PREFERENCE_KEY_MANAGED_RETENTION_DAYS);
        }
        else
        {
            mPreferences.putInt(PREFERENCE_KEY_MANAGED_RETENTION_DAYS, days);
        }

        mManagedRetentionDays = days;
        mManagedRetentionLoaded = true;
        notifyPreferenceUpdated();
    }
}
