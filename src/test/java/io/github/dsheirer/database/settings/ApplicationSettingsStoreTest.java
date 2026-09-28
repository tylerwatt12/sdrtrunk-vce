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
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationSettingsStoreTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void storesGenericAndTunerSettingsUnderIndependentKeys() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ApplicationSettingsStore store = new ApplicationSettingsStore(database);
        String displaySettingsKey = "test.display.settings";
        assertFalse(store.contains(displaySettingsKey));
        assertFalse(store.contains(ApplicationSettingsStore.TUNER_SETTINGS));

        Map<String,Object> displaySettings = Map.of("theme", "dark", "zoom", 8);

        AirspyTunerConfiguration airspy = new AirspyTunerConfiguration("airspy-1");
        airspy.setFrequency(853_762_500L);
        airspy.setFrequencyCorrection(1.5d);
        airspy.setCenterFrequencyLocked(true);
        TunerSettings tuners = new TunerSettings();
        tuners.setTunerConfigurations(List.of(airspy));

        store.save(displaySettingsKey, displaySettings);
        store.save(ApplicationSettingsStore.TUNER_SETTINGS, tuners);
        assertTrue(store.contains(displaySettingsKey));
        assertTrue(store.contains(ApplicationSettingsStore.TUNER_SETTINGS));

        Map<?,?> loadedDisplaySettings = store.load(displaySettingsKey, Map.class).orElseThrow();
        assertEquals("dark", loadedDisplaySettings.get("theme"));
        assertEquals(8, loadedDisplaySettings.get("zoom"));

        TunerSettings loadedTuners = store.load(ApplicationSettingsStore.TUNER_SETTINGS, TunerSettings.class)
            .orElseThrow();
        TunerConfiguration loadedTuner = loadedTuners.getTunerConfigurations().getFirst();
        assertInstanceOf(AirspyTunerConfiguration.class, loadedTuner);
        assertEquals("airspy-1", loadedTuner.getUniqueID());
        assertEquals(853_762_500L, loadedTuner.getFrequency());
        assertEquals(1.5d, loadedTuner.getFrequencyCorrection());
        assertTrue(loadedTuner.isCenterFrequencyLocked());
    }

    @Test
    void explicitShutdownFlushCommitsTheLatestQueuedTunerSettings() throws Exception
    {
        Path database = mTemporaryFolder.resolve("queued-settings.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ApplicationSettingsStore store = new ApplicationSettingsStore(database);

        AirspyTunerConfiguration first = new AirspyTunerConfiguration("airspy-1");
        first.setMinimumFrequency(100_000_000L);
        TunerSettings earlier = new TunerSettings();
        earlier.setTunerConfigurations(List.of(first));
        store.saveLater(ApplicationSettingsStore.TUNER_SETTINGS, earlier);

        AirspyTunerConfiguration latest = new AirspyTunerConfiguration("airspy-1");
        latest.setMinimumFrequency(150_000_000L);
        TunerSettings updated = new TunerSettings();
        updated.setTunerConfigurations(List.of(latest));
        store.saveLater(ApplicationSettingsStore.TUNER_SETTINGS, updated);

        ApplicationSettingsStore.flushPendingWritesNow();
        TunerSettings loaded = store.load(ApplicationSettingsStore.TUNER_SETTINGS, TunerSettings.class).orElseThrow();
        assertEquals(150_000_000L, loaded.getTunerConfigurations().getFirst().getMinimumFrequency());
    }
}
