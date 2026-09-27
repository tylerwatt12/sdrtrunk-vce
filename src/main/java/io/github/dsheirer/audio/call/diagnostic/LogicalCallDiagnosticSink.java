/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/**
 * Non-blocking destination for completed logical-call diagnostic decisions.
 *
 * <p>Implementations must not make the calling observer thread wait for file, database, network, serialization, or
 * user-interface work. A false return value means that the optional observation was rejected.</p>
 */
@FunctionalInterface
public interface LogicalCallDiagnosticSink
{
    /**
     * Offers a completed decision without waiting.
     *
     * @return true when accepted for diagnostic processing, otherwise false
     */
    boolean offer(LogicalCallDiagnosticDecision decision);
}
