/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.buffer.sample;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
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
        float expectedDc = 0;

        for(int buffer = 0; buffer < 8; buffer++)
        {
            //The unsigned range has mean 2047.5; every step must retain the same DC filter state.
            expectedDc += ((-0.5f / 2048.0f) - expectedDc) * 0.05f;
            assertArrayEquals(expected, scalar.convert(scalarBuffer));
            assertArrayEquals(expected, vector.convert(vectorBuffer));
            assertEquals(Float.floatToRawIntBits(expectedDc), Float.floatToRawIntBits(scalar.getAverageDc()));
            assertEquals(Float.floatToRawIntBits(expectedDc), Float.floatToRawIntBits(vector.getAverageDc()),
                "SIMD DC accumulation must match the unsigned mean through stream buffer " + buffer);
        }
    }

    @Test
    void vectorUnpackedConverterMatchesScalarAtPreferredWidthAndTailBoundaries()
    {
        int laneCount = ByteVector.SPECIES_PREFERRED.length() / Short.BYTES;

        for(int length: new int[]{1, laneCount - 1, laneCount, laneCount + 1, 2 * laneCount - 1,
            2 * laneCount, 2 * laneCount + 1})
        {
            short[] expected = createSamples(length, laneCount + length);
            ScalarUnpackedSampleConverter scalar = new ScalarUnpackedSampleConverter();
            VectorUnpackedSampleConverter vector = new VectorUnpackedSampleConverter();
            short[] scalarSamples = scalar.convert(unpack(expected, true));
            short[] vectorSamples = vector.convert(unpack(expected, true));

            assertArrayEquals(expected, scalarSamples, "Scalar fixture mismatch for length " + length);
            assertArrayEquals(scalarSamples, vectorSamples, "Vector mismatch for length " + length);
            assertEquals(Float.floatToRawIntBits(scalar.getAverageDc()),
                Float.floatToRawIntBits(vector.getAverageDc()), "DC mismatch for length " + length);
        }
    }

    @Test
    void vectorUnpackedConverterPreservesSliceOffsetsReadOnlyAndAbsoluteBufferSemantics()
    {
        short[] expected = createSamples(67, 0xABC);

        for(boolean direct: new boolean[]{false, true})
        {
            for(boolean readOnly: new boolean[]{false, true})
            {
                ByteBuffer buffer = unpackedSlice(expected, direct, readOnly);
                buffer.position(5).order(ByteOrder.BIG_ENDIAN);

                assertArrayEquals(expected, new VectorUnpackedSampleConverter().convert(buffer));
                assertEquals(5, buffer.position(), "Absolute reads must not change the source position");
                assertEquals(expected.length * 2 + 1, buffer.limit(), "The final odd byte is ignored");
                assertEquals(ByteOrder.BIG_ENDIAN, buffer.order(), "Conversion must not change input byte order");
            }
        }
    }

    @Test
    void vectorUnpackedConverterHonorsSliceLimitsWithoutReadingBackingBytes()
    {
        short[] expected = createSamples(67, 0x123);

        for(boolean direct: new boolean[]{false, true})
        {
            for(boolean readOnly: new boolean[]{false, true})
            {
                ByteBuffer scalarBuffer = unpackedSlice(expected, direct, readOnly);
                ByteBuffer vectorBuffer = unpackedSlice(expected, direct, readOnly);
                scalarBuffer.position(3).limit(expected.length * 2);
                vectorBuffer.position(3).limit(expected.length * 2);
                assertArrayEquals(expected, new ScalarUnpackedSampleConverter().convert(scalarBuffer));
                assertArrayEquals(expected, new VectorUnpackedSampleConverter().convert(vectorBuffer));

                scalarBuffer.limit(expected.length * 2 - 1);
                vectorBuffer.limit(expected.length * 2 - 1);
                assertThrows(IndexOutOfBoundsException.class,
                    () -> new ScalarUnpackedSampleConverter().convert(scalarBuffer));
                assertThrows(IndexOutOfBoundsException.class,
                    () -> new VectorUnpackedSampleConverter().convert(vectorBuffer));
                assertEquals(3, scalarBuffer.position());
                assertEquals(3, vectorBuffer.position());
                assertEquals(expected.length * 2 - 1, vectorBuffer.limit());
            }
        }
    }

    @Test
    void vectorUnpackedConverterOwnsEarlierOutputsWhenInputIsReused()
    {
        short[] firstSamples = createSamples(67, 0xABC);
        short[] secondSamples = createSamples(67, 0x123);
        ByteBuffer reusableInput = unpack(firstSamples, true);
        VectorUnpackedSampleConverter vector = new VectorUnpackedSampleConverter();
        short[] retained = vector.convert(reusableInput);
        ByteBuffer replacement = unpack(secondSamples, true);

        for(int x = 0; x < reusableInput.capacity(); x++)
        {
            reusableInput.put(x, replacement.get(x));
        }

        short[] current = vector.convert(reusableInput);
        assertNotSame(retained, current, "Each native buffer owns its converted samples");
        assertArrayEquals(firstSamples, retained, "Reusing ingress bytes must not change a queued earlier result");
        assertArrayEquals(secondSamples, current);
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

    private static ByteBuffer unpackedSlice(short[] samples, boolean direct, boolean readOnly)
    {
        ByteBuffer encoded = unpack(samples, true);
        int prefix = 13;
        int capacity = encoded.capacity() + 1;
        ByteBuffer parent = direct ? ByteBuffer.allocateDirect(prefix + capacity + prefix) :
            ByteBuffer.allocate(prefix + capacity + prefix);

        for(int x = 0; x < parent.capacity(); x++)
        {
            parent.put(x, (byte)0x5A);
        }

        for(int x = 0; x < encoded.capacity(); x++)
        {
            parent.put(prefix + x, encoded.get(x));
        }

        parent.position(prefix).limit(prefix + capacity);
        ByteBuffer slice = parent.slice();
        return readOnly ? slice.asReadOnlyBuffer() : slice;
    }
}
