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
        CalibrationEnvironment environment = environment("cpu-1", 4, "vm-1", 256, 128);

        assertEquals(environment.signature(), environment("cpu-1", 4, "vm-1", 256, 128).signature());
        assertNotEquals(environment.signature(), environment("cpu-2", 4, "vm-1", 256, 128).signature());
        assertNotEquals(environment.signature(), environment("cpu-1", 8, "vm-1", 256, 128).signature());
        assertNotEquals(environment.signature(), environment("cpu-1", 4, "vm-2", 256, 128).signature());
        assertNotEquals(environment.signature(), environment("cpu-1", 4, "vm-1", 128, 128).signature());
        assertNotEquals(environment.signature(), environment("cpu-1", 4, "vm-1", 256, 64).signature());
    }

    @Test
    void environmentMismatchInvalidatesEveryCalibration()
    {
        CalibrationEnvironment environment = environment("cpu-1", 4, "vm-1", 256, 128);
        ResetTrackingCalibration first = new ResetTrackingCalibration(CalibrationType.GAIN_COMPLEX);
        ResetTrackingCalibration second = new ResetTrackingCalibration(CalibrationType.WINDOW);
        List<Calibration> calibrations = List.of(first, second);

        assertTrue(environment.invalidateIfChanged(null, calibrations));
        assertEquals(1, first.resetCount());
        assertEquals(1, second.resetCount());

        assertFalse(environment.invalidateIfChanged(environment.signature(), calibrations));
        assertEquals(1, first.resetCount());
        assertEquals(1, second.resetCount());

        assertTrue(environment.invalidateIfChanged(environment("cpu-2", 4, "vm-1", 256, 128).signature(),
            calibrations));
        assertEquals(2, first.resetCount());
        assertEquals(2, second.resetCount());
    }

    @Test
    void rejectsFixedWidthsThatDoNotFitTheCurrentSpecies()
    {
        CalibrationEnvironment environment = environment("cpu-1", 4, "vm-1", 128, 64);

        assertTrue(environment.supports(CalibrationType.GAIN_COMPLEX, Implementation.SCALAR));
        assertTrue(environment.supports(CalibrationType.GAIN_COMPLEX, Implementation.VECTOR_SIMD_PREFERRED));
        assertFalse(environment.supports(CalibrationType.GAIN_COMPLEX, Implementation.VECTOR_SIMD_128));
        assertFalse(environment.supports(CalibrationType.GAIN_COMPLEX, Implementation.VECTOR_SIMD_256));

        assertFalse(environment.supports(CalibrationType.RSP_SAMPLE_CONVERTER, Implementation.VECTOR_SIMD_64));
        assertFalse(environment.supports(CalibrationType.RSP_SAMPLE_CONVERTER, Implementation.VECTOR_SIMD_128));
    }

    @Test
    void rejectsFixedLabelWhenPreferredMeasuredTheSameNativeWidth()
    {
        CalibrationEnvironment environment = environment("cpu-1", 8, "vm-1", 256, 256);

        assertTrue(environment.supports(CalibrationType.FILTER_FIR, Implementation.VECTOR_SIMD_PREFERRED));
        assertTrue(environment.supports(CalibrationType.FILTER_FIR, Implementation.VECTOR_SIMD_128));
        assertFalse(environment.supports(CalibrationType.FILTER_FIR, Implementation.VECTOR_SIMD_256));
        assertFalse(environment.supports(CalibrationType.AM_DEMODULATOR, Implementation.VECTOR_SIMD_256));
        assertFalse(environment.supports(CalibrationType.FILTER_HALF_BAND_REAL_DEFAULT,
            Implementation.VECTOR_SIMD_256));

        //The specialized half-band calibration really does measure the equal-width fixed implementation.
        assertTrue(environment.supports(CalibrationType.FILTER_HALF_BAND_REAL_23_TAP,
            Implementation.VECTOR_SIMD_256));
    }

    @Test
    void rejectsImplementationsThatTheCalibrationNeverMeasures()
    {
        CalibrationEnvironment environment = environment("cpu-1", 16, "vm-1", 512, 512);

        assertTrue(environment.supports(CalibrationType.INTERPOLATOR, Implementation.VECTOR_SIMD_256));
        assertFalse(environment.supports(CalibrationType.INTERPOLATOR, Implementation.VECTOR_SIMD_PREFERRED));
        assertFalse(environment.supports(CalibrationType.INTERPOLATOR, Implementation.VECTOR_SIMD_512));
        assertTrue(environment.supports(CalibrationType.OSCILLATOR_COMPLEX,
            Implementation.VECTOR_SIMD_PREFERRED));
        assertFalse(environment.supports(CalibrationType.OSCILLATOR_COMPLEX, Implementation.VECTOR_SIMD_256));
        assertTrue(environment.supports(CalibrationType.P25P1_SOFT_SYNC_DETECTOR,
            Implementation.VECTOR_SIMD_512));
        assertFalse(environment.supports(CalibrationType.P25P1_SOFT_SYNC_DETECTOR,
            Implementation.VECTOR_SIMD_PREFERRED));
    }

    private static CalibrationEnvironment environment(String processorIdentifier, int logicalProcessorCount,
                                                      String vmVersion, int floatBits, int shortBits)
    {
        return new CalibrationEnvironment("Test OS", "test-architecture", "1.0", processorIdentifier,
            logicalProcessorCount, "Test Vendor", "Test VM", vmVersion, "mixed mode", "25", floatBits, shortBits);
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
