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
        int vectorUpperBound = FloatVector.SPECIES_64.loopBound(subChannelCount);

        for(int channel = 0; channel < vectorUpperBound; channel += FloatVector.SPECIES_64.length())
        {
            FloatVector accumulated = FloatVector.zero(FloatVector.SPECIES_64);

            for(int tap = 0; tap < tapsPerChannel; tap++)
            {
                int index = tap * subChannelCount + channel;
                FloatVector sample = FloatVector.fromArray(FloatVector.SPECIES_64, samples, index);
                FloatVector coefficient = FloatVector.fromArray(FloatVector.SPECIES_64, coefficients, index);
                accumulated = accumulated.add(sample.mul(coefficient));
            }

            accumulated.intoArray(accumulator, channel);
        }

        filterScalarTail(samples, coefficients, accumulator, tapsPerChannel, subChannelCount, vectorUpperBound);
    }

    static void filter128(float[] samples, float[] coefficients, float[] accumulator, int tapsPerChannel,
                          int subChannelCount)
    {
        int vectorUpperBound = FloatVector.SPECIES_128.loopBound(subChannelCount);

        for(int channel = 0; channel < vectorUpperBound; channel += FloatVector.SPECIES_128.length())
        {
            FloatVector accumulated = FloatVector.zero(FloatVector.SPECIES_128);

            for(int tap = 0; tap < tapsPerChannel; tap++)
            {
                int index = tap * subChannelCount + channel;
                FloatVector sample = FloatVector.fromArray(FloatVector.SPECIES_128, samples, index);
                FloatVector coefficient = FloatVector.fromArray(FloatVector.SPECIES_128, coefficients, index);
                accumulated = accumulated.add(sample.mul(coefficient));
            }

            accumulated.intoArray(accumulator, channel);
        }

        filterScalarTail(samples, coefficients, accumulator, tapsPerChannel, subChannelCount, vectorUpperBound);
    }

    static void filter256(float[] samples, float[] coefficients, float[] accumulator, int tapsPerChannel,
                          int subChannelCount)
    {
        int vectorUpperBound = FloatVector.SPECIES_256.loopBound(subChannelCount);

        for(int channel = 0; channel < vectorUpperBound; channel += FloatVector.SPECIES_256.length())
        {
            FloatVector accumulated = FloatVector.zero(FloatVector.SPECIES_256);

            for(int tap = 0; tap < tapsPerChannel; tap++)
            {
                int index = tap * subChannelCount + channel;
                FloatVector sample = FloatVector.fromArray(FloatVector.SPECIES_256, samples, index);
                FloatVector coefficient = FloatVector.fromArray(FloatVector.SPECIES_256, coefficients, index);
                accumulated = accumulated.add(sample.mul(coefficient));
            }

            accumulated.intoArray(accumulator, channel);
        }

        filterScalarTail(samples, coefficients, accumulator, tapsPerChannel, subChannelCount, vectorUpperBound);
    }

    static void filter512(float[] samples, float[] coefficients, float[] accumulator, int tapsPerChannel,
                          int subChannelCount)
    {
        int vectorUpperBound = FloatVector.SPECIES_512.loopBound(subChannelCount);

        for(int channel = 0; channel < vectorUpperBound; channel += FloatVector.SPECIES_512.length())
        {
            FloatVector accumulated = FloatVector.zero(FloatVector.SPECIES_512);

            for(int tap = 0; tap < tapsPerChannel; tap++)
            {
                int index = tap * subChannelCount + channel;
                FloatVector sample = FloatVector.fromArray(FloatVector.SPECIES_512, samples, index);
                FloatVector coefficient = FloatVector.fromArray(FloatVector.SPECIES_512, coefficients, index);
                accumulated = accumulated.add(sample.mul(coefficient));
            }

            accumulated.intoArray(accumulator, channel);
        }

        filterScalarTail(samples, coefficients, accumulator, tapsPerChannel, subChannelCount, vectorUpperBound);
    }

    /**
     * Finishes channels that do not fill the selected vector width.  Keeping the species constants in the four hot
     * kernels above is intentional: passing a species object through this shared helper caused Java 25 to allocate
     * vector intermediates instead of lowering them to SIMD instructions on x64 receivers.
     */
    private static void filterScalarTail(float[] samples, float[] coefficients, float[] accumulator,
                                         int tapsPerChannel, int subChannelCount, int firstChannel)
    {
        for(int channel = firstChannel; channel < subChannelCount; channel++)
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
