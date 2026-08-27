/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import java.util.Optional;

/**
 * Timing relationship between one receiver copy and the selected copy of the same confirmed logical call.
 * Values are derived from immutable RF/carrier timestamps, so they cannot drift from the call data and do not need
 * another coordinator-owned field.
 */
public record LogicalCallDiagnosticOverlap(long overlapMilliseconds, double shorterCopyOverlapPercent,
                                           double selectedCopyCoveragePercent,
                                           long startOffsetFromSelectedMilliseconds,
                                           long endOffsetFromSelectedMilliseconds)
{
    public LogicalCallDiagnosticOverlap
    {
        overlapMilliseconds = Math.max(0L, overlapMilliseconds);
        shorterCopyOverlapPercent = boundedPercent(shorterCopyOverlapPercent);
        selectedCopyCoveragePercent = boundedPercent(selectedCopyCoveragePercent);
    }

    /**
     * Calculates winner-relative timing only for a confirmed merged call.  Independent and uncertain calls have no
     * proven comparison target and therefore return an empty result instead of a misleading self-comparison.
     */
    public static Optional<LogicalCallDiagnosticOverlap> forCopy(LogicalCallDiagnosticDecision decision,
                                                                  LogicalCallDiagnosticLeg copy)
    {
        if(decision == null || copy == null || decision.outcome() != LogicalCallDecisionOutcome.MERGED)
        {
            return Optional.empty();
        }

        LogicalCallDiagnosticLeg selected = decision.legs().stream().filter(LogicalCallDiagnosticLeg::winner)
            .findFirst().orElse(null);
        return between(selected, copy);
    }

    /** Calculates the signed offsets and shared interval for two valid, non-empty receiver intervals. */
    public static Optional<LogicalCallDiagnosticOverlap> between(LogicalCallDiagnosticLeg selected,
                                                                  LogicalCallDiagnosticLeg copy)
    {
        if(selected == null || copy == null)
        {
            return Optional.empty();
        }

        long selectedDuration = intervalDuration(selected);
        long copyDuration = intervalDuration(copy);

        if(selectedDuration <= 0L || copyDuration <= 0L)
        {
            return Optional.empty();
        }

        long sharedStart = Math.max(selected.startTimestamp(), copy.startTimestamp());
        long sharedEnd = Math.min(selected.endTimestamp(), copy.endTimestamp());
        long overlap = Math.max(0L, sharedEnd - sharedStart);
        long shorterDuration = Math.min(selectedDuration, copyDuration);

        return Optional.of(new LogicalCallDiagnosticOverlap(overlap,
            percent(overlap, shorterDuration), percent(overlap, selectedDuration),
            saturatedDifference(copy.startTimestamp(), selected.startTimestamp()),
            saturatedDifference(copy.endTimestamp(), selected.endTimestamp())));
    }

    private static long intervalDuration(LogicalCallDiagnosticLeg leg)
    {
        return leg.endTimestamp() > leg.startTimestamp() ? leg.endTimestamp() - leg.startTimestamp() : 0L;
    }

    private static double percent(long numerator, long denominator)
    {
        if(numerator <= 0L || denominator <= 0L)
        {
            return 0.0d;
        }

        return Math.min(100.0d, numerator * 100.0d / denominator);
    }

    private static double boundedPercent(double value)
    {
        return Double.isFinite(value) ? Math.max(0.0d, Math.min(100.0d, value)) : 0.0d;
    }

    private static long saturatedDifference(long value, long reference)
    {
        try
        {
            return Math.subtractExact(value, reference);
        }
        catch(ArithmeticException exception)
        {
            return value >= reference ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }
}
