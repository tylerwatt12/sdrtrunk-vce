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

/**
 * Accumulates the polyphase channelizer sample/filter products into one value for each I/Q sub-channel.
 *
 * Implementations must overwrite every accumulator element, must not allocate, and must preserve the ascending tap
 * order for each sub-channel.  The input and filter arrays must each contain at least
 * {@code tapsPerChannel * subChannelCount} elements and the accumulator must contain at least
 * {@code subChannelCount} elements.
 */
public interface IPolyphaseChannelizerFilter
{
    /**
     * Filters one complete channelizer block.
     *
     * @param samples inline channelizer samples
     * @param coefficients inline channelizer filter coefficients
     * @param accumulator destination for one accumulated value per I/Q sub-channel
     * @param tapsPerChannel number of taps for each sub-channel
     * @param subChannelCount number of interleaved I/Q sub-channels
     */
    void filter(float[] samples, float[] coefficients, float[] accumulator, int tapsPerChannel, int subChannelCount);
}
