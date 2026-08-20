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
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasConfigurationDatabaseStore;
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
    void roundTripsMembershipAndAliasOnlyEditPreservesIt() throws Exception
    {
        Path database = database("round-trip.sqlite");
        AliasConfigurationDatabaseStore snapshotStore = new AliasConfigurationDatabaseStore(database);
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        Alias alias = new Alias("Dispatch");
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 1001));
        AliasConfigurationSnapshot initial = aliasState(definition, alias, snapshotStore.load().scanLists());
        AliasConfigurationSnapshot committedInitial = snapshotStore.commit(initial, List.of());
        definition = committedInitial.definitions().getFirst();
        alias = committedInitial.aliases().getFirst();

        ScanListDatabaseStore store = new ScanListDatabaseStore(database);
        ScanListConfiguration seeded = store.loadConfiguration();
        ScanList southwest = new ScanList(0, 1, "SouthWest", "Southwest calls", true, false);
        AliasConfigurationSnapshot definitionsOnly = aliasState(definition, alias, new ScanListConfiguration(
            List.of(seeded.defaultScanList(), southwest), Map.of(), Map.of()));
        AliasConfigurationSnapshot committedDefinitions = snapshotStore.commit(definitionsOnly, List.of());
        southwest = committedDefinitions.scanLists().scanList("SouthWest");
        assertNotEquals(ScanList.UNASSIGNED_ID, southwest.getId());

        ScanListConfiguration memberships = new ScanListConfiguration(
            List.of(seeded.defaultScanList(), southwest),
            Map.of(alias.getId(), Set.of(seeded.defaultScanList().getId(), southwest.getId())),
            Map.of(definition.getId(), Set.of(southwest.getId())));
        AliasConfigurationSnapshot configured = aliasState(definition, alias, memberships);
        snapshotStore.commit(configured, List.of());

        ScanListConfiguration loaded = store.loadConfiguration();
        assertEquals(Set.of(seeded.defaultScanList().getId(), southwest.getId()),
            loaded.scanListIdsForAlias(alias.getId()));
        assertEquals(Set.of(southwest.getId()),
            loaded.scanListIdsForUnmatchedTalkgroups(definition.getId()));

        //An Alias-only edit carries forward the current scan-list snapshot and retains the normalized joins.
        alias.setDescription("Unrelated Alias edit");
        snapshotStore.commit(aliasState(definition, alias, loaded), List.of());
        ScanListConfiguration afterAliasEdit = store.loadConfiguration();
        assertEquals(loaded.aliasMemberships(), afterAliasEdit.aliasMemberships());
        assertEquals(loaded.unmatchedAliasListMemberships(), afterAliasEdit.unmatchedAliasListMemberships());
        assertEquals(loaded.scanLists(), afterAliasEdit.scanLists());
    }

    @Test
    void rejectsMembershipForUnknownAliasAndRollsBackSnapshot() throws Exception
    {
        Path database = database("orphan.sqlite");
        AliasConfigurationDatabaseStore store = new AliasConfigurationDatabaseStore(database);
        AliasConfigurationSnapshot seeded = store.load();
        AliasConfigurationSnapshot state = new AliasConfigurationSnapshot(seeded.definitions(), seeded.aliases(),
            new ScanListConfiguration(seeded.scanLists().scanLists(),
                Map.of(999L, Set.of(seeded.scanLists().defaultScanList().getId())), Map.of()));

        assertThrows(java.sql.SQLException.class,
            () -> store.commit(state, List.of()));

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
        AliasConfigurationDatabaseStore store = new AliasConfigurationDatabaseStore(database);
        AliasConfigurationSnapshot seeded = store.load();
        AliasConfigurationSnapshot state = new AliasConfigurationSnapshot(seeded.definitions(), seeded.aliases(),
            new ScanListConfiguration(seeded.scanLists().scanLists(), Map.of(),
                Map.of(999L, Set.of(seeded.scanLists().defaultScanList().getId()))));

        assertThrows(java.sql.SQLException.class,
            () -> store.commit(state, List.of()));

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

    private static AliasConfigurationSnapshot aliasState(AliasListDefinition definition, Alias alias,
                                                          ScanListConfiguration scanLists)
    {
        return new AliasConfigurationSnapshot(List.of(definition), List.of(alias), scanLists);
    }
}
