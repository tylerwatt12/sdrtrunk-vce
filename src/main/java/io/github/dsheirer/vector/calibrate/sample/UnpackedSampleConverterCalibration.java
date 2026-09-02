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

package io.github.dsheirer.vector.calibrate.sample;

import io.github.dsheirer.buffer.sample.SampleBufferIterator;
import io.github.dsheirer.buffer.sample.SampleBufferIteratorScalar;
import io.github.dsheirer.buffer.sample.SampleBufferIteratorVector128Bits;
import io.github.dsheirer.buffer.sample.SampleBufferIteratorVector256Bits;
import io.github.dsheirer.buffer.sample.SampleBufferIteratorVector512Bits;
import io.github.dsheirer.buffer.sample.SampleBufferIteratorVector64Bits;
import io.github.dsheirer.sample.complex.ComplexSamples;
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
 * Selects the fastest correct non-interleaved iterator for converted unsigned 12-bit sample buffers.
 */
public class UnpackedSampleConverterCalibration extends Calibration
{
    private static final int BUFFER_SIZE = 131_072;
    private static final float AVERAGE_DC = 0.0125f;
    private static final long TIMESTAMP = 1_234_567_890L;
    private static final float SAMPLES_PER_MILLISECOND = 2_048.0f;
    private static final int BENCHMARK_BATCH_SIZE = 1;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_DURATION = Duration.ofMillis(750);
    private static final float ABSOLUTE_TOLERANCE = 0.00002f;
    private static final float RELATIVE_TOLERANCE = 0.00002f;
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
    public UnpackedSampleConverterCalibration()
    {
        super(CalibrationType.SAMPLE_UNPACKED_ITERATOR);
    }

    @Override
    public void calibrate() throws CalibrationException
    {
        short[] samples = createUnsigned12BitFixture(BUFFER_SIZE, "unsigned-12-bit-sample-stream");
        short[] residualI = createUnsigned12BitFixture(SampleBufferIterator.I_OVERLAP, "in-phase-residual");
        short[] residualQ = createUnsigned12BitFixture(SampleBufferIterator.Q_OVERLAP, "quadrature-residual");
        List<ComplexSamples> expected = collect(createIterator(Implementation.SCALAR, samples.clone(),
            residualI.clone(), residualQ.clone()));
        Implementation bestImplementation = Implementation.SCALAR;
        double bestScore = 0.0d;

        for(Implementation implementation: CANDIDATES)
        {
            if(!isSupported(implementation))
            {
                continue;
            }

            List<ComplexSamples> actual = collect(createIterator(implementation, samples.clone(), residualI.clone(),
                residualQ.clone()));
            requireEquivalent(implementation, expected, actual);

            CalibrationBenchmark.measure(WARMUP_DURATION, BENCHMARK_BATCH_SIZE,
                new IteratorOperation(implementation, samples, residualI, residualQ));
            double score = CalibrationBenchmark.measure(TEST_DURATION, BENCHMARK_BATCH_SIZE,
                new IteratorOperation(implementation, samples, residualI, residualQ)).operationsPerSecond();
            mLog.info("UNPACKED SAMPLE ITERATOR - {}: {} buffers/second", implementation,
                DECIMAL_FORMAT.format(score));

            if(score > bestScore)
            {
                bestScore = score;
                bestImplementation = implementation;
            }
        }

        setImplementation(bestImplementation);
        mLog.info("UNPACKED SAMPLE ITERATOR - SET OPTIMAL IMPLEMENTATION TO: {}", getImplementation());
    }

    private static short[] createUnsigned12BitFixture(int size, String fixtureName)
    {
        short[] samples = new short[size];
        int offset = fixtureName.hashCode() & 0xFFF;

        for(int x = 0; x < size; x++)
        {
            //4,051 is odd, so this walks the complete 12-bit value space before repeating.
            samples[x] = (short)(((x + offset) * 4_051) & 0xFFF);
        }

        return samples;
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

    private static SampleBufferIterator<ComplexSamples> createIterator(Implementation implementation,
                                                                         short[] samples, short[] residualI,
                                                                         short[] residualQ)
    {
        return switch(implementation)
        {
            case VECTOR_SIMD_64 -> new SampleBufferIteratorVector64Bits(samples, residualI, residualQ, AVERAGE_DC,
                TIMESTAMP, SAMPLES_PER_MILLISECOND);
            case VECTOR_SIMD_128 -> new SampleBufferIteratorVector128Bits(samples, residualI, residualQ, AVERAGE_DC,
                TIMESTAMP, SAMPLES_PER_MILLISECOND);
            case VECTOR_SIMD_256 -> new SampleBufferIteratorVector256Bits(samples, residualI, residualQ, AVERAGE_DC,
                TIMESTAMP, SAMPLES_PER_MILLISECOND);
            case VECTOR_SIMD_512 -> new SampleBufferIteratorVector512Bits(samples, residualI, residualQ, AVERAGE_DC,
                TIMESTAMP, SAMPLES_PER_MILLISECOND);
            default -> new SampleBufferIteratorScalar(samples, residualI, residualQ, AVERAGE_DC,
                TIMESTAMP, SAMPLES_PER_MILLISECOND);
        };
    }

    private static List<ComplexSamples> collect(SampleBufferIterator<ComplexSamples> iterator)
    {
        List<ComplexSamples> fragments = new ArrayList<>();

        while(iterator.hasNext())
        {
            fragments.add(iterator.next());
        }

        return fragments;
    }

    private static void requireEquivalent(Implementation implementation, List<ComplexSamples> expected,
                                          List<ComplexSamples> actual) throws CalibrationException
    {
        if(expected.size() != actual.size())
        {
            throw new CalibrationException(implementation + " produced " + actual.size() + " fragments; expected " +
                expected.size());
        }

        for(int x = 0; x < expected.size(); x++)
        {
            ComplexSamples expectedFragment = expected.get(x);
            ComplexSamples actualFragment = actual.get(x);
            CalibrationBenchmark.requireEquivalent(implementation + " I fragment " + x, expectedFragment.i(),
                actualFragment.i(), ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);
            CalibrationBenchmark.requireEquivalent(implementation + " Q fragment " + x, expectedFragment.q(),
                actualFragment.q(), ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);

            if(expectedFragment.timestamp() != actualFragment.timestamp())
            {
                throw new CalibrationException(implementation + " timestamp differs for fragment " + x +
                    ": expected " + expectedFragment.timestamp() + ", actual " + actualFragment.timestamp());
            }
        }
    }

    private static class IteratorOperation implements LongSupplier
    {
        private final Implementation mImplementation;
        private final short[] mSamples;
        private final short[] mResidualI;
        private final short[] mResidualQ;
        private int mObservationIndex;

        private IteratorOperation(Implementation implementation, short[] samples, short[] residualI, short[] residualQ)
        {
            mImplementation = implementation;
            mSamples = samples.clone();
            mResidualI = residualI.clone();
            mResidualQ = residualQ.clone();
        }

        @Override
        public long getAsLong()
        {
            SampleBufferIterator<ComplexSamples> iterator = createIterator(mImplementation, mSamples, mResidualI,
                mResidualQ);
            long fingerprint = 0L;

            while(iterator.hasNext())
            {
                ComplexSamples fragment = iterator.next();
                CalibrationBenchmark.consume(fragment);
                int index = mObservationIndex++ % fragment.i().length;
                fingerprint = CalibrationBenchmark.combine(fingerprint,
                    CalibrationBenchmark.combine(CalibrationBenchmark.fingerprint(fragment.i()[index]),
                        CalibrationBenchmark.fingerprint(fragment.q()[index])));
            }

            return fingerprint;
        }
    }
}
