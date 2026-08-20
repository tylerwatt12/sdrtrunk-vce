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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class P25ControlChannelRotationPolicyTest
{
    @Test
    void capsSlowConfiguredDwellForFastControlChannelSearch()
    {
        assertEquals(500, P25ControlChannelRotationPolicy.getSearchDwellMilliseconds(2_000));
        assertEquals(500, P25ControlChannelRotationPolicy.getSearchDwellMilliseconds(10_000));
    }

    @Test
    void preservesConfiguredDwellWhenAlreadyFaster()
    {
        assertEquals(400, P25ControlChannelRotationPolicy.getSearchDwellMilliseconds(400));
        assertEquals(200, P25ControlChannelRotationPolicy.getSearchDwellMilliseconds(200));
    }

    @Test
    void nominalFourFrequencyRecoveryStaysBelowTenSecondsAfterLockedGrace()
    {
        int dwell = P25ControlChannelRotationPolicy.SEARCH_DWELL_MILLISECONDS;
        int monitorCheckInterval = dwell / 2;
        int remainingCandidates = 3;
        int nominalRecoveryMilliseconds =
            P25ControlChannelRotationPolicy.ACTIVE_STATE_LOSS_GRACE_MILLISECONDS +
                monitorCheckInterval +
                (remainingCandidates * (dwell + monitorCheckInterval));

        assertEquals(6_500, nominalRecoveryMilliseconds);
        assertTrue(nominalRecoveryMilliseconds < 10_000);
        assertEquals(4_000, P25ControlChannelRotationPolicy.ACTIVE_STATE_LOSS_GRACE_MILLISECONDS);
    }

    @Test
    void nominalEightFrequencyRecoveryRejectsTheEntireListWithinTenSeconds()
    {
        int dwell = P25ControlChannelRotationPolicy.SEARCH_DWELL_MILLISECONDS;
        int monitorCheckInterval = dwell / 2;
        int remainingCandidates = 7;
        int nominalRecoveryMilliseconds =
            P25ControlChannelRotationPolicy.ACTIVE_STATE_LOSS_GRACE_MILLISECONDS +
                monitorCheckInterval +
                (remainingCandidates * (dwell + monitorCheckInterval));

        assertEquals(9_500, nominalRecoveryMilliseconds);
        assertTrue(nominalRecoveryMilliseconds < 10_000);
    }
}
