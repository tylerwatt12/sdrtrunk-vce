/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.nxdn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.SiteInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.RepeaterIdle;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.SiteID;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

class NXDNNetworkConfigurationMonitorTest
{
    @Test
    void includesTypeDSiteIdInSummary()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(32);
        message.load(3, 2, 1);
        message.load(8, 5, 7);
        SiteID siteID = new SiteID(message, 0, NXDNMessageType.TYPE_D_SCCH_OUT_INFO_4_SITE_ID, 0,
            LICH.RTCH_2_OUTBOUND_SUPER_VOICE_VOICE);
        NXDNNetworkConfigurationMonitor monitor = new NXDNNetworkConfigurationMonitor();

        monitor.process(siteID);

        assertTrue(monitor.getSummary().contains("SITE:7"));
        NXDNNetworkConfigurationSnapshot snapshot = monitor.getSnapshot();
        assertTrue(snapshot.isUseful());
        assertEquals(Protocol.NXDN, snapshot.protocol());
        assertEquals("TYPE_D", snapshot.variant());
        assertEquals(7, snapshot.typeDSite());
        assertEquals("WIDE", snapshot.typeDSiteType());
    }

    @Test
    void extractsTypeCIdentityAndRan()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(176);
        message.load(10, 10, 341); //Global system
        message.load(20, 12, 837); //Global site
        SiteInformation site = new SiteInformation(message, 1_000,
            NXDNMessageType.CONTROL_OUT_24_BC_SITE_INFORMATION, 12, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL);
        NXDNNetworkConfigurationMonitor monitor = new NXDNNetworkConfigurationMonitor();

        monitor.process(site);
        NXDNNetworkConfigurationSnapshot snapshot = monitor.getSnapshot();

        assertEquals("TYPE_C", snapshot.variant());
        assertEquals(12, snapshot.ran());
        assertEquals("GLOBAL", snapshot.currentLocation().category());
        assertEquals(341, snapshot.currentLocation().system());
        assertEquals(837, snapshot.currentLocation().site());
        assertTrue(snapshot.isUseful());
    }

    @Test
    void extractsTypeDRepeaterInformation()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(32);
        message.load(3, 5, 9);
        message.load(8, 5, 14);
        RepeaterIdle idle = new RepeaterIdle(message, 1_000,
            NXDNMessageType.TYPE_D_SCCH_OUT_INFO_4_REPEATER_IDLE, 0,
            LICH.RTCH_2_OUTBOUND_SUPER_VOICE_VOICE);
        NXDNNetworkConfigurationMonitor monitor = new NXDNNetworkConfigurationMonitor();

        monitor.process(idle);
        NXDNNetworkConfigurationSnapshot snapshot = monitor.getSnapshot();

        assertEquals("TYPE_D", snapshot.variant());
        assertEquals(9, snapshot.currentRepeater());
        assertEquals("IDLE", snapshot.repeaterStatus());
        assertEquals(java.util.List.of(14), snapshot.observedRepeaters());
        assertEquals(1_000L, snapshot.observedRepeaterTimestamp(14));

        RepeaterIdle refreshed = new RepeaterIdle(message, 3_000,
            NXDNMessageType.TYPE_D_SCCH_OUT_INFO_4_REPEATER_IDLE, 0,
            LICH.RTCH_2_OUTBOUND_SUPER_VOICE_VOICE);
        monitor.process(refreshed);
        NXDNNetworkConfigurationSnapshot refreshedSnapshot = monitor.getSnapshot();
        assertEquals(3_000L, refreshedSnapshot.observedRepeaterTimestamp(14));
        assertEquals(snapshot, refreshedSnapshot);
        assertNotEquals(snapshot.toString(), refreshedSnapshot.toString());
    }
}
