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

package io.github.dsheirer.scanlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScanListModelTest
{
    @Test
    void publishesImmutableLookupsAndSupportsAdministrativeMutations()
    {
        ScanList defaultList = new ScanList(1, 0, "Default", null, true, true);
        ScanList southwest = new ScanList(2, 1, "SouthWest", "Southwest county calls", true, false);
        AtomicInteger changes = new AtomicInteger();
        ScanListModel model = new ScanListModel(changes::incrementAndGet);
        model.replaceConfiguration(new ScanListConfiguration(List.of(defaultList, southwest),
            Map.of(100L, Set.of(1L)), Map.of(10L, Set.of(2L))));

        assertEquals(defaultList, model.defaultScanList());
        assertEquals(southwest, model.scanList(2));
        assertEquals(southwest, model.scanList("southwest"));
        assertEquals(Set.of(1L), model.scanListIdsForAlias(100));
        assertEquals(Set.of(2L), model.scanListIdsForUnmatchedTalkgroups(10));
        assertThrows(UnsupportedOperationException.class, () -> model.scanListIdsForAlias(100).add(2L));
        assertThrows(UnsupportedOperationException.class,
            () -> model.scanListIdsForUnmatchedTalkgroups(10).add(1L));

        model.addAliasMembership(100, 2);
        assertEquals(Set.of(1L, 2L), model.scanListIdsForAlias(100));
        model.addUnmatchedTalkgroupMembership(10, 1);
        assertEquals(Set.of(1L, 2L), model.scanListIdsForUnmatchedTalkgroups(10));

        model.removeScanList(2);
        assertEquals(Set.of(1L), model.scanListIdsForAlias(100));
        assertEquals(Set.of(1L), model.scanListIdsForUnmatchedTalkgroups(10));
        assertEquals(4, changes.get());
    }

    @Test
    void enforcesOnePublishedDefaultAndDurableMembershipReferences()
    {
        ScanList defaultList = new ScanList(1, 0, "Default", null, true, true);
        ScanListModel model = new ScanListModel(null);
        model.replaceConfiguration(new ScanListConfiguration(List.of(defaultList), Map.of(), Map.of()));

        assertThrows(IllegalArgumentException.class, () -> model.removeScanList(1));
        assertThrows(IllegalArgumentException.class, () -> model.replaceAliasMemberships(100, Set.of(999L)));
        assertThrows(IllegalArgumentException.class,
            () -> model.replaceUnmatchedTalkgroupMemberships(10, Set.of(999L)));
        assertThrows(IllegalArgumentException.class,
            () -> new ScanList(2, 1, "Hidden Default", null, false, true));
        assertThrows(IllegalArgumentException.class,
            () -> new ScanListConfiguration(List.of(new ScanList(2, 0, "No Default", null, true, false)),
                Map.of(), Map.of()));
        assertTrue(model.defaultScanList().isPublished());
    }

    @Test
    void removesOnlyMembershipsWithDeletedOwnersAndSkipsNoOpPublication()
    {
        ScanList defaultList = new ScanList(1, 0, "Default", null, true, true);
        AtomicInteger changes = new AtomicInteger();
        ScanListModel model = new ScanListModel(changes::incrementAndGet);
        model.replaceConfiguration(new ScanListConfiguration(List.of(defaultList),
            Map.of(100L, Set.of(1L), 200L, Set.of(1L)),
            Map.of(10L, Set.of(1L), 20L, Set.of(1L))));

        assertTrue(model.retainMembershipOwners(List.of(100L), List.of(10L)));
        assertEquals(Set.of(1L), model.scanListIdsForAlias(100L));
        assertTrue(model.scanListIdsForAlias(200L).isEmpty());
        assertEquals(Set.of(1L), model.scanListIdsForUnmatchedTalkgroups(10L));
        assertTrue(model.scanListIdsForUnmatchedTalkgroups(20L).isEmpty());
        assertEquals(2, changes.get());

        assertFalse(model.retainMembershipOwners(List.of(100L), List.of(10L)));
        assertEquals(2, changes.get());
    }

    @Test
    void removesAliasListMembershipWithoutChangingAliasMembership()
    {
        ScanList defaultList = new ScanList(1, 0, "Default", null, true, true);
        AtomicInteger changes = new AtomicInteger();
        ScanListModel model = new ScanListModel(changes::incrementAndGet);
        model.replaceConfiguration(new ScanListConfiguration(List.of(defaultList),
            Map.of(100L, Set.of(1L)), Map.of(10L, Set.of(1L))));

        model.removeAliasList(10L);

        assertEquals(Set.of(1L), model.scanListIdsForAlias(100L));
        assertTrue(model.scanListIdsForUnmatchedTalkgroups(10L).isEmpty());
        assertEquals(2, changes.get());
    }
}

