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
    private static final String PREFERENCE_KEY_TRANSCRIPTION_ENABLED = "audio.record.transcription.enabled";
    private static final String PREFERENCE_KEY_TRANSCRIPTION_URL = "audio.record.transcription.url";
    private static final String PREFERENCE_KEY_TRANSCRIPTION_MODEL = "audio.record.transcription.model";
    private static final String PREFERENCE_KEY_TRANSCRIPTION_API_KEY = "audio.record.transcription.api.key";
    private static final String PREFERENCE_KEY_TRANSCRIPTION_MIN_DURATION_MS =
        "audio.record.transcription.min.duration.ms";
    public static final int MAX_MANAGED_RETENTION_DAYS = 3650;
    public static final long DEFAULT_TRANSCRIPTION_MIN_DURATION_MS = 500;
    public static final long MAX_TRANSCRIPTION_MIN_DURATION_MS = 600_000;
    private static final RecordFormat DEFAULT_RECORD_FORMAT = RecordFormat.MP3;
    private static final Logger mLog = LoggerFactory.getLogger(RecordPreference.class);
    private final Preferences mPreferences;
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
        this(updateListener, Preferences.userNodeForPackage(RecordPreference.class));
    }

    RecordPreference(Listener<PreferenceType> updateListener, Preferences preferences)
    {
        super(updateListener);
        mPreferences = preferences;
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

    /** The explicitly saved choice, or null when only the default applies or the saved value is invalid. */
    public RecordingMode getConfiguredRecordingMode()
    {
        String saved = mPreferences.get(PREFERENCE_KEY_RECORDING_MODE, null);
        if(saved == null) return null;
        try
        {
            return RecordingMode.valueOf(saved);
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
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

    /** Transcription is opt-in, including when Managed Recordings is already enabled. */
    public boolean isTranscriptionEnabled()
    {
        return mPreferences.getBoolean(PREFERENCE_KEY_TRANSCRIPTION_ENABLED, false);
    }

    public void setTranscriptionEnabled(boolean enabled)
    {
        mPreferences.putBoolean(PREFERENCE_KEY_TRANSCRIPTION_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    /** Full HTTP(S) POST endpoint, including /v1/audio/transcriptions. */
    public String getTranscriptionUrl()
    {
        return mPreferences.get(PREFERENCE_KEY_TRANSCRIPTION_URL, "");
    }

    public void setTranscriptionUrl(String url)
    {
        if(url == null)
        {
            throw new IllegalArgumentException("Transcription URL is required");
        }
        mPreferences.put(PREFERENCE_KEY_TRANSCRIPTION_URL, url);
        notifyPreferenceUpdated();
    }

    public String getTranscriptionModel()
    {
        return mPreferences.get(PREFERENCE_KEY_TRANSCRIPTION_MODEL, "");
    }

    public void setTranscriptionModel(String model)
    {
        if(model == null)
        {
            throw new IllegalArgumentException("Transcription model is required");
        }
        mPreferences.put(PREFERENCE_KEY_TRANSCRIPTION_MODEL, model);
        notifyPreferenceUpdated();
    }

    /** Access only from the transcription worker. Never include this value in a web response or log. */
    public String getTranscriptionApiKey()
    {
        return mPreferences.get(PREFERENCE_KEY_TRANSCRIPTION_API_KEY, "");
    }

    public boolean isTranscriptionApiKeyConfigured()
    {
        return !getTranscriptionApiKey().isEmpty();
    }

    public void setTranscriptionApiKey(String key)
    {
        if(key == null || key.isEmpty())
        {
            mPreferences.remove(PREFERENCE_KEY_TRANSCRIPTION_API_KEY);
        }
        else
        {
            mPreferences.put(PREFERENCE_KEY_TRANSCRIPTION_API_KEY, key);
        }
        notifyPreferenceUpdated();
    }

    public long getTranscriptionMinimumDurationMs()
    {
        long stored = mPreferences.getLong(PREFERENCE_KEY_TRANSCRIPTION_MIN_DURATION_MS,
            DEFAULT_TRANSCRIPTION_MIN_DURATION_MS);
        return stored >= DEFAULT_TRANSCRIPTION_MIN_DURATION_MS && stored <= MAX_TRANSCRIPTION_MIN_DURATION_MS ?
            stored : DEFAULT_TRANSCRIPTION_MIN_DURATION_MS;
    }

    public void setTranscriptionMinimumDurationMs(long minimum)
    {
        if(minimum < DEFAULT_TRANSCRIPTION_MIN_DURATION_MS || minimum > MAX_TRANSCRIPTION_MIN_DURATION_MS)
        {
            throw new IllegalArgumentException("Minimum transcription duration must be 500 to 600000 milliseconds");
        }
        mPreferences.putLong(PREFERENCE_KEY_TRANSCRIPTION_MIN_DURATION_MS, minimum);
        notifyPreferenceUpdated();
    }
}
