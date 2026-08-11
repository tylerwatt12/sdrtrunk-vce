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

import io.github.dsheirer.gui.theme.Theme;
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
    private static final String PREFERENCE_KEY_DARK_MODE = "dark.mode";
    private static final String PREFERENCE_KEY_THEME = "ui.theme";
    private static final String PREFERENCE_KEY_GUI_SCALE = "ui.gui.scale";
    private static final String PREFERENCE_KEY_STATS_LOGGING_ENABLED = "p25.activity.logging.enabled";
    private static final String PREFERENCE_KEY_STATS_DETAILED_HISTORY_ENABLED =
        "p25.activity.logging.detailed.history.enabled";
    private static final String PREFERENCE_KEY_STATS_LOGGING_RETENTION_DAYS =
        "p25.activity.logging.retention.days";
    private static final String PREFERENCE_KEY_STATS_WEB_SERVER_ENABLED = "stats.web.server.enabled";
    private static final String PREFERENCE_KEY_STATS_WEB_SERVER_PORT = "stats.web.server.port";
    private static final String PREFERENCE_KEY_STATS_WEB_SERVER_ANY_IP_ENABLED = "stats.web.server.any.ip.enabled";
    private static final String PREFERENCE_KEY_STATS_WEB_SERVER_HTTPS_ENABLED = "stats.web.server.https.enabled";
    private static final String PREFERENCE_KEY_STATS_WEB_SERVER_CERTIFICATE_MODE =
        "stats.web.server.certificate.mode";
    public static final boolean DEFAULT_STATS_LOGGING_ENABLED = false;
    public static final boolean DEFAULT_STATS_DETAILED_HISTORY_ENABLED = false;
    public static final boolean DEFAULT_STATS_WEB_SERVER_ENABLED = true;
    public static final boolean DEFAULT_STATS_WEB_SERVER_HTTPS_ENABLED = true;
    public static final int MIN_STATS_LOGGING_RETENTION_DAYS = 1;
    public static final int MAX_STATS_LOGGING_RETENTION_DAYS = 365;
    public static final int DEFAULT_STATS_LOGGING_RETENTION_DAYS = 30;
    public static final int MIN_STATS_WEB_SERVER_PORT = 1024;
    public static final int MAX_STATS_WEB_SERVER_PORT = 65535;
    public static final int DEFAULT_STATS_WEB_SERVER_PORT = 8090;
    public static final double MIN_GUI_SCALE = 0.5d;
    public static final double MAX_GUI_SCALE = 2.0d;
    public static final double DEFAULT_GUI_SCALE = 1.0d;

    private Preferences mPreferences = Preferences.userNodeForPackage(ApplicationPreference.class);
    private Integer mChannelAutoStartTimeout;
    private Boolean mStatsLoggingEnabled;
    private Boolean mStatsDetailedHistoryEnabled;
    private Integer mStatsLoggingRetentionDays;
    private Boolean mStatsWebServerEnabled;
    private Integer mStatsWebServerPort;
    private Boolean mStatsWebServerAnyIpEnabled;
    private Boolean mStatsWebServerHttpsEnabled;
    private WebCertificateMode mStatsWebServerCertificateMode;
    private Theme mTheme;
    private Double mGuiScale;

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
            mStatsLoggingEnabled = mPreferences.getBoolean(PREFERENCE_KEY_STATS_LOGGING_ENABLED,
                DEFAULT_STATS_LOGGING_ENABLED);
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
                mPreferences.getBoolean(PREFERENCE_KEY_STATS_DETAILED_HISTORY_ENABLED,
                    DEFAULT_STATS_DETAILED_HISTORY_ENABLED);
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
            mStatsWebServerEnabled = mPreferences.getBoolean(PREFERENCE_KEY_STATS_WEB_SERVER_ENABLED,
                DEFAULT_STATS_WEB_SERVER_ENABLED);
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
    public boolean isStatsWebServerAnyIpEnabled()
    {
        if(mStatsWebServerAnyIpEnabled == null)
        {
            mStatsWebServerAnyIpEnabled =
                mPreferences.getBoolean(PREFERENCE_KEY_STATS_WEB_SERVER_ANY_IP_ENABLED, false);
        }

        return mStatsWebServerAnyIpEnabled;
    }

    /**
     * Enables or disables access from any IP address for the embedded stats web server.
     */
    public void setStatsWebServerAnyIpEnabled(boolean enabled)
    {
        mStatsWebServerAnyIpEnabled = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_STATS_WEB_SERVER_ANY_IP_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    /**
     * Selects whether the embedded web interface is reachable only from this computer or from other computers on
     * connected networks. Network access always enables HTTPS; returning to local-only access keeps the current HTTPS
     * setting. Both values are saved before publishing one preference update so the listener is recycled only once.
     */
    public void setStatsWebServerNetworkAccessEnabled(boolean enabled)
    {
        mStatsWebServerAnyIpEnabled = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_STATS_WEB_SERVER_ANY_IP_ENABLED, enabled);

        if(enabled)
        {
            mStatsWebServerHttpsEnabled = true;
            mPreferences.putBoolean(PREFERENCE_KEY_STATS_WEB_SERVER_HTTPS_ENABLED, true);
        }

        notifyPreferenceUpdated();
    }

    /**
     * Indicates whether the embedded web server uses HTTPS.
     */
    public boolean isStatsWebServerHttpsEnabled()
    {
        if(mStatsWebServerHttpsEnabled == null)
        {
            mStatsWebServerHttpsEnabled =
                mPreferences.getBoolean(PREFERENCE_KEY_STATS_WEB_SERVER_HTTPS_ENABLED,
                    DEFAULT_STATS_WEB_SERVER_HTTPS_ENABLED);
        }

        return mStatsWebServerHttpsEnabled;
    }

    /**
     * Enables or disables HTTPS for the embedded web server.
     */
    public void setStatsWebServerHttpsEnabled(boolean enabled)
    {
        mStatsWebServerHttpsEnabled = enabled;
        mPreferences.putBoolean(PREFERENCE_KEY_STATS_WEB_SERVER_HTTPS_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    /**
     * Certificate ownership mode for the embedded HTTPS listener.
     */
    public WebCertificateMode getStatsWebServerCertificateMode()
    {
        if(mStatsWebServerCertificateMode == null)
        {
            mStatsWebServerCertificateMode = WebCertificateMode.fromStoredValue(
                mPreferences.get(PREFERENCE_KEY_STATS_WEB_SERVER_CERTIFICATE_MODE, null));
        }

        return mStatsWebServerCertificateMode;
    }

    /**
     * Indicates whether this profile explicitly selected automatic or custom certificate ownership. Profiles created
     * before certificate-mode tracking use the installed files to choose a non-destructive initial mode.
     */
    public boolean isStatsWebServerCertificateModeConfigured()
    {
        return mPreferences.get(PREFERENCE_KEY_STATS_WEB_SERVER_CERTIFICATE_MODE, null) != null;
    }

    /**
     * Initializes certificate ownership for an older profile without publishing a listener-reload event. This is
     * used only while preparing the listener's first secure startup.
     */
    public void initializeStatsWebServerCertificateMode(WebCertificateMode mode)
    {
        if(!isStatsWebServerCertificateModeConfigured())
        {
            mStatsWebServerCertificateMode = mode == null ? WebCertificateMode.AUTOMATIC : mode;
            mPreferences.put(PREFERENCE_KEY_STATS_WEB_SERVER_CERTIFICATE_MODE,
                mStatsWebServerCertificateMode.name());
        }
    }

    /**
     * Selects app-managed automatic certificate material or administrator-supplied custom material.
     */
    public void setStatsWebServerCertificateMode(WebCertificateMode mode)
    {
        mStatsWebServerCertificateMode = mode == null ? WebCertificateMode.AUTOMATIC : mode;
        mPreferences.put(PREFERENCE_KEY_STATS_WEB_SERVER_CERTIFICATE_MODE,
            mStatsWebServerCertificateMode.name());
        notifyPreferenceUpdated();
    }

    /**
     * Selected desktop theme.  The legacy dark-mode flag is honored when a theme has not yet
     * been stored, so existing portable profiles retain their appearance.
     */
    public Theme getTheme()
    {
        if(mTheme == null)
        {
            String stored = mPreferences.get(PREFERENCE_KEY_THEME, null);

            if(stored != null)
            {
                mTheme = Theme.fromName(stored);
            }
            else
            {
                mTheme = mPreferences.getBoolean(PREFERENCE_KEY_DARK_MODE, false) ? Theme.DARK : Theme.LIGHT;
            }
        }

        return mTheme;
    }

    /**
     * Persists the desktop theme in the portable application preferences store.
     */
    public void setTheme(Theme theme)
    {
        if(theme == null)
        {
            theme = Theme.LIGHT;
        }

        mTheme = theme;
        mPreferences.put(PREFERENCE_KEY_THEME, theme.name());
        mPreferences.putBoolean(PREFERENCE_KEY_DARK_MODE, theme.isDark());
        notifyPreferenceUpdated();
    }

    public boolean isDarkMode()
    {
        return getTheme().isDark();
    }

    /**
     * Global Swing and JavaFX scale.  1.0 is the default size.
     */
    public double getGuiScale()
    {
        if(mGuiScale == null)
        {
            mGuiScale = clampScale(mPreferences.getDouble(PREFERENCE_KEY_GUI_SCALE, DEFAULT_GUI_SCALE));
        }

        return mGuiScale;
    }

    public void setGuiScale(double scale)
    {
        mGuiScale = clampScale(scale);
        mPreferences.putDouble(PREFERENCE_KEY_GUI_SCALE, mGuiScale);
        notifyPreferenceUpdated();
    }

    private static double clampScale(double scale)
    {
        if(Double.isNaN(scale) || scale < MIN_GUI_SCALE)
        {
            return MIN_GUI_SCALE;
        }

        return Math.min(MAX_GUI_SCALE, scale);
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
