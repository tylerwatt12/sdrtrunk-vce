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

package io.github.dsheirer.source.tuner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.tuner.test.TestTunerController;
import org.junit.jupiter.api.Test;

class TunerControllerLifecycleTest
{
    @Test
    void disposalStopsControllerOwnedWidebandRecorder()
    {
        TrackingTunerController controller = new TrackingTunerController();

        controller.dispose();

        assertTrue(controller.mStopRecorderInvoked);
    }

    private static class TrackingTunerController extends TestTunerController
    {
        private boolean mStopRecorderInvoked;

        @Override
        public void stopRecorder()
        {
            mStopRecorderInvoked = true;
            super.stopRecorder();
        }
    }
}
