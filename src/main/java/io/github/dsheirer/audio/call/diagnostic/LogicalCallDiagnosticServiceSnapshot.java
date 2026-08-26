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
                                                   long recentDecisionsEvicted,
                                                   List<LogicalCallDiagnosticDecision> recentDecisions,
                                                   LogicalCallDiagnosticStatus status)
{
    public LogicalCallDiagnosticServiceSnapshot
    {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        recentDecisions = List.copyOf(Objects.requireNonNull(recentDecisions,
            "recentDecisions cannot be null"));
        Objects.requireNonNull(status, "status cannot be null");
    }
}
