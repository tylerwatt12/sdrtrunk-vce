/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.message.DroppedSamplesMessage;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.SourceEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ControlChannelQualityMonitorTest
{
    private static final String GUID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void publishesRollingSignalAndDecodeHealthAndClearsOnRotation()
    {
        Channel channel = new Channel("Test Site", ChannelType.STANDARD);
        channel.setRadresGuid(GUID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        List<ControlChannelQualitySnapshot> snapshots = new ArrayList<>();
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 856_137_500L, snapshots::add);
        monitor.start();
        monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -10.0));
        monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -20.0));
        monitor.getMessageListener().receive(mac(1_000L, 0, true, 2));
        monitor.getMessageListener().receive(mac(1_001L, 0, false, 4));
        monitor.getMessageListener().receive(new DroppedSamplesMessage(1_002L, 320, Protocol.APCO25_PHASE2));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);

        ControlChannelQualitySnapshot snapshot = snapshots.getLast();
        assertTrue(snapshot.active());
        assertEquals(GUID, snapshot.guid());
        assertEquals(856_137_500L, snapshot.frequencyHz());
        assertEquals(-20.0, snapshot.signalDbfs());
        assertEquals(-12.596, snapshot.averageSignalDbfs(), 0.001);
        assertEquals(-20.0, snapshot.minimumSignalDbfs());
        assertEquals(-10.0, snapshot.maximumSignalDbfs());
        assertEquals(33.333, snapshot.decodeHealthPercent(), 0.001);
        assertEquals(1, snapshot.validFrames());
        assertEquals(1, snapshot.invalidFrames());
        assertEquals(6, snapshot.correctedBits());
        assertEquals(320, snapshot.droppedBits());
        assertEquals(1_000L, snapshot.lastValidDecodeMs());

        monitor.getSourceEventListener().receive(SourceEvent.frequencyChange(null, 855_137_500L));
        ControlChannelQualitySnapshot inactive = snapshots.getLast();
        assertFalse(inactive.active());
        assertEquals(856_137_500L, inactive.frequencyHz());

        monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -30.0));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        ControlChannelQualitySnapshot rotated = snapshots.getLast();
        assertTrue(rotated.active());
        assertEquals(855_137_500L, rotated.frequencyHz());
        assertEquals(-30.0, rotated.averageSignalDbfs());
        assertNull(rotated.decodeHealthPercent());
        assertEquals(0, rotated.validFrames());
        monitor.stop();
        assertFalse(snapshots.getLast().active());
    }

    private static MacMessage mac(long timestamp, int timeslot, boolean valid, int correctedBits)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(320);
        bits.setCorrectedBitCount(correctedBits);
        MacMessage message = new MacMessage(timeslot, DataUnitID.UNSCRAMBLED_LCCH, bits, timestamp, null);
        message.setValid(valid);
        return message;
    }
}
