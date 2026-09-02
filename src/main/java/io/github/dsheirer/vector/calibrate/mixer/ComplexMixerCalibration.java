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

package io.github.dsheirer.vector.calibrate.mixer;

import io.github.dsheirer.dsp.mixer.ComplexMixer;
import io.github.dsheirer.dsp.mixer.ScalarComplexMixer;
import io.github.dsheirer.dsp.mixer.VectorComplexMixer;
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
 * Selects the fastest correct complex mixer implementation.
 */
public class ComplexMixerCalibration extends Calibration
{
    private static final double FREQUENCY = 12_500.0d;
    private static final double SAMPLE_RATE = 50_000.0d;
    private static final int BUFFER_SIZE = 2048;
    private static final int BENCHMARK_BATCH_SIZE = 2;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_TRIAL_DURATION = Duration.ofMillis(200);
    private static final float ABSOLUTE_TOLERANCE = 0.000001f;
    private static final float RELATIVE_TOLERANCE = 0.000001f;
    private static final Implementation[] CANDIDATES = {
        Implementation.SCALAR,
        Implementation.VECTOR_SIMD_PREFERRED
    };

    /**
     * Constructs an instance.
     */
    public ComplexMixerCalibration()
    {
        super(CalibrationType.MIXER_COMPLEX);
    }

    @Override
    public void calibrate() throws CalibrationException
    {
        float[][] i = {
            getFloatSamples(BUFFER_SIZE, "in-phase-buffer-a"),
            getFloatSamples(BUFFER_SIZE, "in-phase-buffer-b")
        };
        float[][] q = {
            getFloatSamples(BUFFER_SIZE, "quadrature-buffer-a"),
            getFloatSamples(BUFFER_SIZE, "quadrature-buffer-b")
        };
        ComplexSamples[] expected = mixSequence(createMixer(Implementation.SCALAR), i, q);
        List<Implementation> candidates = List.of(CANDIDATES);

        for(Implementation implementation: candidates)
        {
            ComplexSamples[] actual = mixSequence(createMixer(implementation), i, q);

            for(int x = 0; x < expected.length; x++)
            {
                CalibrationBenchmark.requireEquivalent(implementation + " I stream buffer " + x, expected[x].i(),
                    actual[x].i(), ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);
                CalibrationBenchmark.requireEquivalent(implementation + " Q stream buffer " + x, expected[x].q(),
                    actual[x].q(), ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);
            }

            measure(implementation, i, q, WARMUP_DURATION);
        }

        double[] scores = CalibrationSelector.alternatingMedians(candidates,
            implementation -> measure(implementation, i, q, TEST_TRIAL_DURATION));

        for(int x = 0; x < candidates.size(); x++)
        {
            mLog.info("COMPLEX MIXER - {}: {} median buffers/second", candidates.get(x),
                DECIMAL_FORMAT.format(scores[x]));
        }

        setImplementation(candidates.get(CalibrationSelector.selectFastestReliableCandidate(scores)));
        mLog.info("COMPLEX MIXER - SET OPTIMAL IMPLEMENTATION TO: {}", getImplementation());
    }

    private static double measure(Implementation implementation, float[][] i, float[][] q, Duration duration)
    {
        return CalibrationBenchmark.measure(duration, BENCHMARK_BATCH_SIZE,
            new MixerOperation(createMixer(implementation), i, q)).operationsPerSecond();
    }

    private static ComplexMixer createMixer(Implementation implementation)
    {
        return implementation == Implementation.VECTOR_SIMD_PREFERRED ?
            new VectorComplexMixer(FREQUENCY, SAMPLE_RATE) : new ScalarComplexMixer(FREQUENCY, SAMPLE_RATE);
    }

    private static ComplexSamples[] mixSequence(ComplexMixer mixer, float[][] i, float[][] q)
    {
        ComplexSamples[] mixed = new ComplexSamples[i.length];

        for(int x = 0; x < i.length; x++)
        {
            mixed[x] = mixer.mix(i[x], q[x], x * 1_000L);
        }

        return mixed;
    }

    /**
     * Alternates two buffers through one mixer so oscillator continuity remains part of the measured operation.
     */
    private static class MixerOperation implements LongSupplier
    {
        private final ComplexMixer mMixer;
        private final float[][] mI;
        private final float[][] mQ;
        private int mBufferIndex;
        private int mObservationIndex;

        private MixerOperation(ComplexMixer mixer, float[][] i, float[][] q)
        {
            mMixer = mixer;
            mI = clone(i);
            mQ = clone(q);
        }

        @Override
        public long getAsLong()
        {
            ComplexSamples mixed = mMixer.mix(mI[mBufferIndex], mQ[mBufferIndex], mBufferIndex * 1_000L);
            CalibrationBenchmark.consume(mixed);
            mBufferIndex = (mBufferIndex + 1) % mI.length;
            int index = mObservationIndex++;

            if(mObservationIndex >= mixed.i().length)
            {
                mObservationIndex = 0;
            }

            return CalibrationBenchmark.combine(CalibrationBenchmark.fingerprint(mixed.i()[index]),
                CalibrationBenchmark.fingerprint(mixed.q()[index]));
        }

        private static float[][] clone(float[][] source)
        {
            float[][] copy = new float[source.length][];

            for(int x = 0; x < source.length; x++)
            {
                copy[x] = source[x].clone();
            }

            return copy;
        }
    }
}
