/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.filter.interpolator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.vector.calibrate.Implementation;
import org.junit.jupiter.api.Test;

class InterpolatorFactoryTest
{
    private static final float TOLERANCE = 0.000_001f;

    @Test
    void mapsEveryCalibrationResultToAnInterpolator()
    {
        assertInstanceOf(InterpolatorScalar.class,
            InterpolatorFactory.getInterpolator(Implementation.UNCALIBRATED));
        assertInstanceOf(InterpolatorScalar.class,
            InterpolatorFactory.getInterpolator(Implementation.SCALAR));
        assertInstanceOf(InterpolatorVector64.class,
            InterpolatorFactory.getInterpolator(Implementation.VECTOR_SIMD_64));
        assertInstanceOf(InterpolatorVector128.class,
            InterpolatorFactory.getInterpolator(Implementation.VECTOR_SIMD_128));
        assertInstanceOf(InterpolatorVector256.class,
            InterpolatorFactory.getInterpolator(Implementation.VECTOR_SIMD_256));
        assertInstanceOf(InterpolatorVector256.class,
            InterpolatorFactory.getInterpolator(Implementation.VECTOR_SIMD_512));
        assertThrows(IllegalArgumentException.class,
            () -> InterpolatorFactory.getInterpolator(Implementation.VECTOR_SIMD_PREFERRED));
    }

    @Test
    void everyVectorWidthMatchesScalarAcrossAllInterpolationPhasesAndOffsets()
    {
        float[] samples = new float[29];

        for(int x = 0; x < samples.length; x++)
        {
            samples[x] = (float)(Math.sin(x * 0.37) + Math.cos(x * 0.11) * 0.25);
        }

        Interpolator scalar = new InterpolatorScalar();
        Interpolator[] vectors =
            {new InterpolatorVector64(), new InterpolatorVector128(), new InterpolatorVector256()};

        for(Interpolator vector: vectors)
        {
            for(int offset: new int[]{0, 3, 12, 21})
            {
                for(int step = 0; step <= Interpolator.NSTEPS; step++)
                {
                    float mu = (float)step / Interpolator.NSTEPS;
                    assertEquals(scalar.filter(samples, offset, mu), vector.filter(samples, offset, mu), TOLERANCE,
                        vector.getClass().getSimpleName() + " offset=" + offset + " phase=" + step);
                }
            }
        }
    }

    @Test
    void rejectsASequenceShorterThanEightSamples()
    {
        float[] tooShort = new float[Interpolator.NTAPS - 1];

        assertThrows(IllegalArgumentException.class, () -> new InterpolatorScalar().filter(tooShort, 0, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> new InterpolatorVector128().filter(tooShort, 0, 0.5f));
    }
}
