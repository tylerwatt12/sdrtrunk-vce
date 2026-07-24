/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.sdrplay.rsp2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.tuner.sdrplay.api.parameter.tuner.Rsp2AntennaSelection;
import org.junit.jupiter.api.Test;

class Rsp2TunerEditorTest
{
    @Test
    void savesEveryReceiverSpecificControl()
    {
        Rsp2TunerConfiguration configuration = new Rsp2TunerConfiguration();
        Rsp2TunerEditor.applyDeviceSettings(configuration, true, true, true, Rsp2AntennaSelection.ANT_B);
        assertTrue(configuration.isBiasT());
        assertTrue(configuration.isExternalReferenceOutput());
        assertTrue(configuration.isRfNotch());
        assertEquals(Rsp2AntennaSelection.ANT_B, configuration.getAntennaSelection());
    }
}
