/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.vector.calibrate.interpolator;

import io.github.dsheirer.dsp.filter.interpolator.Interpolator;
import io.github.dsheirer.dsp.filter.interpolator.InterpolatorScalar;
import io.github.dsheirer.dsp.filter.interpolator.InterpolatorVector128;
import io.github.dsheirer.dsp.filter.interpolator.InterpolatorVector256;
import io.github.dsheirer.dsp.filter.interpolator.InterpolatorVector64;
import io.github.dsheirer.vector.calibrate.Calibration;
import io.github.dsheirer.vector.calibrate.CalibrationBenchmark;
import io.github.dsheirer.vector.calibrate.CalibrationException;
import io.github.dsheirer.vector.calibrate.CalibrationSelector;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import jdk.incubator.vector.FloatVector;

/** Calibration plugin for the eight-tap interpolator used by symbol timing recovery. */
public class InterpolatorCalibration extends Calibration
{
    private static final int BUFFER_SIZE = 32;
    private static final int INTERPOLATION_POINT_COUNT = 2048;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_TRIAL_DURATION = Duration.ofMillis(200);
    private static final int BENCHMARK_BATCH_SIZE = 1;
    private static final float ABSOLUTE_TOLERANCE = 0.000_001f;
    private static final float RELATIVE_TOLERANCE = 0.000_001f;

    public InterpolatorCalibration()
    {
        super(CalibrationType.INTERPOLATOR);
    }

    @Override public void calibrate() throws CalibrationException
    {
        float[] samples = getFloatSamples(BUFFER_SIZE, "samples");
        float[] interpolationPoints = getPositiveFloatSamples(INTERPOLATION_POINT_COUNT, "interpolation-points");
        interpolationPoints[0] = 0.0f;
        interpolationPoints[1] = 0.5f;
        interpolationPoints[2] = 1.0f;

        List<Candidate> candidates = candidates();
        verifyImplementations(candidates, samples, interpolationPoints);

        for(Candidate candidate: candidates)
        {
            measure(candidate, samples, interpolationPoints, WARMUP_DURATION);
        }

        double[] scores = CalibrationSelector.alternatingMedians(candidates,
            candidate -> measure(candidate, samples, interpolationPoints, TEST_TRIAL_DURATION));

        for(int index = 0; index < candidates.size(); index++)
        {
            Candidate candidate = candidates.get(index);
            mLog.info("INTERPOLATOR - {}: {} median full interpolation sweeps/second", candidate.label(),
                DECIMAL_FORMAT.format(scores[index]));
        }

        setImplementation(candidates.get(CalibrationSelector.selectFastestReliableCandidate(scores)).implementation());
        mLog.info("INTERPOLATOR - SET OPTIMAL IMPLEMENTATION TO: " + getImplementation());
    }

    private static double measure(Candidate candidate, float[] samples, float[] interpolationPoints,
                                  Duration duration)
    {
        return CalibrationBenchmark.measure(duration, BENCHMARK_BATCH_SIZE,
            new InterpolatorOperation(candidate.interpolator(), samples, interpolationPoints)).operationsPerSecond();
    }

    private List<Candidate> candidates()
    {
        int preferredLanes = FloatVector.SPECIES_PREFERRED.length();
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate("SCALAR", Implementation.SCALAR, new InterpolatorScalar()));

        if(preferredLanes >= 8)
        {
            candidates.add(new Candidate("VECTOR 256", Implementation.VECTOR_SIMD_256,
                new InterpolatorVector256()));
        }

        if(preferredLanes >= 4)
        {
            candidates.add(new Candidate("VECTOR 128", Implementation.VECTOR_SIMD_128,
                new InterpolatorVector128()));
        }

        if(preferredLanes >= 2)
        {
            candidates.add(new Candidate("VECTOR 64", Implementation.VECTOR_SIMD_64,
                new InterpolatorVector64()));
        }

        return candidates;
    }

    private void verifyImplementations(List<Candidate> candidates, float[] samples, float[] interpolationPoints)
        throws CalibrationException
    {
        float[] expected = output(candidates.get(0).interpolator(), samples, interpolationPoints);

        for(int x = 1; x < candidates.size(); x++)
        {
            Candidate candidate = candidates.get(x);
            float[] actual = output(candidate.interpolator(), samples, interpolationPoints);
            CalibrationBenchmark.requireEquivalent(candidate.label(), expected, actual, ABSOLUTE_TOLERANCE,
                RELATIVE_TOLERANCE);
        }
    }

    private float[] output(Interpolator interpolator, float[] samples, float[] interpolationPoints)
    {
        int offsetCount = samples.length - Interpolator.NTAPS + 1;
        float[] output = new float[offsetCount * interpolationPoints.length];
        int pointer = 0;

        for(int offset = 0; offset < offsetCount; offset++)
        {
            for(float interpolationPoint: interpolationPoints)
            {
                output[pointer++] = interpolator.filter(samples, offset, interpolationPoint);
            }
        }

        return output;
    }

    private record Candidate(String label, Implementation implementation, Interpolator interpolator)
    {
    }

    private static class InterpolatorOperation implements LongSupplier
    {
        private final Interpolator mInterpolator;
        private final float[] mSamples;
        private final float[] mInterpolationPoints;
        private final int mOffsetCount;
        private int mOffset;

        private InterpolatorOperation(Interpolator interpolator, float[] samples, float[] interpolationPoints)
        {
            mInterpolator = interpolator;
            mSamples = samples;
            mInterpolationPoints = interpolationPoints;
            mOffsetCount = samples.length - Interpolator.NTAPS + 1;
        }

        @Override public long getAsLong()
        {
            float accumulator = 0.0f;

            for(float interpolationPoint: mInterpolationPoints)
            {
                accumulator += mInterpolator.filter(mSamples, mOffset, interpolationPoint);
            }

            mOffset++;

            if(mOffset >= mOffsetCount)
            {
                mOffset = 0;
            }

            return CalibrationBenchmark.fingerprint(accumulator);
        }
    }
}
