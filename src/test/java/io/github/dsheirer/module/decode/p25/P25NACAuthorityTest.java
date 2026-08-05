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

import org.junit.jupiter.api.Test;

class P25NACAuthorityTest
{
    private static final int NAC = 0x123;
    private static final int FOREIGN_NAC = 0x456;

    @Test
    void requiresThreeIndependentObservationsAndThenFreezes()
    {
        P25NACAuthority authority = new P25NACAuthority();

        assertEquals(P25NACAuthority.Result.PENDING, authority.observe(NAC, 1_000L, 0));
        assertEquals(P25NACAuthority.Result.PENDING, authority.observe(NAC, 1_001L, 0));
        assertEquals(P25NACAuthority.Result.ESTABLISHED, authority.observe(NAC, 1_002L, 0));
        assertEquals(NAC, authority.getNAC());
        assertEquals(P25NACAuthority.Result.MATCH, authority.observe(NAC, 1_003L, 0));
        assertEquals(P25NACAuthority.Result.REJECTED, authority.observe(FOREIGN_NAC, 1_004L, 0));
        assertEquals(NAC, authority.getNAC());
    }

    @Test
    void duplicatePhysicalObservationDoesNotCountTwice()
    {
        P25NACAuthority authority = new P25NACAuthority();

        assertEquals(P25NACAuthority.Result.PENDING, authority.observe(NAC, 1_000L, 0));
        assertEquals(P25NACAuthority.Result.DUPLICATE, authority.observe(NAC, 1_000L, 0));
        assertEquals(P25NACAuthority.Result.PENDING, authority.observe(NAC, 1_000L, 1));
        assertEquals(P25NACAuthority.Result.ESTABLISHED, authority.observe(NAC, 1_001L, 0));
    }

    @Test
    void observationsMustBeConsecutiveAndFitWithinOneSecond()
    {
        P25NACAuthority authority = new P25NACAuthority();

        assertEquals(P25NACAuthority.Result.PENDING, authority.observe(NAC, 0L, 0));
        assertEquals(P25NACAuthority.Result.PENDING, authority.observe(NAC, 1_000L, 0));
        assertEquals(P25NACAuthority.Result.PENDING, authority.observe(NAC, 2_000L, 0));
        assertEquals(P25NACAuthority.Result.PENDING, authority.observe(FOREIGN_NAC, 2_001L, 0));
        assertEquals(P25NACAuthority.Result.PENDING, authority.observe(NAC, 2_002L, 0));
        assertEquals(P25NACAuthority.Result.PENDING, authority.observe(NAC, 2_003L, 0));
        assertEquals(P25NACAuthority.Result.ESTABLISHED, authority.observe(NAC, 2_004L, 0));
    }

    @Test
    void resetRequiresFreshAuthorityAndSupportsNacZero()
    {
        P25NACAuthority authority = new P25NACAuthority();
        authority.observe(NAC, 1_000L, 0);
        authority.observe(NAC, 1_001L, 0);
        authority.observe(NAC, 1_002L, 0);

        authority.reset();

        assertEquals(P25NACAuthority.NO_NAC, authority.getNAC());
        assertEquals(P25NACAuthority.Result.PENDING, authority.observe(0, 2_000L, 0));
        assertEquals(P25NACAuthority.Result.PENDING, authority.observe(0, 2_001L, 0));
        assertEquals(P25NACAuthority.Result.ESTABLISHED, authority.observe(0, 2_002L, 0));
        assertEquals(0, authority.getNAC());
    }
}
