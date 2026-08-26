/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import java.util.Objects;

/**
 * Current bounded-service state and lifetime counters for the running session.  Output confirmations are not kept in
 * the recent decision ring; the total, recorded, and stream-submitted fields are their explicit session-only UI
 * counters.
 */
public record LogicalCallDiagnosticStatus(boolean accepting, boolean writerTerminated, int queuedRecords,
                                          int queueCapacity, long decisionsObserved,
                                          long outputConfirmationsObserved, long recordedConfirmationsObserved,
                                          long streamSubmittedConfirmationsObserved, long recordsEnqueued,
                                          long recordsDroppedAtQueue, long recordsRejectedAfterClose,
                                          long fileRecordsWritten, long fileRecordsDropped,
                                          long oversizedRecordsDropped, long fileWriteFailures,
                                          LogicalCallDiagnosticFileState fileState, long activeFileBytes,
                                          int retainedFileCount, long maximumFileBytes, int maximumFiles)
{
    public LogicalCallDiagnosticStatus
    {
        Objects.requireNonNull(fileState, "fileState cannot be null");
    }
}
