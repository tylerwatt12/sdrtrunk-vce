/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.List;
import org.junit.jupiter.api.Test;

class AudioUtilsTest
{
    @Test
    void normalizeScalesPrivateCopiesWithoutChangingCallerBuffers()
    {
        float[] first = {0.1f, -0.05f};
        float[] second = {0.2f, -0.2f};
        float[] originalFirst = first.clone();
        float[] originalSecond = second.clone();
        List<float[]> audioBuffers = List.of(first, second);

        List<float[]> normalized = AudioUtils.normalize(audioBuffers);

        assertNotSame(audioBuffers, normalized);
        assertNotSame(first, normalized.get(0));
        assertNotSame(second, normalized.get(1));
        assertArrayEquals(originalFirst, first);
        assertArrayEquals(originalSecond, second);
        assertArrayEquals(new float[]{0.475f, -0.2375f}, normalized.get(0), 0.000001f);
        assertArrayEquals(new float[]{0.95f, -0.95f}, normalized.get(1), 0.000001f);
    }

    @Test
    void normalizeStillCopiesBuffersWhenNoGainChangeIsNeeded()
    {
        float[] source = {0.95f, -0.25f};
        List<float[]> audioBuffers = List.of(source);

        List<float[]> normalized = AudioUtils.normalize(audioBuffers);

        assertNotSame(audioBuffers, normalized);
        assertNotSame(source, normalized.getFirst());
        assertArrayEquals(source, normalized.getFirst());
    }
}
