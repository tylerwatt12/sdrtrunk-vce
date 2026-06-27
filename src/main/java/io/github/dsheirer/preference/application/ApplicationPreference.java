/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.preference.application;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.util.prefs.Preferences;

/**
 * General/Miscellaneous preferences
 */
public class ApplicationPreference extends Preference
{
    private static final String PREFERENCE_KEY_CHANNEL_AUTO_DIAGNOSTIC_MONITORING = "automatic.diagnostic.monitoring";
    private static final String PREFERENCE_KEY_CHANNEL_AUTO_START_TIMEOUT = "channel.auto.start.timeout";
    private static final String PREFERENCE_KEY_P25_ENCRYPTION_CSV_DEBUG_LOGGER =
        "p25.encryption.csv.debug.logger";

    private Preferences mPreferences = Preferences.userNodeForPackage(ApplicationPreference.class);
    private Integer mChannelAutoStartTimeout;
    private Boolean mAutomaticDiagnosticMonitoring;
    private Boolean mP25EncryptionCsvDebugLogger;

    /**
     * Constructs an instance
     * @param updateListener to receive notifications that a preference has been updated
     */
    public ApplicationPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.APPLICATION;
    }


    /**
     * Channel auto-start timeout.  This is the countdown in seconds to allow the user to cancel the channel auto-start.
     * @return timeout in seconds.
     */
    public int getChannelAutoStartTimeout()
    {
        if(mChannelAutoStartTimeout == null)
        {
            mChannelAutoStartTimeout = mPreferences.getInt(PREFERENCE_KEY_CHANNEL_AUTO_START_TIMEOUT, 10);
        }

        return mChannelAutoStartTimeout;
    }

    /**
     * Sets the channel auto-start timeout seconds value.
     * @param timeout in seconds.
     */
    public void setChannelAutoStartTimeout(int timeout)
    {
        mChannelAutoStartTimeout = timeout;
        mPreferences.putInt(PREFERENCE_KEY_CHANNEL_AUTO_START_TIMEOUT, timeout);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if automatic diagnostic monitoring is enabled.
     * @return enabled.
     */
    public boolean isAutomaticDiagnosticMonitoring()
    {
        if(mAutomaticDiagnosticMonitoring == null)
        {
            mAutomaticDiagnosticMonitoring = mPreferences.getBoolean(PREFERENCE_KEY_CHANNEL_AUTO_DIAGNOSTIC_MONITORING, true);
        }

        return mAutomaticDiagnosticMonitoring;
    }

    /**
     * Sets the enabled state for automatic diagnostic monitoring.
     * @param enabled true to turn on monitoring.
     */
    public void setAutomaticDiagnosticMonitoring(boolean enabled)
    {
        mAutomaticDiagnosticMonitoring = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_CHANNEL_AUTO_DIAGNOSTIC_MONITORING, enabled);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if the P25 encryption CSV debug logger is enabled.
     */
    public boolean isP25EncryptionCsvDebugLogger()
    {
        if(mP25EncryptionCsvDebugLogger == null)
        {
            mP25EncryptionCsvDebugLogger =
                mPreferences.getBoolean(PREFERENCE_KEY_P25_ENCRYPTION_CSV_DEBUG_LOGGER, false);
        }

        return mP25EncryptionCsvDebugLogger;
    }

    /**
     * Sets the enabled state for the P25 encryption CSV debug logger.
     */
    public void setP25EncryptionCsvDebugLogger(boolean enabled)
    {
        mP25EncryptionCsvDebugLogger = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_P25_ENCRYPTION_CSV_DEBUG_LOGGER, enabled);
        notifyPreferenceUpdated();
    }
}
