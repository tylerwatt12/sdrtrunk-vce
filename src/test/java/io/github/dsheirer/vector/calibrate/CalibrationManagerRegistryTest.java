/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.vector.calibrate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class CalibrationManagerRegistryTest
{
    @Test
    void everyDeclaredCalibrationTypeHasARegisteredCalibration()
    {
        EnumSet<CalibrationType> registered = EnumSet.copyOf(CalibrationManager.getInstance().getCalibrationTypes());
        assertEquals(EnumSet.allOf(CalibrationType.class), registered,
            "Calibration enum entries must not become stale, unregistered preferences");
    }
}
