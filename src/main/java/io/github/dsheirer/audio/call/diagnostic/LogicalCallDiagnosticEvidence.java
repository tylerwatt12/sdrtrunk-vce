/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Exact, fixed-cardinality summary of the duplicate comparisons that contributed to one final call decision.
 * Counts replace the former per-pair transcript so diagnostic memory and file size do not grow with the number of
 * unrelated candidate calls checked by the resolver.
 */
public record LogicalCallDiagnosticEvidence(long confirmedDuplicatePairCount, long separatedPairCount,
                                            long uncertainPairCount,
                                            Map<LogicalCallMergeProof,Long> mergeProofCounts,
                                            Map<LogicalCallSeparationReason,Long> rejectionReasonCounts)
{
    public static final LogicalCallDiagnosticEvidence EMPTY = new LogicalCallDiagnosticEvidence(0L, 0L, 0L,
        Map.of(), Map.of());

    public LogicalCallDiagnosticEvidence
    {
        confirmedDuplicatePairCount = Math.max(0L, confirmedDuplicatePairCount);
        separatedPairCount = Math.max(0L, separatedPairCount);
        uncertainPairCount = Math.max(0L, uncertainPairCount);
        mergeProofCounts = immutablePositiveProofCounts(mergeProofCounts);
        rejectionReasonCounts = immutablePositiveReasonCounts(rejectionReasonCounts);
    }

    /** Total duplicate-candidate comparisons, saturated if session counters ever reach the long limit. */
    public long candidateComparisonCount()
    {
        return saturatedAdd(saturatedAdd(confirmedDuplicatePairCount, separatedPairCount), uncertainPairCount);
    }

    public long mergeProofCount(LogicalCallMergeProof proof)
    {
        return proof != null ? mergeProofCounts.getOrDefault(proof, 0L) : 0L;
    }

    public long rejectionReasonCount(LogicalCallSeparationReason reason)
    {
        return reason != null ? rejectionReasonCounts.getOrDefault(reason, 0L) : 0L;
    }

    private static Map<LogicalCallMergeProof,Long> immutablePositiveProofCounts(
        Map<LogicalCallMergeProof,Long> counts)
    {
        EnumMap<LogicalCallMergeProof,Long> cleaned = new EnumMap<>(LogicalCallMergeProof.class);

        if(counts != null)
        {
            counts.forEach((proof, count) ->
            {
                if(proof != null && count != null && count > 0L)
                {
                    cleaned.put(proof, count);
                }
            });
        }

        return cleaned.isEmpty() ? Map.of() : Collections.unmodifiableMap(cleaned);
    }

    private static Map<LogicalCallSeparationReason,Long> immutablePositiveReasonCounts(
        Map<LogicalCallSeparationReason,Long> counts)
    {
        EnumMap<LogicalCallSeparationReason,Long> cleaned = new EnumMap<>(LogicalCallSeparationReason.class);

        if(counts != null)
        {
            counts.forEach((reason, count) ->
            {
                if(reason != null && count != null && count > 0L)
                {
                    cleaned.put(reason, count);
                }
            });
        }

        return cleaned.isEmpty() ? Map.of() : Collections.unmodifiableMap(cleaned);
    }

    private static long saturatedAdd(long first, long second)
    {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }
}
