/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import org.junit.jupiter.api.Test;

class RadioSystemKeyTest
{
    private static final String CHANNEL_ID = "728d2d66-de4e-476b-a696-919f32dd4d12";

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
        assertNull(RadioSystemKey.p25((P25SiteIdentity)null));
        assertNull(RadioSystemKey.p25(-1, 1));
        assertNull(RadioSystemKey.p25(0x100000, 1));
        assertNull(RadioSystemKey.p25(1, -1));
        assertNull(RadioSystemKey.p25(1, 0x1000));
    }

    @Test
    void createsNativeDmrTierThreeKeysAtEachModelBoundary()
    {
        assertEquals("dmr:tier3:tiny:0", RadioSystemKey.dmrTier3("TINY", 0));
        assertEquals("dmr:tier3:tiny:511", RadioSystemKey.dmrTier3("tiny", 511));
        assertEquals("dmr:tier3:small:0", RadioSystemKey.dmrTier3("SMALL", 0));
        assertEquals("dmr:tier3:small:127", RadioSystemKey.dmrTier3("SMALL", 127));
        assertEquals("dmr:tier3:large:0", RadioSystemKey.dmrTier3("LARGE", 0));
        assertEquals("dmr:tier3:large:15", RadioSystemKey.dmrTier3("LARGE", 15));
        assertEquals("dmr:tier3:huge:0", RadioSystemKey.dmrTier3("HUGE", 0));
        assertEquals("dmr:tier3:huge:3", RadioSystemKey.dmrTier3("HUGE", 3));

        assertNull(RadioSystemKey.dmrTier3(null, 1));
        assertNull(RadioSystemKey.dmrTier3("", 1));
        assertNull(RadioSystemKey.dmrTier3(" TINY", 1));
        assertNull(RadioSystemKey.dmrTier3("UNKNOWN", 1));
        assertNull(RadioSystemKey.dmrTier3("TINY", null));
        assertNull(RadioSystemKey.dmrTier3("TINY", -1));
        assertNull(RadioSystemKey.dmrTier3("TINY", 512));
        assertNull(RadioSystemKey.dmrTier3("SMALL", 128));
        assertNull(RadioSystemKey.dmrTier3("LARGE", 16));
        assertNull(RadioSystemKey.dmrTier3("HUGE", 4));
    }

    @Test
    void createsNativeNxdnTypeCKeysAtEachCategoryBoundary()
    {
        assertEquals("nxdn-c:global:1", RadioSystemKey.nxdnTypeC("GLOBAL", 1));
        assertEquals("nxdn-c:global:1022", RadioSystemKey.nxdnTypeC("global", 1022));
        assertEquals("nxdn-c:regional:1", RadioSystemKey.nxdnTypeC("REGIONAL", 1));
        assertEquals("nxdn-c:regional:16382", RadioSystemKey.nxdnTypeC("REGIONAL", 16382));
        assertEquals("nxdn-c:local:1", RadioSystemKey.nxdnTypeC("LOCAL", 1));
        assertEquals("nxdn-c:local:131070", RadioSystemKey.nxdnTypeC("LOCAL", 131070));

        assertNull(RadioSystemKey.nxdnTypeC(null, 1));
        assertNull(RadioSystemKey.nxdnTypeC("", 1));
        assertNull(RadioSystemKey.nxdnTypeC("GLOBAL ", 1));
        assertNull(RadioSystemKey.nxdnTypeC("TYPE_D", 1));
        assertNull(RadioSystemKey.nxdnTypeC("GLOBAL", null));
        assertNull(RadioSystemKey.nxdnTypeC("GLOBAL", 0));
        assertNull(RadioSystemKey.nxdnTypeC("GLOBAL", 1023));
        assertNull(RadioSystemKey.nxdnTypeC("REGIONAL", 16383));
        assertNull(RadioSystemKey.nxdnTypeC("LOCAL", 131071));
    }

    @Test
    void validatesNativeAndCapturedFallbackKeysAgainstTheReceiver()
    {
        String dmrFallback = "dmr:channel:" + CHANNEL_ID;
        String nxdnFallback = "nxdn-c:channel:" + CHANNEL_ID;

        assertEquals("dmr:tier3:small:42", RadioSystemKey.nativeFor(Protocol.DMR,
            TrunkedIdentityDomain.STANDARD, "dmr:tier3:small:42"));
        assertEquals("nxdn-c:local:303", RadioSystemKey.nativeFor(Protocol.NXDN,
            TrunkedIdentityDomain.NXDN_TYPE_C, "nxdn-c:local:303"));
        assertEquals(dmrFallback, RadioSystemKey.effectiveForReceiver(Protocol.DMR,
            TrunkedIdentityDomain.STANDARD, CHANNEL_ID, null));
        assertEquals(dmrFallback, RadioSystemKey.validateForReceiver(Protocol.DMR,
            TrunkedIdentityDomain.STANDARD, CHANNEL_ID, dmrFallback));
        assertEquals(nxdnFallback, RadioSystemKey.validateForReceiver(Protocol.NXDN,
            TrunkedIdentityDomain.NXDN_TYPE_C, CHANNEL_ID, nxdnFallback));

        assertThrows(IllegalArgumentException.class, () -> RadioSystemKey.nativeFor(Protocol.DMR,
            TrunkedIdentityDomain.STANDARD, dmrFallback));
        assertThrows(IllegalArgumentException.class, () -> RadioSystemKey.validateForReceiver(Protocol.DMR,
            TrunkedIdentityDomain.STANDARD, CHANNEL_ID, nxdnFallback));
        assertThrows(IllegalArgumentException.class, () -> RadioSystemKey.validateForReceiver(Protocol.NXDN,
            TrunkedIdentityDomain.NXDN_TYPE_D, CHANNEL_ID, "nxdn-c:local:303"));
    }

    @Test
    void parsesEveryExactCanonicalForm()
    {
        for(String key: List.of(
            "p25:00000:000",
            "p25:fffff:fff",
            "dmr:channel:" + CHANNEL_ID,
            "dmr:tier3:tiny:0",
            "dmr:tier3:tiny:511",
            "dmr:tier3:small:127",
            "dmr:tier3:large:15",
            "dmr:tier3:huge:3",
            "nxdn-c:global:1",
            "nxdn-c:global:1022",
            "nxdn-c:regional:16382",
            "nxdn-c:local:131070",
            "nxdn-c:channel:" + CHANNEL_ID,
            "nxdn-d:channel:" + CHANNEL_ID))
        {
            assertEquals(key, RadioSystemKey.parse(key));
            assertTrue(RadioSystemKey.isCanonical(key));
        }
    }

    @Test
    void rejectsEveryAlternateOrOutOfRangeKeySpelling()
    {
        String upperUuid = CHANNEL_ID.toUpperCase();
        String[] invalid = {
            null,
            "",
            " ",
            " p25:bee00:49f",
            "p25:bee00:49f ",
            "p25:bee00:49f\n",
            "p25:BEE00:49f",
            "p25:bee00:49F",
            "p25:bee0:49f",
            "p25:0bee00:49f",
            "p25:bee00:049f",
            "p25:100000:000",
            "p25:00000:1000",
            "p25:beeg0:49f",
            "p25:bee00:-01",
            "p25:bee00:49f:extra",
            "p25:channel:" + CHANNEL_ID,
            "dmr:channel:" + upperUuid,
            "dmr:channel:1-1-1-1-1",
            "dmr:channel:not-a-uuid",
            "dmr:tier3:TINY:1",
            "dmr:tier3:unknown:1",
            "dmr:tier3:tiny:-1",
            "dmr:tier3:tiny:+1",
            "dmr:tier3:tiny:01",
            "dmr:tier3:tiny:1.0",
            "dmr:tier3:tiny:1e0",
            "dmr:tier3:tiny:0x1",
            "dmr:tier3:tiny:999999999999999999999999999999999999",
            "dmr:tier3:tiny:512",
            "dmr:tier3:small:128",
            "dmr:tier3:large:16",
            "dmr:tier3:huge:4",
            "nxdn-c:GLOBAL:1",
            "nxdn-c:wide:1",
            "nxdn-c:global:0",
            "nxdn-c:global:-1",
            "nxdn-c:global:+1",
            "nxdn-c:global:01",
            "nxdn-c:global:1.0",
            "nxdn-c:local:999999999999999999999999999999999999",
            "nxdn-c:global:1023",
            "nxdn-c:regional:16383",
            "nxdn-c:local:131071",
            "nxdn-d:global:1",
            "nxdn-c:channel:" + upperUuid,
            "nxdn-d:channel:" + upperUuid,
            "nxdn:channel:" + CHANNEL_ID,
            "unknown:channel:" + CHANNEL_ID
        };

        for(String key: invalid)
        {
            assertFalse(RadioSystemKey.isCanonical(key), String.valueOf(key));
            assertThrows(IllegalArgumentException.class, () -> RadioSystemKey.parse(key), String.valueOf(key));
        }
    }
}
