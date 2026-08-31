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

/**
 * Supported P25 bandplan channel types.
 */
public enum P25BandplanChannelType
{
    FDMA(1),
    TDMA(2);

    private final int mTimeslotCount;

    P25BandplanChannelType(int timeslotCount)
    {
        mTimeslotCount = timeslotCount;
    }

    public int getTimeslotCount()
    {
        return mTimeslotCount;
    }
}
