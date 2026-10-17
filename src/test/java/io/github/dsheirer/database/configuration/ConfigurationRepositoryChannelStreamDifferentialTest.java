/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.database.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.configuration.ChannelConfigurationSnapshot;
import io.github.dsheirer.configuration.ConfigurationSnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Database triggers prove that ordinary configuration saves never write an unchanged owner. */
class ConfigurationRepositoryChannelStreamDifferentialTest
{
    private static final List<String> ALIAS_TABLES = List.of("alias", "alias_list", "scan_list",
        "alias_broadcast_channel", "alias_list_unmatched_talkgroup_stream", "alias_list_new_alias_stream",
        "alias_scan_list_membership", "alias_list_unmatched_talkgroup_scan_list_membership",
        "alias_list_new_alias_scan_list_membership");
    @TempDir Path mTemporaryFolder;
    private int mTrigger;

    @Test
    void identicalBackgroundSnapshotWritesNothingIncludingAfterJsonReformatting() throws Exception
    {
        Fixture fixture = fixture("no-op");
        execute(fixture.database(), "UPDATE configuration_channel SET config_json=json(config_json)");
        execute(fixture.database(), "UPDATE configuration_broadcast_stream SET config_json=json(config_json)");
        String before = fixture.repository().fingerprint();
        String sequence = sequence(fixture);
        forbidAll(fixture.database(), "configuration_channel");
        forbidAll(fixture.database(), "configuration_broadcast_stream");
        forbidAliasWrites(fixture);
        save(fixture, fixture.repository().load());
        assertEquals(before, fixture.repository().fingerprint());
        assertEquals(sequence, sequence(fixture));
    }

    @Test
    void channelSaveOnlyUpdatesSelectedRowAndRetainsItsHistoryAndResetMarker() throws Exception
    {
        Fixture fixture = fixture("channel-edit");
        ConfigurationSnapshot current = fixture.repository().load();
        Channel changed = current.channels().getFirst();
        long owner = channelRow(fixture, changed);
        long history = historyRow(fixture, changed);
        execute(fixture.database(), "UPDATE configuration_channel SET analog_activity_reset_ms=50 WHERE " + id(changed));
        String sequence = sequence(fixture);
        forbidExceptUpdate(fixture, "configuration_channel", changed.getConfigurationId());
        forbidAll(fixture.database(), "configuration_broadcast_stream");
        forbidAliasWrites(fixture);
        changed.setName("Renamed channel");
        changed.setSite("Renamed site");
        changed.setAutoStart(true);
        changed.setAutoStartOrder(4);
        ((SourceConfigTuner)changed.getSourceConfiguration()).setFrequency(122_050_000L);
        Channel saved = fixture.repository().commitChannelConfiguration(
            new ChannelConfigurationSnapshot(current.channels())).channels().getFirst();
        assertEquals("Renamed channel", saved.getName());
        assertEquals(122_050_000L, ((SourceConfigTuner)saved.getSourceConfiguration()).getFrequency());
        assertEquals(owner, channelRow(fixture, saved));
        assertEquals(history, historyRow(fixture, saved));
        assertEquals(50, number(fixture, "SELECT analog_activity_reset_ms FROM configuration_channel WHERE " + id(saved)));
        assertEquals(sequence, sequence(fixture));
    }

    @Test
    void learnedSiteAndControlFrequenciesOnlyWriteTheLearningChannel() throws Exception
    {
        Fixture fixture = fixture("learning");
        ConfigurationSnapshot current = fixture.repository().load();
        Channel learned = current.channels().get(2);
        long owner = channelRow(fixture, learned);
        long history = historyRow(fixture, learned);
        forbidExceptUpdate(fixture, "configuration_channel", learned.getConfigurationId());
        forbidAll(fixture.database(), "configuration_broadcast_stream");
        forbidAliasWrites(fixture);
        P25SiteIdentity identity = new P25SiteIdentity(0xBEE00, 0x123, 1, 2);
        learned.setP25SiteIdentity(identity);
        ((DecodeConfigP25Phase1)learned.getDecodeConfiguration()).setLearnedControlFrequencies(
            List.of(852_012_500L, 853_012_500L));
        save(fixture, current);
        Channel saved = fixture.repository().load().channels().get(2);
        assertEquals(identity, saved.getP25SiteIdentity());
        assertEquals(List.of(852_012_500L, 853_012_500L),
            ((DecodeConfigP25Phase1)saved.getDecodeConfiguration()).getLearnedControlFrequencies());
        assertEquals(owner, channelRow(fixture, saved));
        assertEquals(history, historyRow(fixture, saved));
    }

    @Test
    void destinationSaveOnlyUpdatesSelectedRowWithoutSequenceChurn() throws Exception
    {
        Fixture fixture = fixture("destination-edit");
        ConfigurationSnapshot current = fixture.repository().load();
        BroadcastConfiguration changed = current.broadcastConfigurations().getFirst();
        long owner = streamRow(fixture, changed);
        String sequence = sequence(fixture);
        forbidExceptUpdate(fixture, "configuration_broadcast_stream", changed.getConfigurationId());
        forbidAll(fixture.database(), "configuration_channel");
        forbidAliasWrites(fixture);
        changed.setName("Renamed destination");
        List<BroadcastConfiguration> saved = fixture.repository().commitStreamingConfiguration(
            current.broadcastConfigurations());
        assertEquals("Renamed destination", saved.getFirst().getName());
        assertEquals(owner, streamRow(fixture, changed));
        assertEquals(sequence, sequence(fixture));
    }

    @Test
    void streamOnlyBackgroundChangeNeverTouchesChannelsAndChannelOnlyChangeNeverTouchesStreams() throws Exception
    {
        Fixture streams = fixture("background-stream");
        ConfigurationSnapshot streamState = streams.repository().load();
        forbidAll(streams.database(), "configuration_channel");
        forbidExceptUpdate(streams, "configuration_broadcast_stream",
            streamState.broadcastConfigurations().get(1).getConfigurationId());
        streamState.broadcastConfigurations().get(1).setName("Background destination");
        save(streams, streamState);
        assertEquals("Background destination", streams.repository().load().broadcastConfigurations().get(1).getName());

        Fixture channels = fixture("background-channel");
        ConfigurationSnapshot channelState = channels.repository().load();
        forbidAll(channels.database(), "configuration_broadcast_stream");
        forbidExceptUpdate(channels, "configuration_channel", channelState.channels().get(1).getConfigurationId());
        channelState.channels().get(1).setName("Background channel");
        save(channels, channelState);
        assertEquals("Background channel", channels.repository().load().channels().get(1).getName());
    }

    @Test
    void appendingNewOwnersDoesNotWriteExistingRows() throws Exception
    {
        Fixture fixture = fixture("append");
        ConfigurationSnapshot current = fixture.repository().load();
        forbid(fixture.database(), "configuration_channel", "UPDATE", null);
        forbid(fixture.database(), "configuration_channel", "DELETE", null);
        forbid(fixture.database(), "configuration_broadcast_stream", "UPDATE", null);
        forbid(fixture.database(), "configuration_broadcast_stream", "DELETE", null);
        Channel added = channel("New channel", 123_000_000L, current.aliasListDefinitions());
        BroadcastifyCallConfiguration stream = stream("New destination");
        fixture.repository().synchronizeChannelAndBroadcastConfiguration(
            List.of(current.channels().get(0), current.channels().get(1), current.channels().get(2), added),
            List.of(current.broadcastConfigurations().get(0), current.broadcastConfigurations().get(1), stream));
        assertEquals(4, fixture.repository().load().channels().size());
        assertEquals(3, fixture.repository().load().broadcastConfigurations().size());
    }

    @Test
    void deletingFinalOwnersDoesNotWriteRetainedRowsAndCascadesOnlyTheirHistory() throws Exception
    {
        Fixture fixture = fixture("delete");
        ConfigurationSnapshot current = fixture.repository().load();
        Channel removed = current.channels().get(2);
        String removedStream = current.broadcastConfigurations().get(1).getConfigurationId();
        forbid(fixture.database(), "configuration_channel", "INSERT", null);
        forbid(fixture.database(), "configuration_channel", "UPDATE", null);
        forbid(fixture.database(), "configuration_channel", "DELETE", "OLD.configuration_id<>'" + removed.getConfigurationId() + "'");
        forbid(fixture.database(), "configuration_broadcast_stream", "INSERT", null);
        forbid(fixture.database(), "configuration_broadcast_stream", "UPDATE", null);
        forbid(fixture.database(), "configuration_broadcast_stream", "DELETE", "OLD.configuration_id<>'" + removedStream + "'");
        fixture.repository().synchronizeChannelAndBroadcastConfiguration(current.channels().subList(0, 2),
            current.broadcastConfigurations().subList(0, 1));
        assertEquals(2, fixture.repository().load().channels().size());
        assertEquals(2, number(fixture, "SELECT count(*) FROM receiver_channel"));
        assertEquals(0, number(fixture, "SELECT count(*) FROM receiver_channel WHERE " + id(removed)));
    }

    @Test
    void orderChangesUpdateOnlyMovedOwnersWithoutChangingIdentityOrHistory() throws Exception
    {
        Fixture fixture = fixture("order");
        ConfigurationSnapshot current = fixture.repository().load();
        Channel first = current.channels().get(0);
        Channel second = current.channels().get(1);
        Channel third = current.channels().get(2);
        long firstOwner = channelRow(fixture, first);
        long firstHistory = historyRow(fixture, first);
        String sequence = sequence(fixture);
        forbid(fixture.database(), "configuration_channel", "INSERT", null);
        forbid(fixture.database(), "configuration_channel", "DELETE", null);
        forbid(fixture.database(), "configuration_channel", "UPDATE", "OLD.configuration_id='" + third.getConfigurationId() + "'");
        forbid(fixture.database(), "configuration_broadcast_stream", "INSERT", null);
        forbid(fixture.database(), "configuration_broadcast_stream", "DELETE", null);
        fixture.repository().synchronizeChannelAndBroadcastConfiguration(List.of(second, first, third),
            List.of(current.broadcastConfigurations().get(1), current.broadcastConfigurations().get(0)));
        ConfigurationSnapshot saved = fixture.repository().load();
        assertEquals(second.getConfigurationId(), saved.channels().getFirst().getConfigurationId());
        assertEquals(current.broadcastConfigurations().get(1).getConfigurationId(),
            saved.broadcastConfigurations().getFirst().getConfigurationId());
        assertEquals(firstOwner, channelRow(fixture, first));
        assertEquals(firstHistory, historyRow(fixture, first));
        assertEquals(sequence, sequence(fixture));
    }

    @Test
    void radioResolveSwapReleasesOnlyChangingClaimsAndPreservesAllOwners() throws Exception
    {
        Fixture fixture = fixture("correlation-swap");
        ConfigurationSnapshot current = fixture.repository().load();
        Channel first = current.channels().get(0);
        Channel second = current.channels().get(1);
        Channel third = current.channels().get(2);
        long firstOwner = channelRow(fixture, first);
        long firstHistory = historyRow(fixture, first);
        String firstCorrelation = first.getRadioResolveId();
        String secondCorrelation = second.getRadioResolveId();
        forbid(fixture.database(), "configuration_channel", "INSERT", null);
        forbid(fixture.database(), "configuration_channel", "DELETE", null);
        forbid(fixture.database(), "configuration_channel", "UPDATE", "OLD.configuration_id='" + third.getConfigurationId() + "'");
        forbidAll(fixture.database(), "configuration_broadcast_stream");
        first.setRadioResolveId(secondCorrelation);
        second.setRadioResolveId(firstCorrelation);
        fixture.repository().commitChannelConfiguration(new ChannelConfigurationSnapshot(current.channels()));
        assertEquals(secondCorrelation, fixture.repository().load().channels().getFirst().getRadioResolveId());
        assertEquals(firstOwner, channelRow(fixture, first));
        assertEquals(firstHistory, historyRow(fixture, first));
    }

    @Test
    void decoderChangeReplacesOnlyItsOwnerAndDiscardsOnlyIncompatibleHistory() throws Exception
    {
        Fixture fixture = fixture("decoder-change");
        ConfigurationSnapshot current = fixture.repository().load();
        Channel changed = current.channels().getFirst();
        long oldOwner = channelRow(fixture, changed);
        Channel retained = current.channels().get(1);
        long retainedHistory = historyRow(fixture, retained);
        forbid(fixture.database(), "configuration_channel", "INSERT", "NEW.configuration_id<>'" + changed.getConfigurationId() + "'");
        forbid(fixture.database(), "configuration_channel", "DELETE", "OLD.configuration_id<>'" + changed.getConfigurationId() + "'");
        forbid(fixture.database(), "configuration_channel", "UPDATE", null);
        forbidAll(fixture.database(), "configuration_broadcast_stream");
        changed.setDecodeConfiguration(new DecodeConfigNBFM());
        save(fixture, current);
        assertNotEquals(oldOwner, channelRow(fixture, changed));
        assertEquals(0, number(fixture, "SELECT count(*) FROM receiver_channel WHERE " + id(changed)));
        assertEquals(retainedHistory, historyRow(fixture, retained));
    }

    @Test
    void changingChannelKindOrNxdnAddressDomainReplacesOnlyIncompatibleHistory() throws Exception
    {
        for(boolean nxdn: List.of(false, true))
        {
            Fixture fixture = fixture(nxdn ? "nxdn-domain" : "dmr-kind");
            ConfigurationSnapshot initial = fixture.repository().load();
            Channel changed = initial.channels().getFirst();
            changed.setDecodeConfiguration(nxdn ? new DecodeConfigNXDN() : new DecodeConfigDMR());
            changed.setAliasListDefinition(definition(initial.aliasListDefinitions(),
                nxdn ? AliasListFamily.NXDN : AliasListFamily.DMR));
            fixture.repository().commitChannelConfiguration(new ChannelConfigurationSnapshot(initial.channels()));
            execute(fixture.database(), "INSERT INTO receiver_channel(configuration_id,first_seen_ms,last_seen_ms) " +
                "VALUES ('" + changed.getConfigurationId() + "',100,200)");
            long previous = channelRow(fixture, changed);
            long retainedHistory = historyRow(fixture, initial.channels().get(1));
            forbid(fixture.database(), "configuration_channel", "UPDATE", null);
            forbid(fixture.database(), "configuration_channel", "INSERT", "NEW.configuration_id<>'" + changed.getConfigurationId() + "'");
            forbid(fixture.database(), "configuration_channel", "DELETE", "OLD.configuration_id<>'" + changed.getConfigurationId() + "'");
            forbidAll(fixture.database(), "configuration_broadcast_stream");
            if(nxdn)
                ((DecodeConfigNXDN)changed.getDecodeConfiguration()).setTransmissionMode(TransmissionMode.TYPE_D);
            else
                ((DecodeConfigDMR)changed.getDecodeConfiguration()).setChannelMode(DMRChannelMode.TRUNKED);
            fixture.repository().commitChannelConfiguration(new ChannelConfigurationSnapshot(initial.channels()));
            assertNotEquals(previous, channelRow(fixture, changed));
            assertEquals(0, number(fixture, "SELECT count(*) FROM receiver_channel WHERE " + id(changed)));
            assertEquals(retainedHistory, historyRow(fixture, initial.channels().get(1)));
        }
    }

    @Test
    void failureInStreamSaveRollsBackChannelEditsDeletionsAndHistory() throws Exception
    {
        Fixture fixture = fixture("rollback");
        ConfigurationSnapshot current = fixture.repository().load();
        String before = fixture.repository().fingerprint();
        String sequence = sequence(fixture);
        long removedHistory = historyRow(fixture, current.channels().get(2));
        forbid(fixture.database(), "configuration_broadcast_stream", "UPDATE", null);
        current.channels().getFirst().setName("Must roll back");
        current.broadcastConfigurations().getFirst().setName("Rejected destination");
        assertThrows(SQLException.class, () -> fixture.repository().synchronizeChannelAndBroadcastConfiguration(
            current.channels().subList(0, 2), current.broadcastConfigurations()));
        assertEquals(before, fixture.repository().fingerprint());
        assertEquals(sequence, sequence(fixture));
        assertEquals(removedHistory, historyRow(fixture, current.channels().get(2)));
    }

    @Test
    void invalidIdentityFailsBeforeAnyConfigurationWrite() throws Exception
    {
        Fixture fixture = fixture("invalid");
        ConfigurationSnapshot current = fixture.repository().load();
        String before = fixture.repository().fingerprint();
        forbidAll(fixture.database(), "configuration_channel");
        forbidAll(fixture.database(), "configuration_broadcast_stream");
        Channel duplicate = current.channels().get(1);
        duplicate.setConfigurationId(current.channels().getFirst().getConfigurationId());
        assertThrows(IllegalArgumentException.class, () -> save(fixture, current));
        assertEquals(before, fixture.repository().fingerprint());
    }

    private Fixture fixture(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name + ".sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationRepository repository = new ConfigurationRepository(database);
        List<AliasListDefinition> definitions = repository.loadAliasConfiguration().definitions();
        Channel first = channel("First", 121_900_000L, definitions);
        Channel second = channel("Second", 122_000_000L, definitions);
        Channel third = channel("P25 site", 851_012_500L, definitions);
        third.setDecodeConfiguration(new DecodeConfigP25Phase1());
        third.setAliasListDefinition(definition(definitions, AliasListFamily.P25));
        repository.synchronizeChannelAndBroadcastConfiguration(List.of(first, second, third),
            List.of(stream("First destination"), stream("Second destination")));
        for(Channel channel: List.of(first, second, third))
        {
            execute(database, "INSERT INTO receiver_channel(configuration_id,first_seen_ms,last_seen_ms) VALUES ('" +
                channel.getConfigurationId() + "',100,200)");
        }
        return new Fixture(database, repository);
    }

    private static Channel channel(String name, long frequency, List<AliasListDefinition> definitions)
    {
        Channel channel = new Channel(name);
        channel.setDecodeConfiguration(new DecodeConfigAM());
        channel.setAliasListDefinition(definition(definitions, AliasListFamily.NBFM));
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(frequency);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static AliasListDefinition definition(List<AliasListDefinition> definitions, AliasListFamily family)
    {
        return definitions.stream().filter(definition -> definition.getFamily() == family).findFirst().orElseThrow();
    }

    private static BroadcastifyCallConfiguration stream(String name)
    {
        BroadcastifyCallConfiguration configuration = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
        configuration.setName(name);
        return configuration;
    }

    private static void save(Fixture fixture, ConfigurationSnapshot snapshot) throws Exception
    {
        fixture.repository().synchronizeChannelAndBroadcastConfiguration(snapshot.channels(), snapshot.broadcastConfigurations());
    }

    private static String id(Channel channel)
    {
        return "configuration_id='" + channel.getConfigurationId() + "'";
    }

    private static long channelRow(Fixture fixture, Channel channel) throws Exception
    {
        return number(fixture, "SELECT id FROM configuration_channel WHERE " + id(channel));
    }

    private static long historyRow(Fixture fixture, Channel channel) throws Exception
    {
        return number(fixture, "SELECT id FROM receiver_channel WHERE " + id(channel));
    }

    private static long streamRow(Fixture fixture, BroadcastConfiguration configuration) throws Exception
    {
        return number(fixture, "SELECT id FROM configuration_broadcast_stream WHERE configuration_id='" +
            configuration.getConfigurationId() + "'");
    }

    private static String sequence(Fixture fixture) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(fixture.database()); Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT group_concat(name || ':' || seq, ',') FROM " +
                "(SELECT name,seq FROM sqlite_sequence ORDER BY name)"))
        {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static long number(Fixture fixture, String sql) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(fixture.database()); Statement statement = connection.createStatement();
            ResultSet row = statement.executeQuery(sql))
        {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private void forbidAliasWrites(Fixture fixture) throws Exception
    {
        for(String table: ALIAS_TABLES) forbidAll(fixture.database(), table);
    }

    private void forbidExceptUpdate(Fixture fixture, String table, String configurationId) throws Exception
    {
        forbid(fixture.database(), table, "INSERT", null);
        forbid(fixture.database(), table, "DELETE", null);
        forbid(fixture.database(), table, "UPDATE", "OLD.configuration_id<>'" + configurationId + "'");
        //Even the selected owner must not release a correlation ID unless that ID itself changes.
        if(table.equals("configuration_channel"))
            forbid(fixture.database(), table, "UPDATE", "NEW.radioresolve_id IS NOT OLD.radioresolve_id");
    }

    private void forbidAll(Path database, String table) throws Exception
    {
        for(String operation: List.of("INSERT", "UPDATE", "DELETE")) forbid(database, table, operation, null);
    }

    private void forbid(Path database, String table, String operation, String condition) throws Exception
    {
        execute(database, "CREATE TRIGGER forbid_write_" + mTrigger++ + " BEFORE " + operation + " ON " + table +
            (condition == null ? "" : " WHEN " + condition) + " BEGIN SELECT RAISE(ABORT,'unexpected " + operation +
            " on " + table + "'); END");
    }

    private static void execute(Path database, String sql) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database); Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }

    private record Fixture(Path database, ConfigurationRepository repository) {}
}
