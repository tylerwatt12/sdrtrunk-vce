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

package io.github.dsheirer.vector.calibrate.sync;

import java.util.Objects;

/** Shared deterministic boundary fixtures for soft-sync detector calibrations. */
final class SoftSyncCalibrationHelper
{
    /**
     * Leaves enough floating-point headroom for the scalar fixture construction while remaining inside the
     * calibration's permitted correlation-score tolerance.  This lets the decision check catch a vector reduction
     * that is numerically acceptable but lands on the other side of a production threshold.
     */
    static final int BOUNDARY_ULP_STEPS = 64;

    private SoftSyncCalibrationHelper()
    {
    }

    /** Returns a score a small, deterministic number of representable values above or below a threshold. */
    static float boundaryTarget(float threshold, boolean shouldDetect)
    {
        if(!Float.isFinite(threshold) || threshold <= 0.0f)
        {
            throw new IllegalArgumentException("Threshold must be finite and greater than zero");
        }

        float target = threshold;

        for(int x = 0; x < BOUNDARY_ULP_STEPS; x++)
        {
            target = shouldDetect ? Math.nextUp(target) : Math.nextDown(target);
        }

        return target;
    }

    /**
     * Adds deterministic amplitude distortion and normalizes correlation with the ideal pattern to the requested
     * score.  The result is close to a real sync without being an unrealistically perfect scaled copy.
     */
    static float[] createNearSync(float[] exact, float targetScore)
    {
        Objects.requireNonNull(exact, "Exact sync pattern cannot be null");

        if(exact.length == 0)
        {
            throw new IllegalArgumentException("Exact sync pattern cannot be empty");
        }

        if(!Float.isFinite(targetScore))
        {
            throw new IllegalArgumentException("Target score must be finite");
        }

        float[] near = new float[exact.length];
        float score = 0.0f;

        for(int x = 0; x < exact.length; x++)
        {
            float distortion = 1.0f + ((x % 5) - 2) * 0.025f;
            near[x] = exact[x] * distortion;
            score += exact[x] * near[x];
        }

        if(score == 0.0f || !Float.isFinite(score))
        {
            throw new IllegalArgumentException("Exact sync pattern must have finite, non-zero energy");
        }

        float scale = targetScore / score;

        for(int x = 0; x < near.length; x++)
        {
            near[x] *= scale;
        }

        return near;
    }
}
