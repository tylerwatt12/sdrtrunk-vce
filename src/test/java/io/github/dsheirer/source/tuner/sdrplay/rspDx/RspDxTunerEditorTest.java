/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.sdrplay.rspDx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.tuner.sdrplay.api.parameter.tuner.HdrModeBandwidth;
import io.github.dsheirer.source.tuner.sdrplay.api.parameter.tuner.RspDxAntenna;
import org.junit.jupiter.api.Test;

class RspDxTunerEditorTest
{
    @Test
    void savesIndependentNotchesAndHdrBandwidth()
    {
        RspDxTunerConfiguration configuration = new RspDxTunerConfiguration();
        RspDxTunerEditor.applyDeviceSettings(configuration, true, true, HdrModeBandwidth.BANDWIDTH_1_200,
            false, true, RspDxAntenna.ANTENNA_C);
        assertTrue(configuration.isBiasT());
        assertTrue(configuration.isHdrMode());
        assertEquals(HdrModeBandwidth.BANDWIDTH_1_200, configuration.getHdrModeBandwidth());
        assertFalse(configuration.isRfNotch());
        assertTrue(configuration.isRfDabNotch());
        assertEquals(RspDxAntenna.ANTENNA_C, configuration.getAntenna());
    }
}
