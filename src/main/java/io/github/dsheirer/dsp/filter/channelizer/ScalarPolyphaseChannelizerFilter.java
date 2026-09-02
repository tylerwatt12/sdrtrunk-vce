/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.dsp.filter.channelizer;

import java.util.Arrays;

/**
 * Scalar polyphase channelizer filter.  This is the original production loop retained as the calibrated fallback.
 */
final class ScalarPolyphaseChannelizerFilter implements IPolyphaseChannelizerFilter
{
    @Override
    public void filter(float[] samples, float[] coefficients, float[] accumulator, int tapsPerChannel,
                       int subChannelCount)
    {
        Arrays.fill(accumulator, 0.0f);

        for(int tap = 0; tap < tapsPerChannel; tap++)
        {
            int tapOffset = tap * subChannelCount;

            for(int channel = 0; channel < subChannelCount; channel++)
            {
                int index = tapOffset + channel;
                accumulator[channel] += samples[index] * coefficients[index];
            }
        }
    }
}
