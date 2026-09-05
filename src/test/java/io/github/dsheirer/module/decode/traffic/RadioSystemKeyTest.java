/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

class RadioSystemKeyTest
{
    private static final String CHANNEL_ID = "00000000-0000-0000-0000-000000000123";

    @Test
    void p25SitesShareTheWacnAndSystemKey()
    {
        String first = RadioSystemKey.p25(new P25SiteIdentity(0xBEE00, 0x348, 2, 1));
        String second = RadioSystemKey.p25(new P25SiteIdentity(0xBEE00, 0x348, 2, 27));

        assertEquals("p25:bee00:348", first);
        assertEquals(first, second);
        assertTrue(RadioSystemKey.isP25Native(first));
        assertFalse(RadioSystemKey.isP25Native("p25:channel:" + CHANNEL_ID));
    }

    @Test
    void unprovenDmrAndNxdnGroupingStaysOnTheConfiguredChannel()
    {
        assertEquals("dmr:channel:" + CHANNEL_ID,
            RadioSystemKey.channelScoped(Protocol.DMR, TrunkedIdentityDomain.STANDARD, CHANNEL_ID));
        assertEquals("nxdn-c:channel:" + CHANNEL_ID,
            RadioSystemKey.channelScoped(Protocol.NXDN, TrunkedIdentityDomain.NXDN_TYPE_C, CHANNEL_ID));
        assertEquals("nxdn-d:channel:" + CHANNEL_ID,
            RadioSystemKey.channelScoped(Protocol.NXDN, TrunkedIdentityDomain.NXDN_TYPE_D, CHANNEL_ID));
        assertNull(RadioSystemKey.channelScoped(Protocol.NXDN, TrunkedIdentityDomain.STANDARD, CHANNEL_ID));
        assertNull(RadioSystemKey.channelScoped(Protocol.APCO25, TrunkedIdentityDomain.STANDARD, CHANNEL_ID));
        assertNull(RadioSystemKey.channelScoped(Protocol.NBFM, TrunkedIdentityDomain.STANDARD, CHANNEL_ID));
        assertNull(RadioSystemKey.channelScoped(Protocol.DMR, TrunkedIdentityDomain.STANDARD, "not-a-uuid"));
    }

    @Test
    void rejectsOutOfRangeP25Identities()
    {
        assertNull(RadioSystemKey.p25(-1, 1));
        assertNull(RadioSystemKey.p25(1, 0x1000));
    }
}
