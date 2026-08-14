/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.debug.ReceiverIncidentController.IncidentReportState;
import io.github.dsheirer.debug.ReceiverIncidentController.IncidentState;
import io.github.dsheirer.debug.ReceiverIncidentController.ReceiverIncidentStatus;
import io.github.dsheirer.debug.ReceiverIncidentController.ThreadDumpState;
import org.junit.jupiter.api.Test;

/** Verifies the persistent, non-modal main-window receiver diagnostics indication. */
class StatusBoxIncidentStatusTest
{
    @Test
    void compactBadgeDistinguishesArmedRecordingCapturedAndFailed()
    {
        assertEquals("DIAG ARMED", StatusBox.formatIncidentLabel(status(IncidentState.ARMED,
            ThreadDumpState.NONE, 0L, null, IncidentReportState.NONE)));
        assertEquals("DIAG RECORDING", StatusBox.formatIncidentLabel(status(IncidentState.RECORDING,
            ThreadDumpState.SCHEDULED, 0L, null, IncidentReportState.NONE)));
        assertTrue(StatusBox.formatIncidentLabel(status(IncidentState.ARMED, ThreadDumpState.CAPTURED,
            172_800_000L, null, IncidentReportState.CAPTURED_PENDING_SAVE)).startsWith("DIAG DUMP "));
        assertEquals("DIAG SAVING", StatusBox.formatIncidentLabel(status(IncidentState.ARMED,
            ThreadDumpState.NONE, 0L, null, IncidentReportState.CAPTURED_PENDING_SAVE)));
        assertTrue(StatusBox.formatIncidentLabel(status(IncidentState.ARMED, ThreadDumpState.CAPTURED,
            172_800_000L, null, IncidentReportState.SAVED)).startsWith("DIAG SAVED "));
        assertTrue(StatusBox.formatIncidentLabel(status(IncidentState.ARMED, ThreadDumpState.FAILED,
            172_800_000L, "capture failed", IncidentReportState.SAVED)).startsWith("DIAG FAILED "));
        assertEquals("DIAG FAILED", StatusBox.formatIncidentLabel(status(IncidentState.ARMED,
            ThreadDumpState.CAPTURED, 172_800_000L, "save failed", IncidentReportState.SAVE_FAILED)));
        assertEquals("DIAG RECORDING", StatusBox.formatIncidentLabel(new ReceiverIncidentStatus(
            IncidentState.RECORDING, "summary", 123, 900, "later metrics-only capture", 1_000L, 121_000L,
            0, ThreadDumpState.CAPTURED, 500L, "older incident", 37L, 1, 600L, "older incident",
            "receiver-incident.json", null, IncidentReportState.SAVED)));
        assertTrue(StatusBox.formatIncidentLabel(status(IncidentState.RECORDING, ThreadDumpState.CAPTURED,
            172_800_000L, null, IncidentReportState.NONE)).startsWith("DIAG DUMP "));
    }

    @Test
    void tooltipPreservesExactTimestampReasonFileAndFailure()
    {
        String captured = StatusBox.formatIncidentTooltip(status(IncidentState.ARMED, ThreadDumpState.CAPTURED,
            172_800_000L, null, IncidentReportState.SAVED));
        assertTrue(captured.contains("Thread dump captured at 1970-01-"));
        assertTrue(captured.contains("automatic: raw IQ queue remained above 75%"));
        assertTrue(captured.contains("receiver-incident.json"));
        assertTrue(captured.contains("Click to open Receiver Queues"));

        String failed = StatusBox.formatIncidentTooltip(status(IncidentState.ARMED, ThreadDumpState.FAILED,
            172_800_000L, "capture failed", IncidentReportState.SAVED));
        assertTrue(failed.contains("Thread dump failed"));
        assertTrue(failed.contains("Error: capture failed"));

        String pending = StatusBox.formatIncidentTooltip(status(IncidentState.ARMED, ThreadDumpState.CAPTURED,
            172_800_000L, null, IncidentReportState.CAPTURED_PENDING_SAVE));
        assertTrue(pending.contains("captured in memory and waiting for durable save"));
        String saveFailed = StatusBox.formatIncidentTooltip(status(IncidentState.ARMED, ThreadDumpState.CAPTURED,
            172_800_000L, "Unable to save receiver incident", IncidentReportState.SAVE_FAILED));
        assertTrue(saveFailed.contains("durable JSON report could not be saved"));
    }

    private static ReceiverIncidentStatus status(IncidentState incidentState, ThreadDumpState threadDumpState,
                                                  long dumpAt, String error, IncidentReportState reportState)
    {
        boolean saved = reportState == IncidentReportState.SAVED;
        boolean incidentCaptured = reportState != IncidentReportState.NONE;
        return new ReceiverIncidentStatus(incidentState, "summary", 123, 900,
            incidentState == IncidentState.RECORDING ? "manual capture" : null,
            incidentState == IncidentState.RECORDING ? 500L : 0L, 120_500L, 1, threadDumpState, dumpAt,
            "automatic: raw IQ queue remained above 75%", 37L, saved ? 1 : 0,
            incidentCaptured ? 172_700_000L : 0L,
            incidentCaptured ? "automatic receiver queue incident" : null,
            saved ? "receiver-incident.json" : null, error, reportState);
    }
}
