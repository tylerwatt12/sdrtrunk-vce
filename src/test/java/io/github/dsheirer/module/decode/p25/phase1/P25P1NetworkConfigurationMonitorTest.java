/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SNDCPDataChannelAnnouncementExplicit;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import org.junit.jupiter.api.Test;

class P25P1NetworkConfigurationMonitorTest
{
    @Test
    void ignoresSndcpChannelFieldsWhenAutonomousAccessIsClear()
    {
        SNDCPDataChannelAnnouncementExplicit announcement = sndcpDataAnnouncement(false, true);
        P25P1NetworkConfigurationMonitor monitor = new P25P1NetworkConfigurationMonitor(Modulation.C4FM);

        assertFalse(announcement.hasChannel());
        assertTrue(announcement.getChannels().isEmpty());

        P25NetworkConfigurationSnapshot observation = monitor.process(announcement);

        assertTrue(observation.channels().isEmpty());
        assertEquals("Request Only", observation.siteStatus().dataAccess());
    }

    @Test
    void retainsSndcpChannelFieldsWhenAutonomousAccessIsSet()
    {
        SNDCPDataChannelAnnouncementExplicit announcement = sndcpDataAnnouncement(true, true);
        P25P1NetworkConfigurationMonitor monitor = new P25P1NetworkConfigurationMonitor(Modulation.C4FM);

        assertTrue(announcement.hasChannel());
        assertEquals(1, announcement.getChannels().size());

        P25NetworkConfigurationSnapshot observation = monitor.process(announcement);

        assertEquals(1, observation.channels().size());
        assertEquals(851_000_000L, observation.channels().getFirst().downlink());
        assertEquals("Autonomous and by Request", observation.siteStatus().dataAccess());
    }

    private static SNDCPDataChannelAnnouncementExplicit sndcpDataAnnouncement(boolean autonomous,
                                                                               boolean requested)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.setInt(22, IntField.length6(2));

        if(autonomous)
        {
            message.set(24);
        }

        if(requested)
        {
            message.set(25);
        }

        SNDCPDataChannelAnnouncementExplicit announcement = new SNDCPDataChannelAnnouncementExplicit(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, 0x123, 1_000L);
        ((APCO25Channel)announcement.getChannel()).setFrequencyBand(new P25FrequencyBand(0, 851_000_000L,
            -45_000_000L, 6_250L, 12_500, 1));
        return announcement;
    }
}
