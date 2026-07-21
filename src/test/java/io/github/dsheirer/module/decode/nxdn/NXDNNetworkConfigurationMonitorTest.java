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

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.SiteID;
import org.junit.jupiter.api.Test;

class NXDNNetworkConfigurationMonitorTest
{
    @Test
    void includesTypeDSiteIdInSummary()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(32);
        message.load(8, 5, 7);
        SiteID siteID = new SiteID(message, 0, NXDNMessageType.TYPE_D_SCCH_OUT_INFO_4_SITE_ID, 0,
            LICH.RTCH_2_OUTBOUND_SUPER_VOICE_VOICE);
        NXDNNetworkConfigurationMonitor monitor = new NXDNNetworkConfigurationMonitor();

        monitor.process(siteID);

        assertTrue(monitor.getSummary().contains("SITE:7"));
    }
}
