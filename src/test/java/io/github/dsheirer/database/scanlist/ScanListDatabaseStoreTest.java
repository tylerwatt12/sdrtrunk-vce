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

package io.github.dsheirer.database.scanlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.configuration.ConfigurationSnapshotDatabaseStore;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanListDatabaseStoreTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void freshDatabaseContainsOneDurableDefaultScanList() throws Exception
    {
        Path database = database("fresh.sqlite");
        ScanListConfiguration configuration = new ScanListDatabaseStore(database).loadConfiguration();

        assertEquals(1, configuration.scanLists().size());
        ScanList defaultList = configuration.defaultScanList();
        assertNotEquals(ScanList.UNASSIGNED_ID, defaultList.getId());
        assertEquals("Default", defaultList.getName());
        assertTrue(defaultList.isPublished());
        assertTrue(configuration.aliasMemberships().isEmpty());
        assertTrue(configuration.unmatchedAliasListMemberships().isEmpty());
    }

    @Test
    void roundTripsMembershipAndPartialAliasSnapshotPreservesIt() throws Exception
    {
        Path database = database("round-trip.sqlite");
        ConfigurationSnapshotDatabaseStore snapshotStore = new ConfigurationSnapshotDatabaseStore(database);
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        Alias alias = new Alias("Dispatch");
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 1001));
        ConfigurationState initial = aliasState(definition, alias);
        snapshotStore.replace(initial);

        ScanListDatabaseStore store = new ScanListDatabaseStore(database);
        ScanListConfiguration seeded = store.loadConfiguration();
        ScanList southwest = new ScanList(0, 1, "SouthWest", "Southwest calls", true, false);
        ConfigurationState definitionsOnly = aliasState(definition, alias);
        definitionsOnly.setScanListConfiguration(new ScanListConfiguration(
            List.of(seeded.defaultScanList(), southwest), Map.of(), Map.of()));
        snapshotStore.replace(definitionsOnly);
        assertNotEquals(ScanList.UNASSIGNED_ID, southwest.getId());

        ScanListConfiguration memberships = new ScanListConfiguration(
            List.of(seeded.defaultScanList(), southwest),
            Map.of(alias.getId(), Set.of(seeded.defaultScanList().getId(), southwest.getId())),
            Map.of(definition.getId(), Set.of(southwest.getId())));
        ConfigurationState configured = aliasState(definition, alias);
        configured.setScanListConfiguration(memberships);
        snapshotStore.replace(configured);

        ScanListConfiguration loaded = store.loadConfiguration();
        assertEquals(Set.of(seeded.defaultScanList().getId(), southwest.getId()),
            loaded.scanListIdsForAlias(alias.getId()));
        assertEquals(Set.of(southwest.getId()),
            loaded.scanListIdsForUnmatchedTalkgroups(definition.getId()));

        //A desktop or legacy caller that owns Alias fields but not scan-list state must retain the normalized joins.
        alias.setDescription("Unrelated Alias edit");
        snapshotStore.replace(aliasState(definition, alias));
        ScanListConfiguration afterAliasEdit = store.loadConfiguration();
        assertEquals(loaded.aliasMemberships(), afterAliasEdit.aliasMemberships());
        assertEquals(loaded.unmatchedAliasListMemberships(), afterAliasEdit.unmatchedAliasListMemberships());
        assertEquals(loaded.scanLists(), afterAliasEdit.scanLists());
    }

    @Test
    void rejectsMembershipForUnknownAliasAndRollsBackSnapshot() throws Exception
    {
        Path database = database("orphan.sqlite");
        ScanListConfiguration seeded = new ScanListDatabaseStore(database).loadConfiguration();
        ConfigurationState state = new ConfigurationState();
        state.setScanListConfiguration(new ScanListConfiguration(seeded.scanLists(),
            Map.of(999L, Set.of(seeded.defaultScanList().getId())), Map.of()));

        assertThrows(java.sql.SQLException.class,
            () -> new ConfigurationSnapshotDatabaseStore(database).replace(state));

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM scan_list"))
        {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }

    @Test
    void rejectsUnmatchedTalkgroupMembershipForUnknownAliasListAndRollsBackSnapshot() throws Exception
    {
        Path database = database("orphan-alias-list.sqlite");
        ScanListConfiguration seeded = new ScanListDatabaseStore(database).loadConfiguration();
        ConfigurationState state = new ConfigurationState();
        state.setScanListConfiguration(new ScanListConfiguration(seeded.scanLists(), Map.of(),
            Map.of(999L, Set.of(seeded.defaultScanList().getId()))));

        assertThrows(java.sql.SQLException.class,
            () -> new ConfigurationSnapshotDatabaseStore(database).replace(state));

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM scan_list"))
        {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }

    private Path database(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        return database;
    }

    private static ConfigurationState aliasState(AliasListDefinition definition, Alias alias)
    {
        ConfigurationState state = new ConfigurationState();
        state.setAliasListDefinitions(List.of(definition));
        state.setAliases(List.of(alias));
        return state;
    }
}
