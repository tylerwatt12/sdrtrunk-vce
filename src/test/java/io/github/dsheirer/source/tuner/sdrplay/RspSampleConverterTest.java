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

package io.github.dsheirer.source.tuner.sdrplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;

class RspSampleConverterTest
{
    private static final long TIMESTAMP = 1_725_000_123_456L;
    private static final int[] LENGTHS = {0, 1, 2, 3, 4, 7, 8, 15, 16, 31, 32, 33, 63, 64, 65, 127, 128, 129,
        2016, 2048, 2051};
    private static final Implementation[] VECTOR_IMPLEMENTATIONS = {
        Implementation.VECTOR_SIMD_64,
        Implementation.VECTOR_SIMD_128,
        Implementation.VECTOR_SIMD_256,
        Implementation.VECTOR_SIMD_512,
        Implementation.VECTOR_SIMD_PREFERRED
    };

    @Test
    void converterDoesNotRetainRuntimeVectorSpecies()
    {
        assertFalse(List.of(VectorRspSampleConverter.class.getDeclaredFields()).stream().anyMatch(field ->
            !Modifier.isStatic(field.getModifiers()) && VectorSpecies.class.isAssignableFrom(field.getType())),
            "A runtime VectorSpecies field prevents reliable SIMD intrinsic lowering in the converter hot loop");
    }

    @Test
    void preferredSpeciesUsesTheWidestSupportedCommonBitWidth()
    {
        assertEquals(64, VectorRspSampleConverter.selectPreferredVectorBitSize(64, 512));
        assertEquals(128, VectorRspSampleConverter.selectPreferredVectorBitSize(512, 128));
        assertEquals(256, VectorRspSampleConverter.selectPreferredVectorBitSize(384, 512));
        assertEquals(512, VectorRspSampleConverter.selectPreferredVectorBitSize(1024, 512));
        assertThrows(IllegalStateException.class,
            () -> VectorRspSampleConverter.selectPreferredVectorBitSize(32, 512));

        VectorRspSampleConverter preferred =
            new VectorRspSampleConverter(Implementation.VECTOR_SIMD_PREFERRED);
        int preferredBitSize = VectorRspSampleConverter.getPreferredVectorBitSize();
        assertEquals(preferredBitSize, preferred.getVectorBitSize());
        assertTrue(preferredBitSize <= ShortVector.SPECIES_PREFERRED.vectorBitSize());
        assertTrue(preferredBitSize <= FloatVector.SPECIES_PREFERRED.vectorBitSize());
        assertEquals(2 * preferred.getFloatLaneCount(), preferred.getShortLaneCount());
    }

    @Test
    void explicitImplementationsUseMatchingSpeciesAndRejectMismatches()
    {
        Implementation[] implementations = {
            Implementation.VECTOR_SIMD_64,
            Implementation.VECTOR_SIMD_128,
            Implementation.VECTOR_SIMD_256,
            Implementation.VECTOR_SIMD_512
        };
        int[] bitSizes = {64, 128, 256, 512};

        for(int x = 0; x < implementations.length; x++)
        {
            VectorRspSampleConverter converter = new VectorRspSampleConverter(implementations[x]);
            assertEquals(bitSizes[x], converter.getVectorBitSize());
            assertEquals(2 * converter.getFloatLaneCount(), converter.getShortLaneCount());
        }

        assertThrows(IllegalArgumentException.class,
            () -> new VectorRspSampleConverter(ShortVector.SPECIES_64, FloatVector.SPECIES_128));
        assertThrows(IllegalArgumentException.class,
            () -> new VectorRspSampleConverter(ShortVector.SPECIES_256, FloatVector.SPECIES_128));
        assertThrows(IllegalArgumentException.class,
            () -> new VectorRspSampleConverter(Implementation.SCALAR));
    }

    @Test
    void vectorConvertersExactlyMatchScalarForAlignedAndTailLengths()
    {
        IRspSampleConverter scalar = RspSampleConverterFactory.getConverter(Implementation.SCALAR);

        for(int length: LENGTHS)
        {
            short[][] fixture = createFixture(length);
            ConversionOutput expected = convert(scalar, fixture[0], fixture[1]);

            for(Implementation implementation: VECTOR_IMPLEMENTATIONS)
            {
                ConversionOutput actual = convert(RspSampleConverterFactory.getConverter(implementation), fixture[0],
                    fixture[1]);
                String message = implementation + " at length " + length;
                assertArrayEquals(expected.i(), actual.i(), 0.0f, message + " separate I");
                assertArrayEquals(expected.q(), actual.q(), 0.0f, message + " separate Q");
                assertArrayEquals(expected.interleaved(), actual.interleaved(), 0.0f, message + " interleaved");
            }
        }
    }

    @Test
    void nativeBufferPreservesIteratorOutputsAndTimestampForEveryImplementation()
    {
        Implementation[] implementations = {
            Implementation.SCALAR,
            Implementation.VECTOR_SIMD_64,
            Implementation.VECTOR_SIMD_128,
            Implementation.VECTOR_SIMD_256,
            Implementation.VECTOR_SIMD_512,
            Implementation.VECTOR_SIMD_PREFERRED
        };
        IRspSampleConverter scalar = RspSampleConverterFactory.getConverter(Implementation.SCALAR);

        for(int length: LENGTHS)
        {
            short[][] fixture = createFixture(length);
            ConversionOutput expected = convert(scalar, fixture[0], fixture[1]);

            for(Implementation implementation: implementations)
            {
                RspNativeBuffer buffer = new RspNativeBuffer(fixture[0], fixture[1], TIMESTAMP, 2_000.0f,
                    RspSampleConverterFactory.getConverter(implementation));
                assertEquals(length, buffer.sampleCount());

                Iterator<ComplexSamples> separateIterator = buffer.iterator();
                Iterator<InterleavedComplexSamples> interleavedIterator = buffer.iteratorInterleaved();

                if(length == 0)
                {
                    assertFalse(separateIterator.hasNext());
                    assertFalse(interleavedIterator.hasNext());
                    continue;
                }

                assertTrue(separateIterator.hasNext());
                ComplexSamples separate = separateIterator.next();
                assertEquals(TIMESTAMP, separate.timestamp());
                assertArrayEquals(expected.i(), separate.i(), 0.0f, implementation + " native buffer I");
                assertArrayEquals(expected.q(), separate.q(), 0.0f, implementation + " native buffer Q");
                assertFalse(separateIterator.hasNext());

                assertTrue(interleavedIterator.hasNext());
                InterleavedComplexSamples interleaved = interleavedIterator.next();
                assertEquals(TIMESTAMP, interleaved.timestamp());
                assertArrayEquals(expected.interleaved(), interleaved.samples(), 0.0f,
                    implementation + " native buffer interleaved");
                assertFalse(interleavedIterator.hasNext());
            }
        }
    }

    @Test
    void rejectsMismatchedAndIncorrectlySizedBuffers()
    {
        IRspSampleConverter scalar = RspSampleConverterFactory.getConverter(Implementation.SCALAR);
        assertThrows(IllegalArgumentException.class,
            () -> scalar.convert(new short[2], new short[1], new float[2], new float[2]));
        assertThrows(IllegalArgumentException.class,
            () -> scalar.convert(new short[2], new short[2], new float[1], new float[2]));
        assertThrows(IllegalArgumentException.class,
            () -> scalar.convertInterleaved(new short[2], new short[2], new float[3]));
        assertThrows(IllegalArgumentException.class,
            () -> new RspNativeBuffer(new short[2], new short[1], TIMESTAMP, 1.0f, scalar));
        assertThrows(NullPointerException.class,
            () -> new RspNativeBuffer(new short[2], new short[2], TIMESTAMP, 1.0f, null));
    }

    private static ConversionOutput convert(IRspSampleConverter converter, short[] iSamples, short[] qSamples)
    {
        float[] iOutput = new float[iSamples.length];
        float[] qOutput = new float[qSamples.length];
        float[] interleavedOutput = new float[iSamples.length * 2];
        converter.convert(iSamples, qSamples, iOutput, qOutput);
        converter.convertInterleaved(iSamples, qSamples, interleavedOutput);
        return new ConversionOutput(iOutput, qOutput, interleavedOutput);
    }

    private static short[][] createFixture(int length)
    {
        Random random = new Random(0x52535053494D44L + length);
        short[] i = new short[length];
        short[] q = new short[length];

        for(int x = 0; x < length; x++)
        {
            i[x] = (short)random.nextInt();
            q[x] = (short)random.nextInt();
        }

        short[] edgeValues = {Short.MIN_VALUE, Short.MAX_VALUE, -1, 0, 1};

        for(int x = 0; x < Math.min(length, edgeValues.length); x++)
        {
            i[x] = edgeValues[x];
            q[x] = edgeValues[edgeValues.length - 1 - x];
        }

        return new short[][]{i, q};
    }

    private record ConversionOutput(float[] i, float[] q, float[] interleaved)
    {
    }
}
