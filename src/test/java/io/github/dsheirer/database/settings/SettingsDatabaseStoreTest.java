/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.settings.ColorSetting;
import io.github.dsheirer.settings.FileSetting;
import io.github.dsheirer.settings.MapViewSetting;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import java.awt.Color;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsDatabaseStoreTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void roundTripsSettings() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        SettingsDatabaseStore store = new SettingsDatabaseStore(database);
        assertFalse(store.isInitialized());

        ColorSetting colorSetting = new ColorSetting(ColorSetting.ColorSettingName.SPECTRUM_BACKGROUND);
        colorSetting.setColor(Color.MAGENTA);

        FileSetting fileSetting = new FileSetting("recordings", "/tmp/recordings");
        MapViewSetting mapViewSetting = new MapViewSetting("Default", 41.5, -81.7, 8);

        AirspyTunerConfiguration tunerConfiguration = new AirspyTunerConfiguration("airspy-1");
        tunerConfiguration.setFrequency(853_762_500L);
        tunerConfiguration.setFrequencyCorrection(1.5d);

        Settings settings = new Settings();
        settings.setSettings(List.of(colorSetting, fileSetting, mapViewSetting));
        settings.setTunerConfigurations(List.of(tunerConfiguration));

        store.replaceSettings(settings);
        assertTrue(store.isInitialized());

        Settings loaded = store.loadSettings();
        assertEquals(3, loaded.getSettings().size());
        assertEquals(1, loaded.getTunerConfigurations().size());
        assertEquals("#ff00ff", loaded.getColorSetting(ColorSetting.ColorSettingName.SPECTRUM_BACKGROUND).getRgb());
        assertEquals("/tmp/recordings", loaded.getFileSetting("recordings").getPath());
        assertEquals(41.5, loaded.getMapViewSetting("Default").getLatitude());
        assertEquals(-81.7, loaded.getMapViewSetting("Default").getLongitude());
        assertEquals(8, loaded.getMapViewSetting("Default").getZoom());

        TunerConfiguration loadedTuner = loaded.getTunerConfigurations().get(0);
        assertInstanceOf(AirspyTunerConfiguration.class, loadedTuner);
        assertEquals("airspy-1", loadedTuner.getUniqueID());
        assertEquals(853_762_500L, loadedTuner.getFrequency());
        assertEquals(1.5d, loadedTuner.getFrequencyCorrection());
    }
}
