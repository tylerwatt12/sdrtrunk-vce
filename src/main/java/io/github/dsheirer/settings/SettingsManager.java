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
package io.github.dsheirer.settings;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import org.jdesktop.swingx.mapviewer.GeoPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SettingsManager
{
    private static final Logger mLog = LoggerFactory.getLogger(SettingsManager.class);

    private Settings mSettings = new Settings();
    private ApplicationSettingsStore mSettingsStore =
        new ApplicationSettingsStore(SdrTrunkDatabasePath.getDatabasePath());
    private List<SettingChangeListener> mListeners = new ArrayList<>();
    private boolean mLoadingSettings = false;

    public SettingsManager()
    {
        //TODO: move settings into a SettingsModel
        //and update this class to only provide loading, saving, and model
        //change detection producing a save.

        init();
    }

    /**
     * Loads settings from the current settings file, or the default settings file,
     * as specified in the current SDRTrunk system settings
     */
    private void init()
    {
        load();
    }

    public Settings getSettings()
    {
        return mSettings;
    }

    public void setSettings(Settings settings)
    {
        mSettings = settings;
    }

    public Setting getSetting(String name)
    {
        return mSettings.getSetting(name);
    }

    /**
     * Returns the current setting, or if the setting doesn't exist
     * returns a newly created setting with the specified parameters
     */
    public ColorSetting getColorSetting(ColorSetting.ColorSettingName name)
    {
        ColorSetting setting = mSettings.getColorSetting(name);

        if(setting == null)
        {
            setting = new ColorSetting(name);

            addSetting(setting);
        }

        return setting;
    }


    /**
     * Fetches the current setting and applies the parameter(s) to it.  Creates
     * the setting if it does not exist
     */
    public void setColorSetting(ColorSetting.ColorSettingName name, Color color)
    {
        ColorSetting setting = getColorSetting(name);

        setting.setColor(color);

        broadcastSettingChange(setting);

        saveSettings();
    }

    public void resetColorSetting(ColorSetting.ColorSettingName name)
    {
        setColorSetting(name, name.getDefaultColor());
    }

    public void resetAllColorSettings()
    {
        for(ColorSetting color : mSettings.getColorSettings())
        {
            resetColorSetting(color.getColorSettingName());
        }
    }

    /**
     * Returns the current setting, or if the setting doesn't exist
     * returns a newly created setting with the specified parameters
     */
    public FileSetting getFileSetting(String name, String defaultPath)
    {
        FileSetting setting = mSettings.getFileSetting(name);

        if(setting == null)
        {
            setting = new FileSetting(name, defaultPath);

            addSetting(setting);
        }

        return setting;
    }

    /**
     * Fetches the current setting and applies the parameter(s) to it.  Creates
     * the setting if it does not exist
     */
    public void setFileSetting(String name, String path)
    {
        FileSetting setting = getFileSetting(name, path);

        setting.setPath(path);

        broadcastSettingChange(setting);

        saveSettings();
    }

    /**
     * Adds the setting and stores the set of settings
     *
     * @param setting
     */
    private void addSetting(Setting setting)
    {
        mSettings.addSetting(setting);

        saveSettings();

        broadcastSettingChange(setting);
    }

    public MapViewSetting getMapViewSetting(String name, GeoPosition position, int zoom)
    {
        MapViewSetting loc = mSettings.getMapViewSetting(name);

        if(loc != null)
        {
            return loc;
        }
        else
        {
            MapViewSetting newLoc = new MapViewSetting(name, position, zoom);

            addSetting(newLoc);

            return newLoc;
        }
    }

    public void setMapViewSetting(String name, GeoPosition position, int zoom)
    {
        MapViewSetting loc = getMapViewSetting(name, position, zoom);

        loc.setGeoPosition(position);
        loc.setZoom(zoom);

        saveSettings();
    }

    /**
     * Loads settings from SQLite.
     */
    public void load()
    {
        mLoadingSettings = true;

        try
        {
            if(mSettingsStore.contains(ApplicationSettingsStore.UI_SETTINGS))
            {
                mSettings = mSettingsStore.load(ApplicationSettingsStore.UI_SETTINGS, Settings.class)
                    .orElseGet(Settings::new);
                mLog.debug("Loaded UI settings from SQLite [{}]: settings [{}]",
                    mSettingsStore.getDatabasePath(), mSettings.getSettings().size());
            }
            else
            {
                mSettings = new Settings();
                mSettingsStore.save(ApplicationSettingsStore.UI_SETTINGS, mSettings);
                mLog.debug("Initialized UI settings in SQLite [{}]", mSettingsStore.getDatabasePath());
            }
        }
        catch(Exception e)
        {
            mLog.error("Error loading settings from SQLite database [" + mSettingsStore.getDatabasePath() +
                "]", e);

            mSettings = new Settings();
        }

        mLoadingSettings = false;
    }

    public void broadcastSettingChange(Setting setting)
    {
        Iterator<SettingChangeListener> it = mListeners.iterator();

        while(it.hasNext())
        {
            SettingChangeListener listener = it.next();

            if(listener == null)
            {
                it.remove();
            }
            else
            {
                listener.settingChanged(setting);
            }
        }
    }

    public void broadcastSettingDeleted(Setting setting)
    {
        Iterator<SettingChangeListener> it = mListeners.iterator();

        while(it.hasNext())
        {
            SettingChangeListener listener = it.next();

            if(listener == null)
            {
                it.remove();
            }
            else
            {
                listener.settingDeleted(setting);
            }
        }
    }

    public void addListener(SettingChangeListener listener)
    {
        mListeners.add(listener);
    }

    public void removeListener(SettingChangeListener listener)
    {
        mListeners.remove(listener);
    }

    private void saveSettings()
    {
        if(!mLoadingSettings)
        {
            try
            {
                mSettingsStore.saveLater(ApplicationSettingsStore.UI_SETTINGS, mSettings);
            }
            catch(Exception e)
            {
                mLog.error("Error serializing UI settings for SQLite [" + mSettingsStore.getDatabasePath() + "]", e);
            }
        }
    }
}
