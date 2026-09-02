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

import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.dsp.fm.ScalarFMDemodulator;
import io.github.dsheirer.dsp.fm.VectorFMDemodulator128;
import io.github.dsheirer.dsp.fm.VectorFMDemodulator256;
import io.github.dsheirer.dsp.fm.VectorFMDemodulator512;
import io.github.dsheirer.dsp.fm.VectorFMDemodulator64;
import io.github.dsheirer.vector.calibrate.Calibration;
import io.github.dsheirer.vector.calibrate.CalibrationBenchmark;
import io.github.dsheirer.vector.calibrate.CalibrationException;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.time.Duration;
import java.util.function.LongSupplier;
import jdk.incubator.vector.FloatVector;

/**
 * Selects the fastest correct FM demodulator for the current CPU.
 */
public class FmDemodulatorCalibration extends Calibration
{
    private static final int BUFFER_SIZE = 2048;
    private static final int BENCHMARK_BATCH_SIZE = 4;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_DURATION = Duration.ofMillis(750);
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
    public FmDemodulatorCalibration()
    {
        super(CalibrationType.FM_DEMODULATOR);
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
        float[][] expected = demodulateSequence(new ScalarFMDemodulator(), i, q);
        Implementation bestImplementation = Implementation.SCALAR;
        double bestScore = 0.0d;

        for(Implementation implementation: CANDIDATES)
        {
            if(!isSupported(implementation))
            {
                continue;
            }

            float[][] actual = demodulateSequence(createDemodulator(implementation), i, q);

            for(int x = 0; x < expected.length; x++)
            {
                CalibrationBenchmark.requireEquivalent(implementation + " stream buffer " + x, expected[x],
                    actual[x], ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);
            }

            CalibrationBenchmark.measure(WARMUP_DURATION, BENCHMARK_BATCH_SIZE,
                new DemodulatorOperation(createDemodulator(implementation), i, q));
            double score = CalibrationBenchmark.measure(TEST_DURATION, BENCHMARK_BATCH_SIZE,
                new DemodulatorOperation(createDemodulator(implementation), i, q)).operationsPerSecond();
            mLog.info("FM DEMODULATOR - {}: {} buffers/second", implementation, DECIMAL_FORMAT.format(score));

            if(score > bestScore)
            {
                bestScore = score;
                bestImplementation = implementation;
            }
        }

        setImplementation(bestImplementation);
        mLog.info("FM DEMODULATOR - SET OPTIMAL IMPLEMENTATION TO: {}", getImplementation());
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

    private static IDemodulator createDemodulator(Implementation implementation)
    {
        return switch(implementation)
        {
            case VECTOR_SIMD_64 -> new VectorFMDemodulator64();
            case VECTOR_SIMD_128 -> new VectorFMDemodulator128();
            case VECTOR_SIMD_256 -> new VectorFMDemodulator256();
            case VECTOR_SIMD_512 -> new VectorFMDemodulator512();
            default -> new ScalarFMDemodulator();
        };
    }

    private static float[][] demodulateSequence(IDemodulator demodulator, float[][] i, float[][] q)
    {
        float[][] demodulated = new float[i.length][];

        for(int x = 0; x < i.length; x++)
        {
            demodulated[x] = demodulator.demodulate(i[x], q[x]);
        }

        return demodulated;
    }

    /**
     * Alternates two independent buffers through one demodulator so that cross-buffer previous-sample state remains
     * part of the measured production operation.  Warmup and measured contestants each receive a fresh instance.
     */
    private static class DemodulatorOperation implements LongSupplier
    {
        private final IDemodulator mDemodulator;
        private final float[][] mI;
        private final float[][] mQ;
        private int mBufferIndex;
        private int mObservationIndex;

        private DemodulatorOperation(IDemodulator demodulator, float[][] i, float[][] q)
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
