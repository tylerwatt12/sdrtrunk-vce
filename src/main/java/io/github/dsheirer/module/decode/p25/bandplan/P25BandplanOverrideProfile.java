/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.bandplan;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P25 bandplan override for a whole system or one site in that system.
 */
public record P25BandplanOverrideProfile(int wacn, int system, Integer rfss, Integer site,
                                         List<P25BandplanOverrideBand> bands)
{
    private static final int MAXIMUM_WACN = 0xFFFFF;
    private static final int MAXIMUM_SYSTEM = 0xFFF;
    private static final int MAXIMUM_RFSS = 0xFF;
    private static final int MAXIMUM_SITE = 0xFF;

    public P25BandplanOverrideProfile
    {
        requireRange("WACN", wacn, MAXIMUM_WACN);
        requireRange("System", system, MAXIMUM_SYSTEM);

        if((rfss == null) != (site == null))
        {
            throw new IllegalArgumentException("P25 RFSS and Site must both be set or both be empty");
        }

        if(rfss != null)
        {
            requireRange("RFSS", rfss, MAXIMUM_RFSS);
            requireRange("Site", site, MAXIMUM_SITE);
        }

        if(bands == null || bands.isEmpty())
        {
            throw new IllegalArgumentException("P25 bandplan override must contain at least one band");
        }

        bands = List.copyOf(bands);
        Set<Integer> identifiers = new HashSet<>();

        for(P25BandplanOverrideBand band: bands)
        {
            if(band == null)
            {
                throw new IllegalArgumentException("P25 bandplan override cannot contain an empty band");
            }

            if(!identifiers.add(band.identifier()))
            {
                throw new IllegalArgumentException("P25 bandplan override contains duplicate band ID " +
                    band.identifier());
            }
        }
    }

    public boolean isSiteSpecific()
    {
        return rfss != null;
    }

    private static void requireRange(String label, int value, int maximum)
    {
        if(value < 0 || value > maximum)
        {
            throw new IllegalArgumentException(label + " is outside its P25 field range: " + value);
        }
    }
}
