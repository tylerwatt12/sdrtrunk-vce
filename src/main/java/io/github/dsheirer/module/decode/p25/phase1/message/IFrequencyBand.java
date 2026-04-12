/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2019 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message;

public interface IFrequencyBand
{
    /**
     * (Band) Identifier
     */
    public abstract int getIdentifier();

    /**
     * Channel spacing in hertz
     */
    public abstract long getChannelSpacing();

    /**
     * Base frequency in hertz
     */
    public abstract long getBaseFrequency();


    /**
     * Channel bandwidth in hertz
     */
    public abstract int getBandwidth();

    /**
     * Transmit offset in hertz
     */
    public abstract long getTransmitOffset();

    /**
     * Downlink (tower to mobile) Frequency for the specified channel
     */
    public abstract long getDownlinkFrequency(int channelNumber);

    /**
     * Uplink (mobile to tower) Frequency for the specified channel
     */
    public abstract long getUplinkFrequency(int channelNumber);

    /**
     * Indicates if this band is an FDMA (false) or TDMA (true) band
     */
    public boolean isTDMA();

    /**
     * Number of timeslots available on this channel
     */
    public int getTimeslotCount();

    /**
     * Alignment score for conflict resolution. A band whose base frequency is an exact multiple of its
     * channel spacing is considered better-aligned (score 1) than one that is not (score 0).
     */
    default int alignmentScore()
    {
        long spacing = getChannelSpacing();
        return spacing > 0 && getBaseFrequency() % spacing == 0 ? 1 : 0;
    }

    /**
     * Returns true if this band is a better replacement for the existing band with the same identifier.
     */
    default boolean isPreferredOver(IFrequencyBand existing)
    {
        return alignmentScore() > existing.alignmentScore();
    }
}