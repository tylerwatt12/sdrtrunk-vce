/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

import java.util.Locale;

/**
 * Canonical, URL-safe identity tuple used by storage, web navigation and playback controls.
 *
 * <p>The radio-system key scopes this value externally. A P25 identity carries its home WACN and System ID;
 * channel-scoped DMR and NXDN identities use the {@code x-x} sentinel. Database surrogate IDs are
 * deliberately excluded.</p>
 */
public final class RadioSystemIdentityKey
{
    public static final int KIND_TALKGROUP = 1;
    public static final int KIND_RADIO = 2;
    public static final int KIND_PATCH_GROUP = 3;
    /** Largest P25 talkgroup or patch-group identity. 0xFFFF is the all-call value. */
    public static final int MAX_P25_GROUP_ID = 65_534;
    /** Largest non-P25 group identity supported by the shared tuple syntax (DMR uses 24 bits). */
    public static final int MAX_OTHER_GROUP_ID = 0xFFFFFF;
    /** Largest assignable P25 working unit address (TIA-102.AABC-B, Section 2.3.27). */
    public static final int MAX_P25_WORKING_UNIT_ID = 0xFFFFFC;
    public static final int MAX_P25_RADIO_ID = 9_999_999;
    public static final int MAX_OTHER_RADIO_ID = 0xFFFFFF;
    public static final int NO_HOME = -1;

    private RadioSystemIdentityKey()
    {
    }

    public static String format(int kindCode, int homeWacn, int homeSystemId, int identityId)
    {
        Identity identity = new Identity(kindCode, homeWacn, homeSystemId, identityId);
        String home = identity.hasHome() ? String.format(Locale.ROOT, "%05x", homeWacn) : "x";
        String system = identity.hasHome() ? String.format(Locale.ROOT, "%03x", homeSystemId) : "x";
        return "v1-" + token(kindCode) + '-' + home + '-' + system + '-' + identityId;
    }

    public static Identity parse(String value)
    {
        if(value == null || value.isBlank() || !value.equals(value.strip()))
        {
            throw new IllegalArgumentException("Radio identity key is missing");
        }

        String[] parts = value.strip().split("-", -1);
        if(parts.length != 5 || !"v1".equals(parts[0]))
        {
            throw new IllegalArgumentException("Unsupported radio identity key");
        }

        int kind = kind(parts[1]);
        boolean noHome = "x".equals(parts[2]) && "x".equals(parts[3]);
        if(!noHome && (parts[2].length() != 5 || parts[3].length() != 3 ||
            !parts[2].equals(parts[2].toLowerCase(Locale.ROOT)) ||
            !parts[3].equals(parts[3].toLowerCase(Locale.ROOT))))
        {
            throw new IllegalArgumentException("Radio identity home tuple is not canonical");
        }

        try
        {
            int homeWacn = noHome ? NO_HOME : Integer.parseInt(parts[2], 16);
            int homeSystemId = noHome ? NO_HOME : Integer.parseInt(parts[3], 16);
            int identityId = Integer.parseInt(parts[4]);
            Identity identity = new Identity(kind, homeWacn, homeSystemId, identityId);
            if(!format(kind, homeWacn, homeSystemId, identityId).equals(value))
            {
                throw new IllegalArgumentException("Radio identity key is not canonical");
            }
            return identity;
        }
        catch(NumberFormatException exception)
        {
            throw new IllegalArgumentException("Radio identity key contains an invalid number", exception);
        }
    }

    public record Identity(int kindCode, int homeWacn, int homeSystemId, int identityId)
    {
        public Identity
        {
            boolean hasHome = homeWacn >= 0 || homeSystemId >= 0;
            if(kindCode < KIND_TALKGROUP || kindCode > KIND_PATCH_GROUP ||
                hasHome && (homeWacn < 0 || homeWacn > 0xFFFFF || homeSystemId < 0 || homeSystemId > 0xFFF) ||
                !hasHome && (homeWacn != NO_HOME || homeSystemId != NO_HOME) ||
                kindCode == KIND_TALKGROUP && (identityId < 1 || identityId >
                    (hasHome ? MAX_P25_GROUP_ID : MAX_OTHER_GROUP_ID)) ||
                kindCode == KIND_PATCH_GROUP && (!hasHome || identityId < 1 ||
                    identityId > MAX_P25_GROUP_ID) ||
                kindCode == KIND_RADIO && (identityId < 1 || identityId > MAX_OTHER_RADIO_ID) ||
                hasHome && kindCode == KIND_RADIO && identityId > MAX_P25_RADIO_ID)
            {
                throw new IllegalArgumentException("Invalid canonical radio identity tuple");
            }
        }

        public boolean hasHome()
        {
            return homeWacn != NO_HOME;
        }

        public String key()
        {
            return format(kindCode, homeWacn, homeSystemId, identityId);
        }
    }

    private static String token(int kindCode)
    {
        return switch(kindCode)
        {
            case KIND_TALKGROUP -> "g";
            case KIND_RADIO -> "r";
            case KIND_PATCH_GROUP -> "p";
            default -> throw new IllegalArgumentException("Unknown radio identity kind");
        };
    }

    private static int kind(String token)
    {
        return switch(token)
        {
            case "g" -> KIND_TALKGROUP;
            case "r" -> KIND_RADIO;
            case "p" -> KIND_PATCH_GROUP;
            default -> throw new IllegalArgumentException("Unknown radio identity kind");
        };
    }
}
