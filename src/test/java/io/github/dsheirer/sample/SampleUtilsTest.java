/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.sample;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.sample.complex.ComplexSamples;
import org.junit.jupiter.api.Test;

class SampleUtilsTest
{
    @Test
    void asymmetricIqSamplesRoundTripWithTheirTimestamp()
    {
        ComplexSamples original = new ComplexSamples(new float[]{-0.125f, 0.5f, -0.75f},
            new float[]{0.25f, -0.375f, 1.0f}, 123_456_789L);

        float[] interleaved = SampleUtils.interleave(original);
        assertArrayEquals(new float[]{-0.125f, 0.25f, 0.5f, -0.375f, -0.75f, 1.0f}, interleaved);

        ComplexSamples restored = SampleUtils.deinterleave(interleaved, original.timestamp());
        assertArrayEquals(original.i(), restored.i());
        assertArrayEquals(original.q(), restored.q());
        assertEquals(original.timestamp(), restored.timestamp());
    }
}
