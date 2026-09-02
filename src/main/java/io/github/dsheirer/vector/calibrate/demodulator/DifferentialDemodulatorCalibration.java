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

package io.github.dsheirer.vector.calibrate.demodulator;

import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFloat;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFloatScalar;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFloatVector128;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFloatVector256;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFloatVector512;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFloatVector64;
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
 * Selects the fastest correct differential phase demodulator for the current CPU.
 */
public class DifferentialDemodulatorCalibration extends Calibration
{
    private static final int BUFFER_SIZE = 2048;
    private static final double SAMPLE_RATE = 50_000.0d;
    private static final int SYMBOL_RATE = 4_800;
    private static final int BENCHMARK_BATCH_SIZE = 1;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_TRIAL_DURATION = Duration.ofMillis(200);
    private static final float ABSOLUTE_TOLERANCE = 0.000002f;
    private static final float RELATIVE_TOLERANCE = 0.000002f;
    private static final Implementation[] CANDIDATES = {
        Implementation.SCALAR,
        Implementation.VECTOR_SIMD_64,
        Implementation.VECTOR_SIMD_128,
        Implementation.VECTOR_SIMD_256,
        Implementation.VECTOR_SIMD_512
    };

    /**
     * Constructs an instance.
     */
    public DifferentialDemodulatorCalibration()
    {
        super(CalibrationType.DIFFERENTIAL_DEMODULATOR);
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
        float[][] expected = demodulateSequence(createDemodulator(Implementation.SCALAR), i, q);
        List<Implementation> candidates = getCandidates();

        for(Implementation implementation: candidates)
        {
            float[][] actual = demodulateSequence(createDemodulator(implementation), i, q);

            for(int x = 0; x < expected.length; x++)
            {
                CalibrationBenchmark.requireEquivalent(implementation + " stream buffer " + x, expected[x],
                    actual[x], ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);
            }

            measure(implementation, i, q, WARMUP_DURATION);
        }

        double[] medianScores = CalibrationSelector.alternatingMedians(candidates,
            implementation -> measure(implementation, i, q, TEST_TRIAL_DURATION));

        for(int x = 0; x < candidates.size(); x++)
        {
            mLog.info("DQPSK DEMODULATOR - {}: {} median buffers/second", candidates.get(x),
                DECIMAL_FORMAT.format(medianScores[x]));
        }

        setImplementation(candidates.get(CalibrationSelector.selectFastestReliableCandidate(medianScores)));
        mLog.info("DQPSK DEMODULATOR - SET OPTIMAL IMPLEMENTATION TO: {}", getImplementation());
    }

    private static List<Implementation> getCandidates()
    {
        List<Implementation> candidates = new ArrayList<>();

        for(Implementation implementation: CANDIDATES)
        {
            if(isSupported(implementation))
            {
                candidates.add(implementation);
            }
        }

        return candidates;
    }

    private static double measure(Implementation implementation, float[][] i, float[][] q, Duration duration)
    {
        return CalibrationBenchmark.measure(duration, BENCHMARK_BATCH_SIZE,
            new DemodulatorOperation(createDemodulator(implementation), i, q)).operationsPerSecond();
    }

    private static boolean isSupported(Implementation implementation)
    {
        int requiredLanes = switch(implementation)
        {
            case VECTOR_SIMD_64 -> FloatVector.SPECIES_64.length();
            case VECTOR_SIMD_128 -> FloatVector.SPECIES_128.length();
            case VECTOR_SIMD_256 -> FloatVector.SPECIES_256.length();
            case VECTOR_SIMD_512 -> FloatVector.SPECIES_512.length();
            default -> 0;
        };

        return requiredLanes <= FloatVector.SPECIES_PREFERRED.length();
    }

    private static DifferentialDemodulatorFloat createDemodulator(Implementation implementation)
    {
        return switch(implementation)
        {
            case VECTOR_SIMD_64 -> new DifferentialDemodulatorFloatVector64(SAMPLE_RATE, SYMBOL_RATE);
            case VECTOR_SIMD_128 -> new DifferentialDemodulatorFloatVector128(SAMPLE_RATE, SYMBOL_RATE);
            case VECTOR_SIMD_256 -> new DifferentialDemodulatorFloatVector256(SAMPLE_RATE, SYMBOL_RATE);
            case VECTOR_SIMD_512 -> new DifferentialDemodulatorFloatVector512(SAMPLE_RATE, SYMBOL_RATE);
            default -> new DifferentialDemodulatorFloatScalar(SAMPLE_RATE, SYMBOL_RATE);
        };
    }

    private static float[][] demodulateSequence(DifferentialDemodulatorFloat demodulator, float[][] i, float[][] q)
    {
        float[][] demodulated = new float[i.length][];

        for(int x = 0; x < i.length; x++)
        {
            demodulated[x] = demodulator.demodulate(i[x], q[x]);
        }

        return demodulated;
    }

    /**
     * Alternates two input buffers through one fresh stateful demodulator and observes a rotating output element.
     */
    private static class DemodulatorOperation implements LongSupplier
    {
        private final DifferentialDemodulatorFloat mDemodulator;
        private final float[][] mI;
        private final float[][] mQ;
        private int mBufferIndex;
        private int mObservationIndex;

        private DemodulatorOperation(DifferentialDemodulatorFloat demodulator, float[][] i, float[][] q)
        {
            mDemodulator = demodulator;
            mI = clone(i);
            mQ = clone(q);
        }

        @Override
        public long getAsLong()
        {
            float[] demodulated = mDemodulator.demodulate(mI[mBufferIndex], mQ[mBufferIndex]);
            CalibrationBenchmark.consume(demodulated);
            mBufferIndex = (mBufferIndex + 1) % mI.length;
            int index = mObservationIndex++;

            if(mObservationIndex >= demodulated.length)
            {
                mObservationIndex = 0;
            }

            return CalibrationBenchmark.fingerprint(demodulated[index]);
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
