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

import io.github.dsheirer.dsp.oscillator.IRealOscillator;
import io.github.dsheirer.dsp.oscillator.ScalarRealOscillator;
import io.github.dsheirer.dsp.oscillator.VectorRealOscillator;
import io.github.dsheirer.vector.calibrate.Calibration;
import io.github.dsheirer.vector.calibrate.CalibrationBenchmark;
import io.github.dsheirer.vector.calibrate.CalibrationException;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Selects the fastest correct real oscillator implementation.
 */
public class RealOscillatorCalibration extends Calibration
{
    private static final double FREQUENCY = 5_000.0d;
    private static final double SAMPLE_RATE = 50_000.0d;
    private static final int BUFFER_SIZE = 2048;
    private static final int STREAM_BUFFER_COUNT = 2;
    private static final int BENCHMARK_BATCH_SIZE = 2;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_DURATION = Duration.ofMillis(750);
    private static final float ABSOLUTE_TOLERANCE = 0.001f;
    private static final float RELATIVE_TOLERANCE = 0.001f;
    private static final Implementation[] CANDIDATES = {
        Implementation.SCALAR,
        Implementation.VECTOR_SIMD_PREFERRED
    };

    /**
     * Constructs an instance.
     */
    public RealOscillatorCalibration()
    {
        super(CalibrationType.OSCILLATOR_REAL);
    }

    @Override
    public void calibrate() throws CalibrationException
    {
        float[][] expected = generateSequence(createOscillator(Implementation.SCALAR));
        Implementation bestImplementation = Implementation.SCALAR;
        double bestScore = 0.0d;

        for(Implementation implementation: CANDIDATES)
        {
            float[][] actual = generateSequence(createOscillator(implementation));

            for(int x = 0; x < STREAM_BUFFER_COUNT; x++)
            {
                CalibrationBenchmark.requireEquivalent(implementation + " stream buffer " + x, expected[x],
                    actual[x], ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);
            }

            CalibrationBenchmark.measure(WARMUP_DURATION, BENCHMARK_BATCH_SIZE,
                new OscillatorOperation(createOscillator(implementation)));
            double score = CalibrationBenchmark.measure(TEST_DURATION, BENCHMARK_BATCH_SIZE,
                new OscillatorOperation(createOscillator(implementation))).operationsPerSecond();
            mLog.info("REAL OSCILLATOR - {}: {} buffers/second", implementation, DECIMAL_FORMAT.format(score));

            if(score > bestScore)
            {
                bestScore = score;
                bestImplementation = implementation;
            }
        }

        setImplementation(bestImplementation);
        mLog.info("REAL OSCILLATOR - SET OPTIMAL IMPLEMENTATION TO: {}", getImplementation());
    }

    private static IRealOscillator createOscillator(Implementation implementation)
    {
        return implementation == Implementation.VECTOR_SIMD_PREFERRED ?
            new VectorRealOscillator(FREQUENCY, SAMPLE_RATE) : new ScalarRealOscillator(FREQUENCY, SAMPLE_RATE);
    }

    private static float[][] generateSequence(IRealOscillator oscillator)
    {
        float[][] generated = new float[STREAM_BUFFER_COUNT][];

        for(int x = 0; x < STREAM_BUFFER_COUNT; x++)
        {
            generated[x] = oscillator.generate(BUFFER_SIZE);
        }

        return generated;
    }

    private static class OscillatorOperation implements LongSupplier
    {
        private final IRealOscillator mOscillator;
        private int mObservationIndex;

        private OscillatorOperation(IRealOscillator oscillator)
        {
            mOscillator = oscillator;
        }

        @Override
        public long getAsLong()
        {
            float[] generated = mOscillator.generate(BUFFER_SIZE);
            CalibrationBenchmark.consume(generated);
            int index = mObservationIndex++;

            if(mObservationIndex >= generated.length)
            {
                mObservationIndex = 0;
            }

            return CalibrationBenchmark.fingerprint(generated[index]);
        }
    }
}
