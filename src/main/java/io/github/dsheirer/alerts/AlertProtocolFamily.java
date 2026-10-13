/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import io.github.dsheirer.module.decode.DecoderType;
import java.util.Set;

/** Primary source decoder family, independent of a decoded event's auxiliary protocol. */
public final class AlertProtocolFamily
{
    public static final Set<String> FAMILIES = Set.of("P25", "DMR", "NXDN", "AM", "NBFM");
    private AlertProtocolFamily() { }
    public static String fromDecoder(DecoderType decoder)
    {
        if(decoder == null) return "";
        return switch(decoder)
        {
            case P25_CONVENTIONAL, P25_PHASE1, P25_PHASE2 -> "P25";
            case DMR -> "DMR";
            case NXDN -> "NXDN";
            case AM -> "AM";
            case NBFM -> "NBFM";
            default -> "";
        };
    }
    public static String fromDecoder(String decoder)
    {
        try { return fromDecoder(DecoderType.valueOf(decoder)); }
        catch(IllegalArgumentException | NullPointerException ignored) { return ""; }
    }
}
