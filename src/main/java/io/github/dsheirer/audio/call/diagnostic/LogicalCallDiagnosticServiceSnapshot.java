/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import java.util.List;
import java.util.Objects;

/**
 * Immutable session-only view for the diagnostic user interface.
 */
public record LogicalCallDiagnosticServiceSnapshot(String sessionId, long sessionStartedAtEpochMillis,
                                                   long duplicatesEvicted,
                                                   List<LogicalCallDiagnosticDecision> recentDuplicates,
                                                   LogicalCallDiagnosticStatus status)
{
    public LogicalCallDiagnosticServiceSnapshot
    {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        recentDuplicates = List.copyOf(Objects.requireNonNull(recentDuplicates,
            "recentDuplicates cannot be null"));
        Objects.requireNonNull(status, "status cannot be null");
    }
}
