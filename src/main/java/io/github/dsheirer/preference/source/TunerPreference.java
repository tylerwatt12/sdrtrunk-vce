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
import io.github.dsheirer.sample.Listener;
import java.util.prefs.Preferences;

/**
 * Tuner preferences
 */
public class TunerPreference extends Preference
{
    static final String RETIRED_PREFERENCE_KEY_CHANNELIZER_TYPE = "channelizer.type";
    private static final String PREFERENCE_KEY_RSP_DUO_TUNER_MODE = "rsp.duo.tuner.mode";

    private final Preferences mPreferences;
    private RspDuoSelectionMode mRspDuoSelectionMode;

    /**
     * Constructs a tuner preference with the update listener
     *
     * @param updateListener
     */
    public TunerPreference(Listener<PreferenceType> updateListener)
    {
        this(updateListener, Preferences.userNodeForPackage(TunerPreference.class));
    }

    TunerPreference(Listener<PreferenceType> updateListener, Preferences preferences)
    {
        super(updateListener);
        mPreferences = preferences;

        //Retired preference values are deliberately ignored.  All tuners now use the polyphase channelizer.
        mPreferences.remove(RETIRED_PREFERENCE_KEY_CHANNELIZER_TYPE);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.TUNER;
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

}
