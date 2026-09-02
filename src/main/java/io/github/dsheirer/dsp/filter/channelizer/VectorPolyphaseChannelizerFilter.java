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

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Explicit Java Vector API implementation of the polyphase channelizer filter.
 *
 * Vector lanes span independent sub-channels.  Each lane visits taps in the same ascending order as the scalar
 * implementation and uses distinct multiply and add operations instead of FMA, preserving the scalar floating-point
 * arithmetic order.  Any channels beyond the last complete vector are handled by the same ordered scalar loop.
 */
final class VectorPolyphaseChannelizerFilter
{
    private VectorPolyphaseChannelizerFilter()
    {
    }

    static void filter64(float[] samples, float[] coefficients, float[] accumulator, int tapsPerChannel,
                         int subChannelCount)
    {
        filter(FloatVector.SPECIES_64, samples, coefficients, accumulator, tapsPerChannel, subChannelCount);
    }

    static void filter128(float[] samples, float[] coefficients, float[] accumulator, int tapsPerChannel,
                          int subChannelCount)
    {
        filter(FloatVector.SPECIES_128, samples, coefficients, accumulator, tapsPerChannel, subChannelCount);
    }

    static void filter256(float[] samples, float[] coefficients, float[] accumulator, int tapsPerChannel,
                          int subChannelCount)
    {
        filter(FloatVector.SPECIES_256, samples, coefficients, accumulator, tapsPerChannel, subChannelCount);
    }

    static void filter512(float[] samples, float[] coefficients, float[] accumulator, int tapsPerChannel,
                          int subChannelCount)
    {
        filter(FloatVector.SPECIES_512, samples, coefficients, accumulator, tapsPerChannel, subChannelCount);
    }

    private static void filter(VectorSpecies<Float> species, float[] samples, float[] coefficients,
                               float[] accumulator, int tapsPerChannel, int subChannelCount)
    {
        int vectorUpperBound = species.loopBound(subChannelCount);

        //Keep each lane's accumulator in a vector register while visiting taps in the original ascending order.
        for(int channel = 0; channel < vectorUpperBound; channel += species.length())
        {
            FloatVector accumulated = FloatVector.zero(species);

            for(int tap = 0; tap < tapsPerChannel; tap++)
            {
                int index = tap * subChannelCount + channel;
                FloatVector sample = FloatVector.fromArray(species, samples, index);
                FloatVector coefficient = FloatVector.fromArray(species, coefficients, index);
                accumulated = accumulated.add(sample.mul(coefficient));
            }

            accumulated.intoArray(accumulator, channel);
        }

        //The channel count is not guaranteed to fill the widest vector species.
        for(int channel = vectorUpperBound; channel < subChannelCount; channel++)
        {
            float accumulated = 0.0f;

            for(int tap = 0; tap < tapsPerChannel; tap++)
            {
                int index = tap * subChannelCount + channel;
                accumulated += samples[index] * coefficients[index];
            }

            accumulator[channel] = accumulated;
        }
    }
}
