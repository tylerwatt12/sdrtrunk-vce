/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCSecondaryControlChannelBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCSecondaryControlChannelBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SNDCPDataChannelAnnouncementExplicit;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SecondaryControlChannelBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SecondaryControlChannelBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationStabilizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P1NetworkConfigurationMonitorTest
{
    @Test
    void acceptsSecondaryControlOnlyForTheStabilizedSite()
    {
        P25NetworkConfigurationStabilizer stabilizer = stabilizedSite();
        P25P1NetworkConfigurationMonitor monitor = new P25P1NetworkConfigurationMonitor(Modulation.C4FM,
            stabilizer);

        P25NetworkConfigurationSnapshot accepted = monitor.process(secondaryControl(2, 7, 4));
        P25NetworkConfigurationSnapshot rejected = monitor.process(secondaryControl(2, 8, 4));

        assertNotNull(accepted);
        assertEquals(770_606_250L, accepted.channels().getFirst().downlink());
        assertNull(rejected);
    }

    @Test
    void ignoresZeroServiceSecondaryControlSlotsAcrossPhaseOneFormats()
    {
        SecondaryControlChannelBroadcast implicit = secondaryControl(2, 7, 0);
        assertTrue(implicit.getChannels().isEmpty());
        assertNull(new P25P1NetworkConfigurationMonitor(Modulation.C4FM, stabilizedSite()).process(implicit));

        CorrectedBinaryMessage tsbkExplicit = new CorrectedBinaryMessage(96);
        SecondaryControlChannelBroadcastExplicit explicit = new SecondaryControlChannelBroadcastExplicit(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, tsbkExplicit, 0x346, 1_000L);
        assertTrue(explicit.getChannels().isEmpty());
        tsbkExplicit.setInt(4, IntField.length8(72));
        assertEquals(1, explicit.getChannels().size());

        assertTrue(new LCSecondaryControlChannelBroadcast(new CorrectedBinaryMessage(72)).getChannels().isEmpty());
        CorrectedBinaryMessage linkControlExplicit = new CorrectedBinaryMessage(64);
        LCSecondaryControlChannelBroadcastExplicit lcExplicit =
            new LCSecondaryControlChannelBroadcastExplicit(linkControlExplicit);
        assertTrue(lcExplicit.getChannels().isEmpty());
        linkControlExplicit.setInt(4, IntField.length8(56));
        assertEquals(1, lcExplicit.getChannels().size());
    }

    @Test
    void keepsEachValidImplicitSecondaryControlSlot()
    {
        assertEquals(1, secondaryControlSlots(false).getChannels().size());
        assertEquals(2, secondaryControlSlots(true).getChannels().size());
        assertEquals(1, linkControlSecondaryControlSlots(false).getChannels().size());
        assertEquals(2, linkControlSecondaryControlSlots(true).getChannels().size());
    }

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

    private static SecondaryControlChannelBroadcast secondaryControl(int rfss, int site, int serviceClass)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.setInt(57, IntField.length6(2));
        message.setInt(rfss, IntField.length8(16));
        message.setInt(site, IntField.length8(24));
        message.setInt(97, IntField.length12(36));
        message.setInt(serviceClass, IntField.length8(48));
        SecondaryControlChannelBroadcast broadcast = new SecondaryControlChannelBroadcast(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, 0x346, 1_000L);
        broadcast.getChannels().forEach(channel -> ((APCO25Channel)channel).setFrequencyBand(
            new P25FrequencyBand(0, 770_000_000L, 30_000_000L, 6_250L, 12_500, 1)));
        return broadcast;
    }

    private static SecondaryControlChannelBroadcast secondaryControlSlots(boolean distinctBands)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.setInt(distinctBands ? 4 : 0, IntField.length8(48));
        message.setInt(1, IntField.length4(32));
        message.setInt(97, IntField.length12(36));
        message.setInt(distinctBands ? 2 : 1, IntField.length4(56));
        message.setInt(97, IntField.length12(60));
        message.setInt(4, IntField.length8(72));
        return new SecondaryControlChannelBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
            message, 0x346, 1_000L);
    }

    private static LCSecondaryControlChannelBroadcast linkControlSecondaryControlSlots(boolean distinctBands)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(72);
        message.setInt(distinctBands ? 4 : 0, IntField.length8(40));
        message.setInt(1, IntField.length4(24));
        message.setInt(97, IntField.length12(28));
        message.setInt(distinctBands ? 2 : 1, IntField.length4(48));
        message.setInt(97, IntField.length12(52));
        message.setInt(4, IntField.length8(64));
        return new LCSecondaryControlChannelBroadcast(message);
    }

    private static P25NetworkConfigurationStabilizer stabilizedSite()
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x346, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x348, 0x346, 2, 7, null, true),
            List.of(), List.of(), List.of(), List.of(), List.of()), 1_000L);
        return stabilizer;
    }
}
