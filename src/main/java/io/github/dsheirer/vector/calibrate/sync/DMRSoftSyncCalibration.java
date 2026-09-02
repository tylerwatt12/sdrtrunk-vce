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

import io.github.dsheirer.module.decode.dmr.sync.DMRSoftSyncDetector;
import io.github.dsheirer.module.decode.dmr.sync.DMRSoftSyncDetectorScalar;
import io.github.dsheirer.module.decode.dmr.sync.DMRSoftSyncDetectorVector128;
import io.github.dsheirer.module.decode.dmr.sync.DMRSoftSyncDetectorVector256;
import io.github.dsheirer.module.decode.dmr.sync.DMRSoftSyncDetectorVector512;
import io.github.dsheirer.module.decode.dmr.sync.DMRSoftSyncDetectorVector64;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncDetectMode;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
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
 * DMR soft-sync detector calibration.  Each candidate is checked against an independent scalar detector over exact,
 * near-threshold and noisy streaming fixtures before its correlation work is timed.
 */
public class DMRSoftSyncCalibration extends Calibration
{
    private static final int BUFFER_SIZE = 2048;
    private static final int BENCHMARK_BATCH_SIZE = 2;
    private static final float DETECTION_THRESHOLD = 60.0f;
    private static final float[] PRODUCTION_THRESHOLDS = {DETECTION_THRESHOLD, 80.0f, 100.0f};
    private static final float ABSOLUTE_TOLERANCE = 1.0e-4f;
    private static final float RELATIVE_TOLERANCE = 2.0e-5f;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_TRIAL_DURATION = Duration.ofMillis(200);
    private static final DMRSyncDetectMode[] MODES = DMRSyncDetectMode.values();
    private static final DMRSyncPattern[] PATTERNS = {
        DMRSyncPattern.BASE_STATION_DATA,
        DMRSyncPattern.BASE_STATION_VOICE,
        DMRSyncPattern.MOBILE_STATION_DATA,
        DMRSyncPattern.MOBILE_STATION_VOICE,
        DMRSyncPattern.DIRECT_DATA_TIMESLOT_1,
        DMRSyncPattern.DIRECT_DATA_TIMESLOT_2,
        DMRSyncPattern.DIRECT_VOICE_TIMESLOT_1,
        DMRSyncPattern.DIRECT_VOICE_TIMESLOT_2
    };

    /**
     * Constructs an instance.
     */
    public DMRSoftSyncCalibration()
    {
        super(CalibrationType.DMR_SOFT_SYNC_DETECTOR);
    }

    @Override
    public void calibrate() throws CalibrationException
    {
        float[] fixture = createFixture();
        DMRResult[] expected = evaluateAllModes(Implementation.SCALAR, fixture);
        List<Implementation> candidates = getCandidates();

        for(Implementation implementation: candidates)
        {
            validateAnchors(implementation);
            DMRResult[] actual = evaluateAllModes(implementation, fixture);

            for(int mode = 0; mode < MODES.length; mode++)
            {
                requireEquivalent(implementation + " " + MODES[mode], expected[mode], actual[mode]);
            }

            measure(implementation, fixture, WARMUP_DURATION);
        }

        double[] medianScores = CalibrationSelector.alternatingMedians(candidates,
            implementation -> measure(implementation, fixture, TEST_TRIAL_DURATION));

        for(int x = 0; x < candidates.size(); x++)
        {
            mLog.info("DMR SOFT SYNC DETECTOR - {}: {} median fixture passes/second", candidates.get(x),
                DECIMAL_FORMAT.format(medianScores[x]));
        }

        setImplementation(candidates.get(CalibrationSelector.selectFastestReliableCandidate(medianScores)));
        mLog.info("DMR SOFT SYNC DETECTOR - SET OPTIMAL IMPLEMENTATION TO: {}", getImplementation());
    }

    private static double measure(Implementation implementation, float[] fixture, Duration duration)
    {
        return CalibrationBenchmark.measure(duration, BENCHMARK_BATCH_SIZE,
            new DetectorOperation(implementation, fixture)).operationsPerSecond();
    }

    /**
     * Creates a deterministic stream with low-level background symbols plus exact and near-threshold examples of
     * every selectable DMR sync pattern.
     */
    private float[] createFixture()
    {
        float[] fixture = getFloatSamples(BUFFER_SIZE, "representative-soft-symbol-stream");

        for(int x = 0; x < fixture.length; x++)
        {
            fixture[x] *= 0.25f;
        }

        for(int pattern = 0; pattern < PATTERNS.length; pattern++)
        {
            float[] symbols = PATTERNS[pattern].toSymbols();
            int offset = 16 + pattern * 248;
            System.arraycopy(symbols, 0, fixture, offset, symbols.length);
            System.arraycopy(SoftSyncCalibrationHelper.createNearSync(symbols, DETECTION_THRESHOLD - 0.75f), 0,
                fixture, offset + 72, symbols.length);
            System.arraycopy(SoftSyncCalibrationHelper.createNearSync(symbols, DETECTION_THRESHOLD + 0.75f), 0,
                fixture, offset + 144, symbols.length);
        }

        return fixture;
    }

    /**
     * Validates explicit detection outcomes on isolated exact and near-threshold patterns.  This makes the calibration
     * reject a numerically fast implementation that changes a sync-pattern choice or crosses the decoder threshold.
     */
    private static void validateAnchors(Implementation implementation) throws CalibrationException
    {
        for(DMRSyncDetectMode mode: MODES)
        {
            for(DMRSyncPattern pattern: PATTERNS)
            {
                if(!supports(mode, pattern))
                {
                    continue;
                }

                float[] exact = pattern.toSymbols();
                DMRDetection expected = detect(createDetector(Implementation.SCALAR), mode, exact);

                if(expected.pattern() != pattern || !expected.detected())
                {
                    throw new CalibrationException("Invalid DMR exact-sync fixture for " + mode + " " + pattern +
                        ": score=" + expected.score() + ", detected pattern=" + expected.pattern());
                }

                requireEquivalent(implementation + " " + mode + " exact " + pattern, expected,
                    detect(createDetector(implementation), mode, exact));
                for(float threshold: PRODUCTION_THRESHOLDS)
                {
                    validateNearAnchor(implementation, mode, pattern, exact, threshold, false);
                    validateNearAnchor(implementation, mode, pattern, exact, threshold, true);
                }
            }
        }
    }

    private static void validateNearAnchor(Implementation implementation, DMRSyncDetectMode mode,
                                           DMRSyncPattern pattern, float[] exact, float threshold,
                                           boolean shouldDetect)
        throws CalibrationException
    {
        float target = SoftSyncCalibrationHelper.boundaryTarget(threshold, shouldDetect);
        float[] near = SoftSyncCalibrationHelper.createNearSync(exact, target);
        DMRDetection expected = detect(createDetector(Implementation.SCALAR), mode, near, threshold);

        if(expected.pattern() != pattern || expected.detected() != shouldDetect)
        {
            throw new CalibrationException("Invalid DMR near-sync fixture for " + mode + " " + pattern +
                " at threshold " + threshold + ": score=" + expected.score() + ", detected pattern=" +
                expected.pattern());
        }

        requireEquivalent(implementation + " " + mode + " near " + pattern + " threshold " + threshold + " " +
            shouldDetect, expected, detect(createDetector(implementation), mode, near, threshold));
    }

    private static boolean supports(DMRSyncDetectMode mode, DMRSyncPattern pattern)
    {
        return switch(mode)
        {
            case AUTOMATIC -> true;
            case BASE_ONLY -> pattern == DMRSyncPattern.BASE_STATION_DATA ||
                pattern == DMRSyncPattern.BASE_STATION_VOICE;
            case MOBILE_ONLY -> pattern == DMRSyncPattern.MOBILE_STATION_DATA ||
                pattern == DMRSyncPattern.MOBILE_STATION_VOICE;
            case DIRECT_ONLY -> pattern == DMRSyncPattern.DIRECT_DATA_TIMESLOT_1 ||
                pattern == DMRSyncPattern.DIRECT_DATA_TIMESLOT_2 ||
                pattern == DMRSyncPattern.DIRECT_VOICE_TIMESLOT_1 ||
                pattern == DMRSyncPattern.DIRECT_VOICE_TIMESLOT_2;
        };
    }

    private static DMRDetection detect(DMRSoftSyncDetector detector, DMRSyncDetectMode mode, float[] symbols)
    {
        return detect(detector, mode, symbols, DETECTION_THRESHOLD);
    }

    private static DMRDetection detect(DMRSoftSyncDetector detector, DMRSyncDetectMode mode, float[] symbols,
                                       float threshold)
    {
        detector.setMode(mode);
        float score = 0.0f;

        for(float symbol: symbols)
        {
            score = detector.processAndCalculate(symbol);
        }

        return new DMRDetection(score, detector.getDetectedPattern(), score > threshold);
    }

    private static DMRResult[] evaluateAllModes(Implementation implementation, float[] fixture)
    {
        DMRResult[] results = new DMRResult[MODES.length];

        for(int mode = 0; mode < MODES.length; mode++)
        {
            DMRSoftSyncDetector detector = createDetector(implementation);
            detector.setMode(MODES[mode]);
            float[] scores = new float[fixture.length];
            DMRSyncPattern[] patterns = new DMRSyncPattern[fixture.length];
            boolean[] detected = new boolean[fixture.length];

            for(int x = 0; x < fixture.length; x++)
            {
                scores[x] = detector.processAndCalculate(fixture[x]);
                patterns[x] = detector.getDetectedPattern();
                detected[x] = scores[x] > DETECTION_THRESHOLD;
            }

            results[mode] = new DMRResult(scores, patterns, detected);
        }

        return results;
    }

    private static void requireEquivalent(String candidate, DMRResult expected, DMRResult actual)
        throws CalibrationException
    {
        CalibrationBenchmark.requireEquivalent(candidate + " scores", expected.scores(), actual.scores(),
            ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);

        for(int x = 0; x < expected.patterns().length; x++)
        {
            boolean decisionChanged = expected.detected()[x] != actual.detected()[x];
            boolean detectedPatternChanged = expected.detected()[x] && expected.patterns()[x] != actual.patterns()[x];

            if(decisionChanged || detectedPatternChanged)
            {
                throw new CalibrationException(candidate + " changed the DMR sync result at symbol " + x +
                    ": expected " + expected.patterns()[x] + "/" + expected.detected()[x] + ", actual " +
                    actual.patterns()[x] + "/" + actual.detected()[x]);
            }
        }
    }

    private static void requireEquivalent(String candidate, DMRDetection expected, DMRDetection actual)
        throws CalibrationException
    {
        CalibrationBenchmark.requireEquivalent(candidate + " score", new float[]{expected.score()},
            new float[]{actual.score()}, ABSOLUTE_TOLERANCE, RELATIVE_TOLERANCE);

        if(expected.pattern() != actual.pattern() || expected.detected() != actual.detected())
        {
            throw new CalibrationException(candidate + " changed the DMR sync result: expected " +
                expected.pattern() + "/" + expected.detected() + ", actual " + actual.pattern() + "/" +
                actual.detected());
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

    private static DMRSoftSyncDetector createDetector(Implementation implementation)
    {
        return switch(implementation)
        {
            case VECTOR_SIMD_64 -> new DMRSoftSyncDetectorVector64();
            case VECTOR_SIMD_128 -> new DMRSoftSyncDetectorVector128();
            case VECTOR_SIMD_256 -> new DMRSoftSyncDetectorVector256();
            case VECTOR_SIMD_512 -> new DMRSoftSyncDetectorVector512();
            default -> new DMRSoftSyncDetectorScalar();
        };
    }

    private record DMRDetection(float score, DMRSyncPattern pattern, boolean detected)
    {
    }

    private record DMRResult(float[] scores, DMRSyncPattern[] patterns, boolean[] detected)
    {
    }

    /** Benchmarks complete streaming fixture passes with independent detector state for every detection mode. */
    private static class DetectorOperation implements LongSupplier
    {
        private final DMRSoftSyncDetector[] mDetectors = new DMRSoftSyncDetector[MODES.length];
        private final float[] mFixture;

        private DetectorOperation(Implementation implementation, float[] fixture)
        {
            mFixture = fixture;

            for(int mode = 0; mode < MODES.length; mode++)
            {
                mDetectors[mode] = createDetector(implementation);
                mDetectors[mode].setMode(MODES[mode]);
            }
        }

        @Override
        public long getAsLong()
        {
            float scoreSum = 0.0f;
            long resultFingerprint = 0L;

            for(DMRSoftSyncDetector detector: mDetectors)
            {
                int detections = 0;
                int patternFingerprint = 1;

                for(float symbol: mFixture)
                {
                    float score = detector.processAndCalculate(symbol);
                    scoreSum += score;
                    detections += score > DETECTION_THRESHOLD ? 1 : 0;
                    patternFingerprint = 31 * patternFingerprint + detector.getDetectedPattern().ordinal();
                }

                resultFingerprint = CalibrationBenchmark.combine(resultFingerprint,
                    (Integer.toUnsignedLong(detections) << 32) ^ Integer.toUnsignedLong(patternFingerprint));
            }

            return CalibrationBenchmark.combine(resultFingerprint, CalibrationBenchmark.fingerprint(scoreSum));
        }
    }
}
