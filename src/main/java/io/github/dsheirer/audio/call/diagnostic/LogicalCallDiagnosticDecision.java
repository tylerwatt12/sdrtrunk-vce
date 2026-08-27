/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import io.github.dsheirer.audio.call.LogicalCallId;
import java.util.List;

/** One final resolver decision offered to the bounded diagnostic sink. */
public record LogicalCallDiagnosticDecision(long decisionSequence, long decidedAtMs, LogicalCallId logicalCallId,
                                            LogicalCallDecisionOutcome outcome,
                                            LogicalCallDiagnosticCallIdentity callIdentity,
                                            LogicalCallDiagnosticOutputPolicy outputPolicy,
                                            LogicalCallDiagnosticWinner winner,
                                            List<LogicalCallDiagnosticLeg> legs,
                                            LogicalCallDiagnosticEvidence evidence,
                                            List<LogicalCallSeparationReason> decisionReasons)
{
    public LogicalCallDiagnosticDecision
    {
        decisionSequence = Math.max(1L, decisionSequence);
        decidedAtMs = Math.max(0L, decidedAtMs);
        outcome = outcome != null ? outcome : LogicalCallDecisionOutcome.FAIL_OPEN;
        legs = legs != null ? List.copyOf(legs) : List.of();
        evidence = evidence != null ? evidence : LogicalCallDiagnosticEvidence.EMPTY;
        decisionReasons = decisionReasons != null ? List.copyOf(decisionReasons) : List.of();
    }
}
