/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.am;

import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.vector.calibrate.CalibrationManager;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;

/**
 * Creates the calibrated AM envelope detector implementation for this computer.
 */
public class AmplitudeDemodulatorFactory
{
    private AmplitudeDemodulatorFactory()
    {
    }

    public static IDemodulator getDemodulator()
    {
        return getDemodulator(CalibrationManager.getInstance().getImplementation(CalibrationType.AM_DEMODULATOR));
    }

    /**
     * Creates a specific implementation. Exposed for calibration and parity testing.
     */
    public static IDemodulator getDemodulator(Implementation implementation)
    {
        return switch(implementation)
        {
            case VECTOR_SIMD_PREFERRED, VECTOR_SIMD_64, VECTOR_SIMD_128, VECTOR_SIMD_256, VECTOR_SIMD_512 ->
                new VectorAmplitudeDemodulator(implementation);
            case SCALAR, UNCALIBRATED -> new AmplitudeDemodulator();
        };
    }
}
