/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StatsApiProtocolTest
{
    @Test
    void mapsDatabaseCodesAndDecoderNamesToStableWireProtocols()
    {
        assertAll(
            () -> assertEquals(StatsApiProtocol.P25, StatsApiProtocol.fromCode(1)),
            () -> assertEquals(StatsApiProtocol.P25, StatsApiProtocol.fromCode(2)),
            () -> assertEquals(StatsApiProtocol.DMR, StatsApiProtocol.fromCode(3)),
            () -> assertEquals(StatsApiProtocol.NXDN, StatsApiProtocol.fromCode(4)),
            () -> assertEquals(StatsApiProtocol.NBFM, StatsApiProtocol.fromCode(10)),
            () -> assertEquals(StatsApiProtocol.UNKNOWN, StatsApiProtocol.fromCode(999)),
            () -> assertEquals(StatsApiProtocol.P25, StatsApiProtocol.fromName("APCO25_PHASE2")),
            () -> assertEquals(StatsApiProtocol.P25, StatsApiProtocol.fromName("p25_phase_1")),
            () -> assertEquals(StatsApiProtocol.DMR, StatsApiProtocol.fromName("dmr")),
            () -> assertEquals(StatsApiProtocol.NXDN, StatsApiProtocol.fromName(" NXDN ")),
            () -> assertEquals(StatsApiProtocol.NBFM, StatsApiProtocol.fromName("nbfm")),
            () -> assertEquals(StatsApiProtocol.ARS, StatsApiProtocol.fromName("ARS")),
            () -> assertEquals(StatsApiProtocol.CELLOCATOR, StatsApiProtocol.fromName("Cellocator")),
            () -> assertEquals(StatsApiProtocol.DCS, StatsApiProtocol.fromName("DCS")),
            () -> assertEquals(StatsApiProtocol.FLEETSYNC, StatsApiProtocol.fromName("FleetSync")),
            () -> assertEquals(StatsApiProtocol.IPV4, StatsApiProtocol.fromName("IPv4")),
            () -> assertEquals(StatsApiProtocol.LOJACK, StatsApiProtocol.fromName("LoJack")),
            () -> assertEquals(StatsApiProtocol.LRRP, StatsApiProtocol.fromName("LRRP")),
            () -> assertEquals(StatsApiProtocol.MDC1200, StatsApiProtocol.fromName("MDC-1200")),
            () -> assertEquals(StatsApiProtocol.TAIT1200, StatsApiProtocol.fromName("Tait-1200")),
            () -> assertEquals(StatsApiProtocol.UDP, StatsApiProtocol.fromName("UDP")),
            () -> assertEquals(StatsApiProtocol.UNKNOWN, StatsApiProtocol.fromName(null)));

        assertAll(
            () -> assertEquals("p25", StatsApiProtocol.P25.wireName()),
            () -> assertEquals("dmr", StatsApiProtocol.DMR.wireName()),
            () -> assertEquals("nxdn", StatsApiProtocol.NXDN.wireName()),
            () -> assertEquals("nbfm", StatsApiProtocol.NBFM.wireName()));
    }

    @Test
    void mapsSharedAndProtocolSpecificSemantics()
    {
        assertAll(
            () -> assertEquals("linked_system", StatsApiProtocol.P25.scopeKind(1)),
            () -> assertEquals("receiver_context", StatsApiProtocol.DMR.scopeKind(2)),
            () -> assertEquals("unknown", StatsApiProtocol.NXDN.scopeKind(0)),
            () -> assertEquals("standard", StatsApiProtocol.P25.addressDomain(2)),
            () -> assertEquals("standard", StatsApiProtocol.NXDN.addressDomain(0)),
            () -> assertEquals("nxdn_type_c", StatsApiProtocol.NXDN.addressDomain(1)),
            () -> assertEquals("nxdn_type_d", StatsApiProtocol.NXDN.addressDomain(2)));

        assertAll(
            () -> assertEquals("tier_iii", StatsApiProtocol.DMR.variant(1)),
            () -> assertEquals("connect_plus", StatsApiProtocol.DMR.variant(2)),
            () -> assertEquals("capacity_max", StatsApiProtocol.DMR.variant(3)),
            () -> assertEquals("hytera_tier_iii", StatsApiProtocol.DMR.variant(4)),
            () -> assertEquals("capacity_plus", StatsApiProtocol.DMR.variant(5)),
            () -> assertEquals("type_c", StatsApiProtocol.NXDN.variant(1)),
            () -> assertEquals("type_d", StatsApiProtocol.NXDN.variant(2)),
            () -> assertEquals("unknown", StatsApiProtocol.P25.variant(1)),
            () -> assertEquals("unknown", StatsApiProtocol.DMR.variant(99)),
            () -> assertEquals("unknown", StatsApiProtocol.DMR.variant("decoder_private_mode")),
            () -> assertEquals("phase_1", StatsApiProtocol.P25.variant("P25-1")),
            () -> assertEquals("phase_2", StatsApiProtocol.P25.variant("P25_PHASE_2")),
            () -> assertEquals("unknown", StatsApiProtocol.DMR.variant("type_c")));

        assertAll(
            () -> assertEquals("tiny", StatsApiProtocol.DMR.siteClassification(1)),
            () -> assertEquals("small", StatsApiProtocol.DMR.siteClassification(2)),
            () -> assertEquals("large", StatsApiProtocol.DMR.siteClassification(3)),
            () -> assertEquals("huge", StatsApiProtocol.DMR.siteClassification(4)),
            () -> assertEquals("global", StatsApiProtocol.NXDN.siteClassification(1)),
            () -> assertEquals("regional", StatsApiProtocol.NXDN.siteClassification(2)),
            () -> assertEquals("local", StatsApiProtocol.NXDN.siteClassification(3)),
            () -> assertEquals("type_d", StatsApiProtocol.NXDN.siteClassification(4)),
            () -> assertEquals("reserved", StatsApiProtocol.NXDN.siteClassification(5)),
            () -> assertEquals("unknown", StatsApiProtocol.P25.siteClassification(1)));

        assertAll(
            () -> assertEquals("motorola_connect_plus", StatsApiProtocol.DMR.brand(2)),
            () -> assertEquals("open_system", StatsApiProtocol.DMR.operatingMode(1)),
            () -> assertEquals("halted_cwid", StatsApiProtocol.NXDN.operatingMode(3)),
            () -> assertEquals("traffic", StatsApiProtocol.DMR.channelType(2)),
            () -> assertEquals(java.util.List.of("multi_site", "voice_call"),
                StatsApiProtocol.NXDN.services(0x8200)));
    }

    @Test
    void exposesOneProtocolNeutralCapabilityMatrix()
    {
        Map<String,Boolean> p25System = StatsApiProtocol.P25.systemCapabilities();
        assertAll(
            () -> assertTrue(p25System.get("sites")),
            () -> assertTrue(p25System.get("group_identities")),
            () -> assertTrue(p25System.get("radios")),
            () -> assertTrue(p25System.get("activity")),
            () -> assertTrue(p25System.get("talker_aliases")),
            () -> assertTrue(p25System.get("current_affiliations")),
            () -> assertTrue(p25System.get("patch_groups")));

        Map<String,Boolean> dmrSystem = StatsApiProtocol.DMR.systemCapabilities();
        assertAll(
            () -> assertTrue(dmrSystem.get("sites")),
            () -> assertFalse(dmrSystem.get("current_affiliations")),
            () -> assertFalse(dmrSystem.get("patch_groups")));

        Map<String,Boolean> nxdnSite = StatsApiProtocol.NXDN.siteCapabilities();
        assertAll(
            () -> assertTrue(nxdnSite.get("channels")),
            () -> assertTrue(nxdnSite.get("neighbors")),
            () -> assertTrue(nxdnSite.get("quality")),
            () -> assertFalse(nxdnSite.get("frequency_bands")),
            () -> assertFalse(nxdnSite.get("patch_groups")));

        Map<String,Boolean> conventional = StatsApiProtocol.NBFM.siteCapabilities();
        assertTrue(conventional.values().stream().noneMatch(Boolean::booleanValue));
    }
}
