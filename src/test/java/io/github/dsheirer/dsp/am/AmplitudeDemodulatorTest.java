/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.am;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Random;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;

class AmplitudeDemodulatorTest
{
    private static final Implementation[] VECTOR_IMPLEMENTATIONS = {
        Implementation.VECTOR_SIMD_64,
        Implementation.VECTOR_SIMD_128,
        Implementation.VECTOR_SIMD_256,
        Implementation.VECTOR_SIMD_512,
        Implementation.VECTOR_SIMD_PREFERRED
    };

    @Test
    void vectorKernelDoesNotRetainRuntimeVectorSpecies()
    {
        assertFalse(List.of(VectorAmplitudeDemodulator.class.getDeclaredFields()).stream().anyMatch(field ->
            !Modifier.isStatic(field.getModifiers()) && VectorSpecies.class.isAssignableFrom(field.getType())),
            "A runtime VectorSpecies field prevents reliable SIMD intrinsic lowering in the AM hot loop");
    }

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

        for(Implementation implementation: VECTOR_IMPLEMENTATIONS)
        {
            int laneCount = laneCount(implementation);
            int[] lengths = {0, 1, laneCount - 1, laneCount, laneCount + 1, (2 * laneCount) - 1,
                2 * laneCount, (2 * laneCount) + 1, (3 * laneCount) + 5, 2048, 2051};
            IDemodulator vector = AmplitudeDemodulatorFactory.getDemodulator(implementation);

            for(int length: lengths)
            {
                float[] i = new float[length];
                float[] q = new float[length];

                for(int x = 0; x < length; x++)
                {
                    i[x] = ((random.nextFloat() * 2.0f) - 1.0f) * (x % 17 == 0 ? 1_000.0f : 1.0f);
                    q[x] = ((random.nextFloat() * 2.0f) - 1.0f) * (x % 19 == 0 ? 0.001f : 1.0f);
                }

                float[] expected = new AmplitudeDemodulator().demodulate(i, q);
                assertArrayEquals(expected, vector.demodulate(i, q), 0.000001f,
                    "Mismatch for " + implementation + " at length " + length);
            }
        }
    }

    private static int laneCount(Implementation implementation)
    {
        return switch(implementation)
        {
            case VECTOR_SIMD_64 -> FloatVector.SPECIES_64.length();
            case VECTOR_SIMD_128 -> FloatVector.SPECIES_128.length();
            case VECTOR_SIMD_256 -> FloatVector.SPECIES_256.length();
            case VECTOR_SIMD_512 -> FloatVector.SPECIES_512.length();
            case VECTOR_SIMD_PREFERRED -> FloatVector.SPECIES_PREFERRED.length();
            default -> throw new IllegalArgumentException("Vector implementation required: " + implementation);
        };
    }
}
