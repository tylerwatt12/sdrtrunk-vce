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
package io.github.dsheirer.source.tuner.frequency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.source.tuner.test.TestTunerController;
import org.junit.jupiter.api.Test;

class TunerFrequencyErrorManagerTest
{
    private static final double TOLERANCE = 0.000000001;

    @Test
    void appliesNormalAdjustmentInsideSafetyLimit() throws Exception
    {
        TestTunerController controller = new TestTunerController();
        TunerFrequencyErrorManager manager = controller.getTunerFrequencyErrorManager();

        try
        {
            setBaseline(controller, manager, 1.25);
            manager.applyAutomaticCorrection(100);

            double expected = 1.25 + (10.0 / (controller.getFrequency() / 1_000_000.0));
            assertEquals(expected, controller.getFrequencyCorrection(), TOLERANCE);
        }
        finally
        {
            manager.dispose();
        }
    }

    @Test
    void automaticNotificationsCannotMoveSafetyBaseline() throws Exception
    {
        TestTunerController controller = new TestTunerController();
        TunerFrequencyErrorManager manager = controller.getTunerFrequencyErrorManager();

        try
        {
            setBaseline(controller, manager, 2.5);
            applyRepeatedly(manager, 100);
            assertEquals(5.5, controller.getFrequencyCorrection(), TOLERANCE);

            applyRepeatedly(manager, -100);
            assertEquals(-0.5, controller.getFrequencyCorrection(), TOLERANCE);
        }
        finally
        {
            manager.dispose();
        }
    }

    @Test
    void reEnablingCapturesCurrentCorrectionAsNewBaseline() throws Exception
    {
        TestTunerController controller = new TestTunerController();
        TunerFrequencyErrorManager manager = controller.getTunerFrequencyErrorManager();

        try
        {
            setBaseline(controller, manager, 0.0);
            applyRepeatedly(manager, 100);
            assertEquals(3.0, controller.getFrequencyCorrection(), TOLERANCE);

            manager.setEnabled(false);
            manager.setEnabled(true);
            applyRepeatedly(manager, 100);
            assertEquals(6.0, controller.getFrequencyCorrection(), TOLERANCE);
        }
        finally
        {
            manager.dispose();
        }
    }

    @Test
    void manualChangeWhileDisabledBecomesNextBaseline() throws Exception
    {
        TestTunerController controller = new TestTunerController();
        TunerFrequencyErrorManager manager = controller.getTunerFrequencyErrorManager();

        try
        {
            manager.setEnabled(false);
            controller.setFrequencyCorrection(8.0);
            applyRepeatedly(manager, -100);
            assertEquals(8.0, controller.getFrequencyCorrection(), TOLERANCE);

            manager.setEnabled(true);
            applyRepeatedly(manager, -100);
            assertEquals(5.0, controller.getFrequencyCorrection(), TOLERANCE);
        }
        finally
        {
            manager.dispose();
        }
    }

    @Test
    void manualChangeWhileEnabledMovesSafetyBaseline() throws Exception
    {
        TestTunerController controller = new TestTunerController();
        TunerFrequencyErrorManager manager = controller.getTunerFrequencyErrorManager();

        try
        {
            setBaseline(controller, manager, 1.0);
            manager.applyAutomaticCorrection(100);
            controller.setFrequencyCorrection(10.0);
            applyRepeatedly(manager, 100);
            assertEquals(13.0, controller.getFrequencyCorrection(), TOLERANCE);
        }
        finally
        {
            manager.dispose();
        }
    }

    private static void setBaseline(TestTunerController controller, TunerFrequencyErrorManager manager,
                                    double correction) throws Exception
    {
        manager.setEnabled(false);
        controller.setFrequencyCorrection(correction);
        manager.setEnabled(true);
    }

    private static void applyRepeatedly(TunerFrequencyErrorManager manager, long requestedChangeHz)
    {
        for(int x = 0; x < 500; x++)
        {
            manager.applyAutomaticCorrection(requestedChangeHz);
        }
    }
}
