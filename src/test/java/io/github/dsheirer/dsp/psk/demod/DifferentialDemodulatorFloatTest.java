package io.github.dsheirer.dsp.psk.demod;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DifferentialDemodulatorFloatTest
{
    @Test
    void measuresOneSymbolOfPhaseAtIntegerAndFractionalSampleRates()
    {
        for(double rate: new double[]{19200, 24000, 25000, 37500})
        {
            for(DifferentialDemodulatorFloat demodulator: implementations(rate))
            {
                float[] i = new float[1024];
                float[] q = new float[i.length];
                double frequency = 250;

                for(int x = 0; x < i.length; x++)
                {
                    double phase = 2 * Math.PI * frequency * x / rate;
                    i[x] = (float)Math.cos(phase);
                    q[x] = (float)Math.sin(phase);
                }

                float[] phases = demodulator.demodulate(i, q);
                double expected = 2 * Math.PI * frequency / 4800;

                for(int x = 64; x < phases.length; x++)
                {
                    assertEquals(expected, phases[x], 0.0006,
                        demodulator.getClass().getSimpleName() + " rate=" + rate + " sample=" + x);
                }
            }
        }
    }

    @Test
    void changingBufferSizesAndVectorFallbackPreserveTheContinuousStream()
    {
        float[] i = new float[4096];
        float[] q = new float[i.length];

        for(int x = 0; x < i.length; x++)
        {
            double phase = 0.07 * x + 0.6 * Math.sin(0.03 * x);
            i[x] = (float)Math.cos(phase);
            q[x] = (float)Math.sin(phase);
        }

        float[] expected = new DifferentialDemodulatorFloatScalar(25000, 4800).demodulate(i, q);

        for(DifferentialDemodulatorFloat demodulator: implementations(25000))
        {
            int offset = 0;

            for(int length: new int[]{512, 514, 503, 1024, 1, 0, 17, 512, 1013})
            {
                float[] actual = demodulator.demodulate(Arrays.copyOfRange(i, offset, offset + length),
                    Arrays.copyOfRange(q, offset, offset + length));
                assertArrayEquals(Arrays.copyOfRange(expected, offset, offset + length), actual, 0.00001f,
                    demodulator.getClass().getSimpleName() + " offset=" + offset + " length=" + length);
                offset += length;
            }

            assertEquals(i.length, offset);
        }
    }

    private static DifferentialDemodulatorFloat[] implementations(double rate)
    {
        return new DifferentialDemodulatorFloat[]{new DifferentialDemodulatorFloatScalar(rate, 4800),
            new DifferentialDemodulatorFloatVector64(rate, 4800), new DifferentialDemodulatorFloatVector128(rate, 4800),
            new DifferentialDemodulatorFloatVector256(rate, 4800), new DifferentialDemodulatorFloatVector512(rate, 4800)};
    }
}
