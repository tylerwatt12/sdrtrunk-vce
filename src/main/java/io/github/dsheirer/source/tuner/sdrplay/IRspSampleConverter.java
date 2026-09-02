/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.sdrplay;

/**
 * Converts signed SDRplay native I/Q samples to normalized floating point samples.
 */
public interface IRspSampleConverter
{
    /** Signed 16-bit sample normalization used by SDRplay buffers. */
    float SAMPLE_TO_FLOAT = 1.0f / 32768.0f;

    /**
     * Converts I and Q samples into separate output arrays.
     */
    void convert(short[] iSamples, short[] qSamples, float[] iOutput, float[] qOutput);

    /**
     * Converts I and Q samples into an output array ordered I, Q, I, Q, ...
     */
    void convertInterleaved(short[] iSamples, short[] qSamples, float[] output);
}
