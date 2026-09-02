/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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

package io.github.dsheirer.vector.calibrate.gain;

import io.github.dsheirer.dsp.gain.complex.ComplexGain;
import io.github.dsheirer.dsp.gain.complex.ScalarComplexGain;
import io.github.dsheirer.dsp.gain.complex.VectorComplexGain;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.vector.calibrate.Calibration;
import io.github.dsheirer.vector.calibrate.CalibrationBenchmark;
import io.github.dsheirer.vector.calibrate.CalibrationException;
import io.github.dsheirer.vector.calibrate.CalibrationSelector;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Determines the optimal scalar vs vector implementation of complex gain.
 */
public class ComplexGainCalibration extends Calibration
{
    private static final int BUFFER_SIZE = 2048;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_TRIAL_DURATION = Duration.ofMillis(200);
    private static final int BENCHMARK_BATCH_SIZE = 16;
    private static final float GAIN = 0.99f;
    private static final float INVERSE_GAIN = 1.0f / GAIN;
    private static final List<Implementation> CANDIDATES = List.of(Implementation.SCALAR,
        Implementation.VECTOR_SIMD_PREFERRED);

    /**
     * Constructs an instance
     */
    public ComplexGainCalibration()
    {
        super(CalibrationType.GAIN_COMPLEX);
    }

    @Override public void calibrate() throws CalibrationException
    {
        float[] i = getFloatSamples(BUFFER_SIZE, "in-phase");
        float[] q = getFloatSamples(BUFFER_SIZE, "quadrature");
        verifyImplementations(i, q);

        for(Implementation implementation: CANDIDATES)
        {
            measure(implementation, i, q, WARMUP_DURATION);
        }

        double[] scores = CalibrationSelector.alternatingMedians(CANDIDATES,
            implementation -> measure(implementation, i, q, TEST_TRIAL_DURATION));

        for(int x = 0; x < CANDIDATES.size(); x++)
        {
            mLog.info("COMPLEX GAIN - {}: {} median buffers/second", CANDIDATES.get(x),
                DECIMAL_FORMAT.format(scores[x]));
        }

        setImplementation(CANDIDATES.get(CalibrationSelector.selectFastestReliableCandidate(scores)));
        mLog.info("COMPLEX GAIN - SET IMPLEMENTATION TO:" + getImplementation());
    }

    /**
     * Verifies candidate output before using performance to select an implementation.
     */
    private void verifyImplementations(float[] i, float[] q) throws CalibrationException
    {
        float[] expectedI = i.clone();
        float[] expectedQ = q.clone();
        float[] actualI = i.clone();
        float[] actualQ = q.clone();
        new ScalarComplexGain(GAIN).apply(expectedI, expectedQ, 0);
        new VectorComplexGain(GAIN).apply(actualI, actualQ, 0);
        CalibrationBenchmark.requireExact("Vector complex gain I", expectedI, actualI);
        CalibrationBenchmark.requireExact("Vector complex gain Q", expectedQ, actualQ);
    }

    private double measure(Implementation implementation, float[] i, float[] q, Duration duration)
    {
        return implementation == Implementation.VECTOR_SIMD_PREFERRED ?
            test(new VectorComplexGain(GAIN), new VectorComplexGain(INVERSE_GAIN), i, q, duration) :
            test(new ScalarComplexGain(GAIN), new ScalarComplexGain(INVERSE_GAIN), i, q, duration);
    }

    private double test(ComplexGain forward, ComplexGain reverse, float[] i, float[] q, Duration duration)
    {
        GainOperation operation = new GainOperation(forward, reverse, i, q);
        return CalibrationBenchmark.measure(duration, BENCHMARK_BATCH_SIZE, operation)
            .operationsPerSecond();
    }

    /**
     * Alternates forward and inverse gain so the timed operation measures gain rather than an input-buffer copy and
     * the samples do not decay toward zero during the benchmark.
     */
    private static class GainOperation implements LongSupplier
    {
        private final ComplexGain mForward;
        private final ComplexGain mReverse;
        private final float[] mI;
        private final float[] mQ;
        private boolean mApplyForward = true;
        private int mObservationIndex;

        private GainOperation(ComplexGain forward, ComplexGain reverse, float[] i, float[] q)
        {
            mForward = forward;
            mReverse = reverse;
            mI = i.clone();
            mQ = q.clone();
        }

        @Override public long getAsLong()
        {
            ComplexGain gain = mApplyForward ? mForward : mReverse;
            mApplyForward = !mApplyForward;
            ComplexSamples amplified = gain.apply(mI, mQ, 0);
            CalibrationBenchmark.consume(amplified);
            int index = mObservationIndex++;

            if(mObservationIndex >= mI.length)
            {
                mObservationIndex = 0;
            }

            return CalibrationBenchmark.combine(CalibrationBenchmark.fingerprint(amplified.i()[index]),
                CalibrationBenchmark.fingerprint(amplified.q()[index]));
        }
    }
}
