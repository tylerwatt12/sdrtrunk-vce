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

package io.github.dsheirer.alias;

import io.github.dsheirer.module.decode.DecoderType;

/**
 * Protocol family that owns an alias list.  Decoder variants that produce compatible identifiers, such as all P25
 * decoder variants, intentionally share one family.
 */
public enum AliasListFamily
{
    P25("P25"),
    DMR("DMR"),
    NXDN("NXDN"),
    NBFM("NBFM");

    private final String mLabel;

    AliasListFamily(String label)
    {
        mLabel = label;
    }

    public static AliasListFamily from(DecoderType decoderType)
    {
        if(decoderType == null)
        {
            return null;
        }

        return switch(decoderType)
        {
            case P25_CONVENTIONAL, P25_PHASE1, P25_PHASE2 -> P25;
            case DMR -> DMR;
            case NXDN -> NXDN;
            case NBFM -> NBFM;
            case MPT1327, DCS, FLEETSYNC2, LJ_1200, MDC1200, TAIT_1200 -> null;
        };
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}
