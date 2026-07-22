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

package io.github.dsheirer.spectrum.stream;

import java.util.List;

/**
 * Optional control surface for an interactive, exclusive spectrum source.
 *
 * <p>Implementations coalesce requests and apply them away from the tuner sample callback.  A target change is a
 * full-width request; callers issue a later viewport request after receiving the target's first live frame.</p>
 */
public interface InteractiveSpectrumFrameSource extends SpectrumFrameSource
{
    int BASE_FFT_SIZE = 4_096;
    int MAXIMUM_FFT_SIZE = 32_768;
    int MAXIMUM_TRANSMITTED_BINS = 4_096;

    /**
     * Returns the currently selectable, non-sensitive target descriptions.  Implementations must not expose serial
     * numbers or tuner preferred names.
     */
    List<Target> getTargets();

    /**
     * Coalesces and applies the newest requested view asynchronously.
     */
    void requestView(ViewRequest request);

    /**
     * Returns the newest view that has produced a frame, or {@code null} before the first frame.
     */
    AppliedView getAppliedView();

    record Target(String id, String label)
    {
        public Target
        {
            if(id == null || id.isBlank() || id.length() > 32 || !id.matches("[A-Z0-9_\\-]+"))
            {
                throw new IllegalArgumentException("Spectrum target ID is invalid");
            }

            if(label == null || label.isBlank() || label.length() > 64)
            {
                throw new IllegalArgumentException("Spectrum target label is invalid");
            }
        }
    }

    record Viewport(long startFrequencyHz, long endFrequencyHz)
    {
        public Viewport
        {
            if(startFrequencyHz < 0 || endFrequencyHz <= startFrequencyHz)
            {
                throw new IllegalArgumentException("Spectrum viewport frequency range is invalid");
            }
        }
    }

    record ViewRequest(long revision, String targetId, Viewport viewport)
    {
        public ViewRequest
        {
            if(revision < 0)
            {
                throw new IllegalArgumentException("Spectrum view revision cannot be negative");
            }

            if(targetId != null)
            {
                targetId = targetId.trim().toUpperCase(java.util.Locale.ROOT);

                if(targetId.isBlank() || targetId.length() > 32 || !targetId.matches("[A-Z0-9_\\-]+"))
                {
                    throw new IllegalArgumentException("Spectrum target ID is invalid");
                }
            }
        }
    }

    record AppliedView(long revision, String targetId, String targetLabel, long targetGeneration,
                       long centerFrequencyHz, long sampleRateHz, int fftSize, int firstBin, int binCount)
    {
        public AppliedView
        {
            if(revision < 0 || targetGeneration < 0 || centerFrequencyHz < 0 || sampleRateHz <= 0)
            {
                throw new IllegalArgumentException("Applied spectrum view metadata is invalid");
            }

            new Target(targetId, targetLabel);

            if(fftSize < 1 || fftSize > MAXIMUM_FFT_SIZE)
            {
                throw new IllegalArgumentException("Applied spectrum FFT size is unsupported");
            }

            if(firstBin < 0 || binCount < 1 || binCount > MAXIMUM_TRANSMITTED_BINS ||
                firstBin > fftSize - binCount)
            {
                throw new IllegalArgumentException("Applied spectrum crop is invalid");
            }
        }

        public double binWidthHz()
        {
            return (double)sampleRateHz / fftSize;
        }

        public double visibleStartFrequencyHz()
        {
            return centerFrequencyHz - sampleRateHz / 2.0 + firstBin * binWidthHz();
        }

        public double visibleEndFrequencyHz()
        {
            return visibleStartFrequencyHz() + binCount * binWidthHz();
        }
    }

    static boolean isSupportedFftSize(int fftSize)
    {
        return fftSize == 4_096 || fftSize == 8_192 || fftSize == 16_384 || fftSize == 32_768;
    }
}
