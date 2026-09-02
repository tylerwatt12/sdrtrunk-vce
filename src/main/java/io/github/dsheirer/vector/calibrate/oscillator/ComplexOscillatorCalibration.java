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

package io.github.dsheirer.vector.calibrate.oscillator;

import io.github.dsheirer.dsp.oscillator.IComplexOscillator;
import io.github.dsheirer.dsp.oscillator.ScalarComplexOscillator;
import io.github.dsheirer.dsp.oscillator.VectorComplexOscillator;
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
import jdk.incubator.vector.FloatVector;

/**
 * Selects the fastest correct complex oscillator implementation.
 */
public class ComplexOscillatorCalibration extends Calibration
{
    private static final double FREQUENCY = 5_000.0d;
    private static final double SAMPLE_RATE = 50_000.0d;
    private static final int BUFFER_SIZE = 2048;
    private static final int SIMD_LANE_COUNT = FloatVector.SPECIES_PREFERRED.length();
    private static final int[] STREAM_BUFFER_SIZES = {
        BUFFER_SIZE + 1,
        SIMD_LANE_COUNT + 1,
        BUFFER_SIZE - 1,
        SIMD_LANE_COUNT * 2,
        (SIMD_LANE_COUNT * 2) + 3
    };
    private static final int BENCHMARK_BATCH_SIZE = 2;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_TRIAL_DURATION = Duration.ofMillis(200);
    private static final float ABSOLUTE_TOLERANCE = 0.001f;
    private static final float RELATIVE_TOLERANCE = 0.001f;
    private static final Implementation[] CANDIDATES = {
        Implementation.SCALAR,
        Implementation.VECTOR_SIMD_PREFERRED
    };

    /**
     * Constructs an instance.
     */
    public ComplexOscillatorCalibration()
    {
        super(CalibrationType.OSCILLATOR_COMPLEX);
    }

    @Override
    public void calibrate() throws CalibrationException
    {
        float[][] expectedInterleaved = generateInterleaved(createOscillator(Implementation.SCALAR));
        ComplexSamples[] expectedDeinterleaved = generateDeinterleaved(createOscillator(Implementation.SCALAR));
        List<Implementation> candidates = List.of(CANDIDATES);

        for(Implementation implementation: candidates)
        {
            float[][] actualInterleaved = generateInterleaved(createOscillator(implementation));
            ComplexSamples[] actualDeinterleaved = generateDeinterleaved(createOscillator(implementation));

            for(int x = 0; x < STREAM_BUFFER_SIZES.length; x++)
            {
                CalibrationBenchmark.requireEquivalent(implementation + " interleaved stream buffer " + x,
                    expectedInterleaved[x], actualInterleaved[x], ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);
                CalibrationBenchmark.requireEquivalent(implementation + " I stream buffer " + x,
                    expectedDeinterleaved[x].i(), actualDeinterleaved[x].i(), ABSOLUTE_TOLERANCE,
                    RELATIVE_TOLERANCE);
                CalibrationBenchmark.requireEquivalent(implementation + " Q stream buffer " + x,
                    expectedDeinterleaved[x].q(), actualDeinterleaved[x].q(), ABSOLUTE_TOLERANCE,
                    RELATIVE_TOLERANCE);
            }

            measure(implementation, WARMUP_DURATION);
        }

        double[] scores = CalibrationSelector.alternatingMedians(candidates,
            implementation -> measure(implementation, TEST_TRIAL_DURATION));

        for(int x = 0; x < candidates.size(); x++)
        {
            mLog.info("COMPLEX OSCILLATOR - {}: {} median buffers/second", candidates.get(x),
                DECIMAL_FORMAT.format(scores[x]));
        }

        setImplementation(candidates.get(CalibrationSelector.selectFastestReliableCandidate(scores)));
        mLog.info("COMPLEX OSCILLATOR - SET OPTIMAL IMPLEMENTATION TO: {}", getImplementation());
    }

    private static double measure(Implementation implementation, Duration duration)
    {
        return CalibrationBenchmark.measure(duration, BENCHMARK_BATCH_SIZE,
            new OscillatorOperation(createOscillator(implementation))).operationsPerSecond();
    }

    private static IComplexOscillator createOscillator(Implementation implementation)
    {
        return implementation == Implementation.VECTOR_SIMD_PREFERRED ?
            new VectorComplexOscillator(FREQUENCY, SAMPLE_RATE) :
            new ScalarComplexOscillator(FREQUENCY, SAMPLE_RATE);
    }

    private static float[][] generateInterleaved(IComplexOscillator oscillator)
    {
        float[][] generated = new float[STREAM_BUFFER_SIZES.length][];

        for(int x = 0; x < STREAM_BUFFER_SIZES.length; x++)
        {
            generated[x] = oscillator.generate(STREAM_BUFFER_SIZES[x]);
        }

        return generated;
    }

    private static ComplexSamples[] generateDeinterleaved(IComplexOscillator oscillator)
    {
        ComplexSamples[] generated = new ComplexSamples[STREAM_BUFFER_SIZES.length];

        for(int x = 0; x < STREAM_BUFFER_SIZES.length; x++)
        {
            generated[x] = oscillator.generateComplexSamples(STREAM_BUFFER_SIZES[x], x * 1_000L);
        }

        return generated;
    }

    /**
     * Measures the deinterleaved generation path used by the production complex mixer and preserves stream state.
     */
    private static class OscillatorOperation implements LongSupplier
    {
        private final IComplexOscillator mOscillator;
        private int mObservationIndex;

        private OscillatorOperation(IComplexOscillator oscillator)
        {
            mOscillator = oscillator;
        }

        @Override
        public long getAsLong()
        {
            ComplexSamples generated = mOscillator.generateComplexSamples(BUFFER_SIZE, 0L);
            CalibrationBenchmark.consume(generated);
            int index = mObservationIndex++;

            if(mObservationIndex >= generated.i().length)
            {
                mObservationIndex = 0;
            }

            return CalibrationBenchmark.combine(CalibrationBenchmark.fingerprint(generated.i()[index]),
                CalibrationBenchmark.fingerprint(generated.q()[index]));
        }
    }
}
