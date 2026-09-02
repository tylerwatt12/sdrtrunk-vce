/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.psk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.dsp.filter.interpolator.Interpolator;
import io.github.dsheirer.dsp.filter.interpolator.InterpolatorScalar;
import io.github.dsheirer.dsp.filter.interpolator.InterpolatorVector128;
import io.github.dsheirer.dsp.filter.interpolator.InterpolatorVector256;
import io.github.dsheirer.dsp.filter.interpolator.InterpolatorVector64;
import io.github.dsheirer.sample.complex.Complex;
import org.junit.jupiter.api.Test;

class InterpolatingSampleBufferTest
{
    private static final float TOLERANCE = 0.000_001f;

    @Test
    void vectorInterpolatorsPreserveCompleteCircularBufferBehavior()
    {
        InterpolatingSampleBuffer scalar = buffer(new InterpolatorScalar());

        for(Interpolator vector: new Interpolator[]{new InterpolatorVector64(), new InterpolatorVector128(),
            new InterpolatorVector256()})
        {
            InterpolatingSampleBuffer vectorBuffer = buffer(vector);

            for(float interpolation: new float[]{-0.25f, 0.0f, 0.125f, 0.5f, 0.992_187_5f, 1.0f, 2.75f})
            {
                assertEquals(scalar.getInphase(interpolation), vectorBuffer.getInphase(interpolation), TOLERANCE,
                    vector.getClass().getSimpleName() + " I at " + interpolation);
                assertEquals(scalar.getQuadrature(interpolation), vectorBuffer.getQuadrature(interpolation), TOLERANCE,
                    vector.getClass().getSimpleName() + " Q at " + interpolation);
            }
        }
    }

    private static InterpolatingSampleBuffer buffer(Interpolator interpolator)
    {
        InterpolatingSampleBuffer buffer = new InterpolatingSampleBuffer(8.0f, 0.1f, interpolator);

        for(int x = 0; x < 23; x++)
        {
            buffer.receive(new Complex((float)Math.sin(x * 0.23), (float)Math.cos(x * 0.31)));
        }

        return buffer;
    }
}
