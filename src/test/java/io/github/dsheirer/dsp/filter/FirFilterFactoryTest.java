/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.filter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.filter.fir.real.RealFIRFilter;
import io.github.dsheirer.dsp.filter.fir.real.VectorRealFIRFilter128Bit;
import io.github.dsheirer.dsp.filter.fir.real.VectorRealFIRFilter256Bit;
import io.github.dsheirer.dsp.filter.fir.real.VectorRealFIRFilter512Bit;
import io.github.dsheirer.dsp.filter.fir.real.VectorRealFIRFilter64Bit;
import io.github.dsheirer.dsp.filter.fir.real.VectorRealFIRFilterDefaultBit;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class FirFilterFactoryTest
{
    private static final float TOLERANCE = 0.000_01f;

    @Test
    void mapsEveryCalibrationResultWithoutMutatingCallerCoefficients()
    {
        float[] coefficients = coefficients();
        float[] original = coefficients.clone();

        assertInstanceOf(RealFIRFilter.class,
            FilterFactory.getRealFilter(coefficients, Implementation.UNCALIBRATED));
        assertArrayEquals(original, coefficients, 0.0f);
        assertInstanceOf(RealFIRFilter.class,
            FilterFactory.getRealFilter(coefficients, Implementation.SCALAR));
        assertArrayEquals(original, coefficients, 0.0f);
        assertInstanceOf(VectorRealFIRFilterDefaultBit.class,
            FilterFactory.getRealFilter(coefficients, Implementation.VECTOR_SIMD_PREFERRED));
        assertArrayEquals(original, coefficients, 0.0f);
        assertInstanceOf(VectorRealFIRFilter64Bit.class,
            FilterFactory.getRealFilter(coefficients, Implementation.VECTOR_SIMD_64));
        assertArrayEquals(original, coefficients, 0.0f);
        assertInstanceOf(VectorRealFIRFilter128Bit.class,
            FilterFactory.getRealFilter(coefficients, Implementation.VECTOR_SIMD_128));
        assertArrayEquals(original, coefficients, 0.0f);
        assertInstanceOf(VectorRealFIRFilter256Bit.class,
            FilterFactory.getRealFilter(coefficients, Implementation.VECTOR_SIMD_256));
        assertArrayEquals(original, coefficients, 0.0f);
        assertInstanceOf(VectorRealFIRFilter512Bit.class,
            FilterFactory.getRealFilter(coefficients, Implementation.VECTOR_SIMD_512));
        assertArrayEquals(original, coefficients, 0.0f);
    }

    @Test
    void productionPulseShapingProfilesUseTheirActualCoefficientLengths()
    {
        float[][] profiles = pulseShapingCoefficients();

        assertEquals(16, profiles[0].length); //DMR low-rate, 12-symbol span
        assertEquals(42, profiles[1].length); //P25 Phase 1, 16-symbol span
        assertEquals(40, profiles[2].length); //DMR at 19.2 kHz, 20-symbol span
        assertEquals(87, profiles[3].length); //DMR high-rate, 26-symbol span
        assertEquals(135, profiles[4].length); //NXDN, 26-symbol span
    }

    @TestFactory
    List<DynamicTest> everyVectorWidthMatchesScalarAcrossTapProfilesAndChangingBuffers()
    {
        List<DynamicTest> tests = new ArrayList<>();

        for(FilterVariant variant: vectorVariants())
        {
            for(float[] coefficients: tapProfiles())
            {
                String name = variant.name() + " / " + coefficients.length + " taps";
                tests.add(DynamicTest.dynamicTest(name, () -> assertStreamingParity(coefficients, variant.factory())));
            }
        }

        return tests;
    }

    private static void assertStreamingParity(float[] coefficients, Function<float[], IRealFilter> vectorFactory)
    {
        float[] original = coefficients.clone();
        IRealFilter scalar = FilterFactory.getRealFilter(coefficients, Implementation.SCALAR);
        IRealFilter vector = vectorFactory.apply(coefficients);
        assertArrayEquals(original, coefficients, 0.0f);
        int[] sizes = {1, 0, 17, 2, 63, 257, 5, 512, 3};
        int offset = 0;

        for(int size: sizes)
        {
            float[] buffer = samples(size, offset);
            float[] expected = scalar.filter(buffer);
            assertArrayEquals(expected, vector.filter(buffer), TOLERANCE,
                vector.getClass().getSimpleName() + " with " + coefficients.length + " taps at offset " + offset);
            offset += size;
        }
    }

    private static List<FilterVariant> vectorVariants()
    {
        return List.of(
            new FilterVariant("preferred", VectorRealFIRFilterDefaultBit::new),
            new FilterVariant("64-bit", VectorRealFIRFilter64Bit::new),
            new FilterVariant("128-bit", VectorRealFIRFilter128Bit::new),
            new FilterVariant("256-bit", VectorRealFIRFilter256Bit::new),
            new FilterVariant("512-bit", VectorRealFIRFilter512Bit::new));
    }

    private static float[][] tapProfiles()
    {
        float[][] production = pulseShapingCoefficients();
        return new float[][]{
            production[0],
            coefficients(31),
            production[2],
            production[1],
            coefficients(63),
            production[3],
            production[4]
        };
    }

    private static float[][] pulseShapingCoefficients()
    {
        return new float[][]{
            FilterFactory.getRootRaisedCosine(12_500.0 / 4_800.0, 12, 5_760.0f / 12_500.0f),
            FilterFactory.getRootRaisedCosine(25_000.0 / 4_800.0, 16, 0.2f),
            FilterFactory.getRootRaisedCosine(19_200.0 / 4_800.0, 20, 5_760.0f / 19_200.0f),
            FilterFactory.getRootRaisedCosine(32_000.0 / 4_800.0, 26, 5_760.0f / 32_000.0f),
            FilterFactory.getRRC(25_000.0 / 4_800.0, 26, 0.2f)
        };
    }

    private static float[] coefficients()
    {
        return coefficients(31);
    }

    private static float[] coefficients(int tapCount)
    {
        float[] coefficients = new float[tapCount];

        for(int x = 0; x < coefficients.length; x++)
        {
            coefficients[x] = (float)(Math.sin((x + 1) * 0.31) * 0.03 + x * 0.000_2);
        }

        return coefficients;
    }

    private static float[] samples(int length, int offset)
    {
        float[] samples = new float[length];

        for(int x = 0; x < length; x++)
        {
            samples[x] = (float)(Math.sin((x + offset) * 0.17) + Math.cos((x + offset) * 0.071) * 0.2);
        }

        return samples;
    }

    private record FilterVariant(String name, Function<float[], IRealFilter> factory)
    {
    }
}
