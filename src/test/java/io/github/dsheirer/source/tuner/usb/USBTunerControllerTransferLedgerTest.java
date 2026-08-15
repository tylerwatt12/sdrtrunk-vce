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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.usb4java.LibUsb;

class USBTunerControllerTransferLedgerTest
{
    @Test
    void successfulSubmissionRetriesAndTracksTheHeldTransfer()
    {
        USBTunerController.TransferLedger<Object> ledger = new USBTunerController.TransferLedger<>();
        Object held = new Object();
        Object submitted = new Object();
        List<Object> attempts = new ArrayList<>();
        ledger.recordSubmission(held, LibUsb.ERROR_IO);

        USBTunerController.TransferLedger.SubmissionResult<Object> result = ledger.submit(submitted, transfer -> {
            attempts.add(transfer);
            return LibUsb.SUCCESS;
        });

        assertEquals(2, attempts.size());
        assertSame(submitted, attempts.get(0));
        assertSame(held, attempts.get(1));
        assertSame(held, result.retryTransfer());
        assertEquals(2, ledger.getActiveTransfers().size());
        assertEquals(0, ledger.getRetryTransferCount());
    }

    @Test
    void failedRetryKeepsTheHeldTransferAndNotTheSuccessfulTransfer()
    {
        USBTunerController.TransferLedger<Object> ledger = new USBTunerController.TransferLedger<>();
        Object held = new Object();
        Object submitted = new Object();
        ledger.recordSubmission(held, LibUsb.ERROR_IO);

        ledger.submit(submitted, transfer -> transfer == submitted ? LibUsb.SUCCESS : LibUsb.ERROR_IO);

        assertEquals(1, ledger.getActiveTransfers().size());
        assertEquals(1, ledger.getRetryTransferCount());
        assertSame(held, ledger.pollRetryTransfer());
        assertEquals(2, ledger.getTransferErrorCount());
    }

    @Test
    void nativeResourcesCannotBeReleasedUntilBusyTransferCallbackReturns()
    {
        USBTunerController.TransferLedger<Object> ledger = new USBTunerController.TransferLedger<>();
        Object transfer = new Object();

        ledger.submit(transfer, ignored -> LibUsb.ERROR_BUSY);

        assertTrue(ledger.hasActiveTransfers());

        ledger.transferReturned(transfer);

        assertFalse(ledger.hasActiveTransfers());
    }

    @Test
    void transferIdentityCannotBeDuplicated()
    {
        USBTunerController.TransferLedger<Object> ledger = new USBTunerController.TransferLedger<>();
        Object transfer = new Object();

        ledger.recordSubmission(transfer, LibUsb.SUCCESS);
        ledger.recordSubmission(transfer, LibUsb.ERROR_BUSY);

        assertEquals(1, ledger.getActiveTransfers().size());

        ledger.recordSubmission(transfer, LibUsb.ERROR_IO);
        ledger.recordSubmission(transfer, LibUsb.ERROR_IO);

        assertEquals(0, ledger.getActiveTransfers().size());
        assertEquals(1, ledger.getRetryTransferCount());
        assertEquals(2, ledger.getTransferErrorCount());
    }

    @Test
    void distinctButEqualTransfersRemainDistinct()
    {
        USBTunerController.TransferLedger<String> ledger = new USBTunerController.TransferLedger<>();
        String first = new String("transfer");
        String second = new String("transfer");

        ledger.recordSubmission(first, LibUsb.SUCCESS);
        ledger.recordSubmission(second, LibUsb.SUCCESS);

        assertEquals(2, ledger.getActiveTransfers().size());
    }

    @Test
    void exhaustionAccountingIsTruthfulAndReportedOnce()
    {
        USBTunerController.TransferLedger<Object> ledger = new USBTunerController.TransferLedger<>();
        List<Object> transfers = new ArrayList<>();

        for(int x = 0; x < 8; x++)
        {
            Object transfer = new Object();
            transfers.add(transfer);
            assertEquals(x == 7, ledger.recordSubmission(transfer, LibUsb.ERROR_IO));
            assertEquals(x + 1, ledger.getTransferErrorCount());
            assertEquals(x + 1, ledger.getRetryTransferCount());
        }

        assertFalse(ledger.recordSubmission(transfers.get(0), LibUsb.ERROR_IO));
        assertEquals(9, ledger.getTransferErrorCount());
        assertEquals(8, ledger.getRetryTransferCount());
    }
}
