/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ControlChannelSystemParameters;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

class DMRNetworkConfigurationMonitorTest
{
    @Test
    void extractsTierThreeIdentityIntoImmutableSnapshot()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(32);
        bits.load(0, 4, 2);       //Short-LC control channel system parameters opcode
        bits.load(6, 9, 257);     //Tiny-model network
        bits.load(15, 3, 5);      //Tiny-model site
        ControlChannelSystemParameters parameters = new ControlChannelSystemParameters(bits, 1_000, 1);
        DMRNetworkConfigurationMonitor monitor = new DMRNetworkConfigurationMonitor();

        monitor.process(parameters);
        DMRNetworkConfigurationSnapshot snapshot = monitor.getSnapshot();

        assertTrue(snapshot.isUseful());
        assertEquals(Protocol.DMR, snapshot.protocol());
        assertEquals("DMR", snapshot.decoder());
        assertEquals("TIER_III", snapshot.variant());
        assertEquals(257, snapshot.network());
        assertEquals(5, snapshot.site());
        assertEquals("TINY", snapshot.model());
        assertEquals("Control", snapshot.channelType());
        assertEquals("Tier III Trunking", snapshot.brand());
    }

    @Test
    void colorCodeAloneDoesNotClaimTrunkedSiteMetadataIsUseful()
    {
        DMRNetworkConfigurationSnapshot snapshot = new DMRNetworkConfigurationSnapshot("DMR", null,
            null, null, null, null, null, null, 1, 1, null, null);

        assertFalse(snapshot.isUseful());
        assertEquals(0, snapshot.channels().size());
        assertEquals(0, snapshot.neighborSites().size());
    }
}
