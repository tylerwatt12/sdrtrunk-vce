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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
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
        ScanListModel model = new ScanListModel();
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

        model.replaceAliasMemberships(100, Set.of(1L, 2L));
        assertEquals(Set.of(1L, 2L), model.scanListIdsForAlias(100));
        model.replaceUnmatchedTalkgroupMemberships(10, Set.of(1L, 2L));
        assertEquals(Set.of(1L, 2L), model.scanListIdsForUnmatchedTalkgroups(10));

        model.removeScanList(2);
        assertEquals(Set.of(1L), model.scanListIdsForAlias(100));
        assertEquals(Set.of(1L), model.scanListIdsForUnmatchedTalkgroups(10));
    }

    @Test
    void unchangedMembershipsRetainSnapshotAndChangesPreservePriorSnapshot()
    {
        ScanList first = new ScanList(1, 0, "Default", null, true, true);
        ScanList second = new ScanList(2, 1, "Second", null, true, false);
        ScanListModel model = new ScanListModel();
        model.replaceConfiguration(new ScanListConfiguration(List.of(first, second),
            Map.of(100L, Set.of(1L), 200L, Set.of(2L)), Map.of()));
        ScanListConfiguration original = model.configuration();

        model.replaceAliasMemberships(100, List.of(1L, 1L));
        model.replaceAliasMemberships(300, null);
        model.removeAlias(300);
        assertSame(original, model.configuration(), "No-op commands do not publish another snapshot");

        model.replaceAliasMemberships(100, Set.of(2L));
        ScanListConfiguration changed = model.configuration();
        assertNotSame(original, changed);
        assertEquals(Set.of(1L), original.scanListIdsForAlias(100));
        assertEquals(Set.of(2L), changed.scanListIdsForAlias(100));
        assertEquals(Set.of(2L), changed.scanListIdsForAlias(200));
        assertThrows(UnsupportedOperationException.class, () -> changed.aliasMemberships().clear());
        assertThrows(IllegalArgumentException.class, () -> model.replaceAliasMemberships(0, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> model.replaceAliasMemberships(100, Set.of(999L)));
        assertSame(changed, model.configuration(), "Invalid edits cannot publish partial state");
        model.removeAlias(100);
        assertTrue(model.scanListIdsForAlias(100).isEmpty());
        assertEquals(Set.of(2L), changed.scanListIdsForAlias(100), "Removal cannot mutate a retained snapshot");
    }

    @Test
    void noOpMembershipDoesNotCopyUnrelatedStoredSets() throws ReflectiveOperationException
    {
        AtomicInteger unrelatedTraversals = new AtomicInteger();
        Set<Long> unrelated = new AbstractSet<>()
        {
            @Override public Iterator<Long> iterator()
            {
                unrelatedTraversals.incrementAndGet();
                return Set.of(2L).iterator();
            }
            @Override public int size() { return 1; }
        };
        Map<Long,Set<Long>> source = Map.of(100L, Set.of(1L), 200L, unrelated);
        Method update = ScanListModel.class.getDeclaredMethod("withMembership", Map.class, long.class,
            Collection.class);
        update.setAccessible(true);

        assertSame(source, update.invoke(null, source, 100L, Set.of(1L)));
        assertSame(source, update.invoke(null, source, 300L, Set.of()));
        assertSame(source, update.invoke(null, source, 300L, null));
        assertEquals(0, unrelatedTraversals.get(), "No-op membership edits never walk and copy the whole catalog");
    }

    @Test
    void enforcesOnePublishedDefaultAndDurableMembershipReferences()
    {
        ScanList defaultList = new ScanList(1, 0, "Default", null, true, true);
        ScanListModel model = new ScanListModel();
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
    void activeModelRejectsAnUnassignedScanList()
    {
        ScanListModel model = new ScanListModel();
        model.replaceConfiguration(new ScanListConfiguration(
            List.of(new ScanList(1, 0, "Default", null, true, true)), Map.of(), Map.of()));

        assertThrows(IllegalArgumentException.class,
            () -> model.addScanList(new ScanList(ScanList.UNASSIGNED_ID, 1, "Draft", null, true, false)));
        assertEquals(List.of(1L), model.scanLists().stream().map(ScanList::getId).toList());
    }

    @Test
    void removesAliasListMembershipWithoutChangingAliasMembership()
    {
        ScanList defaultList = new ScanList(1, 0, "Default", null, true, true);
        ScanListModel model = new ScanListModel();
        model.replaceConfiguration(new ScanListConfiguration(List.of(defaultList),
            Map.of(100L, Set.of(1L)), Map.of(10L, Set.of(1L))));

        model.removeAliasList(10L);

        assertEquals(Set.of(1L), model.scanListIdsForAlias(100L));
        assertTrue(model.scanListIdsForUnmatchedTalkgroups(10L).isEmpty());
    }
}
