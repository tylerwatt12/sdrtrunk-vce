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

package io.github.dsheirer.database.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasDatabaseStoreTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void roundTripsOneOperationalMatcherAndBehavior() throws Exception
    {
        Path database = database("round-trip.sqlite");
        AliasDatabaseStore store = new AliasDatabaseStore(database);
        AliasListDefinition definition = definition("Lake County", AliasListFamily.P25);
        definition.setUnmatchedTalkgroupPolicy(new UnmatchedTalkgroupPolicy(true,
            List.of("Unknown Calls", "Archive")));
        Alias alias = alias("County Fire Dispatch", definition, 1001);
        alias.setDescription("Countywide fire dispatch");
        alias.setGroup("Fire");
        alias.setColor(0x123456);
        alias.setIconName("Fire Truck");
        alias.setStreamTalkgroupAlias(new StreamAsTalkgroup(42));
        alias.setRecordable(true);
        alias.addBroadcastChannel(new BroadcastChannel("RadioResolve"));

        AliasConfigurationSnapshot committed = replace(store, List.of(alias), List.of(definition));
        Alias committedAlias = committed.aliases().getFirst();
        AliasListDefinition committedDefinition = committed.definitions().getFirst();

        assertNotEquals(Alias.UNASSIGNED_ID, committedAlias.getId());
        assertNotEquals(AliasListDefinition.UNASSIGNED_ID, committedDefinition.getId());
        assertEquals(committedDefinition.getId(), committedAlias.getAliasListId());

        List<AliasListDefinition> definitions = store.loadAliasListDefinitions();
        Alias loaded = store.loadAliases(definitions).getFirst();
        assertEquals("Lake County", definitions.getFirst().getName());
        assertEquals(AliasListFamily.P25, definitions.getFirst().getFamily());
        assertTrue(definitions.getFirst().getUnmatchedTalkgroupPolicy().isRecordEnabled());
        assertEquals(List.of("Unknown Calls", "Archive"),
            definitions.getFirst().getUnmatchedTalkgroupPolicy().getStreamDestinationNames());
        assertEquals(committedAlias.getId(), loaded.getId());
        assertEquals(definitions.getFirst().getId(), loaded.getAliasListId());
        assertEquals("Countywide fire dispatch", loaded.getDescription());
        assertEquals("Fire", loaded.getGroup());
        assertEquals(0x123456, loaded.getColor());
        assertEquals("Fire Truck", loaded.getIconName());
        assertEquals(42, loaded.getStreamTalkgroupAlias().getValue());
        assertTrue(loaded.isRecordable());
        assertEquals("RadioResolve", loaded.getBroadcastChannels().iterator().next().getChannelName());
        assertEquals("RadioResolve", loaded.getBroadcastChannels().iterator().next().valueProperty().get());
        assertEquals(1001, ((Talkgroup)loaded.getMatchIdentifier()).getValue());
        assertEquals(loaded.getMatchIdentifier().toString(), loaded.getMatchIdentifier().valueProperty().get());
        assertEquals(loaded.getStreamTalkgroupAlias().toString(),
            loaded.getStreamTalkgroupAlias().valueProperty().get());

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT matcher_type, protocol, value FROM alias
                """))
        {
            assertTrue(resultSet.next());
            assertEquals("TALKGROUP", resultSet.getString("matcher_type"));
            assertEquals("APCO25", resultSet.getString("protocol"));
            assertEquals(1001, resultSet.getInt("value"));
            assertEquals(1, countRows(connection, "alias_talkgroup"));
            assertEquals(0, countRows(connection, "alias_radio"));
            assertEquals(2, countRows(connection, "alias_list_unmatched_talkgroup_stream"));
        }
    }

    @Test
    void currentSchemaContainsNoReviewOrLegacySnapshotColumns() throws Exception
    {
        Path database = database("strict-columns.sqlite");

        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            Set<String> aliasListColumns = columns(connection, "alias_list");
            assertEquals(Set.of("id", "name", "family", "unmatched_talkgroup_record_enabled"), aliasListColumns);
            assertFalse(aliasListColumns.contains("system_name"));
            assertFalse(aliasListColumns.contains("assignable"));
            assertFalse(aliasListColumns.contains("needs_review"));
            assertFalse(aliasListColumns.contains("aux_decoder_types"));

            Set<String> aliasColumns = columns(connection, "alias");
            assertFalse(aliasColumns.contains("alias_list_name"));
            assertFalse(aliasColumns.contains("non_recordable"));
            assertFalse(aliasColumns.contains("matcher_enabled"));
            assertFalse(aliasColumns.contains("compatibility_reason"));
            assertFalse(aliasColumns.contains("wacn"));
            assertFalse(aliasColumns.contains("p25_system_id"));
            assertFalse(aliasColumns.contains("priority"));
            assertFalse(hasTable(connection, "alias_action"));
            assertTrue(hasTable(connection, "alias_list_unmatched_talkgroup_stream"));
            assertTrue(hasTable(connection, "scan_list"));
            assertTrue(hasTable(connection, "alias_scan_list_membership"));
            assertFalse(indexes(connection).contains("idx_alias_list_name"));
            assertFalse(indexes(connection).contains("idx_alias_broadcast_channel_alias"));
            assertFalse(indexes(connection).contains("idx_alias_action_alias"));
            assertFalse(columns(connection, "alias_talkgroup").contains("id"));
            assertFalse(columns(connection, "alias_talkgroup").contains("sort_order"));
            assertFalse(columns(connection, "alias_talkgroup").contains("fully_qualified"));
            assertFalse(columns(connection, "alias_talkgroup").contains("wacn"));
            assertFalse(columns(connection, "alias_talkgroup").contains("system_id"));
            assertFalse(columns(connection, "alias_radio").contains("fully_qualified"));
            assertFalse(columns(connection, "alias_radio").contains("wacn"));
            assertFalse(columns(connection, "alias_radio").contains("system_id"));

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name='alias'"))
            {
                assertTrue(resultSet.next());
                assertFalse(resultSet.getString("sql").contains("P25_FULLY_QUALIFIED_TALKGROUP"));
            }
        }
    }

    @Test
    void preservesStableIdsAcrossCompleteSnapshotReplacement() throws Exception
    {
        AliasDatabaseStore store = new AliasDatabaseStore(database("stable-ids.sqlite"));
        AliasListDefinition definition = definition("County", AliasListFamily.P25);
        Alias first = alias("Dispatch", definition, 100);
        Alias second = alias("Operations", definition, 200);
        AliasConfigurationSnapshot committed = replace(store, List.of(first, second), List.of(definition));
        definition = committed.definitions().getFirst();
        first = committed.aliases().stream().filter(saved -> saved.getName().equals("Dispatch")).findFirst()
            .orElseThrow();
        second = committed.aliases().stream().filter(saved -> saved.getName().equals("Operations")).findFirst()
            .orElseThrow();
        long listId = definition.getId();
        long firstId = first.getId();
        long secondId = second.getId();

        first.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 101));
        replace(store, List.of(second, first), List.of(definition));

        List<AliasListDefinition> definitions = store.loadAliasListDefinitions();
        List<Alias> aliases = store.loadAliases(definitions);
        assertEquals(listId, definitions.getFirst().getId());
        assertEquals("County", definitions.getFirst().getName());
        assertEquals(firstId, aliases.get(0).getId());
        assertEquals(secondId, aliases.get(1).getId());
        assertEquals(101, ((Talkgroup)aliases.get(0).getMatchIdentifier()).getValue());
    }

    @Test
    void persistsEmptyProtocolOwnedList() throws Exception
    {
        AliasDatabaseStore store = new AliasDatabaseStore(database("empty-list.sqlite"));
        AliasListDefinition definition = definition("Empty P25", AliasListFamily.P25);

        AliasConfigurationSnapshot committed = replace(store, List.of(), List.of(definition));

        assertEquals(committed.definitions().getFirst().getId(),
            store.loadAliasListDefinitions().getFirst().getId());
        assertTrue(store.loadAliases(store.loadAliasListDefinitions()).isEmpty());
    }

    @Test
    void rejectsAnythingOtherThanOneOperationalMatcher() throws Exception
    {
        AliasDatabaseStore store = new AliasDatabaseStore(database("strict-runtime.sqlite"));
        AliasListDefinition definition = definition("County P25", AliasListFamily.P25);

        Alias missing = new Alias("Missing");
        missing.setAliasListDefinition(definition);
        assertThrows(SQLException.class,
            () -> replace(store, List.of(missing), List.of(definition)));

        Alias wrongFamily = new Alias("Wrong Protocol");
        wrongFamily.setAliasListDefinition(definition);
        wrongFamily.setMatchIdentifier(new Talkgroup(Protocol.DMR, 100));
        SQLException familyFailure = assertThrows(SQLException.class,
            () -> replace(store, List.of(wrongFamily), List.of(definition)));
        assertTrue(familyFailure.getMessage().contains("not valid for alias list"));

        Alias nameOnly = new Alias("Name Only");
        nameOnly.setAliasListName("County P25");
        nameOnly.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        assertThrows(SQLException.class, () -> replace(store, List.of(nameOnly), List.of()));
    }

    @Test
    void rejectsUnclassifiedLists() throws Exception
    {
        AliasDatabaseStore store = new AliasDatabaseStore(database("invalid-list.sqlite"));
        AliasListDefinition unclassified = definition("Unclassified", null);
        SQLException familyFailure =
            assertThrows(SQLException.class, () -> replace(store, List.of(), List.of(unclassified)));
        assertTrue(familyFailure.getMessage().contains("protocol family"));
    }

    @Test
    void malformedCurrentRowsFailWithoutRepair() throws Exception
    {
        AliasDatabaseStore protocolStore = populatedStore("malformed-protocol.sqlite");
        bypassChecks(protocolStore.getDatabasePath(), "UPDATE alias SET protocol = 'NOT_A_PROTOCOL'");
        assertThrows(SQLException.class, () -> loadAliases(protocolStore));
        assertEquals("NOT_A_PROTOCOL",
            scalarText(protocolStore.getDatabasePath(), "SELECT protocol FROM alias"));

        AliasDatabaseStore requiredValueStore = populatedStore("missing-value.sqlite");
        bypassChecks(requiredValueStore.getDatabasePath(), "UPDATE alias SET value = NULL");
        assertThrows(SQLException.class, () -> loadAliases(requiredValueStore));
        assertNull(scalarText(requiredValueStore.getDatabasePath(), "SELECT value FROM alias"));

        AliasDatabaseStore unusedPayloadStore = populatedStore("unused-payload.sqlite");
        bypassChecks(unusedPayloadStore.getDatabasePath(), "UPDATE alias SET min_value = 99");
        assertThrows(SQLException.class, () -> loadAliases(unusedPayloadStore));
        assertEquals("99", scalarText(unusedPayloadStore.getDatabasePath(), "SELECT min_value FROM alias"));

        AliasDatabaseStore familyStore = populatedStore("malformed-family.sqlite");
        bypassChecks(familyStore.getDatabasePath(), "UPDATE alias_list SET family = 'NOT_A_FAMILY'");
        assertThrows(SQLException.class, familyStore::loadAliasListDefinitions);
        assertEquals("NOT_A_FAMILY",
            scalarText(familyStore.getDatabasePath(), "SELECT family FROM alias_list"));

        AliasDatabaseStore recordStore = populatedStore("malformed-unmatched-record.sqlite");
        bypassChecks(recordStore.getDatabasePath(),
            "UPDATE alias_list SET unmatched_talkgroup_record_enabled = 2");
        assertThrows(SQLException.class, recordStore::loadAliasListDefinitions);
        assertEquals("2", scalarText(recordStore.getDatabasePath(),
            "SELECT unmatched_talkgroup_record_enabled FROM alias_list"));

        AliasDatabaseStore routeStore = populatedStore("malformed-unmatched-route.sqlite");
        bypassChecks(routeStore.getDatabasePath(), """
            INSERT INTO alias_list_unmatched_talkgroup_stream(alias_list_id, channel_name)
            SELECT id, ' ' FROM alias_list
            """);
        assertThrows(SQLException.class, routeStore::loadAliasListDefinitions);

    }

    @Test
    void foreignKeyAndRouteConstraintsAreStrict() throws Exception
    {
        AliasDatabaseStore orphanStore = populatedStore("orphan.sqlite");
        try(Connection connection = SdrTrunkDatabase.open(orphanStore.getDatabasePath());
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("UPDATE alias SET alias_list_id = 9999");
        }
        assertThrows(SQLException.class, () -> loadAliases(orphanStore));

        AliasDatabaseStore routeStore = populatedStore("duplicate-route.sqlite");
        execute(routeStore.getDatabasePath(), """
            INSERT INTO alias_broadcast_channel(alias_id, channel_name)
            SELECT id, 'RadioResolve' FROM alias
            """);
        assertThrows(SQLException.class, () -> execute(routeStore.getDatabasePath(), """
            INSERT INTO alias_broadcast_channel(alias_id, channel_name)
            SELECT id, 'RadioResolve' FROM alias
            """));
    }

    @Test
    void identifierViewsExposeListNameAndRemainReadOnly() throws Exception
    {
        AliasDatabaseStore store = populatedStore("views.sqlite");
        try(Connection connection = SdrTrunkDatabase.open(store.getDatabasePath());
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "SELECT alias_list_name FROM alias_talkgroup"))
        {
            assertTrue(resultSet.next());
            assertEquals("County P25", resultSet.getString("alias_list_name"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO alias_talkgroup (
                    alias_id, protocol, value, ranged, alias_list_name
                ) VALUES (1, 'APCO25', 100, 0, 'County P25')
                """));
        }
    }

    private Path database(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        return database;
    }

    private AliasDatabaseStore populatedStore(String name) throws Exception
    {
        AliasDatabaseStore store = new AliasDatabaseStore(database(name));
        AliasListDefinition definition = definition("County P25", AliasListFamily.P25);
        Alias alias = alias("Dispatch", definition, 100);
        replace(store, List.of(alias), List.of(definition));
        return store;
    }

    private static AliasConfigurationSnapshot replace(AliasDatabaseStore store, List<Alias> aliases,
                                                      List<AliasListDefinition> definitions) throws Exception
    {
        AliasConfigurationDatabaseStore configurationStore =
            new AliasConfigurationDatabaseStore(store.getDatabasePath());
        AliasConfigurationSnapshot current = configurationStore.load();
        ScanListConfiguration scanLists = new ScanListConfiguration(current.scanLists().scanLists(), Map.of(),
            Map.of());
        return configurationStore.commit(new AliasConfigurationSnapshot(definitions, aliases, scanLists), List.of());
    }

    private static List<Alias> loadAliases(AliasDatabaseStore store) throws Exception
    {
        return store.loadAliases(store.loadAliasListDefinitions());
    }

    private static AliasListDefinition definition(String name, AliasListFamily family)
    {
        return new AliasListDefinition(name, family);
    }

    private static Alias alias(String name, AliasListDefinition definition, int talkgroup)
    {
        Alias alias = new Alias(name);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, talkgroup));
        return alias;
    }

    private static void bypassChecks(Path database, String sql) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate(sql);
        }
    }

    private static void execute(Path database, String sql) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }

    private static String scalarText(Path database, String sql) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static Set<String> columns(Connection connection, String table) throws Exception
    {
        Set<String> columns = new HashSet<>();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ')'))
        {
            while(resultSet.next())
            {
                columns.add(resultSet.getString("name"));
            }
        }
        return columns;
    }

    private static Set<String> indexes(Connection connection) throws Exception
    {
        Set<String> indexes = new HashSet<>();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='index'"))
        {
            while(resultSet.next())
            {
                indexes.add(resultSet.getString("name"));
            }
        }
        return indexes;
    }

    private static boolean hasTable(Connection connection, String table) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?"))
        {
            statement.setString(1, table);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static int countRows(Connection connection, String table) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
}
