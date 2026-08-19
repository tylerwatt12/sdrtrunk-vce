/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.window.WindowType;
import java.util.ArrayList;
import java.util.List;

/**
 * Worker-confined, allocation-bounded mixer and half-band decimator for the optional tuner zoom display.
 *
 * <p>The normal mixer and decimator interfaces return a new array at every stage.  A maximum-detail D32 lens can
 * therefore create several megabytes of short-lived arrays for every displayed frame.  This implementation retains
 * two I/Q workspaces and makes every half-band stage write into the alternate workspace.  Configuration may replace
 * the small filter-state objects, but steady-state processing does not allocate arrays.</p>
 */
final class DiagnosticZoomDsp
{
    private static final double TWO_PI = 2.0 * Math.PI;
    private float[] mPingI = new float[0];
    private float[] mPingQ = new float[0];
    private float[] mPongI = new float[0];
    private float[] mPongQ = new float[0];
    private List<HalfBandStage> mStages = List.of();
    private int mDecimation = 1;
    private float mCosineStep = 1.0f;
    private float mSineStep;
    private float mPreviousI = 1.0f;
    private float mPreviousQ;
    private long mWorkspaceAllocations;

    void configure(int sourceSampleCount, int decimation, double mixerFrequencyHz, double sampleRateHz)
    {
        if(sourceSampleCount < 1 || Integer.bitCount(decimation) != 1 || decimation < 1 || decimation > 32 ||
            sourceSampleCount % decimation != 0 || !Double.isFinite(mixerFrequencyHz) ||
            !Double.isFinite(sampleRateHz) || sampleRateHz <= 0)
        {
            throw new IllegalArgumentException("Diagnostic zoom configuration is invalid");
        }

        if(mPingI.length < sourceSampleCount)
        {
            mPingI = new float[sourceSampleCount];
            mPingQ = new float[sourceSampleCount];
            mWorkspaceAllocations += 2;
        }

        int alternateSize = Math.max(1, sourceSampleCount / 2);

        if(mPongI.length < alternateSize)
        {
            mPongI = new float[alternateSize];
            mPongQ = new float[alternateSize];
            mWorkspaceAllocations += 2;
        }

        if(mDecimation != decimation || mStages.size() != Integer.numberOfTrailingZeros(decimation))
        {
            mStages = stages(decimation);
            mDecimation = decimation;
            mWorkspaceAllocations += mStages.size() * 2L;
        }
        else
        {
            mStages.forEach(HalfBandStage::reset);
        }

        double angle = TWO_PI * mixerFrequencyHz / sampleRateHz;
        mCosineStep = (float)Math.cos(angle);
        mSineStep = (float)Math.sin(angle);
        mPreviousI = 1.0f;
        mPreviousQ = 0.0f;
    }

    /** Mixes and decimates interleaved source samples into interleaved destination samples. */
    void process(float[] source, int sourceSampleCount, float[] destination)
    {
        if(source == null || source.length < sourceSampleCount * 2 || sourceSampleCount < 1 ||
            sourceSampleCount % mDecimation != 0 || destination == null ||
            destination.length > sourceSampleCount * 2 / mDecimation || (destination.length & 1) != 0)
        {
            throw new IllegalArgumentException("Diagnostic zoom sample buffers are invalid");
        }

        mix(source, sourceSampleCount);
        float[] inputI = mPingI;
        float[] inputQ = mPingQ;
        float[] outputI = mPongI;
        float[] outputQ = mPongQ;
        int length = sourceSampleCount;

        for(HalfBandStage stage: mStages)
        {
            length = stage.process(inputI, inputQ, length, outputI, outputQ);
            float[] swap = inputI;
            inputI = outputI;
            outputI = swap;
            swap = inputQ;
            inputQ = outputQ;
            outputQ = swap;
        }

        int destinationSamples = destination.length / 2;
        int start = length - destinationSamples;

        for(int sample = start, offset = 0; sample < length; sample++, offset += 2)
        {
            destination[offset] = inputI[sample];
            destination[offset + 1] = inputQ[sample];
        }
    }

    private void mix(float[] source, int sampleCount)
    {
        float previousI = mPreviousI;
        float previousQ = mPreviousQ;

        for(int sample = 0, offset = 0; sample < sampleCount; sample++, offset += 2)
        {
            float oscillatorI = previousI * mCosineStep - previousQ * mSineStep;
            float oscillatorQ = previousI * mSineStep + previousQ * mCosineStep;

            if((sample + 1) % 100 == 0)
            {
                float gain = (3.0f - oscillatorI * oscillatorI - oscillatorQ * oscillatorQ) / 2.0f;
                oscillatorI *= gain;
                oscillatorQ *= gain;
            }

            float sourceI = source[offset];
            float sourceQ = source[offset + 1];
            mPingI[sample] = oscillatorI * sourceI - oscillatorQ * sourceQ;
            mPingQ[sample] = oscillatorQ * sourceI + oscillatorI * sourceQ;
            previousI = oscillatorI;
            previousQ = oscillatorQ;
        }

        mPreviousI = previousI;
        mPreviousQ = previousQ;
    }

    private static List<HalfBandStage> stages(int decimation)
    {
        List<HalfBandStage> stages = new ArrayList<>();

        if(decimation >= 32)
        {
            stages.add(new HalfBandStage(FilterFactory.getHalfBand(11, WindowType.BLACKMAN)));
        }

        if(decimation >= 16)
        {
            stages.add(new HalfBandStage(FilterFactory.getHalfBand(15, WindowType.BLACKMAN)));
        }

        if(decimation >= 8)
        {
            stages.add(new HalfBandStage(FilterFactory.getHalfBand(15, WindowType.BLACKMAN)));
        }

        if(decimation >= 4)
        {
            stages.add(new HalfBandStage(FilterFactory.getHalfBand(23, WindowType.BLACKMAN)));
        }

        if(decimation >= 2)
        {
            stages.add(new HalfBandStage(FilterFactory.getHalfBand(63, WindowType.HAMMING)));
        }

        return List.copyOf(stages);
    }

    static void decibels(float[] fft, float[] destination)
    {
        if(fft == null || destination == null || fft.length != destination.length * 2)
        {
            throw new IllegalArgumentException("Diagnostic decibel buffers are invalid");
        }

        float scale = 1.0f / destination.length;
        int middle = destination.length / 2;

        for(int offset = 0; offset < fft.length; offset += 2)
        {
            float magnitude = fft[offset] * fft[offset] + fft[offset + 1] * fft[offset + 1];
            float decibels = magnitude == 0.0f ? -196.0f : 10.0f * (float)Math.log10(magnitude * scale);
            int index = offset / 2;
            destination[index >= middle ? index - middle : index + middle] = decibels;
        }
    }

    long workspaceAllocationCount()
    {
        return mWorkspaceAllocations;
    }

    private static final class HalfBandStage
    {
        private final float[] mCoefficients;
        private final float[] mOverlapI;
        private final float[] mOverlapQ;

        private HalfBandStage(float[] coefficients)
        {
            mCoefficients = coefficients;
            mOverlapI = new float[coefficients.length - 1];
            mOverlapQ = new float[coefficients.length - 1];
        }

        private int process(float[] inputI, float[] inputQ, int inputLength, float[] outputI, float[] outputQ)
        {
            if((inputLength & 1) != 0 || outputI.length < inputLength / 2 || outputQ.length < inputLength / 2)
            {
                throw new IllegalArgumentException("Diagnostic half-band stage buffers are invalid");
            }

            int overlap = mCoefficients.length - 1;
            int half = overlap / 2;
            int outputLength = inputLength / 2;

            for(int output = 0, pointer = 0; output < outputLength; output++, pointer += 2)
            {
                float accumulatorI = 0.0f;
                float accumulatorQ = 0.0f;

                for(int coefficient = 0; coefficient < half; coefficient += 2)
                {
                    int first = pointer + coefficient;
                    int second = pointer + overlap - coefficient;
                    float tap = mCoefficients[coefficient];
                    accumulatorI += tap * (sample(mOverlapI, inputI, first) +
                        sample(mOverlapI, inputI, second));
                    accumulatorQ += tap * (sample(mOverlapQ, inputQ, first) +
                        sample(mOverlapQ, inputQ, second));
                }

                outputI[output] = accumulatorI + sample(mOverlapI, inputI, pointer + half) * 0.5f;
                outputQ[output] = accumulatorQ + sample(mOverlapQ, inputQ, pointer + half) * 0.5f;
            }

            copyTail(mOverlapI, inputI, inputLength);
            copyTail(mOverlapQ, inputQ, inputLength);
            return outputLength;
        }

        private static float sample(float[] overlap, float[] input, int combinedIndex)
        {
            return combinedIndex < overlap.length ? overlap[combinedIndex] : input[combinedIndex - overlap.length];
        }

        private static void copyTail(float[] overlap, float[] input, int inputLength)
        {
            if(inputLength >= overlap.length)
            {
                System.arraycopy(input, inputLength - overlap.length, overlap, 0, overlap.length);
            }
            else
            {
                int retained = overlap.length - inputLength;
                System.arraycopy(overlap, inputLength, overlap, 0, retained);
                System.arraycopy(input, 0, overlap, retained, inputLength);
            }
        }

        private void reset()
        {
            java.util.Arrays.fill(mOverlapI, 0.0f);
            java.util.Arrays.fill(mOverlapQ, 0.0f);
        }
    }
}
