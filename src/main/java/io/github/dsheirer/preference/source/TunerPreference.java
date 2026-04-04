/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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

package io.github.dsheirer.preference.source;

import io.github.dsheirer.gui.preference.tuner.RspDuoSelectionMode;
import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.properties.SystemProperties;
import io.github.dsheirer.sample.Listener;
import java.util.prefs.Preferences;

/**
 * Tuner preferences
 */
public class TunerPreference extends Preference
{
    public static final String SDRCONNECT_HEADLESS_PATH_PROPERTY = "sdrconnect.headless.path";
    public static final String SDRCONNECT_HEADLESS_AUTOSTART_PROPERTY = "sdrconnect.headless.autostart";
    public static final String SDRCONNECT_HEADLESS_START_DELAY_MS_PROPERTY = "sdrconnect.headless.start.delay.ms";
    public static final String DEFAULT_SDRCONNECT_HEADLESS_PATH =
        "/Applications/SDRconnect.app/Contents/MacOS/SDRconnect_headless";
    public static final int DEFAULT_SDRCONNECT_HEADLESS_START_DELAY_MS = 15000;
    private Preferences mPreferences = Preferences.userNodeForPackage(TunerPreference.class);
    private static final String PREFERENCE_KEY_CHANNELIZER_TYPE = "channelizer.type";
    private static final String PREFERENCE_KEY_RSP_DUO_TUNER_MODE = "rsp.duo.tuner.mode";

    private ChannelizerType mChannelizerType;
    private RspDuoSelectionMode mRspDuoSelectionMode;

    /**
     * Constructs a tuner preference with the update listener
     *
     * @param updateListener
     */
    public TunerPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.TUNER;
    }


    /**
     * Channelizer type used by the tuners
     */
    public ChannelizerType getChannelizerType()
    {
        if(mChannelizerType == null)
        {
            String type = mPreferences.get(PREFERENCE_KEY_CHANNELIZER_TYPE, ChannelizerType.POLYPHASE.name());

            if(type != null)
            {
                if(type.equalsIgnoreCase(ChannelizerType.POLYPHASE.name()))
                {
                    mChannelizerType = ChannelizerType.POLYPHASE;
                }
                else if(type.equalsIgnoreCase(ChannelizerType.HETERODYNE.name()))
                {
                    mChannelizerType = ChannelizerType.HETERODYNE;
                }
            }

            if(type == null)
            {
                mChannelizerType = ChannelizerType.POLYPHASE;
            }
        }

        return mChannelizerType;
    }

    /**
     * Sets the channelizer type use by tuners
     */
    public void setChannelizerType(ChannelizerType type)
    {
        mChannelizerType = type;
        mPreferences.put(PREFERENCE_KEY_CHANNELIZER_TYPE, mChannelizerType.name());
        notifyPreferenceUpdated();
    }

    /**
     * RSPduo tuner select mode.
     * @return mode or a default value of DUAL
     */
    public RspDuoSelectionMode getRspDuoTunerMode()
    {
        if(mRspDuoSelectionMode == null)
        {
            String mode = mPreferences.get(PREFERENCE_KEY_RSP_DUO_TUNER_MODE, RspDuoSelectionMode.DUAL.name());
            mRspDuoSelectionMode = RspDuoSelectionMode.fromValue(mode);
        }

        return mRspDuoSelectionMode;
    }

    /**
     * Sets the RSPduo tuner select mode
     * @param mode to use
     */
    public void setRspDuoTunerMode(RspDuoSelectionMode mode)
    {
        mRspDuoSelectionMode = mode;
        mPreferences.put(PREFERENCE_KEY_RSP_DUO_TUNER_MODE, mRspDuoSelectionMode.name());
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if local SDRconnect headless instances should be auto-started.
     */
    public boolean isSDRconnectHeadlessAutostartEnabled()
    {
        return SystemProperties.getInstance().get(SDRCONNECT_HEADLESS_AUTOSTART_PROPERTY, true);
    }

    /**
     * Sets the local SDRconnect headless auto-start preference.
     */
    public void setSDRconnectHeadlessAutostartEnabled(boolean enabled)
    {
        SystemProperties.getInstance().set(SDRCONNECT_HEADLESS_AUTOSTART_PROPERTY, enabled);
        notifyPreferenceUpdated();
    }

    /**
     * Path to the SDRconnect headless executable.
     */
    public String getSDRconnectHeadlessPath()
    {
        return SystemProperties.getInstance().get(SDRCONNECT_HEADLESS_PATH_PROPERTY, DEFAULT_SDRCONNECT_HEADLESS_PATH);
    }

    /**
     * Sets the SDRconnect headless executable path.
     */
    public void setSDRconnectHeadlessPath(String path)
    {
        SystemProperties.getInstance().set(SDRCONNECT_HEADLESS_PATH_PROPERTY, path);
        notifyPreferenceUpdated();
    }

    /**
     * Maximum time to wait for SDRconnect headless readiness during startup.
     */
    public int getSDRconnectHeadlessStartDelayMs()
    {
        return SystemProperties.getInstance().get(SDRCONNECT_HEADLESS_START_DELAY_MS_PROPERTY,
            DEFAULT_SDRCONNECT_HEADLESS_START_DELAY_MS);
    }

    /**
     * Sets the SDRconnect headless readiness timeout.
     */
    public void setSDRconnectHeadlessStartDelayMs(int delayMs)
    {
        SystemProperties.getInstance().set(SDRCONNECT_HEADLESS_START_DELAY_MS_PROPERTY, delayMs);
        notifyPreferenceUpdated();
    }
}
