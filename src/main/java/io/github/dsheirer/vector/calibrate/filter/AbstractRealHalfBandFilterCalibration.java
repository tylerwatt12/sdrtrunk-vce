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
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.window.WindowType;
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

/**
 * Shared correctness and measurement harness for the real half-band decimation filter calibrations.
 *
 * <p>Half-band filters retain overlap samples between calls.  Correctness therefore has to cover a stream of
 * consecutive buffers, rather than comparing one isolated output.  Each candidate is verified with a fresh filter,
 * warmed with another fresh filter and finally measured with a third fresh filter so that correctness and candidate
 * ordering cannot leak retained samples into the timed result.</p>
 */
abstract class AbstractRealHalfBandFilterCalibration extends Calibration
{
    private static final int BUFFER_SIZE = 2048;
    private static final int BENCHMARK_BATCH_SIZE = 4;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_TRIAL_DURATION = Duration.ofMillis(200);
    private static final float ABSOLUTE_TOLERANCE = 0.000_02f;
    private static final float RELATIVE_TOLERANCE = 0.000_02f;
    private final int mTapCount;
    private final String mLabel;
    private final boolean mIncludePreferred;

    /**
     * Constructs a calibration.
     *
     * @param type calibration preference type
     * @param tapCount half-band coefficient count
     * @param includePreferred whether production dispatch supports the preferred-species implementation
     */
    protected AbstractRealHalfBandFilterCalibration(CalibrationType type, int tapCount, boolean includePreferred)
    {
        super(type);
        mTapCount = tapCount;
        mLabel = "REAL HALF-BAND " + tapCount + "-TAP DECIMATE";
        mIncludePreferred = includePreferred;
    }

    @Override
    public void calibrate() throws CalibrationException
    {
        float[] coefficients = FilterFactory.getHalfBand(mTapCount, WindowType.BLACKMAN);
        List<Fixture> fixtures = fixtures();
        List<Candidate> candidates = candidates();
        verifyImplementations(coefficients, fixtures, candidates);

        for(Candidate candidate: candidates)
        {
            measure(candidate, coefficients, fixtures, WARMUP_DURATION);
        }

        double[] scores = CalibrationSelector.alternatingMedians(candidates,
            candidate -> measure(candidate, coefficients, fixtures, TEST_TRIAL_DURATION));

        for(int x = 0; x < candidates.size(); x++)
        {
            mLog.info("{} - {}: {} median buffers/second", mLabel, candidates.get(x).label(),
                DECIMAL_FORMAT.format(scores[x]));
        }

        setImplementation(candidates.get(CalibrationSelector.selectFastestReliableCandidate(scores)).implementation());
        mLog.info("{} - SET OPTIMAL IMPLEMENTATION TO: {}", mLabel, getImplementation());
    }

    private double measure(Candidate candidate, float[] coefficients, List<Fixture> fixtures, Duration duration)
    {
        return CalibrationBenchmark.measure(duration, BENCHMARK_BATCH_SIZE,
            new FilterOperation(createFilter(candidate.implementation(), coefficients.clone()), fixtures))
            .operationsPerSecond();
    }

    /**
     * Creates one filter candidate.  Implementations not returned by {@link #candidates()} do not need to be handled.
     */
    protected abstract IRealDecimationFilter createFilter(Implementation implementation, float[] coefficients);

    private List<Fixture> fixtures()
    {
        String fixturePrefix = "half-band-" + mTapCount + "-tap-";
        return List.of(
            new Fixture("wideband-a", getFloatSamples(BUFFER_SIZE, fixturePrefix + "wideband-a")),
            new Fixture("wideband-b", getFloatSamples(BUFFER_SIZE, fixturePrefix + "wideband-b")),
            new Fixture("wideband-c", getFloatSamples(BUFFER_SIZE, fixturePrefix + "wideband-c")));
    }

    private List<Candidate> candidates()
    {
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate("SCALAR", Implementation.SCALAR));

        if(mIncludePreferred)
        {
            candidates.add(new Candidate("VECTOR PREFERRED", Implementation.VECTOR_SIMD_PREFERRED));
        }

        addIfSupported(candidates, "VECTOR 64", Implementation.VECTOR_SIMD_64, FloatVector.SPECIES_64.length());
        addIfSupported(candidates, "VECTOR 128", Implementation.VECTOR_SIMD_128, FloatVector.SPECIES_128.length());
        addIfSupported(candidates, "VECTOR 256", Implementation.VECTOR_SIMD_256, FloatVector.SPECIES_256.length());
        addIfSupported(candidates, "VECTOR 512", Implementation.VECTOR_SIMD_512, FloatVector.SPECIES_512.length());
        return candidates;
    }

    private void addIfSupported(List<Candidate> candidates, String label, Implementation implementation,
                                int requiredLanes)
    {
        int preferredLanes = FloatVector.SPECIES_PREFERRED.length();

        //The generic/default calibration already measures the preferred implementation, so don't measure the same
        //hardware width a second time through its explicit-width wrapper.
        if(requiredLanes <= preferredLanes && (!mIncludePreferred || requiredLanes != preferredLanes))
        {
            candidates.add(new Candidate(label, implementation));
        }
    }

    /**
     * Compares multiple consecutive outputs produced by independent fresh scalar and candidate filters.  Processing
     * the fixtures in sequence checks both arithmetic and the overlap state carried from one buffer into the next.
     */
    private void verifyImplementations(float[] coefficients, List<Fixture> fixtures, List<Candidate> candidates)
        throws CalibrationException
    {
        float[][] expected = filterSequence(createFilter(Implementation.SCALAR, coefficients.clone()), fixtures);

        for(Candidate candidate: candidates)
        {
            float[][] actual = filterSequence(createFilter(candidate.implementation(), coefficients.clone()), fixtures);

            for(int x = 0; x < fixtures.size(); x++)
            {
                CalibrationBenchmark.requireEquivalent(candidate.label() + " / " + fixtures.get(x).name(),
                    expected[x], actual[x], ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);
            }
        }
    }

    private static float[][] filterSequence(IRealDecimationFilter filter, List<Fixture> fixtures)
    {
        float[][] outputs = new float[fixtures.size()][];

        for(int x = 0; x < fixtures.size(); x++)
        {
            outputs[x] = filter.decimateReal(fixtures.get(x).samples());
        }

        return outputs;
    }

    private record Candidate(String label, Implementation implementation)
    {
    }

    private record Fixture(String name, float[] samples)
    {
    }

    /** Advances the stateful filter through the deterministic fixture sequence and observes rotating outputs. */
    private static class FilterOperation implements LongSupplier
    {
        private final IRealDecimationFilter mFilter;
        private final List<Fixture> mFixtures;
        private int mFixtureIndex;
        private int mObservationIndex;

        private FilterOperation(IRealDecimationFilter filter, List<Fixture> fixtures)
        {
            mFilter = filter;
            mFixtures = fixtures;
        }

        @Override
        public long getAsLong()
        {
            float[] output = mFilter.decimateReal(mFixtures.get(mFixtureIndex).samples());
            mFixtureIndex = (mFixtureIndex + 1) % mFixtures.size();
            int observationIndex = mObservationIndex++ % output.length;
            CalibrationBenchmark.consume(output);
            return CalibrationBenchmark.fingerprint(output[observationIndex]);
        }
    }
}
