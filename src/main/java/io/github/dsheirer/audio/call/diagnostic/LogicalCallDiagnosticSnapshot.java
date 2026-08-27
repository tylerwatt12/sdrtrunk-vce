/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import java.util.List;

/** Latest immutable resolver state. Polling never calls back onto the coordinator worker. */
public record LogicalCallDiagnosticSnapshot(String sessionId, long startedAtMs, long generatedAtMs, long revision,
                                            boolean accepting, boolean disposed,
                                            LogicalCallDiagnosticCounters counters, int activeLegCount,
                                            int activeCohortCount, long retainedAudioSampleCount,
                                            List<LogicalCallDiagnosticLeg> activeLegs,
                                            List<LogicalCallDiagnosticCohort> activeCohorts)
{
    public LogicalCallDiagnosticSnapshot
    {
        startedAtMs = Math.max(0L, startedAtMs);
        generatedAtMs = Math.max(0L, generatedAtMs);
        revision = Math.max(0L, revision);
        activeLegCount = Math.max(0, activeLegCount);
        activeCohortCount = Math.max(0, activeCohortCount);
        retainedAudioSampleCount = Math.max(0L, retainedAudioSampleCount);
        activeLegs = activeLegs != null ? List.copyOf(activeLegs) : List.of();
        activeCohorts = activeCohorts != null ? List.copyOf(activeCohorts) : List.of();
    }
}
