/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityPlusNeighbors;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Clear;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.AnnounceChannelFrequency;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.TalkgroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ControlChannelSystemParameters;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ConnectPlusControlChannel;
import io.github.dsheirer.module.decode.dmr.message.data.mbc.MBCContinuationBlock;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import java.util.List;
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

    @Test
    void ignoresOneConflictingNetworkFamilyDecode()
    {
        CorrectedBinaryMessage tierThreeBits = new CorrectedBinaryMessage(32);
        tierThreeBits.load(0, 4, 2);
        tierThreeBits.load(6, 9, 257);
        tierThreeBits.load(15, 3, 5);
        DMRNetworkConfigurationMonitor monitor = new DMRNetworkConfigurationMonitor();
        monitor.process(new ControlChannelSystemParameters(tierThreeBits, 1_000L, 1));

        CorrectedBinaryMessage connectPlusBits = new CorrectedBinaryMessage(32);
        connectPlusBits.load(0, 4, 10);
        connectPlusBits.load(4, 12, 12);
        connectPlusBits.load(16, 8, 34);
        monitor.process(new ConnectPlusControlChannel(connectPlusBits, 2_000L, 1));

        DMRNetworkConfigurationSnapshot snapshot = monitor.getSnapshot();
        assertEquals("TIER_III", snapshot.variant());
        assertEquals(257, snapshot.network());
        assertEquals(5, snapshot.site());
        assertEquals("Tier III Trunking", snapshot.brand());
    }

    @Test
    void tracksBothTimeslotsAndResolvesTierThreeGrantsThroughConfiguredMap()
    {
        TimeslotFrequency mapping = mapping(802, 139_518_750L, 149_518_750L);
        DMRNetworkConfigurationMonitor monitor = new DMRNetworkConfigurationMonitor(List.of(mapping));

        monitor.process(grant(802, 1));
        monitor.process(grant(802, 2));
        monitor.process(clear(802));
        DMRNetworkConfigurationSnapshot snapshot = monitor.getSnapshot();

        assertEquals(2, snapshot.channels().size());
        assertTrue(snapshot.channels().stream().anyMatch(channel ->
            channel.logicalChannelNumber() == 802 && channel.timeslot() == 1 &&
                channel.downlink() == 139_518_750L &&
                channel.roles().contains(DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC) &&
                channel.roles().contains(DMRNetworkConfigurationSnapshot.ChannelRole.CONTROL) &&
                channel.frequencySource() == DMRNetworkConfigurationSnapshot.FrequencySource.CONFIGURED_MAP));
        assertTrue(snapshot.channels().stream().anyMatch(channel ->
            channel.logicalChannelNumber() == 802 && channel.timeslot() == 2 &&
                channel.downlink() == 139_518_750L));
    }

    @Test
    void retainsActualChannelObservationTimeAcrossCumulativeSnapshots()
    {
        DMRNetworkConfigurationMonitor monitor = new DMRNetworkConfigurationMonitor();

        monitor.process(grant(802, 1, 1_000L));
        monitor.process(grant(802, 1, 6_000L));
        monitor.process(grant(802, 1, 11_000L));
        monitor.process(grant(803, 1, 2_000L));
        monitor.process(grant(803, 1, 7_000L));
        monitor.process(grant(803, 1, 12_000L));
        DMRNetworkConfigurationSnapshot first = monitor.getSnapshot();

        assertEquals(11_000L, first.channels().stream()
            .filter(channel -> channel.logicalChannelNumber() == 802)
            .findFirst().orElseThrow().observedAtEpochMilliseconds());
        assertEquals(12_000L, first.channels().stream()
            .filter(channel -> channel.logicalChannelNumber() == 803)
            .findFirst().orElseThrow().observedAtEpochMilliseconds());

        monitor.process(grant(802, 1, 13_000L));
        DMRNetworkConfigurationSnapshot refreshed = monitor.getSnapshot();

        assertEquals(13_000L, refreshed.channels().stream()
            .filter(channel -> channel.logicalChannelNumber() == 802)
            .findFirst().orElseThrow().observedAtEpochMilliseconds());
        assertEquals(12_000L, refreshed.channels().stream()
            .filter(channel -> channel.logicalChannelNumber() == 803)
            .findFirst().orElseThrow().observedAtEpochMilliseconds());
        assertEquals(first, refreshed);
        assertNotEquals(first.toString(), refreshed.toString());
    }

    @Test
    void retainsAbsoluteOverTheAirFrequencyWhenLaterGrantUsesConfiguredMap()
    {
        TimeslotFrequency mapping = mapping(844, 140_000_000L, 150_000_000L);
        DMRNetworkConfigurationMonitor monitor = new DMRNetworkConfigurationMonitor(List.of(mapping));
        monitor.process(announcement(844, 1_000L));
        monitor.process(announcement(844, 6_000L));
        monitor.process(grant(844, 1));
        monitor.process(grant(844, 2));
        List<DMRNetworkConfigurationSnapshot.Channel> channels = monitor.getSnapshot().channels();
        DMRNetworkConfigurationSnapshot.Channel channel = channels.getFirst();

        assertEquals(2, channels.size());
        assertEquals(844, channel.logicalChannelNumber());
        assertEquals(140_043_750L, channel.downlink());
        assertEquals(150_043_750L, channel.uplink());
        assertEquals(DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC, channel.role());
        assertEquals(DMRNetworkConfigurationSnapshot.FrequencySource.OVER_THE_AIR, channel.frequencySource());
        assertTrue(channels.stream().anyMatch(value -> value.timeslot() == 2 &&
            value.downlink() == 140_043_750L &&
            value.frequencySource() == DMRNetworkConfigurationSnapshot.FrequencySource.OVER_THE_AIR));
    }

    @Test
    void tracksCapacityPlusRestChannel()
    {
        DMRNetworkConfigurationMonitor monitor = new DMRNetworkConfigurationMonitor(
            List.of(mapping(3, 451_000_000L, 456_000_000L)));
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(80);
        bits.load(19, 5, 5);
        bits.load(25, 4, 7);
        CapacityPlusNeighbors neighbors = new CapacityPlusNeighbors(DMRSyncPattern.BASE_STATION_DATA,
            bits, null, slotType(), 1_000L, 1);

        monitor.process(neighbors);
        DMRNetworkConfigurationSnapshot snapshot = monitor.getSnapshot();

        assertEquals("CAPACITY_PLUS", snapshot.variant());
        assertEquals("Motorola Capacity+", snapshot.brand());
        assertEquals(7, snapshot.site());
        assertEquals(1, snapshot.channels().size());
        assertEquals(3, snapshot.channels().getFirst().logicalChannelNumber());
        assertEquals(1, snapshot.channels().getFirst().timeslot());
        assertEquals(451_000_000L, snapshot.channels().getFirst().downlink());
        assertTrue(snapshot.channels().getFirst().roles()
            .contains(DMRNetworkConfigurationSnapshot.ChannelRole.CONTROL));
    }

    private static TalkgroupVoiceChannelGrant grant(int lcn, int timeslot)
    {
        return grant(lcn, timeslot, 1_000L);
    }

    private static TalkgroupVoiceChannelGrant grant(int lcn, int timeslot, long timestamp)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(80);
        bits.load(16, 12, lcn);

        if(timeslot == 2)
        {
            bits.set(28);
        }

        return new TalkgroupVoiceChannelGrant(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(),
            timestamp, 1);
    }

    private static Clear clear(int lcn)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(80);
        bits.load(16, 12, lcn);
        return new Clear(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(), 1_000L, 1);
    }

    private static AnnounceChannelFrequency announcement(int lcn, long timestamp)
    {
        CorrectedBinaryMessage continuationBits = new CorrectedBinaryMessage(80);
        continuationBits.load(22, 12, lcn);
        continuationBits.load(34, 10, 150);
        continuationBits.load(44, 13, 350);
        continuationBits.load(57, 10, 140);
        continuationBits.load(67, 13, 350);
        MBCContinuationBlock continuation = new MBCContinuationBlock(DMRSyncPattern.BASE_STATION_DATA,
            continuationBits, null, slotType(), timestamp, 1);
        return new AnnounceChannelFrequency(DMRSyncPattern.BASE_STATION_DATA,
            new CorrectedBinaryMessage(80), null, slotType(), timestamp, 1, continuation);
    }

    private static SlotType slotType()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(24);
        bits.load(8, 4, 3);
        return new SlotType(bits);
    }

    private static TimeslotFrequency mapping(int lcn, long downlink, long uplink)
    {
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(lcn);
        mapping.setDownlinkFrequency(downlink);
        mapping.setUplinkFrequency(uplink);
        return mapping;
    }
}
