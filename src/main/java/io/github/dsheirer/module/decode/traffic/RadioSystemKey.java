/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.protocol.Protocol;
import java.util.Locale;
import java.util.UUID;

/** Creates the stable internal key for a trunked radio system. */
public final class RadioSystemKey
{
    private RadioSystemKey()
    {
    }

    /** P25 sites with the same over-the-air WACN and System ID belong to one radio system. */
    public static String p25(P25SiteIdentity identity)
    {
        return identity != null ? p25(identity.wacn(), identity.system()) : null;
    }

    public static String p25(int wacn, int systemId)
    {
        if(wacn < 0 || wacn > 0xFFFFF || systemId < 0 || systemId > 0xFFF)
        {
            return null;
        }

        return String.format(Locale.ROOT, "p25:%05x:%03x", wacn, systemId);
    }

    /**
     * Isolates a system to one saved channel when a complete, proven over-the-air system identity is unavailable.
     * This is currently the deliberate rule for every DMR and NXDN trunking variant.
     */
    public static String configured(Protocol protocol, String configurationId)
    {
        String family = switch(protocol != null ? protocol : Protocol.UNKNOWN)
        {
            case APCO25, APCO25_PHASE2 -> "p25";
            case DMR -> "dmr";
            case NXDN -> "nxdn";
            default -> null;
        };
        String canonicalId = canonicalUuid(configurationId);
        return family != null && canonicalId != null ? family + ":channel:" + canonicalId : null;
    }

    public static boolean isP25Native(String key)
    {
        return key != null && key.matches("p25:[0-9a-f]{5}:[0-9a-f]{3}");
    }

    private static String canonicalUuid(String value)
    {
        if(value == null || value.isBlank())
        {
            return null;
        }

        try
        {
            return UUID.fromString(value.strip()).toString();
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }
}
