/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.vector.calibrate.filter;

import io.github.dsheirer.dsp.filter.channelizer.IPolyphaseChannelizerFilter;
import io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelizerFilterFactory;
import io.github.dsheirer.vector.calibrate.Calibration;
import io.github.dsheirer.vector.calibrate.CalibrationBenchmark;
import io.github.dsheirer.vector.calibrate.CalibrationException;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.LongSupplier;
import jdk.incubator.vector.FloatVector;

/**
 * Selects the fastest scalar or explicit vector implementation for the full-rate polyphase channelizer filter.
 */
public class PolyphaseChannelizerFilterCalibration extends Calibration
{
    private static final int TAPS_PER_CHANNEL = 9;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_DURATION = Duration.ofMillis(750);
    private static final int BENCHMARK_BATCH_SIZE = 4;

    //Representative 1.0, 1.25, 2.4, 3.2, and 10 MHz channelizer sizes plus the minimum supported size.  Counts 12
    //and 100 exercise tails for the wider vector species while remaining valid I/Q sub-channel counts.
    private static final int[] SUB_CHANNEL_COUNTS = new int[]{4, 12, 80, 100, 192, 256, 800};
    private final BenchmarkShape[] mShapes = new BenchmarkShape[SUB_CHANNEL_COUNTS.length];

    /**
     * Constructs the calibration with reusable benchmark buffers.  No allocation occurs inside a timed filter call.
     */
    public PolyphaseChannelizerFilterCalibration()
    {
        super(CalibrationType.POLYPHASE_CHANNELIZER_FILTER);

        for(int x = 0; x < SUB_CHANNEL_COUNTS.length; x++)
        {
            int subChannelCount = SUB_CHANNEL_COUNTS[x];
            int inputLength = subChannelCount * TAPS_PER_CHANNEL;
            mShapes[x] = new BenchmarkShape(subChannelCount,
                getFloatSamples(inputLength, "samples-" + subChannelCount),
                getFloatSamples(inputLength, "coefficients-" + subChannelCount));
        }
    }

    @Override
    public void calibrate() throws CalibrationException
    {
        Implementation[] candidates = getSupportedImplementations();

        for(Implementation candidate: candidates)
        {
            verify(candidate);
        }

        for(Implementation candidate: candidates)
        {
            test(candidate, WARMUP_DURATION);
        }

        Implementation bestImplementation = Implementation.SCALAR;
        double bestScore = -1.0;

        for(Implementation candidate: candidates)
        {
            double score = test(candidate, TEST_DURATION);
            mLog.info("POLYPHASE CHANNELIZER FILTER - {}: {} representative shape passes/second", candidate,
                DECIMAL_FORMAT.format(score));

            if(score > bestScore)
            {
                bestScore = score;
                bestImplementation = candidate;
            }
        }

        setImplementation(bestImplementation);
        mLog.info("POLYPHASE CHANNELIZER FILTER - SET OPTIMAL IMPLEMENTATION TO: " + getImplementation());
    }

    /**
     * Performs an exact scalar/vector comparison before timing a candidate.  Vector lanes span independent channels,
     * so every value is expected to be bit-for-bit identical to the scalar tap-order result.
     */
    private void verify(Implementation implementation) throws CalibrationException
    {
        IPolyphaseChannelizerFilter scalar = PolyphaseChannelizerFilterFactory.getFilter(Implementation.SCALAR);
        IPolyphaseChannelizerFilter candidate = PolyphaseChannelizerFilterFactory.getFilter(implementation);

        for(BenchmarkShape shape: mShapes)
        {
            float[] expected = new float[shape.subChannelCount()];
            float[] actual = new float[shape.subChannelCount()];
            Arrays.fill(actual, Float.NaN);
            scalar.filter(shape.samples(), shape.coefficients(), expected, TAPS_PER_CHANNEL, shape.subChannelCount());
            candidate.filter(shape.samples(), shape.coefficients(), actual, TAPS_PER_CHANNEL, shape.subChannelCount());
            CalibrationBenchmark.requireExact("Polyphase channelizer " + implementation + " with " +
                shape.subChannelCount() + " sub-channels", expected, actual);
        }
    }

    /**
     * Measures complete passes across the representative channelizer shapes.
     */
    private double test(Implementation implementation, Duration duration)
    {
        IPolyphaseChannelizerFilter filter = PolyphaseChannelizerFilterFactory.getFilter(implementation);
        return CalibrationBenchmark.measure(duration, BENCHMARK_BATCH_SIZE,
            new FilterOperation(filter, mShapes)).operationsPerSecond();
    }

    private static Implementation[] getSupportedImplementations()
    {
        int preferredLanes = FloatVector.SPECIES_PREFERRED.length();

        if(preferredLanes >= FloatVector.SPECIES_512.length())
        {
            return new Implementation[]{Implementation.SCALAR, Implementation.VECTOR_SIMD_64,
                Implementation.VECTOR_SIMD_128, Implementation.VECTOR_SIMD_256, Implementation.VECTOR_SIMD_512};
        }
        else if(preferredLanes >= FloatVector.SPECIES_256.length())
        {
            return new Implementation[]{Implementation.SCALAR, Implementation.VECTOR_SIMD_64,
                Implementation.VECTOR_SIMD_128, Implementation.VECTOR_SIMD_256};
        }
        else if(preferredLanes >= FloatVector.SPECIES_128.length())
        {
            return new Implementation[]{Implementation.SCALAR, Implementation.VECTOR_SIMD_64,
                Implementation.VECTOR_SIMD_128};
        }

        return new Implementation[]{Implementation.SCALAR, Implementation.VECTOR_SIMD_64};
    }

    private record BenchmarkShape(int subChannelCount, float[] samples, float[] coefficients, float[] accumulator)
    {
        private BenchmarkShape(int subChannelCount, float[] samples, float[] coefficients)
        {
            this(subChannelCount, samples, coefficients, new float[subChannelCount]);
        }
    }

    /** Runs one complete pass across the representative channelizer shapes and publishes every reused output. */
    private static class FilterOperation implements LongSupplier
    {
        private final IPolyphaseChannelizerFilter mFilter;
        private final BenchmarkShape[] mShapes;
        private final float[][] mObservableOutputs;
        private int mObservationIndex;

        private FilterOperation(IPolyphaseChannelizerFilter filter, BenchmarkShape[] shapes)
        {
            mFilter = filter;
            mShapes = shapes;
            mObservableOutputs = new float[shapes.length][];

            for(int x = 0; x < shapes.length; x++)
            {
                mObservableOutputs[x] = shapes[x].accumulator();
            }
        }

        @Override public long getAsLong()
        {
            long fingerprint = 0;

            for(BenchmarkShape shape: mShapes)
            {
                mFilter.filter(shape.samples(), shape.coefficients(), shape.accumulator(), TAPS_PER_CHANNEL,
                    shape.subChannelCount());
                int index = mObservationIndex % shape.subChannelCount();
                fingerprint = CalibrationBenchmark.combine(fingerprint,
                    CalibrationBenchmark.fingerprint(shape.accumulator()[index]));
            }

            CalibrationBenchmark.consume(mObservableOutputs);
            mObservationIndex++;
            return fingerprint;
        }
    }
}
