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

package io.github.dsheirer.database.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasFactory;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.scanlist.ScanListDatabaseStore;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasConfigurationDatabaseStoreTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void commitReturnsCanonicalGeneratedIdsWithoutMutatingCallerObjects() throws Exception
    {
        Path database = database("generated-identities.sqlite");
        AliasListDefinition proposedDefinition = new AliasListDefinition("County P25", AliasListFamily.P25);
        Alias proposedAlias = alias("Dispatch", proposedDefinition, 1001);
        ScanList proposedDefault = ScanList.defaultScanList();
        AliasConfigurationSnapshot proposed = new AliasConfigurationSnapshot(List.of(proposedDefinition),
            List.of(proposedAlias), new ScanListConfiguration(List.of(proposedDefault), Map.of(), Map.of()));

        AliasConfigurationSnapshot committed = new AliasConfigurationDatabaseStore(database)
            .commit(proposed, Set.of());

        AliasListDefinition committedDefinition = committed.definitions().getFirst();
        Alias committedAlias = committed.aliases().getFirst();
        ScanList committedDefault = committed.scanLists().defaultScanList();
        assertNotSame(proposedDefinition, committedDefinition);
        assertNotSame(proposedAlias, committedAlias);
        assertNotSame(proposedDefault, committedDefault);
        assertTrue(committedDefinition.getId() > AliasListDefinition.UNASSIGNED_ID);
        assertTrue(committedAlias.getId() > Alias.UNASSIGNED_ID);
        assertTrue(committedDefault.getId() > ScanList.UNASSIGNED_ID);
        assertEquals(committedDefinition.getId(), committedAlias.getAliasListId());

        assertEquals(AliasListDefinition.UNASSIGNED_ID, proposedDefinition.getId());
        assertEquals(Alias.UNASSIGNED_ID, proposedAlias.getId());
        assertEquals(Alias.UNASSIGNED_ALIAS_LIST_ID, proposedAlias.getAliasListId());
        assertEquals(ScanList.UNASSIGNED_ID, proposedDefault.getId());

        AliasConfigurationSnapshot reloaded = new AliasConfigurationDatabaseStore(database).load();
        assertEquals(committedDefinition.getId(), reloaded.definitions().getFirst().getId());
        assertEquals(committedAlias.getId(), reloaded.aliases().getFirst().getId());
        assertEquals(committedAlias.getAliasListId(), reloaded.aliases().getFirst().getAliasListId());
        assertEquals(committedDefault.getId(), reloaded.scanLists().defaultScanList().getId());
    }

    @Test
    void ordinaryAliasCommitLeavesChannelAndBroadcastRowsUnchanged() throws Exception
    {
        Path database = database("row-scope.sqlite");
        AliasConfigurationDatabaseStore store = new AliasConfigurationDatabaseStore(database);
        AliasConfigurationSnapshot baseline = seedAlias(store, database, "County P25", 1001);

        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            insertChannel(connection, 41L, "County P25",
                "{  \"aliasListName\" : \"County P25\", \"payload\" : \"channel bytes\"  }");
            insertBroadcast(connection, 71L, "{  \"payload\" : \"broadcast bytes\"  }");
        }

        List<List<Object>> channelsBefore = rows(database, "configuration_channel");
        List<List<Object>> broadcastsBefore = rows(database, "configuration_broadcast_stream");
        Alias replacement = AliasFactory.copyOf(baseline.aliases().getFirst());
        replacement.setId(baseline.aliases().getFirst().getId());
        replacement.setDescription("Updated description");

        AliasConfigurationSnapshot committed = store.commit(new AliasConfigurationSnapshot(
            baseline.definitions(), List.of(replacement), baseline.scanLists()), Set.of());

        assertEquals("Updated description", committed.aliases().getFirst().getDescription());
        assertEquals(channelsBefore, rows(database, "configuration_channel"));
        assertEquals(broadcastsBefore, rows(database, "configuration_broadcast_stream"));
    }

    @Test
    void aliasListDeletionClearsOnlyMatchingChannelAssignments() throws Exception
    {
        Path database = database("list-delete.sqlite");
        AliasConfigurationDatabaseStore store = new AliasConfigurationDatabaseStore(database);
        AliasConfigurationSnapshot baseline = seedAlias(store, database, "County P25", 1001);

        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            insertChannel(connection, 41L, "cOuNtY p25",
                "{\"aliasListName\":\"County P25\",\"payload\":\"matching channel\"}");
            insertChannel(connection, 42L, "Other",
                "{  \"aliasListName\" : \"Other\", \"payload\" : \"unrelated channel bytes\"  }");
            insertBroadcast(connection, 71L, "{  \"payload\" : \"broadcast bytes\"  }");
        }

        List<Object> unrelatedChannelBefore = row(database, "configuration_channel", 42L);
        List<List<Object>> broadcastsBefore = rows(database, "configuration_broadcast_stream");
        ScanListConfiguration retainedScanLists = new ScanListConfiguration(
            baseline.scanLists().scanLists(), Map.of(), Map.of());

        AliasConfigurationSnapshot committed = store.commit(new AliasConfigurationSnapshot(
            List.of(), List.of(), retainedScanLists), Set.of("County P25"));

        assertTrue(committed.definitions().isEmpty());
        assertTrue(committed.aliases().isEmpty());
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("""
                SELECT alias_list_name,
                       json_extract(config_json, '$.aliasListName') AS json_alias_list_name,
                       json_extract(config_json, '$.payload') AS payload
                FROM configuration_channel
                WHERE id = 41
                """))
        {
            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                assertNull(resultSet.getString("alias_list_name"));
                assertNull(resultSet.getString("json_alias_list_name"));
                assertEquals("matching channel", resultSet.getString("payload"));
            }
        }

        assertEquals(unrelatedChannelBefore, row(database, "configuration_channel", 42L));
        assertEquals(broadcastsBefore, rows(database, "configuration_broadcast_stream"));
    }

    @Test
    void lateChannelFailureRollsBackAliasAndScanListChanges() throws Exception
    {
        Path database = database("late-rollback.sqlite");
        AliasConfigurationDatabaseStore store = new AliasConfigurationDatabaseStore(database);
        AliasConfigurationSnapshot seeded = seedAlias(store, database, "County P25", 1001);
        long aliasId = seeded.aliases().getFirst().getId();
        long defaultScanListId = seeded.scanLists().defaultScanList().getId();
        AliasConfigurationSnapshot baseline = store.commit(new AliasConfigurationSnapshot(seeded.definitions(),
            seeded.aliases(), new ScanListConfiguration(seeded.scanLists().scanLists(),
                Map.of(aliasId, Set.of(defaultScanListId)), Map.of())), Set.of());

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            insertChannel(connection, 41L, "County P25",
                "{\"aliasListName\":\"County P25\",\"payload\":\"must survive\"}");
            statement.executeUpdate("""
                CREATE TRIGGER reject_alias_list_clear
                BEFORE UPDATE OF alias_list_name ON configuration_channel
                WHEN OLD.id = 41
                BEGIN
                    SELECT RAISE(ABORT, 'forced late Alias-list clear failure');
                END
                """);
        }

        Map<String,List<List<Object>>> rowsBefore = aliasOwnedRows(database);
        List<Object> channelBefore = row(database, "configuration_channel", 41L);
        ScanListConfiguration proposedScanLists = new ScanListConfiguration(
            baseline.scanLists().scanLists(), Map.of(), Map.of());

        assertThrows(SQLException.class, () -> store.commit(new AliasConfigurationSnapshot(
            List.of(), List.of(), proposedScanLists), Set.of("County P25")));

        assertEquals(rowsBefore, aliasOwnedRows(database));
        assertEquals(channelBefore, row(database, "configuration_channel", 41L));
        AliasConfigurationSnapshot reloaded = store.load();
        assertEquals(baseline.definitions().getFirst().getId(), reloaded.definitions().getFirst().getId());
        assertEquals(aliasId, reloaded.aliases().getFirst().getId());
        assertEquals(Set.of(defaultScanListId), reloaded.scanLists().scanListIdsForAlias(aliasId));
    }

    @Test
    void lateBroadcastFailureRollsBackStreamAndAliasReferenceChanges() throws Exception
    {
        Path database = database("broadcast-rollback.sqlite");
        AliasConfigurationDatabaseStore store = new AliasConfigurationDatabaseStore(database);
        AliasConfigurationSnapshot seeded = seedAlias(store, database, "County P25", 1001);
        seeded.definitions().getFirst().setUnmatchedTalkgroupPolicy(
            new UnmatchedTalkgroupPolicy(false, List.of("Old Stream")));
        seeded.aliases().getFirst().addBroadcastChannel("Old Stream");
        AliasConfigurationSnapshot baseline = store.commit(seeded, Set.of());

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            insertBroadcast(connection, 71L, "{\"name\":\"Old Stream\",\"payload\":\"must survive\"}");
            statement.executeUpdate("""
                CREATE TRIGGER reject_new_stream
                BEFORE UPDATE OF name ON configuration_broadcast_stream
                WHEN NEW.name = 'New Stream'
                BEGIN
                    SELECT RAISE(ABORT, 'forced late broadcast failure');
                END
                """);
        }

        AliasListDefinition proposedDefinition = new AliasListDefinition("County P25", AliasListFamily.P25,
            new UnmatchedTalkgroupPolicy(false, List.of("New Stream")));
        proposedDefinition.setId(baseline.definitions().getFirst().getId());
        Alias proposedAlias = AliasFactory.copyOf(baseline.aliases().getFirst());
        proposedAlias.setId(baseline.aliases().getFirst().getId());
        proposedAlias.removeBroadcastChannel("Old Stream");
        proposedAlias.addBroadcastChannel("New Stream");
        proposedAlias.setAliasListDefinition(proposedDefinition);
        AliasConfigurationSnapshot proposed = new AliasConfigurationSnapshot(List.of(proposedDefinition),
            List.of(proposedAlias), baseline.scanLists());
        BroadcastifyCallConfiguration proposedStream = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
        proposedStream.setName("Old Stream");
        Map<String,List<List<Object>>> aliasRowsBefore = aliasOwnedRows(database);
        List<List<Object>> broadcastRowsBefore = rows(database, "configuration_broadcast_stream");

        assertThrows(SQLException.class, () -> store.commitWithBroadcastConfigurationRename(proposed, Set.of(),
            List.of(proposedStream), "Old Stream", "New Stream"));

        assertEquals(aliasRowsBefore, aliasOwnedRows(database));
        assertEquals(broadcastRowsBefore, rows(database, "configuration_broadcast_stream"));
    }

    private Path database(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        return database;
    }

    private static AliasConfigurationSnapshot seedAlias(AliasConfigurationDatabaseStore store, Path database,
                                                         String listName, int talkgroup) throws Exception
    {
        AliasListDefinition definition = new AliasListDefinition(listName, AliasListFamily.P25);
        Alias alias = alias("Dispatch", definition, talkgroup);
        ScanListConfiguration scanLists = new ScanListDatabaseStore(database).loadConfiguration();
        return store.commit(new AliasConfigurationSnapshot(List.of(definition), List.of(alias), scanLists),
            Set.of());
    }

    private static Alias alias(String name, AliasListDefinition definition, int talkgroup)
    {
        Alias alias = new Alias(name);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, talkgroup));
        return alias;
    }

    private static void insertChannel(Connection connection, long id, String aliasListName, String configJson)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_channel (
                id, sort_order, system_name, site_name, name, alias_list_name, radres_guid,
                auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz,
                frequency_count, recording_enabled, event_logging_enabled, config_json
            ) VALUES (?, 7, 'Test System', 'Test Site', ?, ?, ?, 1, 3, 'P25_PHASE1', 'TUNER',
                      851012500, 1, 1, 1, ?)
            """))
        {
            statement.setLong(1, id);
            statement.setString(2, "Channel " + id);
            statement.setString(3, aliasListName);
            statement.setString(4, "channel-guid-" + id);
            statement.setString(5, configJson);
            statement.executeUpdate();
        }
    }

    private static void insertBroadcast(Connection connection, long id, String configJson) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_broadcast_stream (
                id, sort_order, name, server_type, enabled, host, port, delay_ms,
                maximum_recording_age_ms, config_json
            ) VALUES (?, 9, ?, 'BROADCASTIFY_CALL', 1, 'example.invalid', 443, 1500, 90000, ?)
            """))
        {
            statement.setLong(1, id);
            statement.setString(2, "Stream " + id);
            statement.setString(3, configJson);
            statement.executeUpdate();
        }
    }

    private static Map<String,List<List<Object>>> aliasOwnedRows(Path database) throws Exception
    {
        Map<String,List<List<Object>>> snapshot = new LinkedHashMap<>();
        snapshot.put("alias_list", rows(database, "alias_list"));
        snapshot.put("alias", rows(database, "alias"));
        snapshot.put("alias_broadcast_channel", rows(database, "alias_broadcast_channel"));
        snapshot.put("alias_list_unmatched_talkgroup_stream",
            rows(database, "alias_list_unmatched_talkgroup_stream"));
        snapshot.put("scan_list", rows(database, "scan_list"));
        snapshot.put("alias_scan_list_membership", rows(database, "alias_scan_list_membership"));
        snapshot.put("alias_list_unmatched_talkgroup_scan_list_membership",
            rows(database, "alias_list_unmatched_talkgroup_scan_list_membership"));
        return snapshot;
    }

    private static List<Object> row(Path database, String table, long id) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table + " WHERE id = ?"))
        {
            statement.setLong(1, id);
            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return values(resultSet);
            }
        }
    }

    private static List<List<Object>> rows(Path database, String table) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT * FROM " + table + " ORDER BY 1"))
        {
            List<List<Object>> rows = new ArrayList<>();
            while(resultSet.next())
            {
                rows.add(values(resultSet));
            }
            return rows;
        }
    }

    private static List<Object> values(ResultSet resultSet) throws SQLException
    {
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<Object> values = new ArrayList<>(metadata.getColumnCount());
        for(int column = 1; column <= metadata.getColumnCount(); column++)
        {
            values.add(resultSet.getObject(column));
        }
        return values;
    }
}
