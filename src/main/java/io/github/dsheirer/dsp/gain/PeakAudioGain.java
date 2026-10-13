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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies one peak-limited gain to a completed call, preserving waveform shape instead of clipping individual samples.
 * The input buffers remain unchanged. This two-pass operation belongs on a completed-call worker, never a decoder
 * callback. Instances have no per-call state and can be reused concurrently with immutable input buffers.
 */
public final class PeakAudioGain
{
    private final float mMaximumGain;
    private final float mTargetPeak;

    /**
     * @param maximumGain maximum amplification, at least 1.0
     * @param targetPeak maximum output amplitude, greater than zero and at most 1.0
     */
    public PeakAudioGain(float maximumGain, float targetPeak)
    {
        if(!Float.isFinite(maximumGain) || maximumGain < 1.0f || !Float.isFinite(targetPeak) ||
            targetPeak <= 0.0f || targetPeak > 1.0f)
        {
            throw new IllegalArgumentException("Finite maximum gain >= 1 and target peak in (0, 1] are required");
        }

        mMaximumGain = maximumGain;
        mTargetPeak = targetPeak;
    }

    /** Determines a single gain for every sample in the supplied buffers. Silence and empty calls use unity gain. */
    public float calculateGain(Iterable<float[]> buffers)
    {
        Objects.requireNonNull(buffers, "Audio buffers are required");
        float peak = 0.0f;

        for(float[] buffer: buffers)
        {
            Objects.requireNonNull(buffer, "Audio buffer is required");

            for(float sample: buffer)
            {
                requireFinite(sample);
                peak = Math.max(peak, Math.abs(sample));
            }
        }

        if(peak == 0.0f)
        {
            return 1.0f;
        }

        float gain = mTargetPeak / peak;

        if(gain >= mMaximumGain)
        {
            return mMaximumGain;
        }

        //Leave a rounding margin without flattening peaks with a per-sample clamp.
        return Math.nextDown(gain);
    }

    /** Returns new buffers with a common call-wide gain. Original buffer lengths and ordering are preserved. */
    public List<float[]> process(List<float[]> buffers)
    {
        float gain = calculateGain(buffers);
        List<float[]> adjusted = new ArrayList<>(buffers.size());

        for(float[] buffer: buffers)
        {
            adjusted.add(apply(buffer, gain));
        }

        return List.copyOf(adjusted);
    }

    /** Returns a new array with peak-limited gain. */
    public float[] process(float[] samples)
    {
        Objects.requireNonNull(samples, "Audio samples are required");
        return apply(samples, calculateGain(List.of(samples)));
    }

    /** Returns a new array using a previously calculated common gain, without mutating or clipping the input. */
    public static float[] apply(float[] samples, float gain)
    {
        Objects.requireNonNull(samples, "Audio samples are required");

        if(!Float.isFinite(gain) || gain <= 0.0f)
        {
            throw new IllegalArgumentException("Finite positive gain is required");
        }

        float[] adjusted = new float[samples.length];

        for(int index = 0; index < samples.length; index++)
        {
            requireFinite(samples[index]);
            float sample = samples[index] * gain;
            requireFinite(sample);
            adjusted[index] = sample;
        }

        return adjusted;
    }

    private static void requireFinite(float sample)
    {
        if(!Float.isFinite(sample))
        {
            throw new IllegalArgumentException("Audio samples must be finite");
        }
    }
}
