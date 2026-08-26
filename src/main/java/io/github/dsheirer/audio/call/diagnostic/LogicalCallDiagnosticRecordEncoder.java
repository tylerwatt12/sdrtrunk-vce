/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/**
 * Worker-only diagnostic encoder boundary.  Package visibility permits isolation tests to prove that serialization
 * cannot fall back to the offering thread.
 */
interface LogicalCallDiagnosticRecordEncoder
{
    byte[] encodeSessionHeader(String sessionId, long sessionStartedAtEpochMillis, long segmentNumber,
                               LogicalCallDiagnosticConfiguration configuration);

    byte[] encodeDecision(LogicalCallDiagnosticDecision decision);

    byte[] encodeOutput(LogicalCallDiagnosticOutputEvent event);
}
