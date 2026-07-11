/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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
import io.github.dsheirer.source.tuner.configuration.TunerSettings;
import java.awt.Color;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationSettingsStoreTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void storesUiAndTunerSettingsUnderIndependentKeys() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ApplicationSettingsStore store = new ApplicationSettingsStore(database);
        assertFalse(store.contains(ApplicationSettingsStore.UI_SETTINGS));
        assertFalse(store.contains(ApplicationSettingsStore.TUNER_SETTINGS));

        ColorSetting colorSetting = new ColorSetting(ColorSetting.ColorSettingName.SPECTRUM_BACKGROUND);
        colorSetting.setColor(Color.MAGENTA);
        Settings ui = new Settings();
        ui.setSettings(List.of(colorSetting, new FileSetting("recordings", "/tmp/recordings"),
            new MapViewSetting("Default", 41.5, -81.7, 8)));

        AirspyTunerConfiguration airspy = new AirspyTunerConfiguration("airspy-1");
        airspy.setFrequency(853_762_500L);
        airspy.setFrequencyCorrection(1.5d);
        TunerSettings tuners = new TunerSettings();
        tuners.setTunerConfigurations(List.of(airspy));

        store.save(ApplicationSettingsStore.UI_SETTINGS, ui);
        store.save(ApplicationSettingsStore.TUNER_SETTINGS, tuners);
        assertTrue(store.contains(ApplicationSettingsStore.UI_SETTINGS));
        assertTrue(store.contains(ApplicationSettingsStore.TUNER_SETTINGS));

        Settings loadedUi = store.load(ApplicationSettingsStore.UI_SETTINGS, Settings.class).orElseThrow();
        assertEquals(3, loadedUi.getSettings().size());
        assertEquals("#ff00ff",
            loadedUi.getColorSetting(ColorSetting.ColorSettingName.SPECTRUM_BACKGROUND).getRgb());
        assertEquals("/tmp/recordings", loadedUi.getFileSetting("recordings").getPath());

        TunerSettings loadedTuners = store.load(ApplicationSettingsStore.TUNER_SETTINGS, TunerSettings.class)
            .orElseThrow();
        TunerConfiguration loadedTuner = loadedTuners.getTunerConfigurations().getFirst();
        assertInstanceOf(AirspyTunerConfiguration.class, loadedTuner);
        assertEquals("airspy-1", loadedTuner.getUniqueID());
        assertEquals(853_762_500L, loadedTuner.getFrequency());
        assertEquals(1.5d, loadedTuner.getFrequencyCorrection());
    }
}
