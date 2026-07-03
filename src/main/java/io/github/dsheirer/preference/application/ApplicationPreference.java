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
    private static final String PREFERENCE_KEY_P25_ACTIVITY_LOGGING_ENABLED = "p25.activity.logging.enabled";
    private static final String PREFERENCE_KEY_P25_SITE_STATISTICS_LOGGING_ENABLED =
        "p25.site.statistics.logging.enabled";
    private static final String PREFERENCE_KEY_DATABASE_LOGGER_STATUS_LOGGING_ENABLED =
        "database.logger.status.logging.enabled";
    private static final String PREFERENCE_KEY_P25_ACTIVITY_LOGGING_RETENTION_DAYS =
        "p25.activity.logging.retention.days";
    public static final int MIN_P25_ACTIVITY_LOGGING_RETENTION_DAYS = 1;
    public static final int MAX_P25_ACTIVITY_LOGGING_RETENTION_DAYS = 365;
    public static final int DEFAULT_P25_ACTIVITY_LOGGING_RETENTION_DAYS = 30;

    private Preferences mPreferences = Preferences.userNodeForPackage(ApplicationPreference.class);
    private Integer mChannelAutoStartTimeout;
    private Boolean mAutomaticDiagnosticMonitoring;
    private Boolean mP25ActivityLoggingEnabled;
    private Boolean mP25SiteStatisticsLoggingEnabled;
    private Boolean mDatabaseLoggerStatusLoggingEnabled;
    private Integer mP25ActivityLoggingRetentionDays;

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
     * Indicates if P25 activity should be logged to SQLite.
     */
    public boolean isP25ActivityLoggingEnabled()
    {
        if(mP25ActivityLoggingEnabled == null)
        {
            mP25ActivityLoggingEnabled =
                mPreferences.getBoolean(PREFERENCE_KEY_P25_ACTIVITY_LOGGING_ENABLED, false);
        }

        return mP25ActivityLoggingEnabled;
    }

    /**
     * Enables or disables P25 activity logging to SQLite.
     */
    public void setP25ActivityLoggingEnabled(boolean enabled)
    {
        mP25ActivityLoggingEnabled = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_P25_ACTIVITY_LOGGING_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if P25 site/network statistics snapshots should be logged to SQLite.
     */
    public boolean isP25SiteStatisticsLoggingEnabled()
    {
        if(mP25SiteStatisticsLoggingEnabled == null)
        {
            mP25SiteStatisticsLoggingEnabled = mPreferences.getBoolean(
                PREFERENCE_KEY_P25_SITE_STATISTICS_LOGGING_ENABLED, isP25ActivityLoggingEnabled());
        }

        return mP25SiteStatisticsLoggingEnabled;
    }

    /**
     * Enables or disables P25 site/network statistics logging to SQLite.
     */
    public void setP25SiteStatisticsLoggingEnabled(boolean enabled)
    {
        mP25SiteStatisticsLoggingEnabled = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_P25_SITE_STATISTICS_LOGGING_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if database writer status and counters should be logged to SQLite.
     */
    public boolean isDatabaseLoggerStatusLoggingEnabled()
    {
        if(mDatabaseLoggerStatusLoggingEnabled == null)
        {
            mDatabaseLoggerStatusLoggingEnabled = mPreferences.getBoolean(
                PREFERENCE_KEY_DATABASE_LOGGER_STATUS_LOGGING_ENABLED, isP25ActivityLoggingEnabled());
        }

        return mDatabaseLoggerStatusLoggingEnabled;
    }

    /**
     * Enables or disables database writer status and counter logging.
     */
    public void setDatabaseLoggerStatusLoggingEnabled(boolean enabled)
    {
        mDatabaseLoggerStatusLoggingEnabled = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_DATABASE_LOGGER_STATUS_LOGGING_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    /**
     * Indicates if any optional P25 database logger is enabled.
     */
    public boolean isAnyP25DatabaseLoggingEnabled()
    {
        return isP25ActivityLoggingEnabled() || isP25SiteStatisticsLoggingEnabled() ||
            isDatabaseLoggerStatusLoggingEnabled();
    }

    /**
     * Retention period for P25 activity logging.
     */
    public int getP25ActivityLoggingRetentionDays()
    {
        if(mP25ActivityLoggingRetentionDays == null)
        {
            mP25ActivityLoggingRetentionDays = clampRetentionDays(mPreferences.getInt(
                PREFERENCE_KEY_P25_ACTIVITY_LOGGING_RETENTION_DAYS,
                DEFAULT_P25_ACTIVITY_LOGGING_RETENTION_DAYS));
        }

        return mP25ActivityLoggingRetentionDays;
    }

    /**
     * Sets the retention period for P25 activity logging.
     */
    public void setP25ActivityLoggingRetentionDays(int days)
    {
        mP25ActivityLoggingRetentionDays = clampRetentionDays(days);
        mPreferences.putInt(PREFERENCE_KEY_P25_ACTIVITY_LOGGING_RETENTION_DAYS, mP25ActivityLoggingRetentionDays);
        notifyPreferenceUpdated();
    }

    private static int clampRetentionDays(int days)
    {
        return Math.max(MIN_P25_ACTIVITY_LOGGING_RETENTION_DAYS,
            Math.min(MAX_P25_ACTIVITY_LOGGING_RETENTION_DAYS, days));
    }
}
