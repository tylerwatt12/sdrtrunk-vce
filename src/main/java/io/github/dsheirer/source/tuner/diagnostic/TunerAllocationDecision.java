/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.diagnostic;

import java.util.List;
import java.util.Objects;

/** One final allocator result. Captures only facts needed to explain a move or unsuccessful request. */
public record TunerAllocationDecision(long occurredAtMs, TunerAllocationContext context, String tunerIdentity,
                                      Outcome outcome, Reason reason, Long requestedFrequencyHz,
                                      Long requestedBandwidthHz, Long beforeFrequencyHz,
                                      Long requestedCenterFrequencyHz, Long afterFrequencyHz,
                                      Long usableBandwidthHz, Long middleUnusableHalfBandwidthHz,
                                      Long controlFrequencyHz, Integer coveredBefore, Integer coveredAfter,
                                      List<Candidate> candidates)
{
    public static final int MAXIMUM_CANDIDATES = 8;

    public TunerAllocationDecision
    {
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(reason);
        candidates = candidates == null ? List.of() :
            List.copyOf(candidates.subList(0, Math.min(MAXIMUM_CANDIDATES, candidates.size())));
    }

    public enum Outcome { MOVED, MOVED_CALL_FAILED, RETUNE_FAILED, ALLOCATION_FAILED }

    public enum Reason
    {
        SPARE_TUNER, KEEP_CURRENT_CALLS, COVERAGE_GAP, CENTER_LOCKED, OUTSIDE_LIMITS,
        CONTROL_CHANNEL_PROTECTED, NO_TUNER_AVAILABLE, NO_ROOM, TUNER_BUSY, TUNER_UNAVAILABLE,
        SOURCE_START_FAILED, HARDWARE_RETUNE_FAILED
    }

    public record Candidate(String tunerIdentity, Reason reason, Long minimumFrequencyHz, Long maximumFrequencyHz) { }
}
