/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.vector.calibrate.sample;

import io.github.dsheirer.source.tuner.sdrplay.IRspSampleConverter;
import io.github.dsheirer.source.tuner.sdrplay.RspSampleConverterFactory;
import io.github.dsheirer.source.tuner.sdrplay.VectorRspSampleConverter;
import io.github.dsheirer.vector.calibrate.Calibration;
import io.github.dsheirer.vector.calibrate.CalibrationBenchmark;
import io.github.dsheirer.vector.calibrate.CalibrationException;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Selects the fastest correct signed-short sample converter for SDRplay native buffers.
 */
public class RspSampleConverterCalibration extends Calibration
{
    private static final int BUFFER_SIZE = 2048;
    private static final int CORRECTNESS_BUFFER_SIZE = BUFFER_SIZE + 3;
    private static final int BATCH_SIZE = 4;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_DURATION = Duration.ofMillis(750);

    public RspSampleConverterCalibration()
    {
        super(CalibrationType.RSP_SAMPLE_CONVERTER);
    }

    @Override
    public void calibrate() throws CalibrationException
    {
        short[] correctnessI = getShortSamples(CORRECTNESS_BUFFER_SIZE, "correctness-inphase");
        short[] correctnessQ = getShortSamples(CORRECTNESS_BUFFER_SIZE, "correctness-quadrature");
        insertEdgeValues(correctnessI);
        insertEdgeValues(correctnessQ);

        IRspSampleConverter scalar = RspSampleConverterFactory.getConverter(Implementation.SCALAR);
        ConversionOutput expected = convert(scalar, correctnessI, correctnessQ);
        short[] iSamples = getShortSamples(BUFFER_SIZE, "benchmark-inphase");
        short[] qSamples = getShortSamples(BUFFER_SIZE, "benchmark-quadrature");
        insertEdgeValues(iSamples);
        insertEdgeValues(qSamples);

        Implementation bestImplementation = Implementation.SCALAR;
        double bestScore = 0.0d;

        for(Implementation implementation: getCandidates())
        {
            IRspSampleConverter converter = RspSampleConverterFactory.getConverter(implementation);
            requireCorrect(implementation, expected, convert(converter, correctnessI, correctnessQ));
            ConverterOperation operation = new ConverterOperation(converter, iSamples, qSamples);

            CalibrationBenchmark.measure(WARMUP_DURATION, BATCH_SIZE, operation);
            CalibrationBenchmark.Result result = CalibrationBenchmark.measure(TEST_DURATION, BATCH_SIZE, operation);
            double score = result.operationsPerSecond();
            mLog.info("SDRPLAY RSP SAMPLE CONVERTER - {}: {} paired conversions/second", implementation,
                DECIMAL_FORMAT.format(score));

            if(score > bestScore)
            {
                bestScore = score;
                bestImplementation = implementation;
            }
        }

        setImplementation(bestImplementation);
        RspSampleConverterFactory.setImplementation(bestImplementation);
        mLog.info("SDRPLAY RSP SAMPLE CONVERTER - SET OPTIMAL IMPLEMENTATION TO: {}", getImplementation());
    }

    /**
     * Benchmarks every distinct hardware-native vector shape.  Preferred represents the largest shape supported by
     * both short and float vectors, while explicit smaller shapes remain candidates because some CPUs execute
     * narrower vectors more efficiently.
     */
    private static List<Implementation> getCandidates()
    {
        int preferredBits = VectorRspSampleConverter.getPreferredVectorBitSize();
        List<Implementation> candidates = new ArrayList<>();
        candidates.add(Implementation.SCALAR);

        if(preferredBits > 64)
        {
            candidates.add(Implementation.VECTOR_SIMD_64);
        }

        if(preferredBits > 128)
        {
            candidates.add(Implementation.VECTOR_SIMD_128);
        }

        if(preferredBits > 256)
        {
            candidates.add(Implementation.VECTOR_SIMD_256);
        }

        candidates.add(Implementation.VECTOR_SIMD_PREFERRED);
        return candidates;
    }

    private static ConversionOutput convert(IRspSampleConverter converter, short[] iSamples, short[] qSamples)
    {
        float[] iOutput = new float[iSamples.length];
        float[] qOutput = new float[qSamples.length];
        float[] interleavedOutput = new float[iSamples.length * 2];
        converter.convert(iSamples, qSamples, iOutput, qOutput);
        converter.convertInterleaved(iSamples, qSamples, interleavedOutput);
        return new ConversionOutput(iOutput, qOutput, interleavedOutput);
    }

    private static void requireCorrect(Implementation implementation, ConversionOutput expected,
                                       ConversionOutput actual) throws CalibrationException
    {
        String candidate = implementation.name();
        CalibrationBenchmark.requireExact(candidate + " separate I", expected.i(), actual.i());
        CalibrationBenchmark.requireExact(candidate + " separate Q", expected.q(), actual.q());
        CalibrationBenchmark.requireExact(candidate + " interleaved", expected.interleaved(), actual.interleaved());
    }

    private static void insertEdgeValues(short[] samples)
    {
        if(samples.length >= 5)
        {
            samples[0] = Short.MIN_VALUE;
            samples[1] = Short.MAX_VALUE;
            samples[2] = -1;
            samples[3] = 0;
            samples[4] = 1;
        }
    }

    private record ConversionOutput(float[] i, float[] q, float[] interleaved)
    {
    }

    /**
     * Reuses all benchmark output arrays.  A fixed wrapper publishes every output array to the benchmark black hole,
     * while the rotating output-derived fingerprint supplies inexpensive data dependence for the timed operation.
     */
    private static class ConverterOperation implements LongSupplier
    {
        private static final int INDEX_STEP = 127;
        private final IRspSampleConverter mConverter;
        private final short[] mISamples;
        private final short[] mQSamples;
        private final float[] mIOutput;
        private final float[] mQOutput;
        private final float[] mInterleavedOutput;
        private final Object[] mObservableOutputs;
        private int mFingerprintIndex;

        private ConverterOperation(IRspSampleConverter converter, short[] iSamples, short[] qSamples)
        {
            mConverter = converter;
            mISamples = iSamples;
            mQSamples = qSamples;
            mIOutput = new float[iSamples.length];
            mQOutput = new float[qSamples.length];
            mInterleavedOutput = new float[iSamples.length * 2];
            mObservableOutputs = new Object[]{mIOutput, mQOutput, mInterleavedOutput};
        }

        @Override
        public long getAsLong()
        {
            mConverter.convert(mISamples, mQSamples, mIOutput, mQOutput);
            mConverter.convertInterleaved(mISamples, mQSamples, mInterleavedOutput);
            CalibrationBenchmark.consume(mObservableOutputs);
            int index = mFingerprintIndex;
            int interleavedIndex = 2 * index;
            long fingerprint = CalibrationBenchmark.fingerprint(mIOutput[index]);
            fingerprint = CalibrationBenchmark.combine(fingerprint,
                CalibrationBenchmark.fingerprint(mQOutput[index]));
            fingerprint = CalibrationBenchmark.combine(fingerprint,
                CalibrationBenchmark.fingerprint(mInterleavedOutput[interleavedIndex]));
            fingerprint = CalibrationBenchmark.combine(fingerprint,
                CalibrationBenchmark.fingerprint(mInterleavedOutput[interleavedIndex + 1]));
            mFingerprintIndex = (index + INDEX_STEP) & (BUFFER_SIZE - 1);
            return fingerprint;
        }
    }
}
