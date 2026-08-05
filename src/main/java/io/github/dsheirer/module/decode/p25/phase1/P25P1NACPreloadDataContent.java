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

package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.controller.channel.event.PreloadDataContent;

/**
 * Expected Network Access Code (NAC) to preload into a dynamically allocated P25 Phase 1 traffic decoder.
 */
public class P25P1NACPreloadDataContent extends PreloadDataContent<Integer>
{
    private static final int MAX_NAC = 0xFFF;
    private static final int RECEIVER_WILDCARD_NAC = 0xF7E;
    private static final int REPEATER_WILDCARD_NAC = 0xF7F;

    /**
     * Constructs an instance for a concrete NAC observed on the controlling channel.
     *
     * @param nac expected traffic-channel NAC
     */
    public P25P1NACPreloadDataContent(int nac)
    {
        super(validate(nac));
    }

    /**
     * Expected NAC value.
     */
    public int getNAC()
    {
        return getData();
    }

    /**
     * Indicates whether the value is a concrete on-air NAC. TIA-102.BAAC-A section 2.1 defines F7E and F7F as
     * receive-side wildcard settings and advises implementations not to transmit them.
     */
    public static boolean isConcreteNAC(int nac)
    {
        return nac >= 0 && nac <= MAX_NAC && nac != RECEIVER_WILDCARD_NAC && nac != REPEATER_WILDCARD_NAC;
    }

    private static int validate(int nac)
    {
        if(!isConcreteNAC(nac))
        {
            throw new IllegalArgumentException("Expected P25 Phase 1 traffic NAC must be a concrete 12-bit value");
        }

        return nac;
    }
}
