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

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;
import io.github.dsheirer.source.config.SourceConfigRecording;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrunkedSiteMetadataMapperTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void rejectsUsefulMetadataWithoutAKnownTrunkingVariant()
    {
        Channel channel = channel(451_000_000L);
        DMRNetworkConfigurationSnapshot unknown = new DMRNetworkConfigurationSnapshot(
            "DMR", null, 10, 20, null, null, null, null, 1, 2, List.of(), List.of());

        assertNull(TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(channel, unknown, 1_000L)));
    }

    @Test
    void mapsDmrIdentityChannelsNeighborsAndConfiguredLabels()
    {
        Channel channel = channel(451_000_000L);
        DMRNetworkConfigurationSnapshot source = new DMRNetworkConfigurationSnapshot(
            "DMR", "CAPACITY_MAX", 10, 20, "Capacity Max Tier III Trunking", "SMALL", "Advantage",
            "Control", 3, 4,
            List.of(new DMRNetworkConfigurationSnapshot.Channel("DMRChannel", 42, 1,
                452_000_000L, 457_000_000L, DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC,
                DMRNetworkConfigurationSnapshot.FrequencySource.CONFIGURED_MAP, 700L)),
            List.of(new DMRNetworkConfigurationSnapshot.NeighborSite("TIER_III", 10, 21, "SMALL",
                43, 453_000_000L, 458_000_000L, true, 1, 2, 800L)));

        TrunkedSiteSchema.Snapshot mapped = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(channel, source, 1_000L));
        assertNotNull(mapped);
        assertEquals(TrunkedSiteSchema.PROTOCOL_DMR, mapped.protocolCode());
        assertEquals(3, mapped.variantCode());
        assertEquals(2, mapped.identityDomainCode());
        assertEquals("Metro Radio", mapped.configuredSystem());
        assertEquals("Downtown", mapped.channelName());
        assertEquals(10, mapped.networkId());
        assertEquals(20, mapped.siteId());
        assertEquals(3, mapped.brandCode());
        assertEquals(2, mapped.modelCode());
        assertEquals(2, mapped.modeCode());
        assertEquals(1, mapped.channelTypeCode());
        assertEquals(451_000_000L, mapped.currentControlHertz());
        assertTrue(mapped.channels().stream().anyMatch(value ->
            Long.valueOf(451_000_000L).equals(value.frequencyHertz()) &&
                (value.roleFlags() & TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL) != 0));
        assertTrue(mapped.channels().stream().anyMatch(value ->
            Long.valueOf(452_000_000L).equals(value.frequencyHertz()) &&
                (value.roleFlags() & TrunkedSiteSchema.CHANNEL_ROLE_OBSERVED) != 0 &&
                (value.roleFlags() & TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC) != 0 &&
                (value.roleFlags() & TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_FROM_CONFIGURED_MAP) != 0 &&
                value.observedAtEpochMilliseconds() == 700L));
        assertEquals(TrunkedSiteSchema.NEIGHBOR_STATUS_ACTIVE,
            mapped.neighbors().getFirst().statusFlags());
        assertEquals(2, mapped.neighbors().getFirst().identityDomainCode());
        assertEquals(800L, mapped.neighbors().getFirst().observedAtEpochMilliseconds());

        TrunkedSiteSchema.Snapshot heartbeat = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(channel, source, 6_000L));
        assertEquals(mapped.snapshotHash(), heartbeat.snapshotHash());

        DMRNetworkConfigurationSnapshot refreshedSource = new DMRNetworkConfigurationSnapshot(
            "DMR", "CAPACITY_MAX", 10, 20, "Capacity Max Tier III Trunking", "SMALL", "Advantage",
            "Control", 3, 4,
            List.of(new DMRNetworkConfigurationSnapshot.Channel("DMRChannel", 42, 1,
                452_000_000L, 457_000_000L, DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC,
                DMRNetworkConfigurationSnapshot.FrequencySource.CONFIGURED_MAP, 900L)),
            List.of(new DMRNetworkConfigurationSnapshot.NeighborSite("TIER_III", 10, 21, "SMALL",
                43, 453_000_000L, 458_000_000L, true, 1, 2, 950L)));
        assertEquals(source, refreshedSource);
        TrunkedSiteSchema.Snapshot refreshed = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(channel, refreshedSource, 6_000L));
        assertEquals(mapped.snapshotHash(), refreshed.snapshotHash());

        channel.setSite("Airport");
        TrunkedSiteSchema.Snapshot renamed = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(channel, source, 7_000L));
        assertNotEquals(mapped.snapshotHash(), renamed.snapshotHash());

        channel.setSite(" ");
        TrunkedSiteSchema.Snapshot fallback = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(channel, source, 8_000L));
        assertEquals("Control", fallback.channelName());
    }

    @Test
    void mapsDmrOverTheAirFrequencyAndAdditiveControlFlags()
    {
        Channel channel = channel(451_000_000L);
        DMRNetworkConfigurationSnapshot source = new DMRNetworkConfigurationSnapshot(
            "DMR", "TIER_III", 10, 20, "Tier III Trunking", "SMALL", null, "Control", 1, 2,
            List.of(
                new DMRNetworkConfigurationSnapshot.Channel("DMRAbsoluteChannel", 42, 1,
                    452_000_000L, 457_000_000L, DMRNetworkConfigurationSnapshot.ChannelRole.OBSERVED,
                    DMRNetworkConfigurationSnapshot.FrequencySource.OVER_THE_AIR),
                new DMRNetworkConfigurationSnapshot.Channel("DMRTier3Channel", 43, 1,
                    451_000_000L, 456_000_000L,
                    Set.of(DMRNetworkConfigurationSnapshot.ChannelRole.CONTROL,
                        DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC),
                    DMRNetworkConfigurationSnapshot.FrequencySource.CONFIGURED_MAP)),
            List.of());

        TrunkedSiteSchema.Snapshot mapped = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(channel, source, 1_000L));
        TrunkedSiteSchema.Channel absolute = mapped.channels().stream()
            .filter(value -> Integer.valueOf(42).equals(value.channelNumber()))
            .findFirst().orElseThrow();
        TrunkedSiteSchema.Channel control = mapped.channels().stream()
            .filter(value -> Integer.valueOf(43).equals(value.channelNumber()))
            .findFirst().orElseThrow();

        assertTrue((absolute.roleFlags() & TrunkedSiteSchema.CHANNEL_ROLE_OBSERVED) != 0);
        assertTrue((absolute.roleFlags() &
            TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_ANNOUNCED_OVER_THE_AIR) != 0);
        assertEquals(1_000L, absolute.observedAtEpochMilliseconds());
        assertTrue((control.roleFlags() & TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL) != 0);
        assertTrue((control.roleFlags() & TrunkedSiteSchema.CHANNEL_ROLE_OBSERVED) != 0);
        assertTrue((control.roleFlags() & TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC) != 0);
        assertTrue((control.roleFlags() &
            TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_FROM_CONFIGURED_MAP) != 0);
        assertTrue(mapped.channels().stream().noneMatch(value ->
            value.channelNumber() == null && value.inboundChannelNumber() == null && value.timeslot() == null &&
                Long.valueOf(451_000_000L).equals(value.frequencyHertz())));
    }

    @Test
    void mapsNxdnTypeDIdentityRepeaterAndNeighbor()
    {
        Channel channel = channel(155_000_000L);
        NXDNNetworkConfigurationSnapshot source = new NXDNNetworkConfigurationSnapshot(
            "NXDN", "TYPE-D", 5,
            new NXDNNetworkConfigurationSnapshot.Location("TYPE_D", 8, null, 7),
            9, "CONTROL", null, null, List.of("VOICE", "DATA"), List.of(),
            new NXDNNetworkConfigurationSnapshot.FailureStatus(null, "60 SECONDS"),
            List.of(new NXDNNetworkConfigurationSnapshot.Channel("CONTROL_1", "DFA", null,
                120, 121, "BW_12_5", 155_000_000L, 160_000_000L, null, 600L)),
            List.of(new NXDNNetworkConfigurationSnapshot.NeighborSite("TYPE_D", null,
                new NXDNNetworkConfigurationSnapshot.Location("TYPE_D", 8, 10, 7), null, true, 700L)),
            12, "FREE", List.of(12, 13), Map.of(12, 800L, 13, 900L));

        TrunkedSiteSchema.Snapshot mapped = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(channel, source, 1_000L));
        assertNotNull(mapped);
        assertEquals(TrunkedSiteSchema.PROTOCOL_NXDN, mapped.protocolCode());
        assertEquals(2, mapped.variantCode());
        assertEquals(4, mapped.identityDomainCode());
        assertEquals(7, mapped.networkId());
        assertEquals(8, mapped.systemId());
        assertEquals(9, mapped.siteId());
        assertEquals(5, mapped.ran());
        assertEquals(12, mapped.currentRepeater());
        assertEquals(2, mapped.modeCode());
        assertEquals(60, mapped.failureCode());
        assertTrue(mapped.serviceFlags() > 0);
        assertTrue(mapped.channels().stream().anyMatch(value -> Integer.valueOf(13).equals(value.channelNumber()) &&
            value.observedAtEpochMilliseconds() == 900L));
        assertEquals(TrunkedSiteSchema.NEIGHBOR_STATUS_ISOLATED,
            mapped.neighbors().getFirst().statusFlags());
        assertEquals(4, mapped.neighbors().getFirst().identityDomainCode());
        assertEquals(700L, mapped.neighbors().getFirst().observedAtEpochMilliseconds());

        NXDNNetworkConfigurationSnapshot refreshedSource = new NXDNNetworkConfigurationSnapshot(
            "NXDN", "TYPE-D", 5,
            new NXDNNetworkConfigurationSnapshot.Location("TYPE_D", 8, null, 7),
            9, "CONTROL", null, null, List.of("VOICE", "DATA"), List.of(),
            new NXDNNetworkConfigurationSnapshot.FailureStatus(null, "60 SECONDS"),
            List.of(new NXDNNetworkConfigurationSnapshot.Channel("CONTROL_1", "DFA", null,
                120, 121, "BW_12_5", 155_000_000L, 160_000_000L, null, 1_600L)),
            List.of(new NXDNNetworkConfigurationSnapshot.NeighborSite("TYPE_D", null,
                new NXDNNetworkConfigurationSnapshot.Location("TYPE_D", 8, 10, 7), null, true, 1_700L)),
            12, "FREE", List.of(12, 13), Map.of(12, 1_800L, 13, 1_900L));
        assertEquals(source, refreshedSource);
        TrunkedSiteSchema.Snapshot refreshed = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(channel, refreshedSource, 2_000L));
        assertEquals(mapped.snapshotHash(), refreshed.snapshotHash());

        NXDNNetworkConfigurationSnapshot changedSource = new NXDNNetworkConfigurationSnapshot(
            refreshedSource.decoder(), refreshedSource.variant(), refreshedSource.ran(),
            refreshedSource.currentLocation(), refreshedSource.typeDSite(), refreshedSource.typeDSiteType(),
            refreshedSource.station(), refreshedSource.siteConfiguration(), refreshedSource.services(),
            refreshedSource.restrictions(), refreshedSource.failureStatus(), refreshedSource.controlChannels(),
            refreshedSource.neighborSites(), refreshedSource.currentRepeater(), "HALTED_CWID",
            refreshedSource.observedRepeaters(), refreshedSource.observedRepeaterTimestamps());
        TrunkedSiteSchema.Snapshot changed = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(channel, changedSource, 2_001L));
        assertNotEquals(mapped.snapshotHash(), changed.snapshotHash());
    }

    @Test
    void hashMaterialKeepsConfiguredFieldBoundaries()
    {
        DMRNetworkConfigurationSnapshot source = new DMRNetworkConfigurationSnapshot(
            "DMR", "TIER_III", 10, 20, "Tier III Trunking", "SMALL", null, "Control", 1, 2,
            List.of(), List.of());
        Channel first = channel(451_000_000L);
        first.setSystem("A|B");
        first.setSite("C");
        Channel second = channel(451_000_000L);
        second.setSystem("A");
        second.setSite("B|C");

        TrunkedSiteSchema.Snapshot firstMapped = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(first, source, 1_000L));
        TrunkedSiteSchema.Snapshot secondMapped = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(second, source, 1_000L));

        assertNotEquals(firstMapped.snapshotHash(), secondMapped.snapshotHash());
    }

    @Test
    void preferredRotatingFrequencyIsNotClaimedAsCurrentControl()
    {
        Channel channel = channel(451_000_000L);
        SourceConfigTunerMultipleFrequency multiple = new SourceConfigTunerMultipleFrequency();
        multiple.setFrequencies(List.of(451_000_000L, 452_000_000L));
        multiple.setPreferredFrequency(451_000_000L);
        channel.setSourceConfiguration(multiple);
        DMRNetworkConfigurationSnapshot source = new DMRNetworkConfigurationSnapshot(
            "DMR", "TIER_III", 10, 20, "Tier III Trunking", "SMALL", null, "Control", 1, 2,
            List.of(), List.of());

        TrunkedSiteSchema.Snapshot mapped = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(channel, source, 1_000L));

        assertNotNull(mapped);
        assertEquals(451_000_000L, mapped.primaryFrequencyHertz());
        assertNull(mapped.currentControlHertz());
        assertTrue(mapped.channels().stream().noneMatch(value ->
            (value.roleFlags() & TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL) != 0));
    }

    @Test
    void existingSingleWriterPersistsMappedTrunkedSnapshot() throws Exception
    {
        Path database = mTemporaryFolder.resolve("writer.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Channel channel = channel(451_000_000L);
        DMRNetworkConfigurationSnapshot source = new DMRNetworkConfigurationSnapshot(
            "DMR", "TIER_III", 10, 20, "Tier III Trunking", "SMALL", null, "Control", 1, 2,
            List.of(), List.of());
        TrunkedSiteSchema.Snapshot mapped = TrunkedSiteMetadataMapper.map(
            new ProtocolSiteMetadataEvent(channel, source, 1_000L));
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, false, 10, 250, 25);
        writer.start();
        writer.enqueue(new P25ActivityLogRecords.TrunkedSiteSnapshot(
            mapped.observedAtEpochMilliseconds(), mapped));
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);

        while(writer.getWrittenRecords() < 1 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        writer.close();
        assertEquals(1, writer.getWrittenRecords());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT protocol_code, network_id, site_id
                FROM trunked_site_snapshot
                WHERE guid='00000000-0000-0000-0000-000000000123'
                """))
        {
            assertTrue(resultSet.next());
            assertEquals(TrunkedSiteSchema.PROTOCOL_DMR, resultSet.getInt("protocol_code"));
            assertEquals(10, resultSet.getInt("network_id"));
            assertEquals(20, resultSet.getInt("site_id"));
        }
    }

    private static Channel channel(long frequency)
    {
        Channel channel = new Channel("Control");
        channel.setSystem("Metro Radio");
        channel.setSite("Downtown");
        channel.setAliasListName("County");
        channel.setRadresGuid("00000000-0000-0000-0000-000000000123");
        SourceConfigRecording recording = new SourceConfigRecording();
        recording.setFrequency(frequency);
        channel.setSourceConfiguration(recording);
        return channel;
    }
}
