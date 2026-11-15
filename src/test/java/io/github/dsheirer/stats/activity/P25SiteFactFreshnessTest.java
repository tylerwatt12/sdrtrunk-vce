/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderState;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.harris.osp.L3HarrisGroupRegroupExplicitEncryptionCommand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp.MotorolaGroupRegroupAddCommand;
import io.github.dsheirer.metadata.site.SiteReceiverContext;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25SiteFactFreshnessTest
{
    private static final String CONFIGURATION = "123e4567-e89b-42d3-a456-426614174000";
    private static final List<String> SUMMARIES = List.of("p25_site_channel_summary",
        "p25_site_channel_tag_summary", "p25_site_frequency_band_summary", "p25_site_neighbor_summary",
        "p25_site_patch_group_summary", "p25_site_patch_group_talkgroup_summary", "p25_site_patch_group_radio_summary");

    @Test
    void learnedCallsignSurvivesControlOnlyUpdatesAndIndependentEvidenceMergesWithoutDuplicateChannels() throws Exception
    {
        try(Connection connection = database())
        {
            var original = snapshot(61_000, 61_000, "a");
            var control = original.channels().getFirst();
            var bsi = new P25NetworkConfigurationSnapshot.Channel("base_station", control.descriptor(),
                control.downlink(), control.uplink(), false, 1, "WTEST77", 60_000L);
            ReceiverActivitySchema.insertSite(connection, withChannels(original, List.of(control, bsi)));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_site_channel_summary"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_site_channel"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_site_bsi WHERE callsign='WTEST77'"));
            var voice = new P25NetworkConfigurationSnapshot.Channel("base_station_voice", null,
                control.downlink(), null, false, 1, "VOICE77", 60_001L);
            ReceiverActivitySchema.insertSite(connection, withChannels(snapshot(62_000, 62_000, "c"),
                List.of(control, bsi, voice)));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM p25_site_bsi"));
            ReceiverActivitySchema.insertSite(connection, snapshot(71_000, 71_000, "b"));
            assertEquals(60_000, scalar(connection, "SELECT last_heard_ms FROM p25_site_bsi WHERE source=0"));
            assertEquals(60_001, scalar(connection, "SELECT last_heard_ms FROM p25_site_bsi WHERE source=1"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_site_bsi WHERE callsign='WTEST77'"));
            try(var statement = connection.createStatement(); var plan = statement.executeQuery(
                "EXPLAIN QUERY PLAN SELECT callsign,last_heard_ms FROM p25_site_bsi WHERE channel_id=1 AND frequency_hz=770606250"))
            {
                assertTrue(plan.next());
                assertTrue(plan.getString("detail").contains("PRIMARY KEY"));
            }
            assertEquals(61_000, scalar(connection, "SELECT first_seen_ms FROM p25_site_channel_summary"));
        }
    }

    @Test
    void contentChangesSaveImmediatelyButUnchangedFreshnessUsesMinuteHeartbeats()
    {
        var context = SiteReceiverContext.detached(CONFIGURATION, 770606250L);
        var first = networkSnapshot(1_000, null);
        var fresh = networkSnapshot(6_000, null);
        var learned = networkSnapshot(7_000, "WTEST77");
        String initialHash = ReceiverActivityMapper.siteStructureHash(first);
        assertEquals(initialHash, ReceiverActivityMapper.siteStructureHash(fresh));
        var previous = new ReceiverActivityService.SiteWriteReceipt(context, initialHash, 1_000);
        assertFalse(new ReceiverActivityService.SiteWriteReceipt(context, initialHash, 6_000).shouldWrite(previous));
        assertFalse(new ReceiverActivityService.SiteWriteReceipt(context, initialHash, 60_999).shouldWrite(previous));
        assertTrue(new ReceiverActivityService.SiteWriteReceipt(context, initialHash, 61_000).shouldWrite(previous));
        assertTrue(new ReceiverActivityService.SiteWriteReceipt(context,
            ReceiverActivityMapper.siteStructureHash(learned), 7_000).shouldWrite(previous));
        assertTrue(new ReceiverActivityService.SiteWriteReceipt(SiteReceiverContext.detached(CONFIGURATION, 773831250L),
            initialHash, 7_000).shouldWrite(previous));
        assertFalse(new ReceiverActivityService.SiteWriteReceipt(context,
            ReceiverActivityMapper.siteStructureHash(learned), 999).shouldWrite(previous));
        //A -> B -> A is a real content transition; do not suppress A just because it was saved earlier this minute.
        var changed = new ReceiverActivityService.SiteWriteReceipt(context,
            ReceiverActivityMapper.siteStructureHash(learned), 7_000);
        assertTrue(new ReceiverActivityService.SiteWriteReceipt(context, initialHash, 8_000).shouldWrite(changed));
    }

    private static P25NetworkConfigurationSnapshot networkSnapshot(long received, String callsign)
    {
        return new P25NetworkConfigurationSnapshot("P25_PHASE_1", null, null,
            List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", "1-1376", 770606250L,
                800606250L, false, 1, callsign, received)), List.of(), List.of(), List.of(), List.of());
    }

    private static ReceiverActivityRecords.SiteSnapshot withChannels(ReceiverActivityRecords.SiteSnapshot source,
        List<P25NetworkConfigurationSnapshot.Channel> channels)
    {
        return new ReceiverActivityRecords.SiteSnapshot(source.observedAtEpochMilliseconds(), source.configurationId(),
            source.receiverKind(), source.snapshotHash(), source.protocol(), source.wacn(), source.systemId(), source.nac(),
            source.rfss(), source.site(), source.lra(), source.activeRfssNetworkConnection(), source.tdma(),
            source.siteStatus(), source.sourceFrequencyHertz(), source.primaryFrequencyHertz(),
            source.currentControlHertz(), channels, source.neighborSites(), source.frequencyBands(),
            source.patchGroups(), source.foreignSystemBands());
    }

    @Test
    void changedAndUnchangedExportsDoNotCountCachedFactsAndFreshReceptionAdvancesThem() throws Exception
    {
        try(Connection connection = database())
        {
            ReceiverActivitySchema.insertSite(connection, snapshot(1_000, 1_000, "a"));
            ReceiverActivitySchema.insertSite(connection, snapshot(31_000, 1_000, "b"));
            ReceiverActivitySchema.insertSite(connection, snapshot(61_000, 1_000, "b"));
            for(String table: SUMMARIES)
            {
                assertEquals(1, scalar(connection, "SELECT observation_count FROM " + table), table);
                assertEquals(1_000, scalar(connection, "SELECT last_seen_ms FROM " + table), table);
            }
            for(String table: List.of("p25_site_channel", "p25_site_channel_tag", "p25_site_frequency_band",
                "p25_site_neighbor", "p25_site_patch_group", "p25_site_patch_group_talkgroup", "p25_site_patch_group_radio"))
            {
                assertEquals(1_000, scalar(connection, "SELECT confirmed_at_ms FROM " + table), table);
            }

            ReceiverActivitySchema.insertSite(connection, snapshot(71_000, 70_000, "c"));
            for(String table: SUMMARIES)
            {
                assertEquals(2, scalar(connection, "SELECT observation_count FROM " + table), table);
                assertEquals(70_000, scalar(connection, "SELECT last_seen_ms FROM " + table), table);
                assertEquals(1_000, scalar(connection, "SELECT first_seen_ms FROM " + table), table);
            }
            try(var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA quick_check"))
            {
                assertTrue(result.next());
                assertEquals("ok", result.getString(1));
            }
        }
    }

    @Test
    void firstDecodedHarrisAndMotorolaActivationsReachTheSavedSite() throws Exception
    {
        for(String vendor: List.of("Harris", "Motorola"))
        {
            try(Connection connection = database())
            {
                var channel = new Channel(vendor,
                    Channel.ChannelType.STANDARD);
                channel.setConfigurationId(CONFIGURATION);
                channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
                var decoder = new P25P1DecoderState(channel, null);
                var bits = new CorrectedBinaryMessage(96);
                TSBKMessage message;
                if(vendor.equals("Harris"))
                {
                    bits.load(2, 6, 0x30);
                    bits.load(8, 8, 0xA4);
                    bits.load(16, 8, 0x69);
                    bits.load(24, 16, 100);
                    bits.load(64, 16, 200);
                    message = new L3HarrisGroupRegroupExplicitEncryptionCommand(
                            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
                            bits, 0x123, 1_000L);
                }
                else
                {
                    bits.load(8, 8, 0x90);
                    bits.load(16, 16, 100);
                    bits.load(32, 16, 200);
                    message = new MotorolaGroupRegroupAddCommand(
                            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
                            bits, 0x123, 1_000L);
                }
                message.setValid(true);
                decoder.receive(message);
                var decoded = decoder.getP25NetworkConfigurationSnapshot();
                assertEquals(1, decoded.patchGroups().size(), vendor);
                // The fixture supplies the already identified serving site and its advertised control source.
                var metadata = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
                    new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x123, 0),
                    new P25NetworkConfigurationSnapshot.CurrentSite(0x348, 0x123, 2, 7, 0, true),
                    List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", "1-1376",
                        770606250L, 800606250L, false, 1)), List.of(), List.of(), decoded.patchGroups(), List.of());
                var record = new ReceiverActivityMapper().map(new SiteMetadataEvent(
                    channel, metadata, 1_000L, 770606250L));
                assertNotNull(record, vendor);
                ReceiverActivitySchema.insertSite(connection, record);
                assertEquals(1, scalar(connection, "SELECT regroup_type FROM p25_site_patch_group"), vendor);
                assertEquals(200, scalar(connection,
                    "SELECT local_talkgroup_id FROM p25_site_patch_group_talkgroup"), vendor);
                assertEquals(1_000, scalar(connection, "SELECT confirmed_at_ms FROM p25_site_patch_group"), vendor);
            }
        }
    }

    @Test
    void patchAndSimulselectTypesPersistWithoutGuessingHistoricalMembership() throws Exception
    {
        try(Connection connection = database())
        {
            var original = snapshot(1_000, 1_000, "a");
            ReceiverActivitySchema.insertSite(connection, original);
            assertEquals(1, scalar(connection, "SELECT regroup_type FROM p25_site_patch_group"));
            assertEquals(1, scalar(connection, "SELECT regroup_type FROM p25_site_patch_group_summary"));
            var changed = new ReceiverActivityRecords.SiteSnapshot(2_000, original.configurationId(),
                original.receiverKind(), "b".repeat(64), original.protocol(), original.wacn(), original.systemId(),
                original.nac(), original.rfss(), original.site(), original.lra(), original.activeRfssNetworkConnection(),
                original.tdma(), original.siteStatus(), original.sourceFrequencyHertz(), original.primaryFrequencyHertz(),
                original.currentControlHertz(), original.channels(), original.neighborSites(), original.frequencyBands(),
                List.of(new P25NetworkConfigurationSnapshot.PatchGroup(100, 2, 2, List.of(300), List.of(), 2_000L)),
                original.foreignSystemBands());
            ReceiverActivitySchema.insertSite(connection, changed);
            assertEquals(2, scalar(connection, "SELECT regroup_type FROM p25_site_patch_group"));
            assertEquals(2, scalar(connection, "SELECT regroup_type FROM p25_site_patch_group_summary"));
            assertEquals(300, scalar(connection, "SELECT local_talkgroup_id FROM p25_site_patch_group_talkgroup"));
            assertEquals(2, scalar(connection, "SELECT observation_count FROM p25_site_patch_group_summary"));
            // An older complete snapshot cannot restore the previous generation or type.
            ReceiverActivitySchema.insertSite(connection, original);
            assertEquals(2, scalar(connection, "SELECT regroup_type FROM p25_site_patch_group"));
            assertEquals(2, scalar(connection, "SELECT version FROM p25_site_patch_group"));
        }
    }

    @Test
    void legacyInflatedValuesArePreservedUntilAnActuallyNewerReception() throws Exception
    {
        try(Connection connection = database())
        {
            ReceiverActivitySchema.insertSite(connection, snapshot(1_000, 1_000, "a"));
            for(String table: SUMMARIES)
            {
                connection.createStatement().executeUpdate("UPDATE " + table +
                    " SET observation_count=100,last_seen_ms=60000");
            }
            ReceiverActivitySchema.insertSite(connection, snapshot(61_000, 30_000, "b"));
            for(String table: SUMMARIES)
            {
                assertEquals(100, scalar(connection, "SELECT observation_count FROM " + table), table);
                assertEquals(60_000, scalar(connection, "SELECT last_seen_ms FROM " + table), table);
            }
            ReceiverActivitySchema.insertSite(connection, snapshot(71_000, 70_000, "c"));
            for(String table: SUMMARIES)
            {
                assertEquals(101, scalar(connection, "SELECT observation_count FROM " + table), table);
                assertEquals(70_000, scalar(connection, "SELECT last_seen_ms FROM " + table), table);
            }
        }
    }

    private static ReceiverActivityRecords.SiteSnapshot snapshot(long exported, long received, String hash)
    {
        return new ReceiverActivityRecords.SiteSnapshot(exported, CONFIGURATION,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, hash.repeat(64), "APCO25", 0xBEE00, 0x348,
            0x123, 2, 7, 0, true, false, null, 770606250L, 770606250L, 770606250L,
            List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", "1-1376", 770606250L,
                800606250L, false, 1, null, received)),
            List.of(new P25NetworkConfigurationSnapshot.NeighborSite(0x348, 0x123, 2, 1, 0, "1-1892",
                773831250L, 803831250L, "VALID", received)),
            List.of(new P25NetworkConfigurationSnapshot.FrequencyBand(1, false, 762006250L, 12500,
                6250L, 30000000L, 1, received)),
            List.of(new P25NetworkConfigurationSnapshot.PatchGroup(100, 1, 1, List.of(200), List.of(10001), received)),
            List.of());
    }

    private static Connection database() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.createStatement().execute("PRAGMA foreign_keys=ON");
        SdrTrunkDatabaseSchema.create(connection);
        SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
        ReceiverActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        ActivityTestDatabase.currentOwners(connection);
        connection.createStatement().executeUpdate("""
            INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,name,
                alias_list_id,auto_start,decoder_type,primary_frequency_hz,config_json)
            VALUES('%s','TRUNKED',0,'Test','Test','Control',
                (SELECT id FROM alias_list WHERE family='P25' LIMIT 1),0,'P25_PHASE1',770606250,'{}')
            """.formatted(CONFIGURATION));
        return connection;
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(var statement = connection.createStatement(); var result = statement.executeQuery(sql))
        {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
