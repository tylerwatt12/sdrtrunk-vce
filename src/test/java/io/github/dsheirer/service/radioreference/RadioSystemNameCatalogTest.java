/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.service.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RadioSystemNameCatalogTest
{
    @Test
    void usesBothP25HexadecimalIdentityFieldsAndPreservesCombinedSourceNames()
    {
        RadioSystemNameCatalog catalog = RadioSystemNameCatalog.parse("""
            ; DSD+ networks file
            P25, abcde.123, "First County / Second County" ; Multiple systems with same System ID
            P25, 00001.123, "Different Network"
            P25, ABCDE.124, "A, B and C; Communications"
            """);
        assertEquals("First County / Second County", catalog.nameFor("p25:abcde:123"));
        assertEquals("Different Network", catalog.nameFor("p25:00001:123"));
        assertEquals("A, B and C; Communications", catalog.nameFor("p25:abcde:124"));
        for(String key: List.of("123", "p25:ABCDE:123", "p25:abcde:0123", "p25:00002:123", "p25:abcde:125"))
            assertNull(catalog.nameFor(key));
        assertNull(catalog.nameFor(null));
    }

    @Test
    void keepsEveryDistinctDuplicateLabelWithoutRequiringAnIdentityChoice()
    {
        RadioSystemNameCatalog catalog = RadioSystemNameCatalog.parse("""
            P25, 12345.001, "Regional Safety"
            P25, 12345.001, "Regional Safety"
            P25, 12345.001, "Federal Site"
            P25, 12345.001, "Malformed quotation
            P25, 12345.002, "Other System"
            """);
        assertEquals("Regional Safety / Federal Site", catalog.nameFor("p25:12345:001"));
        assertEquals(Set.of("p25:12345:001"), catalog.matchingKeys("FEDERAL"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.matchingKeys("regional").clear());
        assertTrue(catalog.matchingKeys("unknown").isEmpty());
        assertTrue(catalog.matchingKeys(" ").isEmpty());
        assertTrue(catalog.matchingKeys(null).isEmpty());
    }

    @Test
    void mapsStandardTierThreeDisplayNumbersToRawNetworkBitsAndChecksEveryModelWidth()
    {
        RadioSystemNameCatalog catalog = RadioSystemNameCatalog.parse("""
            TIIIStd, T1, "Tiny First"
            TIIIStd, T512, "Tiny Last", 3
            TIIIStd, S128, "Small Last", 0 ; optional area length
            TIIIStd, L16, "Large Last"
            TIIIStd, H4, "Huge Last"
            TIIIStd, T513, "Invalid Tiny"
            TIIIStd, S129, "Invalid Small"
            TIIIStd, L17, "Invalid Large"
            TIIIStd, H5, "Invalid Huge"
            TIIIStd, L0, "Invalid Zero"
            TIIIStd, L400:1, "Synthetic Prefix"
            TIIInonStd, L1, "Other Addressing Convention"
            """);
        assertEquals("Tiny First", catalog.nameFor("dmr:tier3:tiny:0"));
        assertEquals("Tiny Last", catalog.nameFor("dmr:tier3:tiny:511"));
        assertEquals("Small Last", catalog.nameFor("dmr:tier3:small:127"));
        assertEquals("Large Last", catalog.nameFor("dmr:tier3:large:15"));
        assertEquals("Huge Last", catalog.nameFor("dmr:tier3:huge:3"));
        assertNull(catalog.nameFor("dmr:tier3:large:0"));
        assertNull(catalog.nameFor("dmr:tier3:tiny:512"));
    }

    @Test
    void neverTurnsCatalogIdsPrefixesOrIncompleteProtocolScopesIntoNativeIdentity()
    {
        RadioSystemNameCatalog catalog = RadioSystemNameCatalog.parse("""
            Cap+, 123, "Synthetic RRDB ID"
            IDAS, 5:1.1, "Synthetic IDAS Prefix"
            Con+, 123, "Connect Plus Channel Scope"
            NEXEDGE48, 123, "No Location Category"
            NEXEDGE96, 123, "Different Baud Family"
            DMR, 123, "Conventional DMR"
            P25, 123, "No WACN"
            P25, 12345:001, "Wrong Separator"
            P25, 1:12345.001, "Prefixed P25"
            """);
        for(String key: List.of("p25:12345:001", "dmr:tier3:small:123", "nxdn-c:global:123",
            "nxdn-c:regional:123", "nxdn-c:local:123", "dmr:channel:00000000-0000-0000-0000-000000000001"))
            assertNull(catalog.nameFor(key));
        assertTrue(catalog.matchingKeys("synthetic").isEmpty());
    }

    @Test
    void isolatesMalformedQuotesMarkupControlCharactersAndExtraColumns()
    {
        RadioSystemNameCatalog catalog = RadioSystemNameCatalog.parse("\uFEFF" + """
            P25, 12345.001, "Good ""quoted"" name"\u00a0; public comment
            P25, 12345.002, "Missing close
            P25, 12345.003, "Smart close”
            P25, 12345.004, "<script>Bad</script>"
            P25, 12345.005, "Control\u0000Name"
            P25, 12345.006, "Good" extra text
            P25, 12345.007, "Extra column", 5
            P25, 12345.008, "Good afterwards"
            """);
        assertEquals("Good \"quoted\" name", catalog.nameFor("p25:12345:001"));
        assertEquals("Good afterwards", catalog.nameFor("p25:12345:008"));
        for(int system = 2; system < 8; system++) assertNull(catalog.nameFor("p25:12345:00" + system));
        assertTrue(RadioSystemNameCatalog.parse("<html>authentication challenge</html>").matchingKeys("html").isEmpty());
    }

    @Test
    void boundsInputAndNeverTruncatesOneOfSeveralPossibleLabels()
    {
        String first = "A".repeat(150);
        String second = "B".repeat(150);
        RadioSystemNameCatalog catalog = RadioSystemNameCatalog.parse(
            "P25, 12345.001, \"" + first + "\"\nP25, 12345.001, \"" + second + "\"\n" +
                "P25, 12345.001, \"Short subset\"\nP25, 12345.002, \"" + "x".repeat(257) + "\"\n");
        assertNull(catalog.nameFor("p25:12345:001"));
        assertNull(catalog.nameFor("p25:12345:002"));
        assertThrows(IllegalArgumentException.class, () -> RadioSystemNameCatalog.parse("x".repeat(2 * 1024 * 1024 + 1)));
        assertThrows(IllegalArgumentException.class, () -> RadioSystemNameCatalog.parse("\n".repeat(50_000)));
        assertThrows(IllegalArgumentException.class, () -> RadioSystemNameCatalog.parse(null));
    }

    @Test
    void bundledSnapshotIsOneImmutableLookup()
    {
        assertSame(RadioSystemNameCatalog.bundled(), RadioSystemNameCatalog.bundled());
    }
}
