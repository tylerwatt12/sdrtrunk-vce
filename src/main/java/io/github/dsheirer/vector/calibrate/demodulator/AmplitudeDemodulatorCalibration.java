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

package io.github.dsheirer.vector.calibrate.demodulator;

import io.github.dsheirer.dsp.am.AmplitudeDemodulatorFactory;
import io.github.dsheirer.dsp.fm.IDemodulator;
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
 * Selects the fastest correct AM envelope detector for the current CPU.
 */
public class AmplitudeDemodulatorCalibration extends Calibration
{
    private static final int BUFFER_SIZE = 2048;
    private static final int BATCH_SIZE = 4;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_TRIAL_DURATION = Duration.ofMillis(200);
    private static final float ABSOLUTE_TOLERANCE = 0.000001f;
    private static final float RELATIVE_TOLERANCE = 0.000001f;
    public AmplitudeDemodulatorCalibration()
    {
        super(CalibrationType.AM_DEMODULATOR);
    }

    @Override
    public void calibrate() throws CalibrationException
    {
        float[] i = getFloatSamples(BUFFER_SIZE, "inphase");
        float[] q = getFloatSamples(BUFFER_SIZE, "quadrature");
        float[] expected = AmplitudeDemodulatorFactory.getDemodulator(Implementation.SCALAR).demodulate(i, q);
        List<Implementation> candidates = getCandidates();

        for(Implementation implementation: candidates)
        {
            IDemodulator demodulator = AmplitudeDemodulatorFactory.getDemodulator(implementation);
            float[] actual = demodulator.demodulate(i, q);
            CalibrationBenchmark.requireEquivalent(implementation.name(), expected, actual, ABSOLUTE_TOLERANCE,
                RELATIVE_TOLERANCE);
            measure(implementation, i, q, WARMUP_DURATION);
        }

        double[] medianScores = CalibrationSelector.alternatingMedians(candidates,
            implementation -> measure(implementation, i, q, TEST_TRIAL_DURATION));

        for(int x = 0; x < candidates.size(); x++)
        {
            mLog.info("AM DEMODULATOR - {}: {} median operations/second", candidates.get(x),
                DECIMAL_FORMAT.format(medianScores[x]));
        }

        setImplementation(candidates.get(CalibrationSelector.selectFastestReliableCandidate(medianScores)));
        mLog.info("AM DEMODULATOR - SET OPTIMAL IMPLEMENTATION TO: {}", getImplementation());
    }

    private static double measure(Implementation implementation, float[] i, float[] q, Duration duration)
    {
        IDemodulator demodulator = AmplitudeDemodulatorFactory.getDemodulator(implementation);
        return CalibrationBenchmark.measure(duration, BATCH_SIZE, new DemodulatorOperation(demodulator, i, q))
            .operationsPerSecond();
    }

    /** Benchmarks each distinct native shape without spending calibration time on wider emulated vectors. */
    private static List<Implementation> getCandidates()
    {
        int preferredBits = FloatVector.SPECIES_PREFERRED.vectorBitSize();
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

    /** Publishes each returned array and fingerprints a rotating output element. */
    private static class DemodulatorOperation implements LongSupplier
    {
        private static final int INDEX_STEP = 127;
        private final IDemodulator mDemodulator;
        private final float[] mI;
        private final float[] mQ;
        private int mIndex;

        private DemodulatorOperation(IDemodulator demodulator, float[] i, float[] q)
        {
            mDemodulator = demodulator;
            mI = i;
            mQ = q;
        }

        @Override
        public long getAsLong()
        {
            float[] output = mDemodulator.demodulate(mI, mQ);
            CalibrationBenchmark.consume(output);
            long fingerprint = CalibrationBenchmark.fingerprint(output[mIndex]);
            mIndex = (mIndex + INDEX_STEP) & (BUFFER_SIZE - 1);
            return fingerprint;
        }
    }
}
