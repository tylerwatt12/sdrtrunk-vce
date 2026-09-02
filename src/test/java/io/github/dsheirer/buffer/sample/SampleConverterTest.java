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
import java.nio.ByteOrder;
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
    void vectorUnpackedConverterDecodesEveryTwelveBitValueAndIgnoresHighNibble()
    {
        short[] expected = new short[4096];

        for(int x = 0; x < expected.length; x++)
        {
            expected[x] = (short)x;
        }

        ByteBuffer scalarBuffer = unpack(expected, true);
        ByteBuffer vectorBuffer = unpack(expected, true);
        ScalarUnpackedSampleConverter scalar = new ScalarUnpackedSampleConverter();
        VectorUnpackedSampleConverter vector = new VectorUnpackedSampleConverter();

        assertArrayEquals(expected, scalar.convert(scalarBuffer));
        assertArrayEquals(expected, vector.convert(vectorBuffer));
        assertEquals(Float.floatToRawIntBits(scalar.getAverageDc()),
            Float.floatToRawIntBits(vector.getAverageDc()), "SIMD DC accumulation must match scalar exactly");
    }

    @Test
    void vectorUnpackedConverterMatchesScalarAtEveryWidthAndTailBoundary()
    {
        for(int vectorBitSize: new int[]{64, 128, 256, 512})
        {
            int laneCount = vectorBitSize / Short.SIZE;

            for(int length: new int[]{1, laneCount - 1, laneCount, laneCount + 1, 2 * laneCount - 1,
                2 * laneCount, 2 * laneCount + 1})
            {
                short[] expected = createSamples(length, vectorBitSize + length);
                ScalarUnpackedSampleConverter scalar = new ScalarUnpackedSampleConverter();
                VectorUnpackedSampleConverter vector = new VectorUnpackedSampleConverter(vectorBitSize);
                short[] scalarSamples = scalar.convert(unpack(expected, true));
                short[] vectorSamples = vector.convert(unpack(expected, true));

                assertArrayEquals(expected, scalarSamples,
                    "Scalar fixture mismatch for " + vectorBitSize + "-bit width and length " + length);
                assertArrayEquals(scalarSamples, vectorSamples,
                    "Vector mismatch for " + vectorBitSize + "-bit width and length " + length);
                assertEquals(Float.floatToRawIntBits(scalar.getAverageDc()),
                    Float.floatToRawIntBits(vector.getAverageDc()),
                    "DC mismatch for " + vectorBitSize + "-bit width and length " + length);
            }
        }
    }

    @Test
    void vectorUnpackedConverterPreservesAbsoluteBufferSemantics()
    {
        short[] expected = createSamples(67, 0xABC);
        ByteBuffer scalarBuffer = directUnpacked(expected);
        ByteBuffer vectorBuffer = directUnpacked(expected);
        scalarBuffer.position(5);
        vectorBuffer.position(5);
        scalarBuffer.order(ByteOrder.BIG_ENDIAN);
        vectorBuffer.order(ByteOrder.BIG_ENDIAN);

        assertArrayEquals(new ScalarUnpackedSampleConverter().convert(scalarBuffer),
            new VectorUnpackedSampleConverter().convert(vectorBuffer));
        assertEquals(5, scalarBuffer.position(), "Scalar absolute reads must not change the source position");
        assertEquals(5, vectorBuffer.position(), "Vector loads must not change the source position");
    }

    @Test
    void vectorUnpackedConverterHonorsTheOriginalLimit()
    {
        short[] expected = createSamples(67, 0x123);
        ByteBuffer scalarBuffer = unpack(expected, true);
        ByteBuffer vectorBuffer = unpack(expected, true);
        scalarBuffer.position(3).limit(scalarBuffer.capacity() - 1);
        vectorBuffer.position(3).limit(vectorBuffer.capacity() - 1);

        assertThrows(IndexOutOfBoundsException.class,
            () -> new ScalarUnpackedSampleConverter().convert(scalarBuffer));
        assertThrows(IndexOutOfBoundsException.class,
            () -> new VectorUnpackedSampleConverter().convert(vectorBuffer));
        assertEquals(3, scalarBuffer.position());
        assertEquals(3, vectorBuffer.position());
    }

    @Test
    void vectorUnpackedConverterRejectsUnsupportedWidths()
    {
        assertThrows(IllegalArgumentException.class, () -> new VectorUnpackedSampleConverter(32));
        assertThrows(IllegalArgumentException.class, () -> new VectorUnpackedSampleConverter(1024));
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

    private static short[] createSamples(int length, int offset)
    {
        short[] samples = new short[length];

        for(int x = 0; x < samples.length; x++)
        {
            samples[x] = (short)((x * 977 + offset) & 0xFFF);
        }

        return samples;
    }

    private static ByteBuffer unpack(short[] samples, boolean setIgnoredHighNibble)
    {
        byte[] unpacked = new byte[samples.length * 2];

        for(int x = 0; x < samples.length; x++)
        {
            unpacked[2 * x] = (byte)samples[x];
            int ignoredHighNibble = setIgnoredHighNibble ? ((x * 13 + 1) & 0xF) << 4 : 0;
            unpacked[2 * x + 1] = (byte)(ignoredHighNibble | ((samples[x] >>> 8) & 0xF));
        }

        return ByteBuffer.wrap(unpacked);
    }

    private static ByteBuffer directUnpacked(short[] samples)
    {
        ByteBuffer heapBuffer = unpack(samples, true);
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(heapBuffer.capacity());

        for(int x = 0; x < heapBuffer.capacity(); x++)
        {
            directBuffer.put(x, heapBuffer.get(x));
        }

        return directBuffer;
    }
}
