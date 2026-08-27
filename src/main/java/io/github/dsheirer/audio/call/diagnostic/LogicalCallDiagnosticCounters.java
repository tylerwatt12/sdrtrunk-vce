/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/** Monotonic process-session counters from the resolver. */
public record LogicalCallDiagnosticCounters(long acceptedIngress, long droppedIngress, long droppedLifecycle,
                                            long droppedOperations, long abortedLegs,
                                            long completedReceiverLegs, long eligibleReceiverLegs,
                                            long emittedLogicalCalls, long mergedLogicalCalls,
                                            long mergedReceiverCopies, long independentLogicalCalls,
                                            long failOpenLogicalCalls, long separatedPairComparisons,
                                            long diagnosticDecisionsOffered, long diagnosticDecisionsRejected)
{
    public LogicalCallDiagnosticCounters
    {
        acceptedIngress = nonnegative(acceptedIngress);
        droppedIngress = nonnegative(droppedIngress);
        droppedLifecycle = nonnegative(droppedLifecycle);
        droppedOperations = nonnegative(droppedOperations);
        abortedLegs = nonnegative(abortedLegs);
        completedReceiverLegs = nonnegative(completedReceiverLegs);
        eligibleReceiverLegs = nonnegative(eligibleReceiverLegs);
        emittedLogicalCalls = nonnegative(emittedLogicalCalls);
        mergedLogicalCalls = nonnegative(mergedLogicalCalls);
        mergedReceiverCopies = nonnegative(mergedReceiverCopies);
        independentLogicalCalls = nonnegative(independentLogicalCalls);
        failOpenLogicalCalls = nonnegative(failOpenLogicalCalls);
        separatedPairComparisons = nonnegative(separatedPairComparisons);
        diagnosticDecisionsOffered = nonnegative(diagnosticDecisionsOffered);
        diagnosticDecisionsRejected = nonnegative(diagnosticDecisionsRejected);
    }

    private static long nonnegative(long value)
    {
        return Math.max(0L, value);
    }
}
