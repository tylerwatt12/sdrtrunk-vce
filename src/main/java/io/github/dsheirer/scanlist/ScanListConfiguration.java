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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable complete scan-list configuration. Membership is normalized around durable Alias and Alias List IDs; no
 * mutable Alias or Alias List instances are retained by this snapshot.
 */
public final class ScanListConfiguration
{
    private static final Comparator<ScanList> ORDER = Comparator.comparingInt(ScanList::getSortOrder)
        .thenComparing(ScanList::getName, String.CASE_INSENSITIVE_ORDER)
        .thenComparingLong(ScanList::getId);

    private final List<ScanList> mScanLists;
    private final Map<Long,Set<Long>> mAliasMemberships;
    private final Map<Long,Set<Long>> mUnmatchedAliasListMemberships;

    public ScanListConfiguration(Collection<ScanList> scanLists,
                                 Map<Long,? extends Collection<Long>> aliasMemberships,
                                 Map<Long,? extends Collection<Long>> unmatchedAliasListMemberships)
    {
        List<ScanList> preparedLists = prepareLists(scanLists);
        Set<Long> persistedScanListIds = new LinkedHashSet<>();
        for(ScanList scanList: preparedLists)
        {
            if(scanList.getId() > ScanList.UNASSIGNED_ID)
            {
                persistedScanListIds.add(scanList.getId());
            }
        }

        mScanLists = List.copyOf(preparedLists);
        mAliasMemberships = immutableMemberships(aliasMemberships, persistedScanListIds, "Alias");
        mUnmatchedAliasListMemberships = immutableMemberships(unmatchedAliasListMemberships, persistedScanListIds,
            "Unmatched-talkgroup Alias List");
    }

    public static ScanListConfiguration defaultConfiguration()
    {
        return new ScanListConfiguration(List.of(ScanList.defaultScanList()), Map.of(), Map.of());
    }

    public List<ScanList> scanLists()
    {
        return mScanLists;
    }

    public Map<Long,Set<Long>> aliasMemberships()
    {
        return mAliasMemberships;
    }

    /**
     * Returns memberships that route an unmatched talkgroup from an Alias List to scan lists.
     */
    public Map<Long,Set<Long>> unmatchedAliasListMemberships()
    {
        return mUnmatchedAliasListMemberships;
    }

    public Set<Long> scanListIdsForAlias(long aliasId)
    {
        return mAliasMemberships.getOrDefault(aliasId, Set.of());
    }

    /**
     * Returns scan lists that receive unmatched talkgroups from the supplied durable Alias List ID.
     */
    public Set<Long> scanListIdsForUnmatchedTalkgroups(long aliasListId)
    {
        return mUnmatchedAliasListMemberships.getOrDefault(aliasListId, Set.of());
    }

    public ScanList scanList(long scanListId)
    {
        return mScanLists.stream().filter(scanList -> scanList.getId() == scanListId).findFirst().orElse(null);
    }

    public ScanList scanList(String name)
    {
        if(name == null)
        {
            return null;
        }

        String prepared = name.strip();
        return mScanLists.stream().filter(scanList -> scanList.getName().equalsIgnoreCase(prepared))
            .findFirst().orElse(null);
    }

    public ScanList defaultScanList()
    {
        return mScanLists.stream().filter(ScanList::isDefault).findFirst().orElseThrow();
    }

    private static List<ScanList> prepareLists(Collection<ScanList> scanLists)
    {
        if(scanLists == null || scanLists.isEmpty())
        {
            throw new IllegalArgumentException("Scan-list configuration requires one default scan list");
        }

        List<ScanList> prepared = new ArrayList<>();
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        int defaults = 0;
        int unassigned = 0;

        for(ScanList scanList: scanLists)
        {
            if(scanList == null)
            {
                throw new IllegalArgumentException("Scan-list configuration contains a null definition");
            }
            if(scanList.getId() == ScanList.UNASSIGNED_ID)
            {
                unassigned++;
            }
            else if(!ids.add(scanList.getId()))
            {
                throw new IllegalArgumentException("Duplicate scan-list ID [" + scanList.getId() + "]");
            }
            if(!names.add(scanList.normalizedName()))
            {
                throw new IllegalArgumentException("Duplicate scan-list name [" + scanList.getName() + "]");
            }
            if(scanList.isDefault())
            {
                defaults++;
            }
            prepared.add(scanList);
        }

        if(unassigned > 1)
        {
            throw new IllegalArgumentException("Only one unpersisted scan list can be saved at a time");
        }
        if(defaults != 1)
        {
            throw new IllegalArgumentException("Scan-list configuration requires exactly one default scan list");
        }

        prepared.sort(ORDER);
        return prepared;
    }

    private static Map<Long,Set<Long>> immutableMemberships(
        Map<Long,? extends Collection<Long>> memberships, Set<Long> validScanListIds, String label)
    {
        if(memberships == null || memberships.isEmpty())
        {
            return Map.of();
        }

        Map<Long,Set<Long>> prepared = new LinkedHashMap<>();
        for(Map.Entry<Long,? extends Collection<Long>> entry: memberships.entrySet())
        {
            Long ownerId = entry.getKey();
            if(ownerId == null || ownerId <= 0)
            {
                throw new IllegalArgumentException(label + " membership requires a positive durable ID");
            }

            Set<Long> scanListIds = new LinkedHashSet<>();
            Collection<Long> values = entry.getValue();
            if(values != null)
            {
                for(Long scanListId: values)
                {
                    if(scanListId == null || !validScanListIds.contains(scanListId))
                    {
                        throw new IllegalArgumentException(label + " membership references unknown scan-list ID [" +
                            scanListId + "]");
                    }
                    scanListIds.add(scanListId);
                }
            }

            if(!scanListIds.isEmpty())
            {
                prepared.put(ownerId, Set.copyOf(scanListIds));
            }
        }
        return Map.copyOf(prepared);
    }
}
