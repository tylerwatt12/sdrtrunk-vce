/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25;

/**
 * P25-specific timing policy for control-channel rotation.
 *
 * A locked control channel tolerates brief decode interruptions before rotating. Once searching, each candidate is
 * checked quickly enough to scan a typical control-channel list without extending the locked-channel grace period.
 * Frequency acquisition is intentionally separate from Phase 1 decoder modulation selection.
 */
public final class P25ControlChannelRotationPolicy
{
    public static final int SEARCH_DWELL_MILLISECONDS = 500;
    public static final int ACTIVE_STATE_LOSS_GRACE_MILLISECONDS = 4_000;

    private P25ControlChannelRotationPolicy()
    {
    }

    /**
     * Caps legacy or user-configured P25 dwell values while preserving an explicitly configured faster dwell.
     *
     * @param configuredDwellMilliseconds configured frequency rotation delay
     * @return effective P25 control-channel search dwell
     */
    public static int getSearchDwellMilliseconds(int configuredDwellMilliseconds)
    {
        return Math.min(configuredDwellMilliseconds, SEARCH_DWELL_MILLISECONDS);
    }
}
