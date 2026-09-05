/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RadioSystemIdentityKeyTest
{
    @Test
    void exactP25AndChannelScopedTuplesRoundTrip()
    {
        assertRoundTrip("v1-g-bee00-3a9-65534", RadioSystemIdentityKey.KIND_TALKGROUP,
            0xBEE00, 0x3A9, 65_534);
        assertRoundTrip("v1-p-abcde-321-1200", RadioSystemIdentityKey.KIND_PATCH_GROUP,
            0xABCDE, 0x321, 1_200);
        assertRoundTrip("v1-r-00000-000-9999999", RadioSystemIdentityKey.KIND_RADIO,
            0, 0, 9_999_999);
        assertRoundTrip("v1-g-x-x-16777215", RadioSystemIdentityKey.KIND_TALKGROUP,
            RadioSystemIdentityKey.NO_HOME, RadioSystemIdentityKey.NO_HOME, 0xFFFFFF);
        assertRoundTrip("v1-r-x-x-16777215", RadioSystemIdentityKey.KIND_RADIO,
            RadioSystemIdentityKey.NO_HOME, RadioSystemIdentityKey.NO_HOME, 0xFFFFFF);
    }

    @Test
    void parserRejectsNonCanonicalOrUnsupportedTuples()
    {
        for(String value: new String[]{
            " v1-g-x-x-1 ", "V1-g-x-x-1", "v1-G-x-x-1", "v1-g-BEE00-3a9-1",
            "v1-g-bee00-3A9-1", "v1-g-bee0-3a9-1", "v1-g-bee00-3a9-01",
            "v1-g-x-3a9-1", "v1-g-bee00-x-1", "v2-g-x-x-1", "v1-z-x-x-1",
            "v1-g-bee00-3a9-65535", "v1-r-bee00-3a9-10000000", "v1-g-x-x-0",
            "v1-p-x-x-1"
        })
        {
            assertThrows(IllegalArgumentException.class, () -> RadioSystemIdentityKey.parse(value), value);
        }
    }

    private static void assertRoundTrip(String expected, int kind, int homeWacn, int homeSystemId, int identityId)
    {
        assertEquals(expected, RadioSystemIdentityKey.format(kind, homeWacn, homeSystemId, identityId));
        RadioSystemIdentityKey.Identity parsed = RadioSystemIdentityKey.parse(expected);
        assertEquals(kind, parsed.kindCode());
        assertEquals(homeWacn, parsed.homeWacn());
        assertEquals(homeSystemId, parsed.homeSystemId());
        assertEquals(identityId, parsed.identityId());
        assertEquals(expected, parsed.key());
    }
}
