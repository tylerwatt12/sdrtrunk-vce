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

package io.github.dsheirer.metadata.site;

import io.github.dsheirer.protocol.Protocol;
import java.util.Locale;
import java.util.Set;

/**
 * Authoritative classification for DMR and NXDN metadata that positively identifies a trunked control receiver.
 */
public final class TrunkedSiteMetadataClassifier
{
    private static final Set<String> DMR_VARIANTS = Set.of(
        "TIER_III", "CONNECT_PLUS", "CAPACITY_MAX", "HYTERA_TIER_III", "CAPACITY_PLUS");
    private static final Set<String> NXDN_VARIANTS = Set.of("TYPE_C", "TYPE_D");

    private TrunkedSiteMetadataClassifier()
    {
    }

    /**
     * Requires useful metadata from a standard parent channel and a known trunking variant. This prevents ordinary
     * conventional DMR/NXDN observations from creating a learned site or retained control-channel quality history.
     */
    public static boolean isKnownTrunkingMetadata(ProtocolSiteMetadataEvent event)
    {
        if(event == null || !event.isUseful() || event.channel() == null ||
            !event.channel().isStandardChannel() || event.channel().isTrafficChannel() ||
            event.snapshot().protocol() == null)
        {
            return false;
        }

        String variant = canonicalVariant(event.snapshot().variant());

        if(event.snapshot().protocol() == Protocol.DMR)
        {
            return DMR_VARIANTS.contains(variant);
        }
        else if(event.snapshot().protocol() == Protocol.NXDN)
        {
            return NXDN_VARIANTS.contains(variant);
        }

        return false;
    }

    /**
     * Canonical spelling shared by classification and protocol-specific storage adapters.
     */
    public static String canonicalVariant(String value)
    {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
            .replace('-', '_').replace(' ', '_');
    }
}
