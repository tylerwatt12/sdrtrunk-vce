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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates the stable internal key for a trunked radio system. */
public final class RadioSystemKey
{
    private static final Pattern P25_KEY = Pattern.compile("p25:[0-9a-f]{5}:[0-9a-f]{3}");
    private static final Pattern CHANNEL_KEY = Pattern.compile(
        "(?:dmr|nxdn-c|nxdn-d):channel:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private static final Pattern DMR_TIER_3_KEY = Pattern.compile(
        "dmr:tier3:(tiny|small|large|huge):(0|[1-9][0-9]*)");
    private static final Pattern NXDN_TYPE_C_KEY = Pattern.compile(
        "nxdn-c:(global|regional|local):([1-9][0-9]*)");

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
     * Creates the native ETSI Tier III radio-system key. The model selects the exact network-ID width.
     */
    public static String dmrTier3(String model, Integer network)
    {
        String canonicalModel = canonicalToken(model);
        int maximum = dmrNetworkMaximum(canonicalModel);

        return network != null && network >= 0 && network <= maximum ?
            "dmr:tier3:" + canonicalModel + ':' + network : null;
    }

    /**
     * Creates the native NXDN Type-C radio-system key. The location category selects the exact system-ID width.
     */
    public static String nxdnTypeC(String locationCategory, Integer system)
    {
        String canonicalCategory = canonicalToken(locationCategory);
        int maximum = nxdnSystemMaximum(canonicalCategory);

        return system != null && system >= 1 && system <= maximum ?
            "nxdn-c:" + canonicalCategory + ':' + system : null;
    }

    /**
     * Isolates DMR/NXDN variants without a proven native grouping rule to one saved channel. P25 requires its native
     * WACN and System ID and is never represented by a synthetic channel-scoped system.
     */
    public static String channelScoped(Protocol protocol, TrunkedIdentityDomain identityDomain,
                                       String configurationId)
    {
        String family = switch(protocol != null ? protocol : Protocol.UNKNOWN)
        {
            case DMR -> "dmr";
            case NXDN -> identityDomain == TrunkedIdentityDomain.NXDN_TYPE_C ? "nxdn-c" :
                identityDomain == TrunkedIdentityDomain.NXDN_TYPE_D ? "nxdn-d" : null;
            default -> null;
        };
        String canonicalId = canonicalUuid(configurationId);
        return family != null && canonicalId != null ? family + ":channel:" + canonicalId : null;
    }

    public static boolean isP25Native(String key)
    {
        return key != null && P25_KEY.matcher(key).matches();
    }

    /**
     * Validates native identity evidence against the decoder protocol and configured identity domain. Channel-scoped
     * fallback keys are deliberately rejected: they are configuration ownership, not learned native identity.
     */
    public static String nativeFor(Protocol protocol, TrunkedIdentityDomain identityDomain, String key)
    {
        if(key == null)
        {
            return null;
        }

        String canonical = parse(key);
        boolean compatible = switch(protocol != null ? protocol : Protocol.UNKNOWN)
        {
            case DMR -> identityDomain == TrunkedIdentityDomain.STANDARD &&
                DMR_TIER_3_KEY.matcher(canonical).matches();
            case NXDN -> identityDomain == TrunkedIdentityDomain.NXDN_TYPE_C &&
                NXDN_TYPE_C_KEY.matcher(canonical).matches();
            case APCO25, APCO25_PHASE2 -> identityDomain == TrunkedIdentityDomain.STANDARD &&
                P25_KEY.matcher(canonical).matches();
            default -> false;
        };

        if(!compatible)
        {
            throw new IllegalArgumentException("Native radio-system key does not match the decoder protocol");
        }

        return canonical;
    }

    /** Returns the exact native key when known, otherwise the deterministic saved-channel fallback. */
    public static String effectiveForReceiver(Protocol protocol, TrunkedIdentityDomain identityDomain,
                                              String configurationId, String nativeKey)
    {
        return nativeKey != null ? nativeFor(protocol, identityDomain, nativeKey) :
            channelScoped(protocol, identityDomain, configurationId);
    }

    /**
     * Validates event-time system evidence. It must be either a compatible native key or this receiver's exact
     * deterministic channel fallback.
     */
    public static String validateForReceiver(Protocol protocol, TrunkedIdentityDomain identityDomain,
                                             String configurationId, String key)
    {
        if(key == null)
        {
            return null;
        }

        String canonical = parse(key);
        String fallback = channelScoped(protocol, identityDomain, configurationId);
        if(canonical.equals(fallback))
        {
            return canonical;
        }

        return nativeFor(protocol, identityDomain, canonical);
    }

    public static boolean isChannelScoped(String key)
    {
        return key != null && CHANNEL_KEY.matcher(key).matches();
    }

    /**
     * Parses and returns an exact canonical radio-system key. Alternate spellings, padding, whitespace, and unknown
     * protocol scopes are rejected instead of being normalized.
     *
     * @throws IllegalArgumentException when the supplied key is not one of the current canonical forms
     */
    public static String parse(String key)
    {
        if(key == null || key.isEmpty() || !key.equals(key.strip()))
        {
            throw invalidKey();
        }

        if(P25_KEY.matcher(key).matches())
        {
            return key;
        }

        Matcher channel = CHANNEL_KEY.matcher(key);
        if(channel.matches() && canonicalUuidExact(channel.group(1)))
        {
            return key;
        }

        Matcher dmrTier3 = DMR_TIER_3_KEY.matcher(key);
        if(dmrTier3.matches() && withinMaximum(dmrTier3.group(2), dmrNetworkMaximum(dmrTier3.group(1))))
        {
            return key;
        }

        Matcher nxdnTypeC = NXDN_TYPE_C_KEY.matcher(key);
        if(nxdnTypeC.matches() && withinMaximum(nxdnTypeC.group(2), nxdnSystemMaximum(nxdnTypeC.group(1))))
        {
            return key;
        }

        throw invalidKey();
    }

    public static boolean isCanonical(String key)
    {
        try
        {
            parse(key);
            return true;
        }
        catch(IllegalArgumentException exception)
        {
            return false;
        }
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

    private static boolean canonicalUuidExact(String value)
    {
        try
        {
            return value != null && UUID.fromString(value).toString().equals(value);
        }
        catch(IllegalArgumentException exception)
        {
            return false;
        }
    }

    private static String canonicalToken(String value)
    {
        return value != null && !value.isBlank() && value.equals(value.strip()) ?
            value.toLowerCase(Locale.ROOT) : null;
    }

    private static boolean withinMaximum(String value, int maximum)
    {
        try
        {
            return maximum >= 0 && Integer.parseInt(value) <= maximum;
        }
        catch(NumberFormatException exception)
        {
            return false;
        }
    }

    private static int dmrNetworkMaximum(String model)
    {
        return switch(model != null ? model : "")
        {
            case "tiny" -> 0x1FF;
            case "small" -> 0x7F;
            case "large" -> 0xF;
            case "huge" -> 0x3;
            default -> -1;
        };
    }

    private static int nxdnSystemMaximum(String locationCategory)
    {
        return switch(locationCategory != null ? locationCategory : "")
        {
            case "global" -> 0x3FE;
            case "regional" -> 0x3FFE;
            case "local" -> 0x1FFFE;
            default -> -1;
        };
    }

    private static IllegalArgumentException invalidKey()
    {
        return new IllegalArgumentException("Radio system key is not canonical");
    }
}
