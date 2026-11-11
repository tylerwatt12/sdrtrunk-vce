/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.channel.metadata.activity;

/** A confirmed callsign on one carrier, with its actual RF reception time and evidence source. */
public record BaseStationIdentification(String source, String callsign, Long lastHeardMs)
{
    public BaseStationIdentification
    {
        if(!"control".equals(source) && !"voice".equals(source))
            throw new IllegalArgumentException("Unknown BSI source");
        if(callsign == null || callsign.isBlank()) throw new IllegalArgumentException("Blank BSI");
    }
}
