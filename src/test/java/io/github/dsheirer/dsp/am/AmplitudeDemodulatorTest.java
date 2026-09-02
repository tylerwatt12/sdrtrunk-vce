/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.am;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.util.Random;
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

    @Test
    void vectorImplementationsMatchScalarForAlignedAndTailLengths()
    {
        Random random = new Random(0x53494D44L);
        int[] lengths = {0, 1, 3, 7, 8, 15, 16, 31, 64, 2048, 2051};

        for(int length: lengths)
        {
            float[] i = new float[length];
            float[] q = new float[length];

            for(int x = 0; x < length; x++)
            {
                i[x] = (random.nextFloat() * 2.0f) - 1.0f;
                q[x] = (random.nextFloat() * 2.0f) - 1.0f;
            }

            float[] expected = new AmplitudeDemodulator().demodulate(i, q);

            for(Implementation implementation: new Implementation[]{Implementation.VECTOR_SIMD_64,
                Implementation.VECTOR_SIMD_128, Implementation.VECTOR_SIMD_256, Implementation.VECTOR_SIMD_512,
                Implementation.VECTOR_SIMD_PREFERRED})
            {
                IDemodulator vector = AmplitudeDemodulatorFactory.getDemodulator(implementation);
                assertArrayEquals(expected, vector.demodulate(i, q), 0.000001f,
                    "Mismatch for " + implementation + " at length " + length);
            }
        }
    }
}
