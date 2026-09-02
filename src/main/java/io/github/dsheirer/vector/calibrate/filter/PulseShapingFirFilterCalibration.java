/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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

package io.github.dsheirer.vector.calibrate.filter;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.vector.calibrate.Calibration;
import io.github.dsheirer.vector.calibrate.CalibrationBenchmark;
import io.github.dsheirer.vector.calibrate.CalibrationException;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import jdk.incubator.vector.FloatVector;

/**
 * Selects the FIR implementation for DMR, NXDN and P25 Phase 1 pulse-shaping filters.
 *
 * <p>The decoder configuration values commonly described as 12, 16 or 26 are symbol spans, not final coefficient
 * counts.  The actual coefficient arrays also scale with samples per symbol and range from roughly 16 through 135
 * taps for the production configurations represented here.  Keeping this calibration distinct prevents the general
 * 31-tap FIR result from being applied to this different workload without measurement.</p>
 */
public class PulseShapingFirFilterCalibration extends Calibration
{
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_DURATION = Duration.ofMillis(750);
    private static final int BENCHMARK_BATCH_SIZE = 4;
    private static final int[] BUFFER_LENGTHS = {256, 1024, 2048};
    private static final float ABSOLUTE_TOLERANCE = 0.000_01f;
    private static final float RELATIVE_TOLERANCE = 0.000_01f;

    /**
     * Constructs an instance.
     */
    public PulseShapingFirFilterCalibration()
    {
        super(CalibrationType.FILTER_FIR_PULSE_SHAPING);
    }

    @Override public void calibrate() throws CalibrationException
    {
        List<FilterProfile> profiles = profiles();
        float[][] buffers = buffers();
        List<Candidate> candidates = candidates();
        verifyImplementations(profiles, buffers, candidates);

        for(Candidate candidate: candidates)
        {
            CalibrationBenchmark.measure(WARMUP_DURATION, BENCHMARK_BATCH_SIZE,
                new PulseShapingOperation(candidate.implementation(), profiles, buffers));
        }

        Candidate best = candidates.get(0);
        double bestScore = 0.0d;

        for(Candidate candidate: candidates)
        {
            double score = CalibrationBenchmark.measure(TEST_DURATION, BENCHMARK_BATCH_SIZE,
                new PulseShapingOperation(candidate.implementation(), profiles, buffers)).operationsPerSecond();
            mLog.info("PULSE-SHAPING FIR - {}: {} buffers/second", candidate.label(),
                DECIMAL_FORMAT.format(score));

            if(score > bestScore)
            {
                best = candidate;
                bestScore = score;
            }
        }

        setImplementation(best.implementation());
        mLog.info("PULSE-SHAPING FIR - SET OPTIMAL IMPLEMENTATION TO: " + getImplementation());
    }

    /** Production-shaped coefficient profiles spanning the decoder configurations that consume this result. */
    private List<FilterProfile> profiles()
    {
        return List.of(
            new FilterProfile("DMR 12-symbol low-rate",
                FilterFactory.getRootRaisedCosine(12_500.0 / 4_800.0, 12, 5_760.0f / 12_500.0f)),
            new FilterProfile("P25 Phase 1 16-symbol nominal",
                FilterFactory.getRootRaisedCosine(25_000.0 / 4_800.0, 16, 0.2f)),
            new FilterProfile("DMR 20-symbol 19.2 kHz",
                FilterFactory.getRootRaisedCosine(19_200.0 / 4_800.0, 20, 5_760.0f / 19_200.0f)),
            new FilterProfile("DMR 26-symbol high-rate",
                FilterFactory.getRootRaisedCosine(32_000.0 / 4_800.0, 26, 5_760.0f / 32_000.0f)),
            new FilterProfile("NXDN 26-symbol nominal",
                FilterFactory.getRRC(25_000.0 / 4_800.0, 26, 0.2f)));
    }

    private float[][] buffers()
    {
        float[][] buffers = new float[BUFFER_LENGTHS.length][];

        for(int x = 0; x < BUFFER_LENGTHS.length; x++)
        {
            buffers[x] = getFloatSamples(BUFFER_LENGTHS[x], "stream-buffer-" + BUFFER_LENGTHS[x]);
        }

        return buffers;
    }

    private List<Candidate> candidates()
    {
        int preferredBits = FloatVector.SPECIES_PREFERRED.vectorBitSize();
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate("SCALAR", Implementation.SCALAR));

        if(preferredBits > 64)
        {
            candidates.add(new Candidate("VECTOR 64", Implementation.VECTOR_SIMD_64));
        }

        if(preferredBits > 128)
        {
            candidates.add(new Candidate("VECTOR 128", Implementation.VECTOR_SIMD_128));
        }

        if(preferredBits > 256)
        {
            candidates.add(new Candidate("VECTOR 256", Implementation.VECTOR_SIMD_256));
        }

        candidates.add(new Candidate("VECTOR PREFERRED", Implementation.VECTOR_SIMD_PREFERRED));

        return candidates;
    }

    /**
     * Uses fresh filters for each profile/candidate and compares multiple consecutive buffers so both output values
     * and retained overlap history must match before a vector implementation can be timed.
     */
    private void verifyImplementations(List<FilterProfile> profiles, float[][] buffers, List<Candidate> candidates)
        throws CalibrationException
    {
        for(FilterProfile profile: profiles)
        {
            for(int candidateIndex = 1; candidateIndex < candidates.size(); candidateIndex++)
            {
                Candidate candidate = candidates.get(candidateIndex);
                IRealFilter scalar = FilterFactory.getRealFilter(profile.coefficients(), Implementation.SCALAR);
                IRealFilter actual = FilterFactory.getRealFilter(profile.coefficients(), candidate.implementation());

                for(int bufferIndex = 0; bufferIndex < buffers.length; bufferIndex++)
                {
                    float[] expected = scalar.filter(buffers[bufferIndex]);
                    float[] candidateOutput = actual.filter(buffers[bufferIndex]);
                    CalibrationBenchmark.requireEquivalent(candidate.label() + " / " + profile.label() +
                        " / buffer " + bufferIndex, expected, candidateOutput, ABSOLUTE_TOLERANCE,
                        RELATIVE_TOLERANCE);
                }
            }
        }
    }

    private record FilterProfile(String label, float[] coefficients)
    {
    }

    private record Candidate(String label, Implementation implementation)
    {
    }

    /** One operation advances one profile through one buffer while retaining independent history for every profile. */
    private static class PulseShapingOperation implements LongSupplier
    {
        private final IRealFilter[] mFilters;
        private final float[][] mBuffers;
        private int mProfileIndex;
        private int mBufferIndex;
        private int mObservationIndex;

        private PulseShapingOperation(Implementation implementation, List<FilterProfile> profiles, float[][] buffers)
        {
            mFilters = new IRealFilter[profiles.size()];

            for(int x = 0; x < profiles.size(); x++)
            {
                mFilters[x] = FilterFactory.getRealFilter(profiles.get(x).coefficients(), implementation);
            }

            mBuffers = buffers;
        }

        @Override public long getAsLong()
        {
            float[] output = mFilters[mProfileIndex].filter(mBuffers[mBufferIndex]);
            CalibrationBenchmark.consume(output);
            int observationIndex = mObservationIndex++ % output.length;
            advance();
            return CalibrationBenchmark.fingerprint(output[observationIndex]);
        }

        private void advance()
        {
            mBufferIndex++;

            if(mBufferIndex >= mBuffers.length)
            {
                mBufferIndex = 0;
                mProfileIndex++;

                if(mProfileIndex >= mFilters.length)
                {
                    mProfileIndex = 0;
                }
            }
        }
    }
}
