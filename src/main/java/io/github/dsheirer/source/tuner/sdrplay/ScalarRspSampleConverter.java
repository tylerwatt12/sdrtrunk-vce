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
 * Scalar SDRplay signed-short sample converter.
 */
public class ScalarRspSampleConverter implements IRspSampleConverter
{
    @Override
    public void convert(short[] iSamples, short[] qSamples, float[] iOutput, float[] qOutput)
    {
        RspSampleConverterFactory.validate(iSamples, qSamples, iOutput, qOutput);

        for(int x = 0; x < iSamples.length; x++)
        {
            iOutput[x] = iSamples[x] * SAMPLE_TO_FLOAT;
            qOutput[x] = qSamples[x] * SAMPLE_TO_FLOAT;
        }
    }

    @Override
    public void convertInterleaved(short[] iSamples, short[] qSamples, float[] output)
    {
        RspSampleConverterFactory.validateInterleaved(iSamples, qSamples, output);
        int outputPointer = 0;

        for(int x = 0; x < iSamples.length; x++)
        {
            output[outputPointer++] = iSamples[x] * SAMPLE_TO_FLOAT;
            output[outputPointer++] = qSamples[x] * SAMPLE_TO_FLOAT;
        }
    }
}
