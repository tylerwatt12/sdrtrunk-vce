/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReceiverHealthUsbDeliveryClassifierTest
{
    private static final double REQUIRED_BYTES_PER_SECOND = 1_000.0;

    @Test
    void firstLowRateWindowIsWarningOnly()
    {
        ReceiverHealthService.UsbDeliveryClassifier classifier = classifierWithBaseline();
        ReceiverHealthService.UsbDeliveryAssessment assessment = classifier.evaluate(
            observation(2_000, 1, 1_000, 890, 0, 0, 0, 2_000));

        assertTrue(assessment.rateAvailable());
        assertEquals(89.0, assessment.rawDeliveryPercent(), 0.001);
        assertTrue(assessment.rateWarning());
        assertFalse(assessment.rateCritical());
        assertFalse(assessment.hardLoss());
    }

    @Test
    void rawCatchUpInFollowingWindowPreventsCriticalEscalation()
    {
        ReceiverHealthService.UsbDeliveryClassifier classifier = classifierWithBaseline();
        classifier.evaluate(observation(2_000, 1, 1_000, 500, 0, 0, 0, 2_000));
        ReceiverHealthService.UsbDeliveryAssessment recovered = classifier.evaluate(
            observation(3_000, 1, 2_500, 2_000, 0, 0, 0, 3_000));

        assertEquals(150.0, recovered.rawDeliveryPercent(), 0.001);
        assertEquals(100.0, recovered.twoWindowDeliveryPercent(), 0.001);
        assertFalse(recovered.rateWarning());
        assertFalse(recovered.rateCritical());
    }

    @Test
    void lowTwoWindowAggregateEscalatesToCritical()
    {
        ReceiverHealthService.UsbDeliveryClassifier classifier = classifierWithBaseline();
        classifier.evaluate(observation(2_000, 1, 1_000, 800, 0, 0, 0, 2_000));
        ReceiverHealthService.UsbDeliveryAssessment sustained = classifier.evaluate(
            observation(3_000, 1, 2_000, 1_650, 0, 0, 0, 3_000));

        assertEquals(82.5, sustained.twoWindowDeliveryPercent(), 0.001);
        assertTrue(sustained.rateCritical());
        assertFalse(sustained.gapCorrelated());
        assertFalse(sustained.hardLoss());
    }

    @Test
    void followingLongGapCorroboratesOneLowWindow()
    {
        ReceiverHealthService.UsbDeliveryClassifier classifier = classifierWithBaseline();
        classifier.evaluate(observation(2_000, 1, 1_000, 800, 0, 0, 0, 2_000));
        ReceiverHealthService.UsbDeliveryAssessment corroborated = classifier.evaluate(
            observation(3_000, 1, 2_000, 1_800, 0, 0, 1, 3_000));

        assertEquals(90.0, corroborated.twoWindowDeliveryPercent(), 0.001);
        assertTrue(corroborated.gapCorrelated());
        assertTrue(corroborated.rateCritical());
        assertFalse(corroborated.hardLoss());
    }

    @Test
    void immediatelyPrecedingLongGapCorroboratesLowWindow()
    {
        ReceiverHealthService.UsbDeliveryClassifier classifier = classifierWithBaseline();
        ReceiverHealthService.UsbDeliveryAssessment gapOnly = classifier.evaluate(
            observation(2_000, 1, 1_000, 1_000, 0, 0, 1, 2_000));
        ReceiverHealthService.UsbDeliveryAssessment corroborated = classifier.evaluate(
            observation(3_000, 1, 2_000, 1_800, 0, 0, 1, 3_000));

        assertEquals(1, gapOnly.gapDelta());
        assertFalse(gapOnly.gapCorrelated());
        assertFalse(gapOnly.rateCritical());
        assertTrue(corroborated.gapCorrelated());
        assertTrue(corroborated.rateCritical());
    }

    @Test
    void sameStreamIntegrityOrStatusEvidenceIsImmediatelyCritical()
    {
        ReceiverHealthService.UsbDeliveryClassifier classifier = classifierWithBaseline();
        ReceiverHealthService.UsbDeliveryAssessment hardLoss = classifier.evaluate(
            observation(2_000, 1, 1_000, 1_000, 1, 1, 0, 2_000));

        assertEquals(1, hardLoss.statusDelta());
        assertEquals(1, hardLoss.integrityDelta());
        assertTrue(hardLoss.hardLoss());
        assertFalse(hardLoss.rateWarning());
        assertFalse(hardLoss.rateCritical());
    }

    @Test
    void staleDeliveryIsCriticalEvenWhileRateIsWarmingUp()
    {
        ReceiverHealthService.UsbDeliveryClassifier classifier = new ReceiverHealthService.UsbDeliveryClassifier();
        ReceiverHealthService.UsbDeliveryAssessment stale = classifier.evaluate(
            observation(2_001, 1, 0, 0, 0, 0, 0, 1_000));

        assertFalse(stale.rateAvailable());
        assertEquals(1_001, stale.lastDeliveryAgeMilliseconds());
        assertTrue(stale.stale());
        assertTrue(stale.hardLoss());
    }

    @Test
    void streamSequenceAndCounterResetsEstablishFreshBaselines()
    {
        ReceiverHealthService.UsbDeliveryClassifier classifier = classifierWithBaseline();
        classifier.evaluate(observation(2_000, 1, 1_000, 800, 1, 1, 1, 2_000));
        ReceiverHealthService.UsbDeliveryAssessment restarted = classifier.evaluate(
            observation(3_000, 2, 0, 0, 0, 0, 0, 3_000));
        ReceiverHealthService.UsbDeliveryAssessment normal = classifier.evaluate(
            observation(4_000, 2, 1_000, 1_000, 0, 0, 0, 4_000));
        ReceiverHealthService.UsbDeliveryAssessment reset = classifier.evaluate(
            observation(5_000, 2, 500, 500, 0, 0, 0, 5_000));

        assertFalse(restarted.rateAvailable());
        assertFalse(restarted.hardLoss());
        assertTrue(normal.rateAvailable());
        assertFalse(normal.hardLoss());
        assertFalse(reset.rateAvailable());
        assertFalse(reset.hardLoss());
    }

    @Test
    void healthCounterDecreaseDoesNotReplayOldIntegrityOrGapEvents()
    {
        ReceiverHealthService.UsbDeliveryClassifier classifier = new ReceiverHealthService.UsbDeliveryClassifier();
        classifier.evaluate(observation(1_000, 1, 10_000, 10_000, 5, 8, 3, 1_000));
        ReceiverHealthService.UsbDeliveryAssessment reset = classifier.evaluate(
            observation(2_000, 1, 11_000, 11_000, 0, 0, 0, 2_000));

        assertFalse(reset.rateAvailable());
        assertEquals(0, reset.statusDelta());
        assertEquals(0, reset.integrityDelta());
        assertEquals(0, reset.gapDelta());
        assertFalse(reset.hardLoss());
        assertFalse(reset.rateCritical());
    }

    @Test
    void ninetyToNinetyEightPercentIsMeasurementOnly()
    {
        ReceiverHealthService.UsbDeliveryClassifier classifier = classifierWithBaseline();
        ReceiverHealthService.UsbDeliveryAssessment assessment = classifier.evaluate(
            observation(2_000, 1, 1_000, 950, 0, 0, 0, 2_000));

        assertTrue(assessment.rateMeasurementWarning());
        assertFalse(assessment.rateWarning());
        assertFalse(assessment.rateCritical());
        assertFalse(assessment.hardLoss());
    }

    @Test
    void stoppedStreamClearsPendingLowWindow()
    {
        ReceiverHealthService.UsbDeliveryClassifier classifier = classifierWithBaseline();
        classifier.evaluate(observation(2_000, 1, 1_000, 500, 0, 0, 0, 2_000));
        classifier.evaluate(new ReceiverHealthService.UsbDeliveryObservation(2_500, false, 1, 1_000, 500,
            0, 0, 0, 2_000, 0));
        ReceiverHealthService.UsbDeliveryAssessment restarted = classifier.evaluate(
            observation(3_000, 1, 1_000, 500, 0, 0, 0, 3_000));

        assertFalse(restarted.rateAvailable());
        assertFalse(restarted.rateCritical());
        assertFalse(restarted.rateWarning());
    }

    private static ReceiverHealthService.UsbDeliveryClassifier classifierWithBaseline()
    {
        ReceiverHealthService.UsbDeliveryClassifier classifier = new ReceiverHealthService.UsbDeliveryClassifier();
        classifier.evaluate(observation(1_000, 1, 0, 0, 0, 0, 0, 1_000));
        return classifier;
    }

    private static ReceiverHealthService.UsbDeliveryObservation observation(long now, long sequence,
                                                                             long expectedBytes, long usableBytes,
                                                                             long statusCount, long integrityCount,
                                                                             long longGapCount, long lastDelivery)
    {
        return new ReceiverHealthService.UsbDeliveryObservation(now, true, sequence, expectedBytes, usableBytes,
            statusCount, integrityCount, longGapCount, lastDelivery, REQUIRED_BYTES_PER_SECOND);
    }
}
