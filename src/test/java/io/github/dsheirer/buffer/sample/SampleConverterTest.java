/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.buffer.sample;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class SampleConverterTest
{
    @Test
    void packedConverterPreservesBothSamplesInEveryThreeByteGroup()
    {
        short[] expected = {(short)0xABC, (short)0x123, 0, (short)0xFFF, (short)0x800, (short)0x7FF};
        ByteBuffer packed = pack(expected);

        assertArrayEquals(expected, new ScalarPackedSampleConverter().convert(packed));
    }

    @Test
    void vectorUnpackedConverterMatchesScalarIncludingTailAndDcEstimate()
    {
        short[] expected = new short[67];

        for(int x = 0; x < expected.length; x++)
        {
            expected[x] = (short)((x * 977 + 4095) & 0xFFF);
        }

        ByteBuffer unpacked = unpack(expected);
        ScalarUnpackedSampleConverter scalar = new ScalarUnpackedSampleConverter();
        VectorUnpackedSampleConverter vector = new VectorUnpackedSampleConverter();

        assertArrayEquals(expected, scalar.convert(unpacked));
        assertArrayEquals(expected, vector.convert(unpacked));
        assertEquals(scalar.getAverageDc(), vector.getAverageDc(), 0.0f,
            "SIMD DC accumulation must not overflow short lanes");
    }

    @Test
    void iteratorRejectsAnInvalidSampleLengthWithTheActualLength()
    {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> new SampleBufferIteratorScalar(new short[3], new short[SampleBufferIterator.I_OVERLAP],
                new short[SampleBufferIterator.Q_OVERLAP], 0.0f, 0L, 1.0f));
        assertTrue(exception.getMessage().contains("length [3]"));
    }

    private static ByteBuffer pack(short[] samples)
    {
        if(samples.length % 2 != 0)
        {
            throw new IllegalArgumentException("Packed fixture requires sample pairs");
        }

        byte[] packed = new byte[samples.length / 2 * 3];
        int output = 0;

        for(int x = 0; x < samples.length; x += 2)
        {
            int first = samples[x] & 0xFFF;
            int second = samples[x + 1] & 0xFFF;
            packed[output++] = (byte)(first >>> 4);
            packed[output++] = (byte)(((first & 0xF) << 4) | (second >>> 8));
            packed[output++] = (byte)second;
        }

        return ByteBuffer.wrap(packed);
    }

    private static ByteBuffer unpack(short[] samples)
    {
        byte[] unpacked = new byte[samples.length * 2];

        for(int x = 0; x < samples.length; x++)
        {
            unpacked[2 * x] = (byte)samples[x];
            unpacked[2 * x + 1] = (byte)((samples[x] >>> 8) & 0xF);
        }

        return ByteBuffer.wrap(unpacked);
    }
}
