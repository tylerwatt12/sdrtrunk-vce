/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessageFactory;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.SNDCPDataChannelAnnouncement;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.SynchronizationBroadcast;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class P25P2NetworkConfigurationMonitorTest
{
    @Test
    void ignoresSndcpChannelFieldsWhenAutonomousAccessIsClear()
    {
        MacMessage message = sndcpDataAnnouncement(false, true);
        SNDCPDataChannelAnnouncement announcement =
            (SNDCPDataChannelAnnouncement)message.getMacStructure();
        P25P2NetworkConfigurationMonitor monitor = new P25P2NetworkConfigurationMonitor();

        assertFalse(announcement.hasChannel());
        assertTrue(announcement.getChannels().isEmpty());

        P25NetworkConfigurationSnapshot observation = monitor.processMacMessage(message);

        assertTrue(observation.channels().isEmpty());
        assertEquals("Request Only", observation.siteStatus().dataAccess());
    }

    @Test
    void retainsSndcpChannelFieldsWhenAutonomousAccessIsSet()
    {
        MacMessage message = sndcpDataAnnouncement(true, true);
        SNDCPDataChannelAnnouncement announcement =
            (SNDCPDataChannelAnnouncement)message.getMacStructure();
        P25P2NetworkConfigurationMonitor monitor = new P25P2NetworkConfigurationMonitor();

        assertTrue(announcement.hasChannel());
        assertEquals(1, announcement.getChannels().size());

        P25NetworkConfigurationSnapshot observation = monitor.processMacMessage(message);

        assertEquals(1, observation.channels().size());
        assertEquals(851_000_000L, observation.channels().getFirst().downlink());
        assertEquals("Autonomous and by Request", observation.siteStatus().dataAccess());
    }

    @Test
    void emitsOnlyTheStatusFieldsObservedByTheCurrentMessage()
    {
        P25P2NetworkConfigurationMonitor monitor = new P25P2NetworkConfigurationMonitor();
        P25NetworkConfigurationSnapshot.SiteStatus services =
            new P25NetworkConfigurationSnapshot.SiteStatus(null, null, true, null, null, true, null, true);
        P25NetworkConfigurationSnapshot.SiteStatus timing =
            new P25NetworkConfigurationSnapshot.SiteStatus(1234L, 2, null, null, null, null, null, null);

        monitor.statusObservation(services);
        P25NetworkConfigurationSnapshot observation = monitor.statusObservation(timing);

        assertEquals(1234L, observation.siteStatus().broadcastClockEpochMilliseconds());
        assertEquals(2, observation.siteStatus().microSlots());
        assertNull(observation.siteStatus().dataService());
        assertNull(observation.siteStatus().registrationService());
        assertNull(observation.siteStatus().voiceService());

        P25NetworkConfigurationSnapshot current = monitor.getSnapshot();
        assertTrue(current.siteStatus().dataService());
        assertTrue(current.siteStatus().registrationService());
        assertTrue(current.siteStatus().voiceService());
    }

    @Test
    void acceptsUnlockedValidDateAndRejectsInvalidDateWithoutLosingMicroslots()
    {
        MacMessage valid = synchronizationBroadcast(7, 29, 80);
        SynchronizationBroadcast validStructure = (SynchronizationBroadcast)valid.getMacStructure();
        assertTrue(validStructure.hasValidDate());
        assertTrue(validStructure.isSystemTimeNotLockedToExternalReference());
        assertEquals(Instant.parse("2026-07-29T12:34:00.600Z").toEpochMilli(), validStructure.getSystemTime());
        assertTrue(validStructure.toString().contains("MICROSLOT-MINUTE ROLLOVER:LOCKED"));

        assertFalse(((SynchronizationBroadcast)synchronizationBroadcast(0, 29, 81).getMacStructure()).hasValidDate());
        assertFalse(((SynchronizationBroadcast)synchronizationBroadcast(13, 29, 82).getMacStructure()).hasValidDate());
        assertFalse(((SynchronizationBroadcast)synchronizationBroadcast(7, 0, 83).getMacStructure()).hasValidDate());
        assertTrue(((SynchronizationBroadcast)synchronizationBroadcast(12, 31, 84).getMacStructure()).hasValidDate());

        P25P2NetworkConfigurationMonitor monitor = new P25P2NetworkConfigurationMonitor();
        monitor.processMacMessage(valid);
        P25NetworkConfigurationSnapshot invalidObservation =
            monitor.processMacMessage(synchronizationBroadcast(0, 0, 95));

        assertNull(invalidObservation.siteStatus().broadcastClockEpochMilliseconds());
        assertEquals(95, invalidObservation.siteStatus().microSlots());
        assertNull(monitor.getSnapshot().siteStatus().broadcastClockEpochMilliseconds());
        assertEquals(95, monitor.getSnapshot().siteStatus().microSlots());
    }

    private static MacMessage synchronizationBroadcast(int month, int day, int microSlots)
    {
        int offset = MacMessageFactory.DEFAULT_MAC_STRUCTURE_INDEX;
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.setInt(112, IntField.length8(offset));
        message.set(SynchronizationBroadcast.IST_INVALID_SYSTEM_TIME_NOT_LOCKED_TO_EXTERNAL_REFERENCE_FLAG + offset);
        message.setInt(26, IntField.range(32 + offset, 38 + offset));
        message.setInt(month, IntField.range(39 + offset, 42 + offset));
        message.setInt(day, IntField.range(43 + offset, 47 + offset));
        message.setInt(12, IntField.range(48 + offset, 52 + offset));
        message.setInt(34, IntField.range(53 + offset, 58 + offset));
        message.setInt(microSlots, IntField.range(59 + offset, 71 + offset));
        SynchronizationBroadcast structure = new SynchronizationBroadcast(message, offset);
        return new MacMessage(1, DataUnitID.UNSCRAMBLED_LCCH, message, 1_000L, structure);
    }

    private static MacMessage sndcpDataAnnouncement(boolean autonomous, boolean requested)
    {
        int offset = MacMessageFactory.DEFAULT_MAC_STRUCTURE_INDEX;
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.setInt(214, IntField.length8(offset));

        if(autonomous)
        {
            message.set(16 + offset);
        }

        if(requested)
        {
            message.set(17 + offset);
        }

        SNDCPDataChannelAnnouncement structure = new SNDCPDataChannelAnnouncement(message, offset);
        structure.getChannel().setFrequencyBand(new P25FrequencyBand(0, 851_000_000L, -45_000_000L,
            6_250L, 12_500, 1));
        return new MacMessage(1, DataUnitID.UNSCRAMBLED_LCCH, message, 1_000L, structure);
    }
}
