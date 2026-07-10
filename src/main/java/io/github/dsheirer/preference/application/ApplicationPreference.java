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
    private static final String PREFERENCE_KEY_CHANNEL_AUTO_START_TIMEOUT = "channel.auto.start.timeout";
    private static final String PREFERENCE_KEY_STATS_LOGGING_ENABLED = "p25.activity.logging.enabled";
    private static final String PREFERENCE_KEY_STATS_DETAILED_HISTORY_ENABLED =
        "p25.activity.logging.detailed.history.enabled";
    private static final String PREFERENCE_KEY_STATS_LOGGING_RETENTION_DAYS =
        "p25.activity.logging.retention.days";
    private static final String PREFERENCE_KEY_STATS_WEB_SERVER_ENABLED = "stats.web.server.enabled";
    private static final String PREFERENCE_KEY_STATS_WEB_SERVER_PORT = "stats.web.server.port";
    private static final String PREFERENCE_KEY_STATS_WEB_SERVER_LAN_ENABLED = "stats.web.server.lan.enabled";
    public static final int MIN_STATS_LOGGING_RETENTION_DAYS = 1;
    public static final int MAX_STATS_LOGGING_RETENTION_DAYS = 365;
    public static final int DEFAULT_STATS_LOGGING_RETENTION_DAYS = 30;
    public static final int MIN_STATS_WEB_SERVER_PORT = 1024;
    public static final int MAX_STATS_WEB_SERVER_PORT = 65535;
    public static final int DEFAULT_STATS_WEB_SERVER_PORT = 8090;

    private Preferences mPreferences = Preferences.userNodeForPackage(ApplicationPreference.class);
    private Integer mChannelAutoStartTimeout;
    private Boolean mStatsLoggingEnabled;
    private Boolean mStatsDetailedHistoryEnabled;
    private Integer mStatsLoggingRetentionDays;
    private Boolean mStatsWebServerEnabled;
    private Integer mStatsWebServerPort;
    private Boolean mStatsWebServerLanEnabled;

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
     * Indicates if sdrtrunk-vce stats should be logged to SQLite.
     */
    public boolean isStatsLoggingEnabled()
    {
        if(mStatsLoggingEnabled == null)
        {
            mStatsLoggingEnabled = mPreferences.getBoolean(PREFERENCE_KEY_STATS_LOGGING_ENABLED, false);
        }

        return mStatsLoggingEnabled;
    }

    /**
     * Enables or disables sdrtrunk-vce stats logging to SQLite.
     */
    public void setStatsLoggingEnabled(boolean enabled)
    {
        mStatsLoggingEnabled = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_STATS_LOGGING_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if compact detailed event history rows should be kept in addition to summaries.
     */
    public boolean isStatsDetailedHistoryEnabled()
    {
        if(mStatsDetailedHistoryEnabled == null)
        {
            mStatsDetailedHistoryEnabled =
                mPreferences.getBoolean(PREFERENCE_KEY_STATS_DETAILED_HISTORY_ENABLED, false);
        }

        return mStatsDetailedHistoryEnabled;
    }

    /**
     * Enables or disables detailed event history rows for sdrtrunk-vce stats logging.
     */
    public void setStatsDetailedHistoryEnabled(boolean enabled)
    {
        mStatsDetailedHistoryEnabled = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_STATS_DETAILED_HISTORY_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    /**
     * Retention period for sdrtrunk-vce stats logging.
     */
    public int getStatsLoggingRetentionDays()
    {
        if(mStatsLoggingRetentionDays == null)
        {
            mStatsLoggingRetentionDays = clampRetentionDays(mPreferences.getInt(
                PREFERENCE_KEY_STATS_LOGGING_RETENTION_DAYS,
                DEFAULT_STATS_LOGGING_RETENTION_DAYS));
        }

        return mStatsLoggingRetentionDays;
    }

    /**
     * Sets the retention period for sdrtrunk-vce stats logging.
     */
    public void setStatsLoggingRetentionDays(int days)
    {
        mStatsLoggingRetentionDays = clampRetentionDays(days);
        mPreferences.putInt(PREFERENCE_KEY_STATS_LOGGING_RETENTION_DAYS, mStatsLoggingRetentionDays);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if the embedded stats web server should start.
     */
    public boolean isStatsWebServerEnabled()
    {
        if(mStatsWebServerEnabled == null)
        {
            mStatsWebServerEnabled = mPreferences.getBoolean(PREFERENCE_KEY_STATS_WEB_SERVER_ENABLED, false);
        }

        return mStatsWebServerEnabled;
    }

    /**
     * Enables or disables the embedded stats web server.
     */
    public void setStatsWebServerEnabled(boolean enabled)
    {
        mStatsWebServerEnabled = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_STATS_WEB_SERVER_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    /**
     * Port for the embedded stats web server.
     */
    public int getStatsWebServerPort()
    {
        if(mStatsWebServerPort == null)
        {
            mStatsWebServerPort = clampStatsWebServerPort(
                mPreferences.getInt(PREFERENCE_KEY_STATS_WEB_SERVER_PORT, DEFAULT_STATS_WEB_SERVER_PORT));
        }

        return mStatsWebServerPort;
    }

    /**
     * Sets the port for the embedded stats web server.
     */
    public void setStatsWebServerPort(int port)
    {
        mStatsWebServerPort = clampStatsWebServerPort(port);
        mPreferences.putInt(PREFERENCE_KEY_STATS_WEB_SERVER_PORT, mStatsWebServerPort);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if non-loopback clients can reach the embedded stats web server.
     */
    public boolean isStatsWebServerLanEnabled()
    {
        if(mStatsWebServerLanEnabled == null)
        {
            mStatsWebServerLanEnabled = mPreferences.getBoolean(PREFERENCE_KEY_STATS_WEB_SERVER_LAN_ENABLED, false);
        }

        return mStatsWebServerLanEnabled;
    }

    /**
     * Enables or disables LAN/Tailscale access for the embedded stats web server.
     */
    public void setStatsWebServerLanEnabled(boolean enabled)
    {
        mStatsWebServerLanEnabled = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_STATS_WEB_SERVER_LAN_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    private static int clampRetentionDays(int days)
    {
        return Math.max(MIN_STATS_LOGGING_RETENTION_DAYS, Math.min(MAX_STATS_LOGGING_RETENTION_DAYS, days));
    }

    private static int clampStatsWebServerPort(int port)
    {
        return Math.max(MIN_STATS_WEB_SERVER_PORT, Math.min(MAX_STATS_WEB_SERVER_PORT, port));
    }
}
