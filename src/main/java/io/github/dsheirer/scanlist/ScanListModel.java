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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Service-neutral runtime scan-list model. Reads use one immutable volatile snapshot and never block call processing;
 * administrator mutations construct and publish a complete replacement snapshot.
 */
public final class ScanListModel
{
    private final Runnable mChangeListener;
    private volatile ScanListConfiguration mConfiguration = ScanListConfiguration.defaultConfiguration();

    public ScanListModel(Runnable changeListener)
    {
        mChangeListener = changeListener != null ? changeListener : () -> {};
    }

    public ScanListConfiguration configuration()
    {
        return mConfiguration;
    }

    public List<ScanList> scanLists()
    {
        return mConfiguration.scanLists();
    }

    public ScanList scanList(long scanListId)
    {
        return mConfiguration.scanList(scanListId);
    }

    public ScanList scanList(String name)
    {
        return mConfiguration.scanList(name);
    }

    public ScanList defaultScanList()
    {
        return mConfiguration.defaultScanList();
    }

    public Set<Long> scanListIdsForAlias(long aliasId)
    {
        return mConfiguration.scanListIdsForAlias(aliasId);
    }

    public Set<Long> scanListIdsForUnmatchedTalkgroups(long aliasListId)
    {
        return mConfiguration.scanListIdsForUnmatchedTalkgroups(aliasListId);
    }

    public synchronized void replaceConfiguration(ScanListConfiguration configuration)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Scan-list configuration cannot be null");
        mChangeListener.run();
    }

    public synchronized void addScanList(ScanList scanList)
    {
        Objects.requireNonNull(scanList, "Scan list cannot be null");
        if(scanList.getId() != ScanList.UNASSIGNED_ID && mConfiguration.scanList(scanList.getId()) != null)
        {
            throw new IllegalArgumentException("Scan-list ID already exists [" + scanList.getId() + "]");
        }
        if(mConfiguration.scanList(scanList.getName()) != null)
        {
            throw new IllegalArgumentException("Scan-list name already exists [" + scanList.getName() + "]");
        }

        List<ScanList> definitions = new ArrayList<>(mConfiguration.scanLists());
        if(scanList.isDefault())
        {
            clearDefault(definitions);
        }
        definitions.add(scanList);
        publish(definitions, mConfiguration.aliasMemberships(), mConfiguration.unmatchedAliasListMemberships());
    }

    public synchronized void updateScanList(ScanList replacement)
    {
        Objects.requireNonNull(replacement, "Replacement scan list cannot be null");
        if(replacement.getId() <= ScanList.UNASSIGNED_ID)
        {
            throw new IllegalArgumentException("Replacement scan list requires a durable ID");
        }

        List<ScanList> definitions = new ArrayList<>(mConfiguration.scanLists());
        int index = -1;
        for(int x = 0; x < definitions.size(); x++)
        {
            if(definitions.get(x).getId() == replacement.getId())
            {
                index = x;
                break;
            }
        }
        if(index < 0)
        {
            throw new IllegalArgumentException("Unknown scan-list ID [" + replacement.getId() + "]");
        }
        if(replacement.isDefault())
        {
            clearDefault(definitions);
        }
        definitions.set(index, replacement);
        publish(definitions, mConfiguration.aliasMemberships(), mConfiguration.unmatchedAliasListMemberships());
    }

    public synchronized void removeScanList(long scanListId)
    {
        ScanList removed = requireScanList(scanListId);
        if(removed.isDefault())
        {
            throw new IllegalArgumentException("Select another default scan list before deleting this one");
        }

        List<ScanList> definitions = mConfiguration.scanLists().stream()
            .filter(scanList -> scanList.getId() != scanListId).toList();
        publish(definitions, removeScanListId(mConfiguration.aliasMemberships(), scanListId),
            removeScanListId(mConfiguration.unmatchedAliasListMemberships(), scanListId));
    }

    public synchronized void replaceAliasMemberships(long aliasId, Collection<Long> scanListIds)
    {
        publishAliasMembership(aliasId, scanListIds);
    }

    public synchronized void addAliasMembership(long aliasId, long scanListId)
    {
        Set<Long> memberships = new LinkedHashSet<>(scanListIdsForAlias(aliasId));
        memberships.add(scanListId);
        publishAliasMembership(aliasId, memberships);
    }

    public synchronized void removeAliasMembership(long aliasId, long scanListId)
    {
        Set<Long> memberships = new LinkedHashSet<>(scanListIdsForAlias(aliasId));
        memberships.remove(scanListId);
        publishAliasMembership(aliasId, memberships);
    }

    public synchronized void removeAlias(long aliasId)
    {
        replaceAliasMemberships(aliasId, Set.of());
    }

    public synchronized void replaceUnmatchedTalkgroupMemberships(long aliasListId,
                                                                   Collection<Long> scanListIds)
    {
        publishUnmatchedTalkgroupMembership(aliasListId, scanListIds);
    }

    public synchronized void addUnmatchedTalkgroupMembership(long aliasListId, long scanListId)
    {
        Set<Long> memberships = new LinkedHashSet<>(scanListIdsForUnmatchedTalkgroups(aliasListId));
        memberships.add(scanListId);
        publishUnmatchedTalkgroupMembership(aliasListId, memberships);
    }

    public synchronized void removeUnmatchedTalkgroupMembership(long aliasListId, long scanListId)
    {
        Set<Long> memberships = new LinkedHashSet<>(scanListIdsForUnmatchedTalkgroups(aliasListId));
        memberships.remove(scanListId);
        publishUnmatchedTalkgroupMembership(aliasListId, memberships);
    }

    public synchronized void removeAliasList(long aliasListId)
    {
        replaceUnmatchedTalkgroupMemberships(aliasListId, Set.of());
    }

    /**
     * Removes membership rows whose owning Alias no longer exists. This is used by configuration replacement paths
     * that can bypass the web administration service. Unassigned runtime objects are deliberately ignored because
     * scan-list membership is defined only for durable IDs.
     *
     * @return true when a replacement snapshot was published
     */
    public synchronized boolean retainMembershipOwners(Collection<Long> aliasIds)
    {
        return retainMembershipOwners(aliasIds, mConfiguration.unmatchedAliasListMemberships().keySet());
    }

    /**
     * Removes membership rows whose owning Alias or Alias List no longer exists.
     *
     * @return true when a replacement snapshot was published
     */
    public synchronized boolean retainMembershipOwners(Collection<Long> aliasIds, Collection<Long> aliasListIds)
    {
        Set<Long> retainedAliasIds = positiveIds(aliasIds, "Alias IDs");
        Set<Long> retainedAliasListIds = positiveIds(aliasListIds, "Alias List IDs");
        Map<Long,Set<Long>> aliasMemberships = mutable(mConfiguration.aliasMemberships());
        Map<Long,Set<Long>> unmatchedMemberships = mutable(mConfiguration.unmatchedAliasListMemberships());
        boolean aliasesChanged = aliasMemberships.keySet().removeIf(id -> !retainedAliasIds.contains(id));
        boolean aliasListsChanged = unmatchedMemberships.keySet().removeIf(id -> !retainedAliasListIds.contains(id));

        if(aliasesChanged || aliasListsChanged)
        {
            publish(mConfiguration.scanLists(), aliasMemberships, unmatchedMemberships);
        }

        return aliasesChanged || aliasListsChanged;
    }

    /**
     * Removes unmatched-talkgroup membership rows whose owning Alias List no longer exists.
     *
     * @return true when a replacement snapshot was published
     */
    public synchronized boolean retainUnmatchedTalkgroupMembershipOwners(Collection<Long> aliasListIds)
    {
        return retainMembershipOwners(mConfiguration.aliasMemberships().keySet(), aliasListIds);
    }

    private void publishAliasMembership(long ownerId, Collection<Long> scanListIds)
    {
        Map<Long,Set<Long>> updated = withMembership(mConfiguration.aliasMemberships(), ownerId, scanListIds);
        if(!updated.equals(mConfiguration.aliasMemberships()))
        {
            publish(mConfiguration.scanLists(), updated, mConfiguration.unmatchedAliasListMemberships());
        }
    }

    private void publishUnmatchedTalkgroupMembership(long ownerId, Collection<Long> scanListIds)
    {
        Map<Long,Set<Long>> updated = withMembership(mConfiguration.unmatchedAliasListMemberships(), ownerId,
            scanListIds);
        if(!updated.equals(mConfiguration.unmatchedAliasListMemberships()))
        {
            publish(mConfiguration.scanLists(), mConfiguration.aliasMemberships(), updated);
        }
    }

    private void publish(Collection<ScanList> definitions, Map<Long,? extends Collection<Long>> aliasMemberships,
                         Map<Long,? extends Collection<Long>> unmatchedAliasListMemberships)
    {
        mConfiguration = new ScanListConfiguration(definitions, aliasMemberships, unmatchedAliasListMemberships);
        mChangeListener.run();
    }

    private ScanList requireScanList(long scanListId)
    {
        ScanList scanList = mConfiguration.scanList(scanListId);
        if(scanList == null)
        {
            throw new IllegalArgumentException("Unknown scan-list ID [" + scanListId + "]");
        }
        return scanList;
    }

    private static void clearDefault(List<ScanList> definitions)
    {
        for(int x = 0; x < definitions.size(); x++)
        {
            ScanList current = definitions.get(x);
            if(current.isDefault())
            {
                definitions.set(x, current.withDefinition(current.getSortOrder(), current.getName(),
                    current.getDescription(), current.isPublished(), false));
            }
        }
    }

    private static Map<Long,Set<Long>> mutable(Map<Long,Set<Long>> source)
    {
        Map<Long,Set<Long>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, new LinkedHashSet<>(value)));
        return copy;
    }

    private static Map<Long,Set<Long>> withMembership(Map<Long,Set<Long>> source, long ownerId,
                                                       Collection<Long> scanListIds)
    {
        if(ownerId <= 0)
        {
            throw new IllegalArgumentException("Membership owner requires a positive durable ID");
        }

        Map<Long,Set<Long>> updated = mutable(source);
        Set<Long> prepared = scanListIds != null ? new LinkedHashSet<>(scanListIds) : new LinkedHashSet<>();
        if(prepared.isEmpty())
        {
            updated.remove(ownerId);
        }
        else
        {
            updated.put(ownerId, prepared);
        }
        return updated;
    }

    private static Set<Long> positiveIds(Collection<Long> ids, String label)
    {
        Objects.requireNonNull(ids, label + " cannot be null");
        Set<Long> prepared = new LinkedHashSet<>();
        for(Long id: ids)
        {
            if(id != null && id > 0)
            {
                prepared.add(id);
            }
        }
        return prepared;
    }

    private static Map<Long,Set<Long>> removeScanListId(Map<Long,Set<Long>> source, long scanListId)
    {
        Map<Long,Set<Long>> updated = mutable(source);
        updated.replaceAll((key, value) ->
        {
            value.remove(scanListId);
            return value;
        });
        updated.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return updated;
    }
}
