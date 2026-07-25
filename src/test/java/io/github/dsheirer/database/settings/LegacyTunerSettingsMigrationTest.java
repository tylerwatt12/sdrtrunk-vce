/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.configuration.TunerSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyTunerSettingsMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void importsSupportedFuncubeEntriesIntoSQLite() throws Exception
    {
        Path legacySettings = mTemporaryFolder.resolve("tuner_configuration.json");
        Files.writeString(legacySettings, """
            {
              "disabledTuners": [
                {"tunerClass": "AIRSPY", "id": "airspy-disabled"},
                {"tunerClass": "FUNCUBE_DONGLE_PRO", "id": "fcd-pro"}
              ],
              "tunerConfigurations": [
                {
                  "type": "airspyTunerConfiguration",
                  "uniqueID": "airspy-current",
                  "frequency": 853762500
                },
                {
                  "type": "fcd1TunerConfiguration",
                  "uniqueID": "fcd-pro"
                }
              ]
            }
            """);
        ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        TunerSettings imported = mapper.readValue(legacySettings.toFile(), TunerSettings.class);
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ApplicationSettingsStore store = new ApplicationSettingsStore(database);
        store.save(ApplicationSettingsStore.TUNER_SETTINGS, imported);

        TunerSettings loaded = store.load(ApplicationSettingsStore.TUNER_SETTINGS, TunerSettings.class).orElseThrow();
        assertEquals(2, loaded.getDisabledTuners().size());
        assertEquals(TunerClass.AIRSPY, loaded.getDisabledTuners().getFirst().tunerClass());
        assertEquals(TunerClass.FUNCUBE_DONGLE_PRO, loaded.getDisabledTuners().get(1).tunerClass());
        assertEquals(2, loaded.getTunerConfigurations().size());
        assertEquals("airspy-current", loaded.getTunerConfigurations().getFirst().getUniqueID());
        assertEquals("fcd-pro", loaded.getTunerConfigurations().get(1).getUniqueID());
        assertEquals(0, loaded.getIgnoredEntryCount());

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement(
                "SELECT settings_json FROM application_settings WHERE key = ?"))
        {
            statement.setString(1, ApplicationSettingsStore.TUNER_SETTINGS);

            try(ResultSet resultSet = statement.executeQuery())
            {
                String storedJson = resultSet.next() ? resultSet.getString(1) : "";
                assertTrue(storedJson.contains("FUNCUBE_DONGLE_PRO"));
                assertTrue(storedJson.contains("fcd1TunerConfiguration"));
            }
        }
    }
}
