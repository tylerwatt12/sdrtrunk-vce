/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.vector.calibrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CalibrationEnvironmentTest
{
    @Test
    void signatureTracksHostJvmAndPreferredVectorWidths()
    {
        CalibrationEnvironment environment = environment("vm-1", 256, 128);

        assertEquals(environment.signature(), environment("vm-1", 256, 128).signature());
        assertNotEquals(environment.signature(), environment("vm-2", 256, 128).signature());
        assertNotEquals(environment.signature(), environment("vm-1", 128, 128).signature());
        assertNotEquals(environment.signature(), environment("vm-1", 256, 64).signature());
    }

    @Test
    void environmentMismatchInvalidatesEveryCalibration()
    {
        CalibrationEnvironment environment = environment("vm-1", 256, 128);
        ResetTrackingCalibration first = new ResetTrackingCalibration(CalibrationType.GAIN_COMPLEX);
        ResetTrackingCalibration second = new ResetTrackingCalibration(CalibrationType.WINDOW);
        List<Calibration> calibrations = List.of(first, second);

        assertTrue(environment.invalidateIfChanged(null, calibrations));
        assertEquals(1, first.resetCount());
        assertEquals(1, second.resetCount());

        assertFalse(environment.invalidateIfChanged(environment.signature(), calibrations));
        assertEquals(1, first.resetCount());
        assertEquals(1, second.resetCount());

        assertTrue(environment.invalidateIfChanged(environment("vm-2", 256, 128).signature(), calibrations));
        assertEquals(2, first.resetCount());
        assertEquals(2, second.resetCount());
    }

    @Test
    void rejectsFixedWidthsThatDoNotFitTheCurrentSpecies()
    {
        CalibrationEnvironment environment = environment("vm-1", 128, 64);

        assertTrue(environment.supports(CalibrationType.GAIN_COMPLEX, Implementation.SCALAR));
        assertTrue(environment.supports(CalibrationType.GAIN_COMPLEX, Implementation.VECTOR_SIMD_PREFERRED));
        assertTrue(environment.supports(CalibrationType.GAIN_COMPLEX, Implementation.VECTOR_SIMD_128));
        assertFalse(environment.supports(CalibrationType.GAIN_COMPLEX, Implementation.VECTOR_SIMD_256));

        assertTrue(environment.supports(CalibrationType.RSP_SAMPLE_CONVERTER, Implementation.VECTOR_SIMD_64));
        assertFalse(environment.supports(CalibrationType.RSP_SAMPLE_CONVERTER, Implementation.VECTOR_SIMD_128));
    }

    private static CalibrationEnvironment environment(String vmVersion, int floatBits, int shortBits)
    {
        return new CalibrationEnvironment("Test OS", "test-architecture", "1.0", "Test Vendor", "Test VM",
            vmVersion, "mixed mode", "25", floatBits, shortBits);
    }

    private static class ResetTrackingCalibration extends Calibration
    {
        private int mResetCount;

        private ResetTrackingCalibration(CalibrationType type)
        {
            super(type);
        }

        @Override public void reset()
        {
            mResetCount++;
        }

        @Override public void calibrate()
        {
        }

        private int resetCount()
        {
            return mResetCount;
        }
    }
}
