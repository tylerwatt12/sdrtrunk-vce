package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

class DiagnosticStrengthEncodingTest
{
    @Test
    void preservesEveryBinAndWeakPeakWithBoundedAdaptiveErrorAcrossByteBoundaries()
    {
        float[] values = new float[513];
        for(int index = 0; index < values.length; index++) values[index] = -190 + index * 210.0f / 512;
        DiagnosticStreamFrame frame = compact(values);
        ByteBuffer header = ByteBuffer.wrap(frame.encoded()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, header.get(4));
        assertEquals(84, header.getShort(6));
        assertEquals(values.length, frame.valueCount());
        assertEquals(values.length, frame.sourceBinCount());
        assertEquals(84 + (values.length * 6 + 7) / 8, frame.encoded().length);
        assertEquals(6, header.get(72));
        assertEquals(1, header.get(73));
        float minimum = header.getFloat(76);
        float maximum = header.getFloat(80);
        assertEquals(-190, minimum);
        assertEquals(20, maximum);
        float[] decoded = decode(frame);
        for(int index = 0; index < values.length; index++)
        {
            assertTrue(Math.abs(values[index] - decoded[index]) <= (maximum - minimum) / 126 + 0.0001f);
        }
        assertEquals(values[0], decoded[0]);
        assertEquals(values[512], decoded[512]);
        assertSame(frame.multiplexFrame(5), frame.multiplexFrame(5));
    }

    @Test
    void keepsLegacyClippingAndNonfiniteSentinelAndHandlesConstantOrEmptyFrames()
    {
        float[] values = {-300, -196, -150, -80, 0, 20, 40, Float.NaN, Float.POSITIVE_INFINITY};
        float[] expected = {-196, -196, -150, -80, 0, 20, 20, -196, -196};
        float[] decoded = decode(compact(values));
        for(int index = 0; index < values.length; index++)
        {
            assertTrue(Math.abs(expected[index] - decoded[index]) <= 216.0f / 126 + 0.0001f);
        }
        assertEquals(-137.25f, decode(compact(new float[]{-137.25f, -137.25f, -137.25f}))[2]);
        assertEquals(84, compact(new float[0]).encoded().length);
        assertEquals(0, decode(compact(new float[0])).length);
        assertThrows(IllegalArgumentException.class, () -> DiagnosticStreamFrame.compactStrength(
            DiagnosticStreamFrame.TYPE_CHANNEL_SYMBOLS, 1, 2, 3, 100, 25_000, 3, new float[3]));
        DiagnosticStreamFrame legacy = DiagnosticStreamFrame.tunerFft(1, 2, 3, 100, 25_000, 3, 8,
            new float[]{-196, -88, 20});
        assertEquals(72 + 3, legacy.encoded().length);
    }

    private static DiagnosticStreamFrame compact(float[] values)
    {
        return DiagnosticStreamFrame.compactStrength(DiagnosticStreamFrame.TYPE_TUNER_FFT,
            1, 2, 3, 100, 25_000, values.length, values);
    }

    private static float[] decode(DiagnosticStreamFrame frame)
    {
        ByteBuffer header = ByteBuffer.wrap(frame.encoded()).order(ByteOrder.LITTLE_ENDIAN);
        int headerBytes = Short.toUnsignedInt(header.getShort(6));
        float minimum = header.getFloat(76);
        float span = header.getFloat(80) - minimum;
        BitSet packed = BitSet.valueOf(Arrays.copyOfRange(frame.encoded(), headerBytes, frame.encoded().length));
        float[] result = new float[frame.valueCount()];
        for(int index = 0; index < result.length; index++)
        {
            int code = 0;
            for(int bit = 0; bit < 6; bit++) if(packed.get(index * 6 + bit)) code |= 1 << bit;
            result[index] = minimum + code * span / 63;
        }
        return result;
    }
}
