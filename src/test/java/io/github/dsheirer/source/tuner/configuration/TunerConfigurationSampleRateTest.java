/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.airspy.hf.AirspyHfTunerConfiguration;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerConfiguration;
import io.github.dsheirer.source.tuner.hydrasdr.HydraSdrTunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.r8x.r820t.R820TTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.rsp1.Rsp1TunerConfiguration;
import org.junit.jupiter.api.Test;

class TunerConfigurationSampleRateTest
{
    @Test
    void exposesPersistedSampleRateWithoutChangingSerializedSettings() throws Exception
    {
        AirspyTunerConfiguration airspy = new AirspyTunerConfiguration("airspy");
        AirspyHfTunerConfiguration airspyHf = new AirspyHfTunerConfiguration("airspy-hf");
        airspyHf.setSampleRate(912_000);
        HydraSdrTunerConfiguration hydra = new HydraSdrTunerConfiguration("hydra");
        HackRFTunerConfiguration hackRf = new HackRFTunerConfiguration("hackrf");
        R820TTunerConfiguration rtl = new R820TTunerConfiguration("rtl");
        Rsp1TunerConfiguration rsp = new Rsp1TunerConfiguration("rsp");

        assertEquals(airspy.getSampleRate(), airspy.getConfiguredSampleRate());
        assertEquals(airspyHf.getSampleRate(), airspyHf.getConfiguredSampleRate());
        assertEquals(hydra.getSampleRate(), hydra.getConfiguredSampleRate());
        assertEquals(hackRf.getSampleRate().getRate(), hackRf.getConfiguredSampleRate());
        assertEquals(rtl.getSampleRate().getRate(), rtl.getConfiguredSampleRate());
        assertEquals(rsp.getSampleRate().getEffectiveSampleRate(), rsp.getConfiguredSampleRate());

        ObjectMapper objectMapper = new ObjectMapper();
        assertFalse(objectMapper.writeValueAsString(airspy).contains("configuredSampleRate"));
        assertFalse(objectMapper.writeValueAsString(rtl).contains("configuredSampleRate"));
        assertFalse(objectMapper.writeValueAsString(rsp).contains("configuredSampleRate"));
    }
}
