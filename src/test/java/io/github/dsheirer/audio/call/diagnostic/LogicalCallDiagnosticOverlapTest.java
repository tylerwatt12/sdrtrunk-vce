/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.LogicalCallId;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogicalCallDiagnosticOverlapTest
{
    @Test
    void measuresLateContainedCopyAgainstSelectedCarrierInterval()
    {
        LogicalCallDiagnosticLeg selected = leg("selected", 1_000L, 6_220L, true);
        LogicalCallDiagnosticLeg lateCopy = leg("late", 5_278L, 6_179L, false);
        LogicalCallDiagnosticDecision decision = merged(selected, lateCopy);
        LogicalCallDiagnosticOverlap overlap = LogicalCallDiagnosticOverlap.forCopy(decision, lateCopy)
            .orElseThrow();

        assertEquals(901L, overlap.overlapMilliseconds());
        assertEquals(100.0d, overlap.shorterCopyOverlapPercent(), 0.0001d);
        assertEquals(901.0d * 100.0d / 5_220.0d, overlap.selectedCopyCoveragePercent(), 0.0001d);
        assertEquals(4_278L, overlap.startOffsetFromSelectedMilliseconds());
        assertEquals(-41L, overlap.endOffsetFromSelectedMilliseconds());
    }

    @Test
    void reportsSelectedCopyAsItsOwnReference()
    {
        LogicalCallDiagnosticLeg selected = leg("selected", 1_000L, 6_220L, true);
        LogicalCallDiagnosticDecision decision = merged(selected, leg("copy", 1_060L, 6_180L, false));
        LogicalCallDiagnosticOverlap overlap = LogicalCallDiagnosticOverlap.forCopy(decision, selected)
            .orElseThrow();

        assertEquals(5_220L, overlap.overlapMilliseconds());
        assertEquals(100.0d, overlap.shorterCopyOverlapPercent(), 0.0001d);
        assertEquals(100.0d, overlap.selectedCopyCoveragePercent(), 0.0001d);
        assertEquals(0L, overlap.startOffsetFromSelectedMilliseconds());
        assertEquals(0L, overlap.endOffsetFromSelectedMilliseconds());
    }

    @Test
    void leavesUnprovenAndEmptyIntervalsNotApplicable()
    {
        LogicalCallDiagnosticLeg selected = leg("selected", 1_000L, 6_220L, true);
        LogicalCallDiagnosticLeg copy = leg("copy", 1_060L, 6_180L, false);
        LogicalCallDiagnosticDecision independent = new LogicalCallDiagnosticDecision(1L, 7_000L,
            new LogicalCallId(1L, 1L), LogicalCallDecisionOutcome.INDEPENDENT, null, null, null,
            List.of(selected), LogicalCallDiagnosticEvidence.EMPTY, List.of());

        assertTrue(LogicalCallDiagnosticOverlap.forCopy(independent, selected).isEmpty());
        assertTrue(LogicalCallDiagnosticOverlap.between(selected, leg("empty", 2_000L, 2_000L, false)).isEmpty());
        assertTrue(LogicalCallDiagnosticOverlap.forCopy(merged(copy), copy).isEmpty(),
            "A malformed merged decision without a selected copy must remain not applicable");
    }

    private static LogicalCallDiagnosticDecision merged(LogicalCallDiagnosticLeg... legs)
    {
        return new LogicalCallDiagnosticDecision(1L, 7_000L, new LogicalCallId(1L, 1L),
            LogicalCallDecisionOutcome.MERGED, null, null, null, List.of(legs),
            LogicalCallDiagnosticEvidence.EMPTY, List.of());
    }

    private static LogicalCallDiagnosticLeg leg(String id, long start, long end, boolean selected)
    {
        return new LogicalCallDiagnosticLeg(id, "P25P2", "channel-" + id, "Friendly Site", "site-guid", 42L,
            0xBEE00, 0x123, 1, 2, start, end, Math.max(0L, end - start), 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            0L, 0L, 0.0d, 0.0d, 0.0d, 0.0d, 0L, false, false, selected);
    }
}
