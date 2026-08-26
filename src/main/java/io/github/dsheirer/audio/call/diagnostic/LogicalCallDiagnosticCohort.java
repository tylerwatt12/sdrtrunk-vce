/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import java.util.List;

/** Current worker-owned cohort projected into an immutable, compact diagnostic value. */
public record LogicalCallDiagnosticCohort(long cohortId, long ageMilliseconds, long settleRemainingMilliseconds,
                                          long awaitedLegCeilingRemainingMilliseconds,
                                          List<LogicalCallDiagnosticLeg> completedLegs,
                                          List<String> awaitedActiveLegIds)
{
    public LogicalCallDiagnosticCohort
    {
        cohortId = Math.max(0L, cohortId);
        ageMilliseconds = Math.max(0L, ageMilliseconds);
        settleRemainingMilliseconds = Math.max(0L, settleRemainingMilliseconds);
        awaitedLegCeilingRemainingMilliseconds = Math.max(0L, awaitedLegCeilingRemainingMilliseconds);
        completedLegs = completedLegs != null ? List.copyOf(completedLegs) : List.of();
        awaitedActiveLegIds = awaitedActiveLegIds != null ? List.copyOf(awaitedActiveLegIds) : List.of();
    }
}
