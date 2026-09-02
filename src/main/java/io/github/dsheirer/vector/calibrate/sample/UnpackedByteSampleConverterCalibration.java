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

import io.github.dsheirer.buffer.sample.ISampleConverter;
import io.github.dsheirer.buffer.sample.ScalarUnpackedSampleConverter;
import io.github.dsheirer.buffer.sample.VectorUnpackedSampleConverter;
import io.github.dsheirer.vector.calibrate.Calibration;
import io.github.dsheirer.vector.calibrate.CalibrationBenchmark;
import io.github.dsheirer.vector.calibrate.CalibrationException;
import io.github.dsheirer.vector.calibrate.CalibrationSelector;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Selects the fastest correct converter for unpacked unsigned 12-bit samples stored in two bytes per sample.
 */
public class UnpackedByteSampleConverterCalibration extends Calibration
{
    private static final int UNSIGNED_12_BIT_VALUE_COUNT = 4096;
    private static final int SAMPLE_COUNT = 131_072;
    private static final int CORRECTNESS_STREAM_BUFFER_COUNT = 8;
    private static final int BENCHMARK_BATCH_SIZE = 1;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_TRIAL_DURATION = Duration.ofMillis(200);
    private static final Implementation[] CANDIDATES = {
        Implementation.SCALAR,
        Implementation.VECTOR_SIMD_PREFERRED
    };

    /**
     * Constructs an instance.
     */
    public UnpackedByteSampleConverterCalibration()
    {
        super(CalibrationType.SAMPLE_UNPACKED_BYTE_CONVERTER);
    }

    @Override
    public void calibrate() throws CalibrationException
    {
        byte[] fixture = createUnsigned12BitFixture();
        verifyImplementations(fixture);
        List<Implementation> candidates = List.of(CANDIDATES);

        for(Implementation implementation: candidates)
        {
            measure(implementation, fixture, WARMUP_DURATION);
        }

        double[] scores = CalibrationSelector.alternatingMedians(candidates,
            implementation -> measure(implementation, fixture, TEST_TRIAL_DURATION));

        for(int x = 0; x < candidates.size(); x++)
        {
            mLog.info("UNPACKED BYTE SAMPLE CONVERTER - {}: {} median buffers/second", candidates.get(x),
                DECIMAL_FORMAT.format(scores[x]));
        }

        setImplementation(candidates.get(CalibrationSelector.selectFastestReliableCandidate(scores)));
        mLog.info("UNPACKED BYTE SAMPLE CONVERTER - SET OPTIMAL IMPLEMENTATION TO: {}", getImplementation());
    }

    private static double measure(Implementation implementation, byte[] fixture, Duration duration)
    {
        return CalibrationBenchmark.measure(duration, BENCHMARK_BATCH_SIZE,
            new ConverterOperation(createConverter(implementation), fixture)).operationsPerSecond();
    }

    /**
     * Uses every unsigned 12-bit value repeatedly and sets ignored upper-nibble bits to a non-trivial pattern.  The
     * first 4,096 decoded samples are therefore also an independent check of the scalar reference conversion.
     */
    private static byte[] createUnsigned12BitFixture()
    {
        byte[] fixture = new byte[SAMPLE_COUNT * 2];

        for(int x = 0; x < SAMPLE_COUNT; x++)
        {
            int value = x & 0xFFF;
            fixture[2 * x] = (byte)value;
            fixture[2 * x + 1] = (byte)(((x * 7) & 0xF0) | (value >>> 8));
        }

        return fixture;
    }

    private static ISampleConverter createConverter(Implementation implementation)
    {
        return implementation == Implementation.VECTOR_SIMD_PREFERRED ?
            new VectorUnpackedSampleConverter() : new ScalarUnpackedSampleConverter();
    }

    private static void verifyImplementations(byte[] fixture) throws CalibrationException
    {
        ISampleConverter scalar = createConverter(Implementation.SCALAR);
        ISampleConverter vector = createConverter(Implementation.VECTOR_SIMD_PREFERRED);
        ByteBuffer scalarBuffer = ByteBuffer.wrap(fixture.clone());
        ByteBuffer vectorBuffer = ByteBuffer.wrap(fixture.clone());

        for(int bufferIndex = 0; bufferIndex < CORRECTNESS_STREAM_BUFFER_COUNT; bufferIndex++)
        {
            short[] expected = scalar.convert(scalarBuffer);
            short[] actual = vector.convert(vectorBuffer);
            requireEqual("Vector unpacked-byte converter stream buffer " + bufferIndex, expected, actual);

            if(bufferIndex == 0)
            {
                for(int value = 0; value < UNSIGNED_12_BIT_VALUE_COUNT; value++)
                {
                    if(expected[value] != value)
                    {
                        throw new CalibrationException("Scalar unpacked-byte converter decoded unsigned 12-bit " +
                            "value " + value + " as " + expected[value]);
                    }
                }
            }

            if(Float.floatToRawIntBits(scalar.getAverageDc()) != Float.floatToRawIntBits(vector.getAverageDc()))
            {
                throw new CalibrationException("Vector unpacked-byte converter DC state differs after stream buffer " +
                    bufferIndex + ": scalar=" + scalar.getAverageDc() + ", vector=" + vector.getAverageDc());
            }
        }
    }

    private static void requireEqual(String candidate, short[] expected, short[] actual) throws CalibrationException
    {
        if(expected.length != actual.length)
        {
            throw new CalibrationException(candidate + " produced " + actual.length + " samples; expected " +
                expected.length);
        }

        for(int x = 0; x < expected.length; x++)
        {
            if(expected[x] != actual[x])
            {
                throw new CalibrationException(candidate + " differs at index " + x + ": expected " + expected[x] +
                    ", actual " + actual[x]);
            }
        }
    }

    private static class ConverterOperation implements LongSupplier
    {
        private final ISampleConverter mConverter;
        private final ByteBuffer mBuffer;
        private int mObservationIndex;

        private ConverterOperation(ISampleConverter converter, byte[] fixture)
        {
            mConverter = converter;
            mBuffer = ByteBuffer.wrap(fixture.clone());
        }

        @Override
        public long getAsLong()
        {
            short[] converted = mConverter.convert(mBuffer);
            CalibrationBenchmark.consume(converted);
            int index = mObservationIndex++;

            if(mObservationIndex >= converted.length)
            {
                mObservationIndex = 0;
            }

            return CalibrationBenchmark.combine(converted[index] & 0xFFFFL,
                CalibrationBenchmark.fingerprint(mConverter.getAverageDc()));
        }
    }
}
