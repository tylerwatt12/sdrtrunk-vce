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

package io.github.dsheirer.vector.calibrate.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SoftSyncCalibrationHelperTest
{
    @Test
    void boundaryTargetsStraddleThresholdByTheConfiguredUlpDistance()
    {
        for(float threshold: new float[]{37.0f, 40.0f, 60.0f, 80.0f, 100.0f, 110.0f})
        {
            float below = SoftSyncCalibrationHelper.boundaryTarget(threshold, false);
            float above = SoftSyncCalibrationHelper.boundaryTarget(threshold, true);

            assertTrue(below < threshold);
            assertTrue(above > threshold);
            assertEquals(SoftSyncCalibrationHelper.BOUNDARY_ULP_STEPS,
                Float.floatToRawIntBits(threshold) - Float.floatToRawIntBits(below));
            assertEquals(SoftSyncCalibrationHelper.BOUNDARY_ULP_STEPS,
                Float.floatToRawIntBits(above) - Float.floatToRawIntBits(threshold));
        }
    }

    @Test
    void nearSyncFixtureIsDeterministicAndNormalized()
    {
        float[] exact = {3.0f, -3.0f, 1.0f, -1.0f, 3.0f, -3.0f, 1.0f, -1.0f};
        float target = 40.0f;
        float[] first = SoftSyncCalibrationHelper.createNearSync(exact, target);
        float[] second = SoftSyncCalibrationHelper.createNearSync(exact, target);
        float correlation = 0.0f;

        assertArrayEquals(first, second);

        for(int x = 0; x < exact.length; x++)
        {
            correlation += exact[x] * first[x];
        }

        assertEquals(target, correlation, 1.0e-4f);
    }
}
