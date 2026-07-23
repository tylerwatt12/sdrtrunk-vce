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
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.UnknownDataMessage;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer2.SACCHFragment;
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

    @Test
    void countsOneDmrDataBurstWithSlotTypeAndDmrSyncLossNormalization()
    {
        DecodeConfigDMR config = new DecodeConfigDMR();
        Channel channel = channel(config);
        List<ControlChannelQualitySnapshot> snapshots = new ArrayList<>();
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 452_012_500L, snapshots::add);
        monitor.start();

        monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -15.0));
        monitor.getMessageListener().receive(dmr(1_000L, true, true, 2));
        monitor.getMessageListener().receive(dmr(1_030L, false, false, 4));
        monitor.getMessageListener().receive(new SyncLossMessage(1_060L, 288, Protocol.DMR));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);

        ControlChannelQualitySnapshot snapshot = snapshots.getLast();
        assertEquals(1, snapshot.validFrames());
        assertEquals(1, snapshot.invalidFrames());
        assertEquals(6, snapshot.correctedBits());
        assertEquals(288, snapshot.syncLossBits());
        assertEquals(-15.0, snapshot.signalDbfs());
        assertEquals(33.333, snapshot.decodeHealthPercent(), 0.001);
        assertEquals(1_000L, snapshot.lastValidDecodeMs());
    }

    @Test
    void honorsDmrIgnoreCrcButStillRequiresAValidSlotType()
    {
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setIgnoreCRCChecksums(true);
        Channel channel = channel(config);
        List<ControlChannelQualitySnapshot> snapshots = new ArrayList<>();
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 452_012_500L, snapshots::add);
        monitor.start();

        UnknownDataMessage ignoredCrc = dmr(2_000L, false, true, 0);
        monitor.getMessageListener().receive(ignoredCrc);
        monitor.getMessageListener().receive(dmr(2_030L, false, false, 0, false));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);

        ControlChannelQualitySnapshot snapshot = snapshots.getLast();
        assertEquals(1, snapshot.validFrames());
        assertEquals(1, snapshot.invalidFrames());
        assertEquals(50.0, snapshot.decodeHealthPercent());
    }

    @Test
    void countsOneNxdnRfFrameCarrierForRcchAndTypeDAndNormalizesSyncLoss()
    {
        Channel channel = channel(new DecodeConfigNXDN());
        List<ControlChannelQualitySnapshot> snapshots = new ArrayList<>();
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 451_012_500L, snapshots::add);
        monitor.start();

        monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -30.0));
        SACCHFragment rcch = nxdn(3_000L, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL, true, 3);
        SACCHFragment sameRcchFrame = nxdn(3_000L, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL, true, 7);
        SACCHFragment typeD = nxdn(3_040L, LICH.RTCH_2_OUTBOUND_SINGLE_FACCH1_FACCH1, false, 5);
        rcch.setRfFrameQuality(true, 3);
        typeD.setRfFrameQuality(false, 5);

        monitor.getMessageListener().receive(rcch);
        monitor.getMessageListener().receive(sameRcchFrame);
        monitor.getMessageListener().receive(typeD);
        monitor.getMessageListener().receive(new SyncLossMessage(3_080L, 384, Protocol.NXDN));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);

        ControlChannelQualitySnapshot snapshot = snapshots.getLast();
        assertEquals(1, snapshot.validFrames());
        assertEquals(1, snapshot.invalidFrames());
        assertEquals(8, snapshot.correctedBits());
        assertEquals(384, snapshot.syncLossBits());
        assertEquals(-30.0, snapshot.signalDbfs());
        assertEquals(33.333, snapshot.decodeHealthPercent(), 0.001);
        assertEquals(3_000L, snapshot.lastValidDecodeMs());
    }

    @Test
    void clearsDmrWindowOnFrequencyRotation()
    {
        Channel channel = channel(new DecodeConfigDMR());
        List<ControlChannelQualitySnapshot> snapshots = new ArrayList<>();
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 452_012_500L, snapshots::add);
        monitor.start();
        monitor.getMessageListener().receive(dmr(4_000L, true, false, 0));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);

        monitor.getSourceEventListener().receive(
            SourceEvent.frequencyRotationSuccessNotification(null, 453_012_500L));
        assertFalse(snapshots.getLast().active());
        assertEquals(452_012_500L, snapshots.getLast().frequencyHz());

        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        ControlChannelQualitySnapshot rotated = snapshots.getLast();
        assertEquals(453_012_500L, rotated.frequencyHz());
        assertEquals(0, rotated.validFrames());
        assertNull(rotated.decodeHealthPercent());
    }

    private static MacMessage mac(long timestamp, int timeslot, boolean valid, int correctedBits)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(320);
        bits.setCorrectedBitCount(correctedBits);
        MacMessage message = new MacMessage(timeslot, DataUnitID.UNSCRAMBLED_LCCH, bits, timestamp, null);
        message.setValid(valid);
        return message;
    }

    private static Channel channel(io.github.dsheirer.module.decode.config.DecodeConfiguration config)
    {
        Channel channel = new Channel("Test Site", ChannelType.STANDARD);
        channel.setRadresGuid(GUID);
        channel.setDecodeConfiguration(config);
        return channel;
    }

    private static UnknownDataMessage dmr(long timestamp, boolean valid, boolean ras, int correctedBits)
    {
        return dmr(timestamp, valid, ras, correctedBits, true);
    }

    private static UnknownDataMessage dmr(long timestamp, boolean valid, boolean ras, int correctedBits,
                                          boolean validSlotType)
    {
        CorrectedBinaryMessage slotBits = new CorrectedBinaryMessage(288);
        SlotType slotType;

        if(validSlotType)
        {
            slotType = SlotType.getSlotType(slotBits);
        }
        else
        {
            slotType = new SlotType(new CorrectedBinaryMessage(24))
            {
                @Override
                public boolean isValid()
                {
                    return false;
                }
            };
        }

        CorrectedBinaryMessage payload = new CorrectedBinaryMessage(99);
        payload.setCorrectedBitCount(correctedBits);

        if(ras)
        {
            payload.set(96);
        }

        UnknownDataMessage message = new UnknownDataMessage(DMRSyncPattern.BASE_STATION_DATA, payload, null,
            slotType, timestamp, 1);
        message.setValid(valid);
        return message;
    }

    private static SACCHFragment nxdn(long timestamp, LICH lich, boolean valid, int correctedBits)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(26);
        bits.setCorrectedBitCount(correctedBits);
        SACCHFragment message = new SACCHFragment(bits, timestamp, lich);
        message.setValid(valid);
        return message;
    }
}
