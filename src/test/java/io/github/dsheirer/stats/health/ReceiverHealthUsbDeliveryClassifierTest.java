/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ReceiverHealthUsbDeliveryClassifierTest
{
    private static final double REQUIRED_BYTES_PER_SECOND = 1_000.0;

    @Test
    void aShortLowRateAndLongGapRemainDiagnostic()
    {
        var classifier = classifierWithBaseline();
        var low = classifier.evaluate(observation(1_000, 1, 1_000, 500, 0, 0, 1, 1_000));
        assertTrue(low.rateAvailable());
        assertEquals(50.0, low.rawDeliveryPercent(), 0.001);
        assertEquals(1, low.gapDelta());
        assertFalse(low.sustainedShortage());
        assertFalse(low.stopped());
    }

    @Test
    void oneSecondFiveHundredPercentCatchupPreservesTheCompleteTenSecondWindow()
    {
        var classifier = classifierWithBaseline();
        classifier.evaluate(observation(1_000, 1, 1_000, 0, 0, 0, 0, 1_000));
        var catchup = classifier.evaluate(observation(2_000, 1, 5_000, 5_000, 0, 0, 0, 2_000));
        assertEquals(500.0, catchup.rawDeliveryPercent(), 0.001, "Do not clip raw catchup before aggregation");
        assertFalse(catchup.sustainedShortage());
        var complete = classifier.evaluate(observation(10_000, 1, 10_000, 10_000, 0, 0, 0, 10_000));
        assertEquals(10_000, complete.sustainedWindowMilliseconds());
        assertEquals(100.0, complete.sustainedDeliveryPercent(), 0.001);
        assertFalse(complete.sustainedShortage());
    }

    @Test
    void shortageRequiresAtLeastTenSecondsOfPairedBytesAndElapsedTime()
    {
        var classifier = classifierWithBaseline();
        var early = classifier.evaluate(observation(9_999, 1, 9_999, 7_999, 0, 0, 0, 9_999));
        assertFalse(early.sustainedShortage());
        var low = classifier.evaluate(observation(10_000, 1, 10_000, 8_000, 0, 0, 0, 10_000));
        assertEquals(80.0, low.sustainedDeliveryPercent(), 0.001);
        assertTrue(low.sustainedShortage());
        var next = classifier.evaluate(observation(11_000, 1, 11_000, 9_000, 0, 0, 0, 11_000));
        assertFalse(next.sustainedShortage(), "A new partial window cannot replay the completed shortage");
        var healthy = classifier.evaluate(observation(20_000, 1, 20_000, 18_000, 0, 0, 0, 20_000));
        assertEquals(100.0, healthy.sustainedDeliveryPercent(), 0.001);
        assertFalse(healthy.sustainedShortage());
    }

    @Test
    void unevenSamplingUsesTheActualWindowAndNinetyPercentIsNotBelowTheLimit()
    {
        var classifier = classifierWithBaseline();
        classifier.evaluate(observation(2_000, 1, 2_000, 1_800, 0, 0, 0, 2_000));
        var complete = classifier.evaluate(observation(12_500, 1, 12_500, 11_250, 0, 0, 0, 12_500));
        assertEquals(12_500, complete.sustainedWindowMilliseconds());
        assertEquals(90.0, complete.sustainedDeliveryPercent(), 0.001);
        assertFalse(complete.sustainedShortage());
    }

    @Test
    void statusAndPayloadEvidenceRemainSeparateFromRatePolicy()
    {
        var classifier = classifierWithBaseline();
        var status = classifier.evaluate(observation(1_000, 1, 1_000, 1_000, 1, 0, 0, 1_000));
        assertEquals(1, status.statusDelta());
        assertEquals(0, status.integrityDelta());
        assertFalse(status.sustainedShortage());
        var payload = classifier.evaluate(observation(2_000, 1, 2_000, 2_000, 1, 1, 0, 2_000));
        assertEquals(0, payload.statusDelta());
        assertEquals(1, payload.integrityDelta());
        assertFalse(payload.sustainedShortage());
    }

    @Test
    void historicCountsEstablishTheFirstBaselineWithoutReplayingLoss()
    {
        var classifier = new ReceiverHealthService.UsbDeliveryClassifier();
        var first = classifier.evaluate(observation(1_000, 1, 20_000, 19_000, 3, 5, 7, 1_000));
        assertFalse(first.rateAvailable());
        assertEquals(0, first.statusDelta());
        assertEquals(0, first.integrityDelta());
        assertEquals(0, first.gapDelta());
        assertFalse(first.sustainedShortage());
        var next = classifier.evaluate(observation(2_000, 1, 21_000, 20_000, 3, 5, 7, 2_000));
        assertEquals(100.0, next.rawDeliveryPercent(), 0.001);
        assertEquals(0, next.integrityDelta());
    }

    @Test
    void absentDataRequiresTenSecondsEvenBeforeTheFirstRateBaseline()
    {
        var classifier = new ReceiverHealthService.UsbDeliveryClassifier();
        var early = classifier.evaluate(observation(9_999, 1, 0, 0, 0, 0, 0, 0));
        assertEquals(9_999, early.lastDeliveryAgeMilliseconds());
        assertFalse(early.stopped());
        var stopped = classifier.evaluate(observation(10_000, 1, 0, 0, 0, 0, 0, 0));
        assertEquals(10_000, stopped.lastDeliveryAgeMilliseconds());
        assertTrue(stopped.stopped());
    }

    @Test
    void streamCounterAndRequiredRateChangesResetTheWholeMeasurementWindow()
    {
        var classifier = classifierWithBaseline();
        classifier.evaluate(observation(9_000, 1, 9_000, 4_500, 1, 1, 1, 9_000));
        var restarted = classifier.evaluate(observation(10_000, 2, 0, 0, 0, 0, 0, 10_000));
        assertFalse(restarted.rateAvailable());
        var shortWindow = classifier.evaluate(observation(11_000, 2, 1_000, 500, 0, 0, 0, 11_000));
        assertFalse(shortWindow.sustainedShortage());
        var reset = classifier.evaluate(observation(12_000, 2, 0, 0, 0, 0, 0, 12_000));
        assertFalse(reset.rateAvailable());
        var newRate = classifier.evaluate(new ReceiverHealthService.UsbDeliveryObservation(
            TimeUnit.MILLISECONDS.toNanos(13_000), true, 2, 2_000, 2_000, 0, 0, 0,
            TimeUnit.MILLISECONDS.toNanos(13_000), 2_000.0));
        assertFalse(newRate.rateAvailable());
        assertFalse(newRate.sustainedShortage());
    }

    @Test
    void aHealthCounterDecreaseDoesNotReplayOldIntegrityOrGapEvents()
    {
        var classifier = new ReceiverHealthService.UsbDeliveryClassifier();
        classifier.evaluate(observation(1_000, 1, 10_000, 10_000, 5, 8, 3, 1_000));
        var reset = classifier.evaluate(observation(2_000, 1, 11_000, 11_000, 0, 0, 0, 2_000));
        assertFalse(reset.rateAvailable());
        assertEquals(0, reset.statusDelta());
        assertEquals(0, reset.integrityDelta());
        assertEquals(0, reset.gapDelta());
        assertFalse(reset.sustainedShortage());
    }

    @Test
    void stoppedStreamClearsAnIncompleteLowRateWindow()
    {
        var classifier = classifierWithBaseline();
        classifier.evaluate(observation(9_000, 1, 9_000, 4_500, 0, 0, 0, 9_000));
        classifier.evaluate(new ReceiverHealthService.UsbDeliveryObservation(TimeUnit.MILLISECONDS.toNanos(9_500),
            false, 1, 9_000, 4_500, 0, 0, 0, TimeUnit.MILLISECONDS.toNanos(9_000), 0));
        var restarted = classifier.evaluate(observation(10_000, 1, 9_000, 4_500, 0, 0, 0, 10_000));
        assertFalse(restarted.rateAvailable());
        assertFalse(restarted.stopped());
        assertFalse(restarted.sustainedShortage());
    }

    @Test
    void rateAndAbsenceWindowsSupportNegativeAndWrappedMonotonicOrigins()
    {
        for(long origin: new long[]{Long.MIN_VALUE, -1_000_000_000L, -1, 0,
            Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(5)})
        {
            var classifier = new ReceiverHealthService.UsbDeliveryClassifier();
            classifier.evaluate(new ReceiverHealthService.UsbDeliveryObservation(origin, true, 1, 0, 0,
                0, 0, 0, origin, REQUIRED_BYTES_PER_SECOND));
            long tenSeconds = origin + TimeUnit.SECONDS.toNanos(10);
            var normal = classifier.evaluate(new ReceiverHealthService.UsbDeliveryObservation(tenSeconds, true, 1,
                10_000, 10_000, 0, 0, 0, tenSeconds, REQUIRED_BYTES_PER_SECOND));
            assertEquals(10_000, normal.windowMilliseconds(), "origin=" + origin);
            assertEquals(100.0, normal.sustainedDeliveryPercent(), 0.001);
            assertFalse(normal.sustainedShortage());
            var stopped = classifier.evaluate(new ReceiverHealthService.UsbDeliveryObservation(
                tenSeconds + TimeUnit.SECONDS.toNanos(10), true, 1, 10_000, 10_000, 0, 0, 0, tenSeconds,
                REQUIRED_BYTES_PER_SECOND));
            assertEquals(10_000, stopped.lastDeliveryAgeMilliseconds(), "origin=" + origin);
            assertTrue(stopped.stopped());
        }
    }

    private static ReceiverHealthService.UsbDeliveryClassifier classifierWithBaseline()
    {
        var classifier = new ReceiverHealthService.UsbDeliveryClassifier();
        classifier.evaluate(observation(0, 1, 0, 0, 0, 0, 0, 0));
        return classifier;
    }

    private static ReceiverHealthService.UsbDeliveryObservation observation(long now, long sequence,
                                                                             long expectedBytes, long usableBytes,
                                                                             long statusCount, long integrityCount,
                                                                             long longGapCount, long lastDelivery)
    {
        return new ReceiverHealthService.UsbDeliveryObservation(TimeUnit.MILLISECONDS.toNanos(now), true, sequence,
            expectedBytes, usableBytes, statusCount, integrityCount, longGapCount,
            TimeUnit.MILLISECONDS.toNanos(lastDelivery), REQUIRED_BYTES_PER_SECOND);
    }
}
