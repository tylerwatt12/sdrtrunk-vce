/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import java.util.Objects;

/**
 * Sanitized confirmation that a resolved logical call was submitted to a local output path.
 *
 * @param logicalCallSequence session-local logical-call sequence from the diagnostic decision
 * @param occurredAtEpochMillis local submission time
 * @param outputType local action that was submitted
 */
public record LogicalCallDiagnosticOutputEvent(long logicalCallSequence, long occurredAtEpochMillis,
                                                LogicalCallDiagnosticOutputType outputType)
{
    public LogicalCallDiagnosticOutputEvent
    {
        if(logicalCallSequence < 1)
        {
            throw new IllegalArgumentException("logicalCallSequence must be positive");
        }

        if(occurredAtEpochMillis < 1)
        {
            throw new IllegalArgumentException("occurredAtEpochMillis must be positive");
        }

        Objects.requireNonNull(outputType, "outputType cannot be null");
    }
}
