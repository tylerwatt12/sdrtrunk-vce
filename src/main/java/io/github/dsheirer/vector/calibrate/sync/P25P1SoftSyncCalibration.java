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

package io.github.dsheirer.vector.calibrate.sync;

import io.github.dsheirer.module.decode.p25.phase1.sync.P25P1SoftSyncDetector;
import io.github.dsheirer.module.decode.p25.phase1.sync.P25P1SoftSyncDetectorScalar;
import io.github.dsheirer.module.decode.p25.phase1.sync.P25P1SoftSyncDetectorVector128;
import io.github.dsheirer.module.decode.p25.phase1.sync.P25P1SoftSyncDetectorVector256;
import io.github.dsheirer.module.decode.p25.phase1.sync.P25P1SoftSyncDetectorVector512;
import io.github.dsheirer.module.decode.p25.phase1.sync.P25P1SoftSyncDetectorVector64;
import io.github.dsheirer.module.decode.p25.phase1.sync.P25P1SyncDetector;
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
 * P25 Phase 1 soft-sync detector calibration with deterministic exact, near-threshold and streaming fixtures.
 */
public class P25P1SoftSyncCalibration extends Calibration
{
    private static final int BUFFER_SIZE = 2048;
    private static final int BENCHMARK_BATCH_SIZE = 2;
    private static final float DETECTION_THRESHOLD = 80.0f;
    private static final float[] PRODUCTION_THRESHOLDS = {60.0f, DETECTION_THRESHOLD, 110.0f};
    private static final float ABSOLUTE_TOLERANCE = 1.0e-4f;
    private static final float RELATIVE_TOLERANCE = 2.0e-5f;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_DURATION = Duration.ofMillis(750);

    /**
     * Constructs an instance.
     */
    public P25P1SoftSyncCalibration()
    {
        super(CalibrationType.P25P1_SOFT_SYNC_DETECTOR);
    }

    @Override
    public void calibrate() throws CalibrationException
    {
        float[] fixture = createFixture();
        SyncResult expected = evaluate(createDetector(Implementation.SCALAR), fixture);
        Implementation bestImplementation = Implementation.SCALAR;
        double bestScore = 0.0d;

        for(Implementation implementation: getCandidates())
        {
            validateAnchors(implementation);
            SyncResult actual = evaluate(createDetector(implementation), fixture);
            requireEquivalent(implementation.toString(), expected, actual);

            CalibrationBenchmark.measure(WARMUP_DURATION, BENCHMARK_BATCH_SIZE,
                new DetectorOperation(createDetector(implementation), fixture));
            double score = CalibrationBenchmark.measure(TEST_DURATION, BENCHMARK_BATCH_SIZE,
                new DetectorOperation(createDetector(implementation), fixture)).operationsPerSecond();
            mLog.info("P25P1 SOFT SYNC DETECTOR - {}: {} fixture passes/second", implementation,
                DECIMAL_FORMAT.format(score));

            if(score > bestScore)
            {
                bestScore = score;
                bestImplementation = implementation;
            }
        }

        setImplementation(bestImplementation);
        mLog.info("P25P1 SOFT SYNC DETECTOR - SET OPTIMAL IMPLEMENTATION TO: {}", getImplementation());
    }

    private float[] createFixture()
    {
        float[] fixture = getFloatSamples(BUFFER_SIZE, "representative-soft-symbol-stream");
        float[] exact = P25P1SyncDetector.syncPatternToSymbols();

        for(int x = 0; x < fixture.length; x++)
        {
            fixture[x] *= 0.25f;
        }

        int sequence = 0;

        for(int offset = 24; offset + exact.length <= fixture.length; offset += 112)
        {
            float[] symbols = switch(sequence++ % 3)
            {
                case 0 -> exact;
                case 1 -> SoftSyncCalibrationHelper.createNearSync(exact, DETECTION_THRESHOLD - 0.75f);
                default -> SoftSyncCalibrationHelper.createNearSync(exact, DETECTION_THRESHOLD + 0.75f);
            };

            System.arraycopy(symbols, 0, fixture, offset, symbols.length);
        }

        return fixture;
    }

    /** Verifies both the correlation result and the decoder's threshold decision before timing a candidate. */
    private static void validateAnchors(Implementation implementation) throws CalibrationException
    {
        float[] exact = P25P1SyncDetector.syncPatternToSymbols();
        Detection exactExpected = detect(createDetector(Implementation.SCALAR), exact);

        if(!exactExpected.detected())
        {
            throw new CalibrationException("Invalid P25P1 exact-sync fixture: score=" + exactExpected.score());
        }

        requireEquivalent(implementation + " exact sync", exactExpected,
            detect(createDetector(implementation), exact));
        for(float threshold: PRODUCTION_THRESHOLDS)
        {
            validateNearAnchor(implementation, exact, threshold, false);
            validateNearAnchor(implementation, exact, threshold, true);
        }
    }

    private static void validateNearAnchor(Implementation implementation, float[] exact, float threshold,
                                           boolean shouldDetect)
        throws CalibrationException
    {
        float target = SoftSyncCalibrationHelper.boundaryTarget(threshold, shouldDetect);
        float[] near = SoftSyncCalibrationHelper.createNearSync(exact, target);
        Detection expected = detect(createDetector(Implementation.SCALAR), near, threshold);

        if(expected.detected() != shouldDetect)
        {
            throw new CalibrationException("Invalid P25P1 near-sync fixture at threshold " + threshold +
                ": score=" + expected.score());
        }

        requireEquivalent(implementation + " near sync threshold " + threshold + " " + shouldDetect, expected,
            detect(createDetector(implementation), near, threshold));
    }

    private static Detection detect(P25P1SoftSyncDetector detector, float[] symbols)
    {
        return detect(detector, symbols, DETECTION_THRESHOLD);
    }

    private static Detection detect(P25P1SoftSyncDetector detector, float[] symbols, float threshold)
    {
        float score = 0.0f;

        for(float symbol: symbols)
        {
            score = detector.process(symbol);
        }

        return new Detection(score, score > threshold);
    }

    private static SyncResult evaluate(P25P1SoftSyncDetector detector, float[] fixture)
    {
        float[] scores = new float[fixture.length];
        boolean[] detected = new boolean[fixture.length];

        for(int x = 0; x < fixture.length; x++)
        {
            scores[x] = detector.process(fixture[x]);
            detected[x] = scores[x] > DETECTION_THRESHOLD;
        }

        return new SyncResult(scores, detected);
    }

    private static void requireEquivalent(String candidate, SyncResult expected, SyncResult actual)
        throws CalibrationException
    {
        CalibrationBenchmark.requireEquivalent(candidate + " scores", expected.scores(), actual.scores(),
            ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);

        for(int x = 0; x < expected.detected().length; x++)
        {
            if(expected.detected()[x] != actual.detected()[x])
            {
                throw new CalibrationException(candidate + " changed the P25P1 sync decision at symbol " + x +
                    ": expected " + expected.detected()[x] + ", actual " + actual.detected()[x]);
            }
        }
    }

    private static void requireEquivalent(String candidate, Detection expected, Detection actual)
        throws CalibrationException
    {
        CalibrationBenchmark.requireEquivalent(candidate + " score", new float[]{expected.score()},
            new float[]{actual.score()}, ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);

        if(expected.detected() != actual.detected())
        {
            throw new CalibrationException(candidate + " changed the P25P1 sync decision: expected " +
                expected.detected() + ", actual " + actual.detected());
        }
    }

    /** Returns fixed-width implementations that fit within the platform's preferred native vector width. */
    private static List<Implementation> getCandidates()
    {
        int preferredBits = FloatVector.SPECIES_PREFERRED.vectorBitSize();
        List<Implementation> candidates = new ArrayList<>();
        candidates.add(Implementation.SCALAR);

        if(preferredBits >= 64)
        {
            candidates.add(Implementation.VECTOR_SIMD_64);
        }

        if(preferredBits >= 128)
        {
            candidates.add(Implementation.VECTOR_SIMD_128);
        }

        if(preferredBits >= 256)
        {
            candidates.add(Implementation.VECTOR_SIMD_256);
        }

        if(preferredBits >= 512)
        {
            candidates.add(Implementation.VECTOR_SIMD_512);
        }

        return candidates;
    }

    private static P25P1SoftSyncDetector createDetector(Implementation implementation)
    {
        return switch(implementation)
        {
            case VECTOR_SIMD_64 -> new P25P1SoftSyncDetectorVector64();
            case VECTOR_SIMD_128 -> new P25P1SoftSyncDetectorVector128();
            case VECTOR_SIMD_256 -> new P25P1SoftSyncDetectorVector256();
            case VECTOR_SIMD_512 -> new P25P1SoftSyncDetectorVector512();
            default -> new P25P1SoftSyncDetectorScalar();
        };
    }

    private record Detection(float score, boolean detected)
    {
    }

    private record SyncResult(float[] scores, boolean[] detected)
    {
    }

    /** Benchmarks a continuous detector stream and returns an output-derived fingerprint. */
    private static class DetectorOperation implements LongSupplier
    {
        private final P25P1SoftSyncDetector mDetector;
        private final float[] mFixture;

        private DetectorOperation(P25P1SoftSyncDetector detector, float[] fixture)
        {
            mDetector = detector;
            mFixture = fixture;
        }

        @Override
        public long getAsLong()
        {
            float scoreSum = 0.0f;
            int detections = 0;

            for(float symbol: mFixture)
            {
                float score = mDetector.process(symbol);
                scoreSum += score;
                detections += score > DETECTION_THRESHOLD ? 1 : 0;
            }

            return CalibrationBenchmark.combine(CalibrationBenchmark.fingerprint(scoreSum),
                Integer.toUnsignedLong(detections));
        }
    }
}
