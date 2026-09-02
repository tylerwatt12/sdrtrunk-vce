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

package io.github.dsheirer.vector.calibrate.filter;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.design.FilterDesignException;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.filter.fir.real.RealFIRFilter;
import io.github.dsheirer.dsp.filter.fir.real.VectorRealFIRFilter128Bit;
import io.github.dsheirer.dsp.filter.fir.real.VectorRealFIRFilter256Bit;
import io.github.dsheirer.dsp.filter.fir.real.VectorRealFIRFilter512Bit;
import io.github.dsheirer.dsp.filter.fir.real.VectorRealFIRFilter64Bit;
import io.github.dsheirer.dsp.filter.fir.real.VectorRealFIRFilterDefaultBit;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.vector.calibrate.Calibration;
import io.github.dsheirer.vector.calibrate.CalibrationBenchmark;
import io.github.dsheirer.vector.calibrate.CalibrationException;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.time.Duration;
import java.util.function.LongSupplier;
import jdk.incubator.vector.FloatVector;

/**
 * Selects the fastest correct general-purpose real FIR filter for the current CPU.
 */
public class FirFilterCalibration extends Calibration
{
    private static final int BUFFER_SIZE = 2048;
    private static final int[] TAP_COUNTS = {31, 63};
    private static final int BENCHMARK_BATCH_SIZE = 1;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_DURATION = Duration.ofMillis(750);
    private static final float ABSOLUTE_TOLERANCE = 0.00002f;
    private static final float RELATIVE_TOLERANCE = 0.0002f;
    private static final Implementation[] CANDIDATES = {
        Implementation.SCALAR,
        Implementation.VECTOR_SIMD_PREFERRED,
        Implementation.VECTOR_SIMD_64,
        Implementation.VECTOR_SIMD_128,
        Implementation.VECTOR_SIMD_256,
        Implementation.VECTOR_SIMD_512
    };

    /**
     * Constructs an instance.
     */
    public FirFilterCalibration()
    {
        super(CalibrationType.FILTER_FIR);
    }

    @Override
    public void calibrate() throws CalibrationException
    {
        float[][] coefficients = createCoefficientFixtures();
        float[][][] samples = new float[TAP_COUNTS.length][2][];

        for(int shape = 0; shape < TAP_COUNTS.length; shape++)
        {
            samples[shape][0] = getFloatSamples(BUFFER_SIZE, "tap-" + TAP_COUNTS[shape] + "-buffer-a");
            samples[shape][1] = getFloatSamples(BUFFER_SIZE, "tap-" + TAP_COUNTS[shape] + "-buffer-b");
        }

        float[][][] expected = filterSequences(Implementation.SCALAR, coefficients, samples);
        Implementation bestImplementation = Implementation.SCALAR;
        double bestScore = 0.0d;

        for(Implementation implementation: CANDIDATES)
        {
            if(!isSupported(implementation))
            {
                continue;
            }

            float[][][] actual = filterSequences(implementation, coefficients, samples);

            for(int shape = 0; shape < TAP_COUNTS.length; shape++)
            {
                for(int buffer = 0; buffer < samples[shape].length; buffer++)
                {
                    CalibrationBenchmark.requireEquivalent(implementation + " " + TAP_COUNTS[shape] +
                        "-tap stream buffer " + buffer, expected[shape][buffer], actual[shape][buffer],
                        ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);
                }
            }

            CalibrationBenchmark.measure(WARMUP_DURATION, BENCHMARK_BATCH_SIZE,
                new FilterOperation(implementation, coefficients, samples));
            double score = CalibrationBenchmark.measure(TEST_DURATION, BENCHMARK_BATCH_SIZE,
                new FilterOperation(implementation, coefficients, samples)).operationsPerSecond();
            mLog.info("FIR FILTER - {}: {} buffers/second", implementation, DECIMAL_FORMAT.format(score));

            if(score > bestScore)
            {
                bestScore = score;
                bestImplementation = implementation;
            }
        }

        setImplementation(bestImplementation);
        mLog.info("FIR FILTER - SET OPTIMAL IMPLEMENTATION TO: {}", getImplementation());
    }

    private float[][] createCoefficientFixtures() throws CalibrationException
    {
        float[][] coefficients = new float[TAP_COUNTS.length][];

        try
        {
            for(int x = 0; x < TAP_COUNTS.length; x++)
            {
                coefficients[x] = FilterFactory.getSinc(0.25d, TAP_COUNTS[x], WindowType.BLACKMAN);
            }
        }
        catch(FilterDesignException exception)
        {
            throw new CalibrationException("Unable to design FIR calibration fixtures", exception);
        }

        return coefficients;
    }

    /**
     * The preferred-width implementation represents the CPU's widest hardware species.  Fixed-width candidates are
     * useful only when narrower than that species; the equal-width fixed candidate would duplicate preferred.
     */
    private static boolean isSupported(Implementation implementation)
    {
        int preferredLanes = FloatVector.SPECIES_PREFERRED.length();

        return switch(implementation)
        {
            case VECTOR_SIMD_64 -> FloatVector.SPECIES_64.length() < preferredLanes;
            case VECTOR_SIMD_128 -> FloatVector.SPECIES_128.length() < preferredLanes;
            case VECTOR_SIMD_256 -> FloatVector.SPECIES_256.length() < preferredLanes;
            case VECTOR_SIMD_512 -> FloatVector.SPECIES_512.length() < preferredLanes;
            default -> true;
        };
    }

    private static IRealFilter createFilter(Implementation implementation, float[] coefficients)
    {
        //All current FIR implementations reverse the supplied coefficient array in place.  Give every filter a
        //private copy so contestant construction cannot alter another contestant's coefficients.
        float[] privateCoefficients = coefficients.clone();

        return switch(implementation)
        {
            case VECTOR_SIMD_PREFERRED -> new VectorRealFIRFilterDefaultBit(privateCoefficients);
            case VECTOR_SIMD_64 -> new VectorRealFIRFilter64Bit(privateCoefficients);
            case VECTOR_SIMD_128 -> new VectorRealFIRFilter128Bit(privateCoefficients);
            case VECTOR_SIMD_256 -> new VectorRealFIRFilter256Bit(privateCoefficients);
            case VECTOR_SIMD_512 -> new VectorRealFIRFilter512Bit(privateCoefficients);
            default -> new RealFIRFilter(privateCoefficients);
        };
    }

    private static IRealFilter[] createFilters(Implementation implementation, float[][] coefficients)
    {
        IRealFilter[] filters = new IRealFilter[coefficients.length];

        for(int x = 0; x < coefficients.length; x++)
        {
            filters[x] = createFilter(implementation, coefficients[x]);
        }

        return filters;
    }

    private static float[][][] filterSequences(Implementation implementation, float[][] coefficients,
                                                float[][][] samples)
    {
        IRealFilter[] filters = createFilters(implementation, coefficients);
        float[][][] filtered = new float[samples.length][][];

        for(int shape = 0; shape < samples.length; shape++)
        {
            filtered[shape] = new float[samples[shape].length][];

            for(int buffer = 0; buffer < samples[shape].length; buffer++)
            {
                filtered[shape][buffer] = filters[shape].filter(samples[shape][buffer]);
            }
        }

        return filtered;
    }

    /**
     * Gives 31- and 63-tap filters equal turns on independent stream state and observes rotating output positions.
     */
    private static class FilterOperation implements LongSupplier
    {
        private final IRealFilter[] mFilters;
        private final float[][][] mSamples;
        private final int[] mBufferIndexes;
        private int mShapeIndex;
        private int mObservationIndex;

        private FilterOperation(Implementation implementation, float[][] coefficients, float[][][] samples)
        {
            mFilters = createFilters(implementation, coefficients);
            mSamples = clone(samples);
            mBufferIndexes = new int[samples.length];
        }

        @Override
        public long getAsLong()
        {
            int shape = mShapeIndex;
            int buffer = mBufferIndexes[shape];
            float[] filtered = mFilters[shape].filter(mSamples[shape][buffer]);
            CalibrationBenchmark.consume(filtered);
            mBufferIndexes[shape] = (buffer + 1) % mSamples[shape].length;
            mShapeIndex = (shape + 1) % mFilters.length;
            int index = mObservationIndex++;

            if(mObservationIndex >= filtered.length)
            {
                mObservationIndex = 0;
            }

            return CalibrationBenchmark.fingerprint(filtered[index]);
        }

        private static float[][][] clone(float[][][] source)
        {
            float[][][] copy = new float[source.length][][];

            for(int x = 0; x < source.length; x++)
            {
                copy[x] = new float[source[x].length][];

                for(int y = 0; y < source[x].length; y++)
                {
                    copy[x][y] = source[x][y].clone();
                }
            }

            return copy;
        }
    }
}
