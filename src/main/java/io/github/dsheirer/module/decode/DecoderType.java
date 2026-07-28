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
package io.github.dsheirer.module.decode;

import io.github.dsheirer.protocol.Protocol;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Enumeration of decoder types
 */
public enum DecoderType
{
    //Primary Decoders
    DMR("DMR","DMR", Protocol.DMR),
    MPT1327("MPT1327", "MPT1327", Protocol.MPT1327, Availability.RETIRED_COMPATIBILITY),
    NBFM("NBFM", "NBFM", Protocol.NBFM),
    NXDN("NXDN", "NXDN", Protocol.NXDN),
    P25_CONVENTIONAL("P25 Conventional", "P25-C", Protocol.APCO25),
    P25_PHASE1("P25 Phase 1", "P25-1", Protocol.APCO25),
    P25_PHASE2("P25 Phase 2", "P25-2", Protocol.APCO25_PHASE2),

    //Auxiliary Decoders
    DCS("Digital Coded Squelch (DCS)", "DCS", Protocol.DCS),
    FLEETSYNC2("Fleetsync II", "Fleetsync2", Protocol.FLEETSYNC),
    LJ_1200("LJ1200 173.075", "LJ1200", Protocol.LOJACK),
    MDC1200("MDC1200", "MDC1200", Protocol.MDC1200),
    TAIT_1200("Tait 1200", "Tait 1200", Protocol.TAIT1200);

    private String mDisplayString;
    private String mShortDisplayString;
    private Protocol mProtocol;
    private Availability mAvailability;

    DecoderType(String displayString, String shortDisplayString, Protocol protocol)
    {
        this(displayString, shortDisplayString, protocol, Availability.ACTIVE);
    }

    DecoderType(String displayString, String shortDisplayString, Protocol protocol, Availability availability)
    {
        mDisplayString = displayString;
        mShortDisplayString = shortDisplayString;
        mProtocol = protocol;
        mAvailability = availability;
    }

    /**
     * Primary decoders that operate on I/Q sample streams
     */
    public static final Set<DecoderType> PRIMARY_DECODERS =
        activeOnly(EnumSet.of(DecoderType.DMR,
        DecoderType.MPT1327,
        DecoderType.NBFM,
        DecoderType.NXDN,
        DecoderType.P25_CONVENTIONAL,
        DecoderType.P25_PHASE1,
        DecoderType.P25_PHASE2));

    /**
     * Auxiliary decoders that operate on in-band signalling in the decoded audio channel
     */
    public static final Set<DecoderType> AUX_DECODERS =
        activeOnly(EnumSet.of(DecoderType.DCS,
        DecoderType.FLEETSYNC2,
        DecoderType.LJ_1200,
        DecoderType.MDC1200,
        DecoderType.TAIT_1200));

    /**
     * Decoders that produce a (recordable) bitstream
     */
    public static final Set<DecoderType> BITSTREAM_DECODERS = activeOnly(EnumSet.of(DecoderType.DMR,
        DecoderType.MPT1327, DecoderType.NXDN, DecoderType.P25_CONVENTIONAL, DecoderType.P25_PHASE1,
        DecoderType.P25_PHASE2));

    /**
     * Decoders that produce (recordable) MBE audio codec frames
     */
    public static final Set<DecoderType> MBE_AUDIO_CODEC_DECODERS =
        activeOnly(EnumSet.of(DecoderType.DMR, DecoderType.P25_CONVENTIONAL, DecoderType.P25_PHASE1,
            DecoderType.P25_PHASE2, DecoderType.NXDN));

    private static Set<DecoderType> activeOnly(EnumSet<DecoderType> candidates)
    {
        candidates.removeIf(decoderType -> !decoderType.isActive());
        return Collections.unmodifiableSet(candidates);
    }

    public Protocol getProtocol()
    {
        return mProtocol;
    }

    public String getDisplayString()
    {
        return mDisplayString;
    }

    public String getShortDisplayString()
    {
        return mShortDisplayString;
    }

    /**
     * Indicates whether this decoder can be selected and run.  Retired compatibility entries remain in this enum only
     * so that persisted configuration written by an older release can be recognized without activating old decoder
     * code.
     */
    public boolean isActive()
    {
        return mAvailability == Availability.ACTIVE;
    }

    public boolean isRetiredCompatibility()
    {
        return mAvailability == Availability.RETIRED_COMPATIBILITY;
    }

    @Override
    public String toString()
    {
        return mDisplayString;
    }

    /**
     * Indicates if the decoder type produces a (recordable) bitstream
     */
    public boolean providesBitstream()
    {
        return BITSTREAM_DECODERS.contains(this);
    }

    /**
     * Indicates if the decoder type produces (recordable) MBE audio codec frames
     */
    public boolean providesMBEAudioFrames()
    {
        return MBE_AUDIO_CODEC_DECODERS.contains(this);
    }

    public enum Availability
    {
        ACTIVE,
        RETIRED_COMPATIBILITY
    }
}
