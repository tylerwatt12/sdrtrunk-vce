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
package io.github.dsheirer.database.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigurationSnapshotScanListProjectionTest
{
    @Test
    void listenProjectionPreservesHiddenMembershipsAndPrunesRemovedOwners()
    {
        ScanListConfiguration current = configuration(
            Map.of(10L, Set.of(2L), 99L, Set.of(1L, 2L)),
            Map.of(20L, Set.of(2L), 98L, Set.of(1L)));
        Alias alias = new Alias("Retained");
        alias.setId(10);
        alias.setListen(true);
        AliasListDefinition definition = new AliasListDefinition("P25", AliasListFamily.P25);
        definition.setId(20);
        definition.setListenToUnmatchedTalkgroups(true);
        alias.setAliasListDefinition(definition);
        ConfigurationState state = new ConfigurationState();
        state.setAliases(List.of(alias));
        state.setAliasListDefinitions(List.of(definition));

        ScanListConfiguration projected =
            ConfigurationSnapshotDatabaseStore.projectDefaultMembership(current, state);

        assertEquals(Set.of(1L, 2L), projected.scanListIdsForAlias(10));
        assertEquals(Set.of(), projected.scanListIdsForAlias(99));
        assertEquals(Set.of(1L, 2L), projected.scanListIdsForUnmatchedTalkgroups(20));
        assertEquals(Set.of(), projected.scanListIdsForUnmatchedTalkgroups(98));
    }

    @Test
    void disablingListenRemovesOnlyDefaultMembership()
    {
        ScanListConfiguration current = configuration(Map.of(10L, Set.of(1L, 2L)),
            Map.of(20L, Set.of(1L, 2L)));
        AliasListDefinition definition = new AliasListDefinition("P25", AliasListFamily.P25);
        definition.setId(20);
        definition.setListenToUnmatchedTalkgroups(false);
        Alias alias = new Alias("Hidden");
        alias.setId(10);
        alias.setAliasListDefinition(definition);
        alias.setListen(false);
        ConfigurationState state = new ConfigurationState();
        state.setAliases(List.of(alias));
        state.setAliasListDefinitions(List.of(definition));

        ScanListConfiguration projected =
            ConfigurationSnapshotDatabaseStore.projectDefaultMembership(current, state);

        assertEquals(Set.of(2L), projected.scanListIdsForAlias(10));
        assertEquals(Set.of(2L), projected.scanListIdsForUnmatchedTalkgroups(20));
    }

    private static ScanListConfiguration configuration(Map<Long,Set<Long>> aliases,
                                                        Map<Long,Set<Long>> unmatched)
    {
        return new ScanListConfiguration(List.of(
            new ScanList(1, 0, "Default", null, true, true),
            new ScanList(2, 1, "Hidden", null, false, false)), aliases, unmatched);
    }
}

