/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.am;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AmplitudeDemodulatorTest
{
    @Test
    void detectsTheComplexEnvelope()
    {
        AmplitudeDemodulator demodulator = new AmplitudeDemodulator();
        assertArrayEquals(new float[]{5.0f, 13.0f, 0.0f},
            demodulator.demodulate(new float[]{3.0f, 5.0f, 0.0f}, new float[]{4.0f, 12.0f, 0.0f}),
            0.00001f);
    }

    @Test
    void rejectsMismatchedSampleBuffers()
    {
        AmplitudeDemodulator demodulator = new AmplitudeDemodulator();
        assertThrows(IllegalArgumentException.class,
            () -> demodulator.demodulate(new float[2], new float[1]));
    }
}
