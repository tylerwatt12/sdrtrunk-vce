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
package io.github.dsheirer.debug;

import io.github.dsheirer.portable.PortableApplicationPaths;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Narrow, non-blocking UI/service boundary for the receiver incident flight recorder.  The implementation only
 * consumes the existing one-Hz debug telemetry snapshot; it does not attach another receiver listener.
 */
public final class ReceiverIncidentController implements AutoCloseable
{
    private static final String INCIDENT_DIRECTORY = "diagnostics/receiver-incidents";
    private final ReceiverIncidentRecorder mRecorder;

    /** Creates the recorder in the portable application data directory. */
    public static ReceiverIncidentController createDefault()
    {
        Path directory = PortableApplicationPaths.getDataRoot().resolve(INCIDENT_DIRECTORY);
        return new ReceiverIncidentController(directory, System::currentTimeMillis, System::nanoTime,
            ReceiverThreadDumpCapture::capture);
    }

    /** Test seam for clocks, persistence location, and the intrusive thread-dump operation. */
    ReceiverIncidentController(Path directory, LongSupplier wallClock, LongSupplier nanoClock,
                               Supplier<byte[]> threadDumpSupplier)
    {
        mRecorder = new ReceiverIncidentRecorder(Objects.requireNonNull(directory), wallClock, nanoClock,
            threadDumpSupplier);
    }

    /**
     * Returns an immutable, cheaply copied status.  No receiver polling, serialization, or file access occurs here.
     */
    public ReceiverIncidentStatus getStatus()
    {
        return mRecorder.getStatus();
    }

    /**
     * Starts or extends an incident.  Admission is immediate; thread capture and persistence happen on a bounded,
     * low-priority diagnostics worker.
     */
    public IncidentCaptureResult captureIncident(String reason, boolean includeThreadDump)
    {
        return mRecorder.captureIncident(reason, includeThreadDump);
    }

    /** Returns the cached human-readable latest incident, or an empty string before the first incident is saved. */
    public String getLatestIncidentText()
    {
        return mRecorder.getLatestIncidentText();
    }

    /** Returns cached JSON for the latest saved incident. */
    public byte[] getLatestIncidentJson()
    {
        return mRecorder.getLatestIncidentJson();
    }

    /** Returns a cached bounded index of saved incidents. */
    public byte[] getIncidentIndexJson()
    {
        return mRecorder.getIncidentIndexJson();
    }

    /** Package boundary used only by the existing one-Hz telemetry sampler. */
    void acceptTelemetry(byte[] telemetryJson)
    {
        mRecorder.acceptTelemetry(telemetryJson);
    }

    @Override
    public void close()
    {
        mRecorder.close();
    }

    public enum IncidentState
    {
        ARMED,
        RECORDING,
        CLOSED
    }

    public enum ThreadDumpState
    {
        NONE,
        SCHEDULED,
        CAPTURING,
        CAPTURED,
        FAILED
    }

    /** Distinguishes evidence captured in memory from a report that is durably available to copy. */
    public enum IncidentReportState
    {
        NONE,
        CAPTURED_PENDING_SAVE,
        SAVED,
        SAVE_FAILED
    }

    public enum IncidentCaptureResult
    {
        STARTED,
        COALESCED,
        REJECTED_CLOSED,
        REJECTED_BUSY
    }

    /**
     * User-facing recorder state.  Epoch values are zero when no matching event has occurred.
     */
    public record ReceiverIncidentStatus(IncidentState state, String summary, int retainedSamples,
                                         int retainedSampleLimit, String activeReason, long activeStartedAtMs,
                                         long activeExpectedCompletionAtMs, int activeThreadDumpCount,
                                         ThreadDumpState threadDumpState, long lastThreadDumpAtMs,
                                         String lastThreadDumpReason, long lastThreadDumpDurationMs,
                                         int savedIncidentCount, long latestIncidentAtMs, String latestIncidentReason,
                                         String latestIncidentFileName, String lastError,
                                         IncidentReportState latestIncidentReportState)
    {
    }
}
