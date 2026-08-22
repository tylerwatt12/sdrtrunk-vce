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
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Preferences for browser Live activity and optional Java desktop views.
 */
public class NowPlayingPreference extends Preference
{
    private static final String PREFERENCE_KEY_RETAIN_IDLE_CALL_DETAILS = "retain.idle.call.details";
    private static final String PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS =
        "traffic.grant.age.out.milliseconds";
    private static final String PREFERENCE_KEY_SHOW_CONTROL_DECODE_QUALITY = "show.control.decode.quality";
    private static final String PREFERENCE_KEY_SHOW_VOICE_DECODE_QUALITY = "show.voice.decode.quality";
    private static final String PREFERENCE_KEY_CLEAR_VOICE_DECODE_QUALITY_ON_CALL_END =
        "clear.voice.decode.quality.on.call.end";
    private static final String PREFERENCE_KEY_DECODE_QUALITY_DISPLAY_MODE = "decode.quality.display.mode";
    private static final String PREFERENCE_KEY_LIVE_DETAIL_MATCHING_ROW_LIMIT =
        "live.detail.matching.row.limit";

    public static final int MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 100;
    public static final int MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 15000;
    public static final int DEFAULT_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS = 1000;
    public static final int MIN_LIVE_DETAIL_MATCHING_ROW_LIMIT = 25;
    public static final int MAX_LIVE_DETAIL_MATCHING_ROW_LIMIT = 500;
    public static final int DEFAULT_LIVE_DETAIL_MATCHING_ROW_LIMIT = 200;

    private final Preferences mPreferences = Preferences.userNodeForPackage(NowPlayingPreference.class);
    private volatile LiveActivitySettings mLiveActivitySettings;

    /**
     * Optional Java desktop views that can be independently shown or hidden.
     */
    public enum JavaInterfaceView
    {
        MAP("Map", "java.tab.map.visible", false);

        private final String mLabel;
        private final String mPreferenceKey;
        private final boolean mDefaultEnabled;

        JavaInterfaceView(String label, String preferenceKey, boolean defaultEnabled)
        {
            mLabel = label;
            mPreferenceKey = preferenceKey;
            mDefaultEnabled = defaultEnabled;
        }

        public String getLabel()
        {
            return mLabel;
        }
    }

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

    /** One coherent snapshot of the receiver-wide Live activity preferences. */
    public record LiveActivitySettings(boolean retainIdleCallDetails, int trafficGrantAgeOutMilliseconds,
                                       boolean showControlDecodeQuality, boolean showVoiceDecodeQuality,
                                       boolean clearVoiceDecodeQualityOnCallEnd,
                                       DecodeQualityDisplayMode decodeQualityDisplayMode,
                                       int liveDetailMatchingRowLimit)
    {
        public LiveActivitySettings
        {
            if(trafficGrantAgeOutMilliseconds < MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS ||
                trafficGrantAgeOutMilliseconds > MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS)
            {
                throw new IllegalArgumentException("Traffic grant age-out is outside the supported range");
            }

            decodeQualityDisplayMode = decodeQualityDisplayMode != null ? decodeQualityDisplayMode :
                DecodeQualityDisplayMode.PERCENTAGE;

            if(liveDetailMatchingRowLimit < MIN_LIVE_DETAIL_MATCHING_ROW_LIMIT ||
                liveDetailMatchingRowLimit > MAX_LIVE_DETAIL_MATCHING_ROW_LIMIT)
            {
                throw new IllegalArgumentException("Live detail matching row limit is outside the supported range");
            }
        }
    }

    /**
     * Constructs an instance.
     * @param updateListener to receive notifications that a preference has been updated
     */
    public NowPlayingPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
        mLiveActivitySettings = new LiveActivitySettings(
            mPreferences.getBoolean(PREFERENCE_KEY_RETAIN_IDLE_CALL_DETAILS, false),
            clamp(mPreferences.getInt(PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS,
                DEFAULT_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS), MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS,
                MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS),
            mPreferences.getBoolean(PREFERENCE_KEY_SHOW_CONTROL_DECODE_QUALITY, true),
            mPreferences.getBoolean(PREFERENCE_KEY_SHOW_VOICE_DECODE_QUALITY, true),
            mPreferences.getBoolean(PREFERENCE_KEY_CLEAR_VOICE_DECODE_QUALITY_ON_CALL_END, false),
            readDecodeQualityDisplayMode(),
            clamp(mPreferences.getInt(PREFERENCE_KEY_LIVE_DETAIL_MATCHING_ROW_LIMIT,
                DEFAULT_LIVE_DETAIL_MATCHING_ROW_LIMIT), MIN_LIVE_DETAIL_MATCHING_ROW_LIMIT,
                MAX_LIVE_DETAIL_MATCHING_ROW_LIMIT));
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.NOW_PLAYING;
    }

    /**
     * Indicates if idle Live activity rows retain the last call source/target details.
     */
    public boolean isRetainIdleCallDetails()
    {
        return mLiveActivitySettings.retainIdleCallDetails();
    }

    /**
     * Sets idle row call detail retention.
     */
    public synchronized void setRetainIdleCallDetails(boolean retain)
    {
        LiveActivitySettings current = mLiveActivitySettings;
        LiveActivitySettings updated = new LiveActivitySettings(retain,
            current.trafficGrantAgeOutMilliseconds(), current.showControlDecodeQuality(),
            current.showVoiceDecodeQuality(), current.clearVoiceDecodeQualityOnCallEnd(),
            current.decodeQualityDisplayMode(), current.liveDetailMatchingRowLimit());
        mPreferences.putBoolean(PREFERENCE_KEY_RETAIN_IDLE_CALL_DETAILS, retain);
        mLiveActivitySettings = updated;
        notifyPreferenceUpdated();
    }

    public int getTrafficGrantAgeOutMilliseconds()
    {
        return mLiveActivitySettings.trafficGrantAgeOutMilliseconds();
    }

    public synchronized void setTrafficGrantAgeOutMilliseconds(int milliseconds)
    {
        LiveActivitySettings current = mLiveActivitySettings;
        int ageOut = clamp(milliseconds, MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS,
            MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS);
        LiveActivitySettings updated = new LiveActivitySettings(current.retainIdleCallDetails(), ageOut,
            current.showControlDecodeQuality(), current.showVoiceDecodeQuality(),
            current.clearVoiceDecodeQualityOnCallEnd(), current.decodeQualityDisplayMode(),
            current.liveDetailMatchingRowLimit());
        mPreferences.putInt(PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS, ageOut);
        mLiveActivitySettings = updated;
        notifyPreferenceUpdated();
    }

    public boolean isShowControlDecodeQuality()
    {
        return mLiveActivitySettings.showControlDecodeQuality();
    }

    public synchronized void setShowControlDecodeQuality(boolean show)
    {
        LiveActivitySettings current = mLiveActivitySettings;
        LiveActivitySettings updated = new LiveActivitySettings(current.retainIdleCallDetails(),
            current.trafficGrantAgeOutMilliseconds(), show, current.showVoiceDecodeQuality(),
            current.clearVoiceDecodeQualityOnCallEnd(), current.decodeQualityDisplayMode(),
            current.liveDetailMatchingRowLimit());
        mPreferences.putBoolean(PREFERENCE_KEY_SHOW_CONTROL_DECODE_QUALITY, show);
        mLiveActivitySettings = updated;
        notifyPreferenceUpdated();
    }

    public boolean isShowVoiceDecodeQuality()
    {
        return mLiveActivitySettings.showVoiceDecodeQuality();
    }

    public synchronized void setShowVoiceDecodeQuality(boolean show)
    {
        LiveActivitySettings current = mLiveActivitySettings;
        LiveActivitySettings updated = new LiveActivitySettings(current.retainIdleCallDetails(),
            current.trafficGrantAgeOutMilliseconds(), current.showControlDecodeQuality(), show,
            current.clearVoiceDecodeQualityOnCallEnd(), current.decodeQualityDisplayMode(),
            current.liveDetailMatchingRowLimit());
        mPreferences.putBoolean(PREFERENCE_KEY_SHOW_VOICE_DECODE_QUALITY, show);
        mLiveActivitySettings = updated;
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if the transient voice-channel decode quality should be cleared when its call completes.
     */
    public boolean isClearVoiceDecodeQualityOnCallEnd()
    {
        return mLiveActivitySettings.clearVoiceDecodeQualityOnCallEnd();
    }

    /**
     * Sets whether voice-channel decode quality is cleared when its call completes.
     */
    public synchronized void setClearVoiceDecodeQualityOnCallEnd(boolean clear)
    {
        LiveActivitySettings current = mLiveActivitySettings;
        LiveActivitySettings updated = new LiveActivitySettings(current.retainIdleCallDetails(),
            current.trafficGrantAgeOutMilliseconds(), current.showControlDecodeQuality(),
            current.showVoiceDecodeQuality(), clear, current.decodeQualityDisplayMode(),
            current.liveDetailMatchingRowLimit());
        mPreferences.putBoolean(PREFERENCE_KEY_CLEAR_VOICE_DECODE_QUALITY_ON_CALL_END, clear);
        mLiveActivitySettings = updated;
        notifyPreferenceUpdated();
    }

    public DecodeQualityDisplayMode getDecodeQualityDisplayMode()
    {
        return mLiveActivitySettings.decodeQualityDisplayMode();
    }

    public synchronized void setDecodeQualityDisplayMode(DecodeQualityDisplayMode mode)
    {
        LiveActivitySettings current = mLiveActivitySettings;
        DecodeQualityDisplayMode displayMode = mode != null ? mode : DecodeQualityDisplayMode.PERCENTAGE;
        LiveActivitySettings updated = new LiveActivitySettings(current.retainIdleCallDetails(),
            current.trafficGrantAgeOutMilliseconds(), current.showControlDecodeQuality(),
            current.showVoiceDecodeQuality(), current.clearVoiceDecodeQualityOnCallEnd(), displayMode,
            current.liveDetailMatchingRowLimit());
        mPreferences.put(PREFERENCE_KEY_DECODE_QUALITY_DISPLAY_MODE, displayMode.name());
        mLiveActivitySettings = updated;
        notifyPreferenceUpdated();
    }

    public int getLiveDetailMatchingRowLimit()
    {
        return mLiveActivitySettings.liveDetailMatchingRowLimit();
    }

    public LiveActivitySettings getLiveActivitySettings()
    {
        return mLiveActivitySettings;
    }

    /**
     * Persists one complete Live activity snapshot before publishing it to receiver readers.
     */
    public synchronized void setLiveActivitySettings(LiveActivitySettings settings) throws BackingStoreException
    {
        if(settings == null)
        {
            throw new IllegalArgumentException("Live activity settings cannot be null");
        }

        LiveActivitySettings previous = mLiveActivitySettings;

        try
        {
            writeLiveActivitySettings(settings);
            mPreferences.flush();
            mLiveActivitySettings = settings;
            notifyPreferenceUpdated();
        }
        catch(BackingStoreException | RuntimeException exception)
        {
            mLiveActivitySettings = previous;

            try
            {
                writeLiveActivitySettings(previous);
                mPreferences.flush();
            }
            catch(BackingStoreException | RuntimeException rollbackException)
            {
                exception.addSuppressed(rollbackException);
            }

            throw exception;
        }
    }

    private void writeLiveActivitySettings(LiveActivitySettings settings)
    {
        mPreferences.putBoolean(PREFERENCE_KEY_RETAIN_IDLE_CALL_DETAILS, settings.retainIdleCallDetails());
        mPreferences.putInt(PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS,
            settings.trafficGrantAgeOutMilliseconds());
        mPreferences.putBoolean(PREFERENCE_KEY_SHOW_CONTROL_DECODE_QUALITY, settings.showControlDecodeQuality());
        mPreferences.putBoolean(PREFERENCE_KEY_SHOW_VOICE_DECODE_QUALITY, settings.showVoiceDecodeQuality());
        mPreferences.putBoolean(PREFERENCE_KEY_CLEAR_VOICE_DECODE_QUALITY_ON_CALL_END,
            settings.clearVoiceDecodeQualityOnCallEnd());
        mPreferences.put(PREFERENCE_KEY_DECODE_QUALITY_DISPLAY_MODE, settings.decodeQualityDisplayMode().name());
        mPreferences.putInt(PREFERENCE_KEY_LIVE_DETAIL_MATCHING_ROW_LIMIT,
            settings.liveDetailMatchingRowLimit());
    }

    private DecodeQualityDisplayMode readDecodeQualityDisplayMode()
    {
        try
        {
            return DecodeQualityDisplayMode.valueOf(mPreferences.get(PREFERENCE_KEY_DECODE_QUALITY_DISPLAY_MODE,
                DecodeQualityDisplayMode.PERCENTAGE.name()));
        }
        catch(IllegalArgumentException _)
        {
            return DecodeQualityDisplayMode.PERCENTAGE;
        }
    }

    /**
     * Indicates whether the supplied optional Java desktop view is enabled.
     */
    public boolean isJavaInterfaceViewEnabled(JavaInterfaceView view)
    {
        if(view == null)
        {
            return false;
        }

        return mPreferences.getBoolean(view.mPreferenceKey, view.mDefaultEnabled);
    }

    /**
     * Enables or disables the supplied optional Java desktop view.
     */
    public void setJavaInterfaceViewEnabled(JavaInterfaceView view, boolean enabled)
    {
        if(view != null)
        {
            mPreferences.putBoolean(view.mPreferenceKey, enabled);
            notifyPreferenceUpdated();
        }
    }

    private int clamp(int value, int minimum, int maximum)
    {
        return Math.min(maximum, Math.max(minimum, value));
    }
}
