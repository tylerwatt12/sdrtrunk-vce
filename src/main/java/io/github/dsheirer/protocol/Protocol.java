/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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
 * ****************************************************************************
 */
package io.github.dsheirer.protocol;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Binary protocols supported within this application
 */
public enum Protocol
{
    APCO25("APCO-25", "APCO25PHASE1", 9600),
    APCO25_PHASE2("APCO-25 P2", "APCO25PHASE2", 12000),
    ARS("ARS", "ARS", 0),
    CELLOCATOR("CELLOCATOR", "CELLOCATOR", 0),
    DCS("DCS", "DCS", 134),
    DMR("DMR", "DMR", 9600),
    FLEETSYNC("Fleetsync", "FLEETSYNC", 1200),
    IPV4("IPV4", "IPV4", 0),
    LOJACK("LoJack", "LOJACK", 1200),
    LRRP("LRRP", "LRRP", 0),
    NBFM("NBFM", "NBFM", 0),
    MDC1200("MDC-1200", "MDC1200", 1200),
    MPT1327("MPT-1327", "MPT1327", 0, Availability.RETIRED_COMPATIBILITY),
    NXDN("NXDN", "NXDN", 9600),
    TAIT1200("Tait 1200", "TAIT1200", 1200),
    UDP("UDP", "UDP", 0),
    UNKNOWN("Unknown", "UNKNOWN", 0);

    private String mLabel;
    private String mFileNameLabel;
    private int mBitRate;
    private Availability mAvailability;

    Protocol(String label, String fileNameLabel, int bitRate)
    {
        this(label, fileNameLabel, bitRate, Availability.ACTIVE);
    }

    Protocol(String label, String fileNameLabel, int bitRate, Availability availability)
    {
        mLabel = label;
        mFileNameLabel = fileNameLabel;
        mBitRate = bitRate;
        mAvailability = availability;
    }

    public static final Set<Protocol> TALKGROUP_PROTOCOLS =
        activeOnly(EnumSet.of(APCO25, DMR, FLEETSYNC, MDC1200, MPT1327, NBFM, NXDN));

    private static final Set<Protocol> RADIO_ID_PROTOCOLS = EnumSet.of(APCO25, DMR, NXDN);

    private static Set<Protocol> activeOnly(EnumSet<Protocol> candidates)
    {
        candidates.removeIf(protocol -> !protocol.isActive());
        return Collections.unmodifiableSet(candidates);
    }

    @Override
    public String toString()
    {
        return mLabel;
    }

    public String getFileNameLabel()
    {
        return mFileNameLabel;
    }

    public int getBitRate()
    {
        return mBitRate;
    }

    public boolean isActive()
    {
        return mAvailability == Availability.ACTIVE;
    }

    public boolean isRetiredCompatibility()
    {
        return mAvailability == Availability.RETIRED_COMPATIBILITY;
    }

    public enum Availability
    {
        ACTIVE,
        RETIRED_COMPATIBILITY
    }
}
