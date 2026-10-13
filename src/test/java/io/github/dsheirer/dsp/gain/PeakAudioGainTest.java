/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.dsp.gain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PeakAudioGainTest
{
    private final PeakAudioGain mGain = new PeakAudioGain(5.0f, 0.95f);

    @Test
    void retainsNominalGainForQuietCallsWithoutMutatingInput()
    {
        float[] input = {0.1f, -0.05f, 0.0f};
        float[] output = mGain.process(input);

        assertEquals(5.0f, mGain.calculateGain(List.of(input)));
        assertArrayEquals(new float[]{0.5f, -0.25f, 0.0f}, output);
        assertArrayEquals(new float[]{0.1f, -0.05f, 0.0f}, input);
        assertNotSame(input, output);
    }

    @Test
    void appliesTheSameGainAcrossEveryFrameInsteadOfFlatteningPeaks()
    {
        List<float[]> input = List.of(new float[]{0.1f, -0.2f}, new float[]{0.4f, -0.8f});
        float gain = mGain.calculateGain(input);
        List<float[]> output = mGain.process(input);

        assertEquals(2, output.size());
        assertTrue(gain < 5.0f);
        assertTrue(Math.abs(output.get(1)[1]) <= 0.95f);

        for(int frame = 0; frame < input.size(); frame++)
        {
            assertEquals(input.get(frame).length, output.get(frame).length);
            assertNotSame(input.get(frame), output.get(frame));

            for(int sample = 0; sample < input.get(frame).length; sample++)
            {
                assertEquals(input.get(frame)[sample] * gain, output.get(frame)[sample]);
            }
        }

        assertArrayEquals(new float[]{0.4f, -0.8f}, input.get(1));
        assertEquals(2.0f, output.get(1)[0] / output.get(0)[1] * -1.0f, 0.000001f);
    }

    @Test
    void canAttenuateOverRangeFloatsWithoutClipping()
    {
        float[] input = {2.0f, 1.0f, -0.5f};
        float[] output = mGain.process(input);

        assertTrue(output[0] <= 0.95f);
        assertEquals(2.0f, output[0] / output[1]);
        assertEquals(-2.0f, output[1] / output[2]);
        assertArrayEquals(new float[]{2.0f, 1.0f, -0.5f}, input);
    }

    @Test
    void alreadyAmplifiedAudioUsesOnlyAvailableHeadroom()
    {
        float[] input = {0.75f, -0.95f, 0.5f};
        float gain = mGain.calculateGain(List.of(input));
        float[] output = mGain.process(input);

        assertTrue(gain <= 1.0f);
        assertTrue(Math.abs(output[1]) <= 0.95f);
        assertEquals(input[0] * gain, output[0]);
        assertEquals(input[2] * gain, output[2]);
    }

    @Test
    void silenceEmptyInputsAndSuccessiveCallsHaveNoGainHistory()
    {
        assertEquals(1.0f, mGain.calculateGain(List.of()));
        assertTrue(mGain.process(List.of()).isEmpty());
        float[] silence = {0.0f, -0.0f};
        assertEquals(1.0f, mGain.calculateGain(List.of(silence)));
        assertArrayEquals(silence, mGain.process(silence));
        mGain.process(new float[]{0.9f});
        assertEquals(5.0f, mGain.calculateGain(List.of(new float[]{0.01f})));
    }

    @Test
    void rejectsNonFiniteSamplesAndInvalidConfiguration()
    {
        for(float sample: new float[]{Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY})
        {
            assertThrows(IllegalArgumentException.class, () -> mGain.process(new float[]{sample}));
        }

        assertThrows(IllegalArgumentException.class, () -> new PeakAudioGain(0.5f, 0.95f));
        assertThrows(IllegalArgumentException.class, () -> new PeakAudioGain(Float.NaN, 0.95f));
        assertThrows(IllegalArgumentException.class, () -> new PeakAudioGain(5.0f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> new PeakAudioGain(5.0f, 1.01f));
        assertThrows(IllegalArgumentException.class, () -> PeakAudioGain.apply(new float[]{1.0f}, Float.NaN));
    }
}
