/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.database.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.configuration.ConfigurationSnapshot;
import io.github.dsheirer.configuration.ConfigurationSnapshotValidator;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationRepositorySnapshotTest
{
    private static final List<TableOrder> OWNED_TABLES = List.of(
        new TableOrder("alias_list", "id"),
        new TableOrder("alias", "id"),
        new TableOrder("scan_list", "id"),
        new TableOrder("alias_scan_list_membership", "alias_id, scan_list_id"),
        new TableOrder("alias_list_unmatched_talkgroup_scan_list_membership",
            "alias_list_id, scan_list_id"),
        new TableOrder("alias_broadcast_channel", "id"),
        new TableOrder("alias_list_unmatched_talkgroup_stream", "id"),
        new TableOrder("configuration_channel", "id"),
        new TableOrder("configuration_broadcast_stream", "id"));

    @TempDir
    Path mTemporaryFolder;

    @Test
    void roundTripsACompleteSnapshotAcrossAFreshRepository() throws Exception
    {
        Path database = database("snapshot-round-trip.sqlite");
        ConfigurationSnapshot committed = seed(new ConfigurationRepository(database), "County P25", 1001,
            851_012_500L, "Primary Stream");

        ConfigurationSnapshot restarted = new ConfigurationRepository(database).load();
        ConfigurationSnapshotValidator.validateForStartup(restarted);

        assertEquals(1, restarted.aliasListDefinitions().size());
        assertEquals(1, restarted.aliases().size());
        assertEquals(1, restarted.channels().size());
        assertEquals(1, restarted.broadcastConfigurations().size());
        assertEquals(committed.aliasListDefinitions().getFirst().getId(),
            restarted.aliasListDefinitions().getFirst().getId());
        assertEquals(committed.aliases().getFirst().getId(), restarted.aliases().getFirst().getId());
        assertEquals(Set.of(restarted.scanListConfiguration().defaultScanList().getId()),
            restarted.scanListConfiguration().scanListIdsForAlias(restarted.aliases().getFirst().getId()));
        assertEquals(Set.of(restarted.scanListConfiguration().defaultScanList().getId()),
            restarted.scanListConfiguration().scanListIdsForUnmatchedTalkgroups(
                restarted.aliasListDefinitions().getFirst().getId()));
        SourceConfigTuner source = assertInstanceOf(SourceConfigTuner.class,
            restarted.channels().getFirst().getSourceConfiguration());
        assertEquals(851_012_500L, source.getFrequency());
        assertEquals("Primary Stream", restarted.broadcastConfigurations().getFirst().getName());
        assertDatabaseReferencesValid(database);
    }

    @Test
    void rollsBackEveryOwnedTableWhenTheLastWriteFails() throws Exception
    {
        Path database = database("snapshot-rollback.sqlite");
        ConfigurationRepository repository = new ConfigurationRepository(database);
        ConfigurationSnapshot baseline = seed(repository, "County P25", 1001, 851_012_500L, "Primary Stream");
        Map<String,List<List<Object>>> before = ownedRows(database);

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TRIGGER reject_replacement_stream
                BEFORE INSERT ON configuration_broadcast_stream
                WHEN NEW.name = 'Rejected Stream'
                BEGIN
                    SELECT RAISE(ABORT, 'forced final snapshot write failure');
                END
                """);
        }

        ScanListConfiguration replacementScanLists = new ScanListConfiguration(
            baseline.scanListConfiguration().scanLists(), Map.of(), Map.of());
        ConfigurationSnapshot rejected = candidate(replacementScanLists, "Replacement P25", 2002,
            852_012_500L, "Rejected Stream");
        assertThrows(SQLException.class, () -> repository.replace(rejected));

        assertEquals(before, ownedRows(database));
        ConfigurationSnapshot reloaded = repository.load();
        assertEquals("County P25", reloaded.aliasListDefinitions().getFirst().getName());
        assertEquals("Dispatch 1001", reloaded.aliases().getFirst().getName());
        assertEquals("Control 1001", reloaded.channels().getFirst().getName());
        assertEquals("Primary Stream", reloaded.broadcastConfigurations().getFirst().getName());
        assertDatabaseReferencesValid(database);
    }

    @Test
    void rollsBackWhenCanonicalReadFailsAfterEveryInsert() throws Exception
    {
        Path database = database("snapshot-readback-rollback.sqlite");
        ConfigurationRepository repository = new ConfigurationRepository(database);
        ConfigurationSnapshot baseline = seed(repository, "County P25", 1001,
            851_012_500L, "Primary Stream");
        Map<String,List<List<Object>>> before = ownedRows(database);

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TRIGGER corrupt_replacement_stream
                AFTER INSERT ON configuration_broadcast_stream
                WHEN NEW.name = 'Corrupted Stream'
                BEGIN
                    UPDATE configuration_broadcast_stream SET config_json = '{' WHERE id = NEW.id;
                END
                """);
        }

        ScanListConfiguration replacementScanLists = new ScanListConfiguration(
            baseline.scanListConfiguration().scanLists(), Map.of(), Map.of());
        ConfigurationSnapshot corrupted = candidate(replacementScanLists, "Replacement P25", 2002,
            852_012_500L, "Corrupted Stream");
        assertThrows(java.io.IOException.class, () -> repository.replace(corrupted));

        assertEquals(before, ownedRows(database));
        assertEquals("County P25", repository.load().aliasListDefinitions().getFirst().getName());
        assertDatabaseReferencesValid(database);
    }

    @Test
    void concurrentLoadsPairEachSnapshotWithItsOwnFingerprint() throws Exception
    {
        Path database = database("snapshot-concurrent-read.sqlite");
        ConfigurationRepository repository = new ConfigurationRepository(database);
        seed(repository, "State A", 1001, 851_012_500L, "Primary Stream");

        //Run both updates once so SQLite JSON formatting is stable before capturing the two expected tokens.
        updateMarker(database, "State B", "Alias B", "Channel B");
        updateMarker(database, "State A", "Alias A", "Channel A");
        String fingerprintA = repository.fingerprint();
        updateMarker(database, "State B", "Alias B", "Channel B");
        String fingerprintB = repository.fingerprint();
        updateMarker(database, "State A", "Alias A", "Channel A");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<?> writer = executor.submit(() ->
            {
                start.await();
                try(Connection connection = SdrTrunkDatabase.open(database))
                {
                    for(int iteration = 0; iteration < 8; iteration++)
                    {
                        updateMarker(connection, "State B", "Alias B", "Channel B");
                        updateMarker(connection, "State A", "Alias A", "Channel A");
                    }
                }
                return null;
            });
            start.countDown();

            int reads = 0;
            while(!writer.isDone() || reads < 8)
            {
                ConfigurationRepository.FingerprintedSnapshot read = repository.loadWithFingerprint();
                String listName = read.snapshot().aliasListDefinitions().getFirst().getName();
                if("State A".equals(listName))
                {
                    assertEquals("Alias A", read.snapshot().aliases().getFirst().getName());
                    assertEquals("Channel A", read.snapshot().channels().getFirst().getName());
                    assertEquals("State A", read.snapshot().channels().getFirst().getAliasListName());
                    assertEquals(fingerprintA, read.fingerprint());
                }
                else
                {
                    assertEquals("State B", listName);
                    assertEquals("Alias B", read.snapshot().aliases().getFirst().getName());
                    assertEquals("Channel B", read.snapshot().channels().getFirst().getName());
                    assertEquals("State B", read.snapshot().channels().getFirst().getAliasListName());
                    assertEquals(fingerprintB, read.fingerprint());
                }
                reads++;
            }
            writer.get(20, TimeUnit.SECONDS);
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    private Path database(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        return database;
    }

    private static ConfigurationSnapshot seed(ConfigurationRepository repository, String listName, int talkgroup,
                                              long frequency, String streamName) throws Exception
    {
        ConfigurationSnapshot factory = repository.load();
        ScanListConfiguration blankScanLists = new ScanListConfiguration(
            factory.scanListConfiguration().scanLists(), Map.of(), Map.of());
        ConfigurationSnapshot firstCommit = repository.replace(candidate(blankScanLists, listName, talkgroup,
            frequency, streamName));
        long aliasId = firstCommit.aliases().getFirst().getId();
        long aliasListId = firstCommit.aliasListDefinitions().getFirst().getId();
        long defaultScanListId = firstCommit.scanListConfiguration().defaultScanList().getId();
        ScanListConfiguration memberships = new ScanListConfiguration(
            firstCommit.scanListConfiguration().scanLists(), Map.of(aliasId, Set.of(defaultScanListId)),
            Map.of(aliasListId, Set.of(defaultScanListId)));
        return repository.replace(new ConfigurationSnapshot(firstCommit.aliasListDefinitions(),
            firstCommit.aliases(), memberships, firstCommit.channels(), firstCommit.broadcastConfigurations()));
    }

    private static ConfigurationSnapshot candidate(ScanListConfiguration scanLists, String listName, int talkgroup,
                                                   long frequency, String streamName)
    {
        AliasListDefinition definition = new AliasListDefinition(listName, AliasListFamily.P25);
        Alias alias = new Alias("Dispatch " + talkgroup);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, talkgroup));
        alias.addBroadcastChannel(streamName);

        Channel channel = new Channel("Control " + talkgroup);
        channel.setAliasListName(listName);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(frequency);
        channel.setSourceConfiguration(source);

        RadioResolveConfiguration stream = new RadioResolveConfiguration();
        stream.setName(streamName);
        return new ConfigurationSnapshot(List.of(definition), List.of(alias), scanLists, List.of(channel),
            List.of(stream));
    }

    private static Map<String,List<List<Object>>> ownedRows(Path database) throws Exception
    {
        Map<String,List<List<Object>>> result = new LinkedHashMap<>();
        for(TableOrder table: OWNED_TABLES)
        {
            result.put(table.name(), rows(database, table));
        }
        return result;
    }

    private static void updateMarker(Path database, String aliasListName, String aliasName, String channelName)
        throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            updateMarker(connection, aliasListName, aliasName, channelName);
        }
    }

    private static void updateMarker(Connection connection, String aliasListName, String aliasName,
                                     String channelName) throws Exception
    {
        try(PreparedStatement updateList = connection.prepareStatement("UPDATE alias_list SET name = ?");
            PreparedStatement updateAlias = connection.prepareStatement("UPDATE alias SET name = ?");
            PreparedStatement updateChannel = connection.prepareStatement("""
                UPDATE configuration_channel
                SET name = ?, alias_list_name = ?,
                    config_json = json_set(config_json, '$.name', ?, '$.aliasListName', ?)
                """))
        {
            connection.setAutoCommit(false);
            updateList.setString(1, aliasListName);
            updateList.executeUpdate();
            updateAlias.setString(1, aliasName);
            updateAlias.executeUpdate();
            updateChannel.setString(1, channelName);
            updateChannel.setString(2, aliasListName);
            updateChannel.setString(3, channelName);
            updateChannel.setString(4, aliasListName);
            updateChannel.executeUpdate();
            connection.commit();
        }
    }

    private static List<List<Object>> rows(Path database, TableOrder table) throws Exception
    {
        List<List<Object>> rows = new ArrayList<>();
        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT * FROM " + table.name() +
                " ORDER BY " + table.orderBy()))
        {
            ResultSetMetaData metadata = resultSet.getMetaData();
            while(resultSet.next())
            {
                List<Object> row = new ArrayList<>();
                for(int column = 1; column <= metadata.getColumnCount(); column++)
                {
                    row.add(resultSet.getObject(column));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static void assertDatabaseReferencesValid(Path database) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check"))
        {
            assertTrue(!resultSet.next(), "Configuration contains an orphaned database reference");
        }
    }

    private record TableOrder(String name, String orderBy)
    {
    }
}
