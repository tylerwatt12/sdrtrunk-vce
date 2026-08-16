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
package io.github.dsheirer.source.tuner.usb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.usb4java.LibUsb;

class USBTunerControllerUsbTransferHealthTest
{
    @Test
    void recordsEachSupportedStatusAndUnexpectedStatuses()
    {
        USBTunerController.UsbTransferHealth health = new USBTunerController.UsbTransferHealth();
        health.beginStreaming(1_024, 2);

        health.recordTransfer(LibUsb.TRANSFER_COMPLETED, 1_024, 1_000);
        health.recordTransfer(LibUsb.TRANSFER_STALL, 1_024, 1_010);
        health.recordTransfer(LibUsb.TRANSFER_TIMED_OUT, 1_024, 1_020);
        health.recordTransfer(LibUsb.TRANSFER_ERROR, 1_024, 1_030);
        health.recordTransfer(LibUsb.TRANSFER_CANCELLED, 1_024, 1_040);
        health.recordTransfer(LibUsb.TRANSFER_NO_DEVICE, 1_024, 1_050);

        USBTunerController.UsbTransferHealthSnapshot snapshot = health.snapshot();
        assertEquals(6, snapshot.transferCount());
        assertEquals(1, snapshot.completedTransferCount());
        assertEquals(1, snapshot.stalledTransferCount());
        assertEquals(1, snapshot.timedOutTransferCount());
        assertEquals(1, snapshot.errorTransferCount());
        assertEquals(1, snapshot.cancelledTransferCount());
        assertEquals(1, snapshot.unexpectedStatusTransferCount());
        assertEquals(6_144, snapshot.expectedBytes());
        assertEquals(6_144, snapshot.actualBytes());
        assertEquals(5_120, snapshot.usableBytes());
        assertEquals(1_024, snapshot.unusableBytes());
    }

    @Test
    void exposesNegotiatedDeviceSpeedCapturedOutsideTheTransferPath()
    {
        USBTunerController.UsbTransferHealth health = new USBTunerController.UsbTransferHealth();
        health.setNegotiatedDeviceSpeed(LibUsb.SPEED_HIGH);
        health.recordSubmissionState(8, 6, 2, 3);

        USBTunerController.UsbTransferHealthSnapshot snapshot = health.snapshot();
        assertEquals(LibUsb.SPEED_HIGH, snapshot.negotiatedDeviceSpeedCode());
        assertEquals("high (480 Mb/s)", snapshot.negotiatedDeviceSpeed());
        assertEquals(8, snapshot.transferPoolSize());
        assertEquals(6, snapshot.activeTransferCount());
        assertEquals(2, snapshot.retryTransferCount());
        assertEquals(3, snapshot.submissionFailureCount());
        assertEquals("unknown", USBTunerController.usbDeviceSpeedLabel(Integer.MAX_VALUE));
    }

    @Test
    void accountsForShortZeroAndMalformedTransfersWithoutChangingLegacyPositiveLengthDispatch()
    {
        USBTunerController.UsbTransferHealth health = new USBTunerController.UsbTransferHealth();
        health.beginStreaming(1_024, 2);

        health.recordTransfer(LibUsb.TRANSFER_COMPLETED, 1_001, 1_000);
        health.recordTransfer(LibUsb.TRANSFER_COMPLETED, 0, 1_010);

        USBTunerController.UsbTransferHealthSnapshot snapshot = health.snapshot();
        assertEquals(2, snapshot.completedTransferCount());
        assertEquals(2, snapshot.shortTransferCount());
        assertEquals(1, snapshot.zeroLengthTransferCount());
        assertEquals(2_048, snapshot.expectedBytes());
        assertEquals(1_001, snapshot.actualBytes());
        assertEquals(1_047, snapshot.estimatedMissingBytes());
        assertEquals(0, snapshot.usableBytes());
        assertEquals(1_001, snapshot.unusableBytes());
        assertEquals(1, snapshot.malformedTransferCount());
        assertEquals(1, snapshot.malformedRemainderBytes());
        assertFalse(USBTunerController.isCompleteTransfer(1_024, 1_001));
        assertFalse(USBTunerController.isCompleteTransfer(1_024, 0));
        assertTrue(USBTunerController.isCompleteTransfer(1_024, 1_024));
        assertTrue(USBTunerController.shouldDispatchTransfer(true, 1_001),
            "observability must not change the existing positive-length decode path");
        assertFalse(USBTunerController.shouldDispatchTransfer(true, 0));
        assertFalse(USBTunerController.shouldDispatchTransfer(false, 1_024),
            "shutdown must suppress even a complete late callback");
        assertTrue(USBTunerController.shouldDispatchTransfer(true, 1_024));
    }

    @Test
    void streamingLifecycleExcludesIdleTimeFromTransferGaps()
    {
        USBTunerController.UsbTransferHealth health = new USBTunerController.UsbTransferHealth();
        health.beginStreaming(1_024, 2);
        health.recordTransfer(LibUsb.TRANSFER_COMPLETED, 1_024, 1_000);
        health.recordTransfer(LibUsb.TRANSFER_COMPLETED, 1_024, 1_012);
        health.recordTransfer(LibUsb.TRANSFER_COMPLETED, 1_024, 1_032);

        USBTunerController.UsbTransferHealthSnapshot firstSession = health.snapshot();
        assertTrue(firstSession.streaming());
        assertEquals(1, firstSession.streamSequence());
        assertEquals(1_000, firstSession.firstTransferTimestampMilliseconds());
        assertEquals(1_032, firstSession.lastTransferTimestampMilliseconds());
        assertEquals(20, firstSession.lastInterTransferGapMilliseconds());
        assertEquals(20, firstSession.worstInterTransferGapMilliseconds());

        health.endStreaming();
        assertFalse(health.snapshot().streaming());

        health.beginStreaming(1_024, 2);
        health.recordTransfer(LibUsb.TRANSFER_COMPLETED, 1_024, 100_000);

        USBTunerController.UsbTransferHealthSnapshot secondSession = health.snapshot();
        assertTrue(secondSession.streaming());
        assertEquals(2, secondSession.streamSequence());
        assertEquals(100_000, secondSession.firstTransferTimestampMilliseconds());
        assertEquals(100_000, secondSession.lastTransferTimestampMilliseconds());
        assertEquals(0, secondSession.lastInterTransferGapMilliseconds());
        assertEquals(20, secondSession.worstInterTransferGapMilliseconds());
        assertEquals(4, secondSession.transferCount(), "cumulative transfer counters survive stream restarts");
        assertTrue(secondSession.streamStartedTimestampMilliseconds() > 0);
    }

    @Test
    void retainsLongGapEvidenceAfterNormalCallbacksResume()
    {
        USBTunerController.UsbTransferHealth health = new USBTunerController.UsbTransferHealth();
        health.beginStreaming(1_024, 2);
        health.recordTransfer(LibUsb.TRANSFER_COMPLETED, 1_024, 1_000);
        health.recordTransfer(LibUsb.TRANSFER_COMPLETED, 1_024, 1_250);
        health.recordTransfer(LibUsb.TRANSFER_COMPLETED, 1_024, 1_260);

        USBTunerController.UsbTransferHealthSnapshot snapshot = health.snapshot();
        assertEquals(10, snapshot.lastInterTransferGapMilliseconds());
        assertEquals(250, snapshot.worstInterTransferGapMilliseconds());
        assertEquals(1, snapshot.longTransferGapCount());
    }

    @Test
    void invalidLengthsAreBoundedForLossAndAlignmentAccounting()
    {
        USBTunerController.UsbTransferHealth health = new USBTunerController.UsbTransferHealth();
        health.beginStreaming(1_024, 4);

        health.recordTransfer(LibUsb.TRANSFER_COMPLETED, -1, 1_000);
        health.recordTransfer(LibUsb.TRANSFER_COMPLETED, 1_025, 1_010);

        USBTunerController.UsbTransferHealthSnapshot snapshot = health.snapshot();
        assertEquals(2_048, snapshot.expectedBytes());
        assertEquals(1_025, snapshot.actualBytes());
        assertEquals(1_024, snapshot.estimatedMissingBytes());
        assertEquals(1, snapshot.zeroLengthTransferCount());
        assertEquals(1, snapshot.shortTransferCount());
        assertEquals(1_025, snapshot.unusableBytes());
        assertEquals(1, snapshot.malformedTransferCount());
        assertEquals(1, snapshot.malformedRemainderBytes());
    }
}
