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

package io.github.dsheirer.preference.playback;

import io.github.dsheirer.audio.playback.AudioPlaybackDeviceDescriptor;
import io.github.dsheirer.audio.playback.AudioPlaybackDeviceManager;
import io.github.dsheirer.gui.preference.playback.ToneFrequency;
import io.github.dsheirer.gui.preference.playback.ToneUtil;
import io.github.dsheirer.gui.preference.playback.ToneVolume;
import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * User preferences for audio playback
 */
public class PlaybackPreference extends Preference
{
    private static final String PREFERENCE_KEY_USE_AUDIO_SEGMENT_DROP_TONE = "audio.playback.segment.drop.tone";
    private static final String PREFERENCE_KEY_DROP_TONE_FREQUENCY = "audio.playback.segment.drop.frequency";
    private static final String PREFERENCE_KEY_DROP_TONE_VOLUME = "audio.playback.segment.drop.volume";

    private static final String PREFERENCE_KEY_USE_AUDIO_SEGMENT_START_TONE = "audio.playback.segment.start.tone";
    private static final String PREFERENCE_KEY_START_TONE_FREQUENCY = "audio.playback.segment.start.frequency";
    private static final String PREFERENCE_KEY_START_TONE_VOLUME = "audio.playback.segment.start.volume";

    private static final String PREFERENCE_KEY_AUDIO_DEVICE_NAME = "audio.playback.device.name";
    private static final String PREFERENCE_KEY_AUDIO_CHANNEL_COUNT = "audio.playback.channel.count";
    private static final String PREFERENCE_KEY_MAXIMUM_BACKLOGGED_CALLS = "audio.playback.maximum.backlogged.calls";
    private static final String PREFERENCE_KEY_MUTED = "audio.playback.muted";
    public static final int MINIMUM_BACKLOGGED_CALLS = 0;
    public static final int MAXIMUM_BACKLOGGED_CALLS = 10000;
    public static final int DEFAULT_MAXIMUM_BACKLOGGED_CALLS = 500;
    public static final int TONE_LENGTH_SAMPLES = 160;

    private static final Logger mLog = LoggerFactory.getLogger(PlaybackPreference.class);
    private Preferences mPreferences = Preferences.userNodeForPackage(PlaybackPreference.class);
    private Boolean mUseAudioSegmentStartTone;
    private Boolean mUseAudioSegmentDropTone;
    private ToneFrequency mStartToneFrequency;
    private ToneVolume mStartToneVolume;
    private ToneFrequency mDropToneFrequency;
    private ToneVolume mDropToneVolume;
    private AudioPlaybackDeviceDescriptor mAudioPlaybackDeviceDescriptor;
    private Integer mMaximumBackloggedCalls;
    private Boolean mMuted;

    /**
     * Constructs this preference with an update listener
     * @param updateListener to receive notifications whenever these preferences change
     */
    public PlaybackPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.PLAYBACK;
    }

    /**
     * Indicates if an audio segment drop tone should be used.
     */
    public boolean getUseAudioSegmentDropTone()
    {
        if(mUseAudioSegmentDropTone == null)
        {
            mUseAudioSegmentDropTone = mPreferences.getBoolean(PREFERENCE_KEY_USE_AUDIO_SEGMENT_DROP_TONE, true);
        }

        return mUseAudioSegmentDropTone;
    }

    /**
     * Sets the preference for using an audio segment drop tone
     */
    public void setUseAudioSegmentDropTone(boolean use)
    {
        mUseAudioSegmentDropTone = use;
        mPreferences.putBoolean(PREFERENCE_KEY_USE_AUDIO_SEGMENT_DROP_TONE, use);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if an audio segment start tone should be used.
     */
    public boolean getUseAudioSegmentStartTone()
    {
        if(mUseAudioSegmentStartTone == null)
        {
            mUseAudioSegmentStartTone = mPreferences.getBoolean(PREFERENCE_KEY_USE_AUDIO_SEGMENT_START_TONE, true);
        }

        return mUseAudioSegmentStartTone;
    }

    /**
     * Sets the preference for using an audio segment start tone
     */
    public void setUseAudioSegmentStartTone(boolean use)
    {
        mUseAudioSegmentStartTone = use;
        mPreferences.putBoolean(PREFERENCE_KEY_USE_AUDIO_SEGMENT_START_TONE, use);
        notifyPreferenceUpdated();
    }

    /**
     * Frequency for the drop tone
     */
    public ToneFrequency getDropToneFrequency()
    {
        if(mDropToneFrequency == null)
        {
            int frequency = mPreferences.getInt(PREFERENCE_KEY_DROP_TONE_FREQUENCY, ToneFrequency.F500.getValue());
            mDropToneFrequency = ToneFrequency.fromValue(frequency);
        }

        return mDropToneFrequency;
    }

    /**
     * Sets the frequency for the drop tone
     */
    public void setDropToneFrequency(ToneFrequency toneFrequency)
    {
        mDropToneFrequency = toneFrequency;
        mPreferences.putInt(PREFERENCE_KEY_DROP_TONE_FREQUENCY, toneFrequency.getValue());
        notifyPreferenceUpdated();
    }

    /**
     * Frequency for the start tone
     */
    public ToneFrequency getStartToneFrequency()
    {
        if(mStartToneFrequency == null)
        {
            int frequency = mPreferences.getInt(PREFERENCE_KEY_START_TONE_FREQUENCY, ToneFrequency.F700.getValue());
            mStartToneFrequency = ToneFrequency.fromValue(frequency);
        }

        return mStartToneFrequency;
    }

    /**
     * Sets the frequency for the start tone
     */
    public void setStartToneFrequency(ToneFrequency toneFrequency)
    {
        mStartToneFrequency = toneFrequency;
        mPreferences.putInt(PREFERENCE_KEY_START_TONE_FREQUENCY, toneFrequency.getValue());
        notifyPreferenceUpdated();
    }

    /**
     * Drop tone volume
     */
    public ToneVolume getDropToneVolume()
    {
        if(mDropToneVolume == null)
        {
            int volume = mPreferences.getInt(PREFERENCE_KEY_DROP_TONE_VOLUME, ToneVolume.V3.getValue());
            mDropToneVolume = ToneVolume.fromValue(volume);
        }

        return mDropToneVolume;
    }

    /**
     * Sets the drop tone volume
     */
    public void setDropToneVolume(ToneVolume toneVolume)
    {
        mDropToneVolume = toneVolume;
        mPreferences.putInt(PREFERENCE_KEY_DROP_TONE_VOLUME, toneVolume.getValue());
        notifyPreferenceUpdated();
    }

    /**
     * Start tone volume
     */
    public ToneVolume getStartToneVolume()
    {
        if(mStartToneVolume == null)
        {
            int volume = mPreferences.getInt(PREFERENCE_KEY_START_TONE_VOLUME, ToneVolume.V3.getValue());
            mStartToneVolume = ToneVolume.fromValue(volume);
        }

        return mStartToneVolume;
    }

    /**
     * Sets the start tone volume
     */
    public void setStartToneVolume(ToneVolume toneVolume)
    {
        mStartToneVolume = toneVolume;
        mPreferences.putInt(PREFERENCE_KEY_START_TONE_VOLUME, toneVolume.getValue());
        notifyPreferenceUpdated();
    }

    /**
     * Buffer with samples for the audio segment start tone
     */
    public float[] getStartTone()
    {
        if(getUseAudioSegmentStartTone())
        {
            return getStartTone(TONE_LENGTH_SAMPLES);
        }

        return null;
    }

    public float[] getStartTone(int length)
    {
        return ToneUtil.getTone(getStartToneFrequency(), getStartToneVolume(), length);
    }

    /**
     * Buffer with samples for the audio segment drop tone
     */
    public float[] getDropTone()
    {
        if(getUseAudioSegmentDropTone())
        {
            return getDropTone(TONE_LENGTH_SAMPLES);
        }

        return null;
    }

    public float[] getDropTone(int length)
    {
        return ToneUtil.getTone(getDropToneFrequency(), getDropToneVolume(), length);
    }

    /**
     * Test tone to use for testing the currently selected mixer output
     */
    public float[] getAudioPlaybackTestTone()
    {
        return ToneUtil.getTone(ToneFrequency.F1200, ToneVolume.V10, TONE_LENGTH_SAMPLES * 4);
    }

    /**
     * Maximum queued playback calls to retain before dropping oldest queued calls. Zero disables the limit.
     */
    public int getMaximumBackloggedCalls()
    {
        if(mMaximumBackloggedCalls == null)
        {
            mMaximumBackloggedCalls = clampMaximumBackloggedCalls(mPreferences.getInt(
                PREFERENCE_KEY_MAXIMUM_BACKLOGGED_CALLS, DEFAULT_MAXIMUM_BACKLOGGED_CALLS));
        }

        return mMaximumBackloggedCalls;
    }

    /**
     * Sets the maximum queued playback calls to retain before dropping oldest queued calls. Zero disables the limit.
     */
    public void setMaximumBackloggedCalls(int maximumBackloggedCalls)
    {
        mMaximumBackloggedCalls = clampMaximumBackloggedCalls(maximumBackloggedCalls);
        mPreferences.putInt(PREFERENCE_KEY_MAXIMUM_BACKLOGGED_CALLS, mMaximumBackloggedCalls);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if audio playback should start muted.
     */
    public boolean isMuted()
    {
        if(mMuted == null)
        {
            mMuted = mPreferences.getBoolean(PREFERENCE_KEY_MUTED, false);
        }

        return mMuted;
    }

    /**
     * Sets the persisted audio playback mute state.
     */
    public void setMuted(boolean muted)
    {
        mMuted = muted;
        mPreferences.putBoolean(PREFERENCE_KEY_MUTED, muted);
        notifyPreferenceUpdated();
    }

    private int clampMaximumBackloggedCalls(int maximumBackloggedCalls)
    {
        if(maximumBackloggedCalls < MINIMUM_BACKLOGGED_CALLS)
        {
            return MINIMUM_BACKLOGGED_CALLS;
        }

        return Math.min(maximumBackloggedCalls, MAXIMUM_BACKLOGGED_CALLS);
    }

    /**
     * Preferred audio playback device
     */
    public AudioPlaybackDeviceDescriptor getAudioPlaybackDevice()
    {
        if(mAudioPlaybackDeviceDescriptor == null)
        {
            AudioPlaybackDeviceDescriptor descriptor = AudioPlaybackDeviceManager.getDefaultAudioPLaybackDevice();

            if(descriptor != null)
            {
                String name = mPreferences.get(PREFERENCE_KEY_AUDIO_DEVICE_NAME, descriptor.getMixerInfo().getName());
                int channelCount = mPreferences.getInt(PREFERENCE_KEY_AUDIO_CHANNEL_COUNT, descriptor.getAudioFormat().getChannels());
                mAudioPlaybackDeviceDescriptor = AudioPlaybackDeviceManager.getAudioPlaybackDevice(name, channelCount);
            }
            else
            {
                mLog.error("Error - no audio playback devices available");
            }
        }

        return mAudioPlaybackDeviceDescriptor;
    }

    /**
     * Sets the preferred audio playback device
     */
    public void setAudioPlaybackDevice(AudioPlaybackDeviceDescriptor descriptor)
    {
        if(descriptor != null)
        {
            mAudioPlaybackDeviceDescriptor = descriptor;
            mPreferences.put(PREFERENCE_KEY_AUDIO_DEVICE_NAME, descriptor.getMixerInfo().getName());
            mPreferences.putInt(PREFERENCE_KEY_AUDIO_CHANNEL_COUNT, descriptor.getAudioFormat().getChannels());
            notifyPreferenceUpdated();
        }
    }
}
