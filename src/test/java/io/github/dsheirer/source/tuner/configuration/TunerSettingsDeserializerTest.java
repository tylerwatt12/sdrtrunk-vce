/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.fcd.proV1.FCD1TunerConfiguration;
import io.github.dsheirer.source.tuner.fcd.proplusV2.FCD2TunerConfiguration;
import org.junit.jupiter.api.Test;

class TunerSettingsDeserializerTest
{
    private final ObjectMapper mObjectMapper = new ObjectMapper();

    @Test
    void preservesSupportedEntriesWhenOtherEntriesAreUnavailable() throws Exception
    {
        TunerSettings settings = mObjectMapper.readValue("""
            {
              "disabledTuners": [
                {"tunerClass": "AIRSPY", "id": "airspy-disabled"},
                {"tunerClass": "FUTURE_TUNER", "id": "future-disabled"},
                null
              ],
              "tunerConfigurations": [
                {
                  "type": "airspyTunerConfiguration",
                  "uniqueID": "airspy-1",
                  "frequency": 853762500,
                  "frequencyCorrection": 1.5
                },
                {
                  "type": "futureTunerConfiguration",
                  "uniqueID": "future-1"
                }
              ]
            }
            """, TunerSettings.class);

        assertEquals(1, settings.getDisabledTuners().size());
        assertEquals(TunerClass.AIRSPY, settings.getDisabledTuners().getFirst().tunerClass());
        assertEquals("airspy-disabled", settings.getDisabledTuners().getFirst().id());
        assertEquals(1, settings.getTunerConfigurations().size());
        AirspyTunerConfiguration airspy =
            (AirspyTunerConfiguration)settings.getTunerConfigurations().getFirst();
        assertEquals("airspy-1", airspy.getUniqueID());
        assertEquals(853_762_500L, airspy.getFrequency());
        assertEquals(1.5d, airspy.getFrequencyCorrection());
        assertEquals(3, settings.getIgnoredEntryCount());
    }

    @Test
    void ignoresMalformedEntryWithoutDiscardingOtherEntries() throws Exception
    {
        TunerSettings settings = mObjectMapper.readValue("""
            {
              "disabledTuners": [],
              "tunerConfigurations": [
                {"type": "airspyTunerConfiguration", "uniqueID": "airspy-1"},
                {"type": "airspyTunerConfiguration", "frequency": "not-a-number"},
                {"type": "airspyTunerConfiguration"}
              ]
            }
            """, TunerSettings.class);

        assertEquals(1, settings.getTunerConfigurations().size());
        assertEquals("airspy-1", settings.getTunerConfigurations().getFirst().getUniqueID());
        assertEquals(2, settings.getIgnoredEntryCount());
    }

    @Test
    void loadsFuncubeEntriesAlongsideOtherSupportedTuners() throws Exception
    {
        TunerSettings settings = mObjectMapper.readValue("""
            {
              "disabledTuners": [
                {"tunerClass": "RTL2832", "id": "rtl-current"},
                {"tunerClass": "FUNCUBE_DONGLE_PRO", "id": "fcd-pro"},
                {"tunerClass": "FUNCUBE_DONGLE_PRO_PLUS", "id": "fcd-pro-plus"}
              ],
              "tunerConfigurations": [
                {"type": "airspyTunerConfiguration", "uniqueID": "airspy-current"},
                {"type": "fcd1TunerConfiguration", "uniqueID": "fcd-pro"},
                {"type": "fcd2TunerConfiguration", "uniqueID": "fcd-pro-plus"}
              ]
            }
            """, TunerSettings.class);

        assertEquals(3, settings.getDisabledTuners().size());
        assertEquals(TunerClass.FUNCUBE_DONGLE_PRO, settings.getDisabledTuners().get(1).tunerClass());
        assertEquals(TunerClass.FUNCUBE_DONGLE_PRO_PLUS, settings.getDisabledTuners().get(2).tunerClass());
        assertEquals(FCD1TunerConfiguration.class, settings.getTunerConfigurations().get(1).getClass());
        assertEquals(FCD2TunerConfiguration.class, settings.getTunerConfigurations().get(2).getClass());
        assertEquals(0, settings.getIgnoredEntryCount());
    }

    @Test
    void rejectsMalformedTopLevelPayload()
    {
        assertThrows(JsonMappingException.class, () -> mObjectMapper.readValue("[]", TunerSettings.class));
    }
}
