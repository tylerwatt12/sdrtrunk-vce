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
 * user-interface work. A false return value means that the whole optional observation was rejected; an
 * implementation may still shed a secondary file copy after accepting an in-memory observation and must report
 * that loss through its own status.</p>
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

    /**
     * Offers a downstream output confirmation without waiting.  Implementations that do not collect output
     * confirmations may reject it.
     *
     * @return true when accepted for diagnostic processing, otherwise false
     */
    default boolean offerOutput(LogicalCallDiagnosticOutputEvent outputEvent)
    {
        return false;
    }
}
