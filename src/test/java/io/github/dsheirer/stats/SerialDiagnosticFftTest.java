/*
 * ****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.jtransforms.fft.FloatFFT_1D;
import org.junit.jupiter.api.Test;

class SerialDiagnosticFftTest
{
    @Test
    void matchesTheExistingComplexTransformAtEveryDiagnosticSize()
    {
        int[] sizes = {512, 1_024, 2_048, 4_096, 8_192, 16_384, 32_768};
        Random random = new Random(0x5D47_2026L);

        for(int size: sizes)
        {
            SerialDiagnosticFft serial = new SerialDiagnosticFft(size);

            for(int pass = 0; pass < 2; pass++)
            {
                float[] expected = new float[size * 2];

                for(int x = 0; x < expected.length; x++)
                {
                    expected[x] = random.nextFloat() * 2.0f - 1.0f;
                }

                float[] actual = expected.clone();
                new FloatFFT_1D(size).complexForward(expected);
                serial.forward(actual);

                for(int x = 0; x < expected.length; x++)
                {
                    double tolerance = Math.max(0.001, Math.abs(expected[x]) * 0.000_1);
                    assertEquals(expected[x], actual[x], tolerance,
                        "Transform mismatch at size " + size + ", pass " + pass + " and component " + x);
                }
            }
        }
    }

    @Test
    void rejectsInvalidPlansAndSampleLengths()
    {
        assertThrows(IllegalArgumentException.class, () -> new SerialDiagnosticFft(1));
        assertThrows(IllegalArgumentException.class, () -> new SerialDiagnosticFft(1_000));
        SerialDiagnosticFft fft = new SerialDiagnosticFft(512);
        assertThrows(NullPointerException.class, () -> fft.forward(null));
        assertThrows(IllegalArgumentException.class, () -> fft.forward(new float[512]));
    }
}
