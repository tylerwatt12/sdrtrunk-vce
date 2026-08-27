/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/**
 * Local JSONL writer lifecycle.  Failure details remain counters rather than persisted exception or path text.
 */
public enum LogicalCallDiagnosticFileState
{
    NOT_STARTED,
    ACTIVE,
    DISABLED,
    CLOSED
}
