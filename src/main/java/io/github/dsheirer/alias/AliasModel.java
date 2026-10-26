/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.alias;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alias Model contains all aliases and is responsible for creation and management of durable Alias Lists that can be
 * attached to a decoding channel for aliasing identifiers produced by channel decoder(s).
 */
public class AliasModel
{
    private static final Logger mLog = LoggerFactory.getLogger(AliasModel.class);
    private volatile Snapshot mSnapshot = new Snapshot(List.of(), List.of(), List.of());
    private final Map<Long,AliasList> mAliasListMap = new ConcurrentHashMap<>();
    private final List<Consumer<Change>> mListeners = new CopyOnWriteArrayList<>();
    private boolean mAliasPublicationIncomplete;

    private record Snapshot(List<Alias> aliases, List<AliasListDefinition> definitions, List<String> names) {}

    /** One explicit notification after a committed configuration and its lookup indexes are published. */
    public record Change(boolean aliasesChanged, boolean definitionsChanged, boolean namesChanged) {}

    public void addChangeListener(Consumer<Change> listener)
    {
        mListeners.add(Objects.requireNonNull(listener));
    }

    public void removeChangeListener(Consumer<Change> listener)
    {
        mListeners.remove(listener);
    }

    public List<Alias> aliasList()
    {
        return mSnapshot.aliases();
    }

    public List<String> aliasListNames()
    {
        return mSnapshot.names();
    }

    public List<AliasListDefinition> aliasListDefinitions()
    {
        return mSnapshot.definitions();
    }

    void setAliasListDefinitions(Collection<AliasListDefinition> definitions)
    {
        List<AliasListDefinition> prepared = definitions != null ? List.copyOf(definitions) : List.of();
        validateCommittedDefinitions(prepared);
        replaceSnapshot(mSnapshot.aliases(), prepared);
    }

    /**
     * Publishes one fully committed Alias configuration. Every object must already have its durable SQLite identity;
     * drafts and import candidates are never valid members of the active runtime model.
     */
    public void replaceCommittedConfiguration(Collection<AliasListDefinition> definitions,
                                               Collection<Alias> aliases)
    {
        Set<Long> allAliasIds = new HashSet<>();
        if(aliases != null)
        {
            aliases.stream().filter(Objects::nonNull).map(Alias::getId).forEach(allAliasIds::add);
        }
        mSnapshot.aliases().stream().map(Alias::getId).forEach(allAliasIds::add);
        publishCommittedConfiguration(definitions, aliases, allAliasIds, true, true);
    }

    /**
     * Publishes the changed portion of an already committed configuration while retaining unchanged live Alias
     * instances and alias-list names. This keeps decoder lookups stable across unrelated edits.
     */
    public void publishCommittedConfiguration(Collection<AliasListDefinition> definitions,
                                               Collection<Alias> aliases, Collection<Long> changedAliasIds,
                                               boolean definitionsChanged)
    {
        publishCommittedConfiguration(definitions, aliases, changedAliasIds, definitionsChanged, false);
    }

    private synchronized void publishCommittedConfiguration(Collection<AliasListDefinition> definitions,
                                                Collection<Alias> aliases, Collection<Long> changedAliasIds,
                                                boolean definitionsChanged, boolean refreshAllCachedLists)
    {
        List<AliasListDefinition> committedDefinitions =
            definitions != null ? List.copyOf(definitions) : List.of();
        List<Alias> committedAliases = aliases != null ? List.copyOf(aliases) : List.of();
        Map<Long,AliasListDefinition> committedDefinitionsById = validateCommittedDefinitions(committedDefinitions);
        Map<Long,AliasListDefinition> liveDefinitionsById = validateCommittedDefinitions(mSnapshot.definitions());
        Set<Long> changedIds = changedAliasIds != null ? Set.copyOf(changedAliasIds) : Set.of();

        if(!definitionsChanged && !sameDefinitions(mSnapshot.definitions(), committedDefinitions))
        {
            throw new IllegalArgumentException("Committed Alias List changes must be declared before publication");
        }

        Set<Long> changedDefinitionIds = new HashSet<>(liveDefinitionsById.keySet());
        changedDefinitionIds.removeAll(committedDefinitionsById.keySet());
        List<AliasListDefinition> publishedDefinitions = new ArrayList<>(committedDefinitions.size());
        for(AliasListDefinition committedDefinition: committedDefinitions)
        {
            AliasListDefinition liveDefinition = liveDefinitionsById.get(committedDefinition.getId());
            if(AliasConfigurationEquality.sameDefinition(liveDefinition, committedDefinition))
            {
                publishedDefinitions.add(liveDefinition);
            }
            else
            {
                publishedDefinitions.add(committedDefinition);
                changedDefinitionIds.add(committedDefinition.getId());
            }
        }
        Map<Long,AliasListDefinition> publishedDefinitionsById = validateCommittedDefinitions(publishedDefinitions);

        Set<Long> aliasIds = new HashSet<>();
        for(Alias alias: committedAliases)
        {
            AliasListDefinition definition = alias != null ? publishedDefinitionsById.get(alias.getAliasListId()) : null;
            if(alias == null || alias.getId() <= Alias.UNASSIGNED_ID || !aliasIds.add(alias.getId()) ||
                definition == null || !AliasMatchRegistry.isOperational(definition, alias.getMatchIdentifier()))
            {
                throw new IllegalArgumentException(
                    "Committed aliases require unique positive identities and a valid durable alias-list reference");
            }
        }

        Map<Long,Alias> liveAliasesById = new HashMap<>();
        for(Alias alias: mSnapshot.aliases())
        {
            liveAliasesById.put(alias.getId(), alias);
        }

        Set<Long> removedIds = new HashSet<>(liveAliasesById.keySet());
        removedIds.removeAll(aliasIds);
        if(!changedIds.containsAll(removedIds))
        {
            throw new IllegalArgumentException("Committed Alias removals must be declared before publication");
        }

        Set<Long> affectedAliasListIds = new HashSet<>(changedDefinitionIds);
        for(Long changedId: changedIds)
        {
            Alias previous = liveAliasesById.get(changedId);
            if(previous != null)
            {
                affectedAliasListIds.add(previous.getAliasListId());
            }
        }

        List<Alias> publishedAliases = new ArrayList<>(committedAliases.size());
        for(Alias committedAlias: committedAliases)
        {
            Alias liveAlias = liveAliasesById.get(committedAlias.getId());
            Alias published = liveAlias == null || changedIds.contains(committedAlias.getId()) ? committedAlias :
                liveAlias;
            if(published != liveAlias)
            {
                affectedAliasListIds.add(published.getAliasListId());
            }
            AliasListDefinition definition = publishedDefinitionsById.get(published.getAliasListId());
            if(definition == null || !AliasMatchRegistry.isOperational(definition, published.getMatchIdentifier()))
            {
                throw new IllegalArgumentException("Published aliases require a valid durable alias-list reference");
            }
            publishedAliases.add(published);
        }

        //A failed observer can leave a list's rows newer than its index. Recovery must refresh every cache even
        //when the failed command already removed the old Alias rows that identified its former list ownership.
        boolean refreshAll = refreshAllCachedLists || mAliasPublicationIncomplete;
        mAliasPublicationIncomplete = true;
        for(Map.Entry<Long,AliasList> entry: List.copyOf(mAliasListMap.entrySet()))
        {
            if(refreshAll || changedDefinitionIds.contains(entry.getKey()))
            {
                AliasListDefinition replacement = publishedDefinitionsById.get(entry.getKey());
                if(replacement != null)
                {
                    entry.getValue().replaceDefinition(replacement);
                }
            }
        }
        for(Alias published: publishedAliases)
        {
            AliasListDefinition definition = publishedDefinitionsById.get(published.getAliasListId());
            if(published.getAliasListDefinition() != definition)
            {
                published.setAliasListDefinition(definition);
            }
        }

        Snapshot previous = mSnapshot;
        mSnapshot = snapshot(publishedAliases, publishedDefinitions);
        reconcileCachedAliasLists(refreshAll ? Set.copyOf(mAliasListMap.keySet()) : affectedAliasListIds);
        mAliasListMap.keySet().removeIf(id -> getAliasListDefinition(id) == null);
        notifyChanged(previous);
        mAliasPublicationIncomplete = false;
    }

    private static Snapshot snapshot(Collection<Alias> aliases, Collection<AliasListDefinition> definitions)
    {
        List<AliasListDefinition> lists = List.copyOf(definitions);
        List<String> names = lists.stream().map(AliasListDefinition::getName)
            .filter(name -> name != null && !name.isEmpty()).distinct().toList();
        return new Snapshot(List.copyOf(aliases), lists, names);
    }

    private synchronized void replaceSnapshot(Collection<Alias> aliases, Collection<AliasListDefinition> definitions)
    {
        Snapshot previous = mSnapshot;
        mSnapshot = snapshot(aliases, definitions);
        reconcileCachedAliasLists();
        notifyChanged(previous);
    }

    private void notifyChanged(Snapshot previous)
    {
        Change change = new Change(!previous.aliases().equals(mSnapshot.aliases()),
            !sameDefinitions(previous.definitions(), mSnapshot.definitions()),
            !previous.names().equals(mSnapshot.names()));
        if(change.aliasesChanged() || change.definitionsChanged() || change.namesChanged())
        {
            for(Consumer<Change> listener: mListeners) listener.accept(change);
        }
    }

    private static boolean sameDefinitions(List<AliasListDefinition> current,
                                           List<AliasListDefinition> committed)
    {
        if(current.size() != committed.size())
        {
            return false;
        }

        for(int index = 0; index < current.size(); index++)
        {
            AliasListDefinition first = current.get(index);
            AliasListDefinition second = committed.get(index);
            if(!AliasConfigurationEquality.sameDefinition(first, second))
            {
                return false;
            }
        }
        return true;
    }

    private static Map<Long,AliasListDefinition> validateCommittedDefinitions(
        Collection<AliasListDefinition> definitions)
    {
        Map<Long,AliasListDefinition> definitionsById = new HashMap<>();
        Set<String> definitionNames = new HashSet<>();

        for(AliasListDefinition definition: definitions)
        {
            if(definition == null || definition.getId() <= AliasListDefinition.UNASSIGNED_ID ||
                definition.getName() == null || definition.getName().isBlank() ||
                definition.getFamily() == null || definitionsById.putIfAbsent(definition.getId(), definition) != null ||
                !definitionNames.add(definition.getName().toLowerCase(java.util.Locale.ROOT)))
            {
                throw new IllegalArgumentException(
                    "Committed alias-list definitions require unique positive identities, names, and families");
            }
        }

        return definitionsById;
    }

    void addAliasListDefinition(AliasListDefinition definition)
    {
        if(definition == null)
        {
            return;
        }
        if(definition.getId() <= AliasListDefinition.UNASSIGNED_ID)
        {
            throw new IllegalArgumentException("Active alias-list definitions require a durable ID");
        }

        AliasListDefinition existing = getAliasListDefinition(definition.getId());
        AliasListDefinition sameName = getAliasListDefinition(definition.getName());

        if(existing == null && sameName == null)
        {
            List<AliasListDefinition> definitions = new ArrayList<>(mSnapshot.definitions());
            definitions.add(definition);
            replaceSnapshot(mSnapshot.aliases(), definitions);
        }
        else if(existing != definition || sameName != definition)
        {
            throw new IllegalArgumentException("Alias list [" + definition.getName() + "] already exists");
        }

        refreshAliasListNames();
    }

    /**
     * Removes the persisted alias-list definition from this model. Alias rows and channel assignments are managed by
     * the caller so that the complete operation can be persisted or rolled back atomically.
     */
    void removeAliasListDefinition(AliasListDefinition definition)
    {
        List<AliasListDefinition> definitions = new ArrayList<>(mSnapshot.definitions());
        if(definition != null && definitions.remove(definition))
        {
            replaceSnapshot(mSnapshot.aliases(), definitions);
        }
    }

    public AliasListDefinition getAliasListDefinition(long id)
    {
        if(id <= AliasListDefinition.UNASSIGNED_ID)
        {
            return null;
        }

        return mSnapshot.definitions().stream().filter(definition -> definition.getId() == id).findFirst().orElse(null);
    }

    public AliasListDefinition getAliasListDefinition(String name)
    {
        if(name == null || name.isEmpty())
        {
            return null;
        }

        return mSnapshot.definitions().stream().filter(definition ->
            name.equalsIgnoreCase(definition.getName())).findFirst().orElse(null);
    }

    /**
     * Finds the visible factory Alias List for a decoder's protocol family. A same-named list from another family is
     * never returned, so a malformed or manually replaced definition cannot be assigned to an incompatible channel.
     */
    public AliasListDefinition getDefaultAliasListDefinition(DecoderType decoderType)
    {
        AliasListFamily family = AliasListFamily.from(decoderType);

        if(family == null)
        {
            return null;
        }

        AliasListDefinition definition = getAliasListDefinition(family.getDefaultAliasListName());
        return definition != null && definition.getFamily() == family ? definition : null;
    }

    /**
     * Ensures a newly added saved channel has one compatible Alias List. The canonical factory list is preferred;
     * if it was removed, the oldest compatible administrator-owned list is used instead.
     */
    public void requireAliasListAssignment(Channel channel)
    {
        if(channel == null)
        {
            throw new IllegalArgumentException("Channel cannot be null");
        }
        if(isAliasListCompatible(channel))
        {
            return;
        }
        if(channel.getAliasListId() > AliasListDefinition.UNASSIGNED_ID ||
            channel.getAliasListName() != null && !channel.getAliasListName().isBlank())
        {
            throw new IllegalArgumentException("Channel [" + channel.getName() +
                "] references an incompatible Alias List");
        }

        DecoderType decoderType = channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        AliasListFamily family = AliasListFamily.from(decoderType);
        AliasListDefinition definition = getDefaultAliasListDefinition(decoderType);
        if(definition == null && family != null)
        {
            definition = mSnapshot.definitions().stream()
                .filter(candidate -> candidate.getFamily() == family)
                .findFirst().orElse(null);
        }
        if(definition == null)
        {
            throw new IllegalStateException("Create a compatible Alias List before adding a " + decoderType +
                " channel");
        }
        channel.setAliasListDefinition(definition);
    }

    public AliasListDefinition getAliasListDefinition(Alias alias)
    {
        if(alias == null)
        {
            return null;
        }

        if(alias.getAliasListId() > AliasListDefinition.UNASSIGNED_ID)
        {
            return getAliasListDefinition(alias.getAliasListId());
        }

        AliasListDefinition attached = alias.getAliasListDefinition();
        return attached != null && mSnapshot.definitions().stream().anyMatch(definition -> definition == attached) ?
            attached : null;
    }

    /**
     * Clears and reloads the list of alias list names from the current set of aliases.
     */
    public void refreshAliasListNames()
    {
        replaceSnapshot(mSnapshot.aliases(), mSnapshot.definitions());
    }

    /**
     * Unmodifiable list of all aliases currently in the model
     */
    public List<Alias> getAliases()
    {
        return mSnapshot.aliases();
    }

    /**
     * Finds the one live Alias with the specified durable SQLite identity.
     */
    public Alias getAlias(long aliasId)
    {
        if(aliasId <= Alias.UNASSIGNED_ID)
        {
            return null;
        }

        return mSnapshot.aliases().stream().filter(alias -> alias.getId() == aliasId).findFirst().orElse(null);
    }

    /**
     * Removes all aliases from the list and broadcasts the alias delete event for each
     */
    public void clear()
    {
        replaceSnapshot(List.of(), List.of());
        mAliasListMap.clear();
    }

    /**
     * Returns an optional alias list associated with the identifier collection
     *
     * @return alias list or null
     */
    public AliasList getAliasList(IdentifierCollection identifierCollection)
    {
        if(identifierCollection != null)
        {
            return getAliasList(identifierCollection.getAliasListConfiguration());
        }

        return null;
    }

    /**
     * Retrieves an alias list specified by the alias list configuration identifier
     *
     * @param configurationIdentifier containing the stable list identity and its captured display name
     * @return alias list or null.
     */
    public AliasList getAliasList(AliasListConfigurationIdentifier configurationIdentifier)
    {
        if(configurationIdentifier != null && configurationIdentifier.isValid())
        {
            long aliasListId = configurationIdentifier.getAliasListId();
            if(aliasListId > AliasListDefinition.UNASSIGNED_ID)
            {
                AliasList cached = mAliasListMap.get(aliasListId);
                if(cached != null) return cached;

                AliasListDefinition definition = getAliasListDefinition(aliasListId);
                //A removed list must never resolve to another list that reuses its former display name.
                return definition != null ? getAliasList(definition) :
                    AliasList.empty(configurationIdentifier.getValue());
            }

            return getAliasList(configurationIdentifier.getValue());
        }

        return null;
    }

    /**
     * Resolves a display name to its durable definition, then returns that definition's runtime lookup list.
     */
    public AliasList getAliasList(String name)
    {
        if(name == null || name.isEmpty())
        {
            return AliasList.empty(name);
        }

        AliasListDefinition definition = getAliasListDefinition(name);
        if(definition == null)
        {
            return AliasList.empty(name);
        }

        return getAliasList(definition);
    }

    public AliasList getAliasList(AliasListDefinition definition)
    {
        if(definition == null)
        {
            return null;
        }

        AliasListDefinition current = getAliasListDefinition(definition.getId());
        if(current == null)
        {
            return AliasList.empty(definition.getName());
        }

        AliasList cached = mAliasListMap.get(current.getId());
        if(cached != null) return cached;

        //Only first construction takes the publication lock. Decoder lookups use the cached lock-free index.
        synchronized(this)
        {
            AliasListDefinition latest = getAliasListDefinition(definition.getId());
            if(latest == null) return AliasList.empty(definition.getName());
            return mAliasListMap.computeIfAbsent(latest.getId(), id ->
            {
                AliasList aliasList = new AliasList(latest);
                aliasList.addAliases(mSnapshot.aliases().stream().filter(alias -> alias.belongsTo(latest)).toList());
                return aliasList;
            });
        }
    }

    /**
     * Returns the configured list only when its protocol family matches the channel decoder. Invalid or stale
     * assignments receive an empty list.
     */
    public AliasList getAliasListForChannel(Channel channel)
    {
        if(channel == null || channel.getAliasListId() <= AliasListDefinition.UNASSIGNED_ID)
        {
            return AliasList.empty(channel != null ? channel.getAliasListName() : null);
        }

        AliasListDefinition definition = getAliasListDefinition(channel.getAliasListId());

        if(isAliasListCompatible(channel, definition))
        {
            return getAliasList(definition);
        }

        mLog.warn("Ignoring alias list [{}] that is incompatible with channel decoder [{}]",
            channel.getAliasListName(),
            channel.getDecodeConfiguration() != null ? channel.getDecodeConfiguration().getDecoderType() : null);
        return definition != null ? new AliasList(definition) : AliasList.empty(channel.getAliasListName());
    }

    /**
     * Validates the persisted channel/list protocol-family relationship.
     */
    public boolean isAliasListCompatible(Channel channel)
    {
        return channel != null && isAliasListCompatible(channel,
            getAliasListDefinition(channel.getAliasListId()));
    }

    private boolean isAliasListCompatible(Channel channel, AliasListDefinition definition)
    {
        if(channel == null || definition == null || channel.getDecodeConfiguration() == null)
        {
            return false;
        }

        return AliasMatchRegistry.isChannelCompatible(definition,
            channel.getDecodeConfiguration().getDecoderType());
    }

    /**
     * Returns a list of alias group names for all aliases
     */
    public List<String> getGroupNames()
    {
        Set<String> groupNames = new TreeSet<>();

        for(Alias alias : mSnapshot.aliases())
        {
            if(alias.hasGroup())
            {
                groupNames.add(alias.getGroup());
            }
        }

        return List.copyOf(groupNames);
    }

    /**
     * Bulk loading of aliases
     */
    void addAliases(List<Alias> aliases)
    {
        if(aliases == null || aliases.isEmpty())
        {
            return;
        }

        List<Alias> validated = new ArrayList<>(aliases.size());
        aliases.forEach(alias -> validated.add(validateAndBind(alias)));
        validateDistinctAliases(validated);

        replaceSnapshot(replacementSnapshot(validated), mSnapshot.definitions());
    }

    private List<Alias> replacementSnapshot(List<Alias> replacements)
    {
        Map<Long,Alias> replacementsById = new HashMap<>();
        replacements.forEach(alias -> replacementsById.put(alias.getId(), alias));
        Set<Long> installedIds = new HashSet<>();
        List<Alias> rebuilt = new ArrayList<>(mSnapshot.aliases().size() + replacements.size());

        for(Alias existing: mSnapshot.aliases())
        {
            long id = existing.getId();
            Alias replacement = replacementsById.get(id);
            if(replacement == null)
            {
                rebuilt.add(existing);
            }
            else if(installedIds.add(id))
            {
                rebuilt.add(replacement);
            }
        }

        for(Alias alias: replacements)
        {
            if(installedIds.add(alias.getId()))
            {
                rebuilt.add(alias);
            }
        }

        return rebuilt;
    }

    /**
     * Adds the alias to the model
     */
    void addAlias(Alias alias)
    {
        if(alias == null)
        {
            return;
        }

        addAliases(List.of(alias));
    }

    /**
     * Removes the alias from this model and an alias list (if one exists)
     */
    void removeAlias(Alias alias)
    {
        if(alias != null)
        {
            replaceSnapshot(mSnapshot.aliases().stream().filter(existing -> !sameIdentity(existing, alias)).toList(),
                mSnapshot.definitions());
        }
    }

    /**
     * Removes the list of aliases from this model and any alias lists that might contain each alias.
     * @param aliases
     */
    void removeAliases(List<Alias> aliases)
    {
        if(aliases != null && !aliases.isEmpty())
        {
            Set<Alias> instances = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Long> persistedIds = new HashSet<>();

            for(Alias alias: aliases)
            {
                if(alias != null)
                {
                    instances.add(alias);

                    if(alias.getId() > Alias.UNASSIGNED_ID)
                    {
                        persistedIds.add(alias.getId());
                    }
                }
            }

            replaceSnapshot(mSnapshot.aliases().stream().filter(existing -> !instances.contains(existing) &&
                !(existing.getId() > Alias.UNASSIGNED_ID && persistedIds.contains(existing.getId()))).toList(),
                mSnapshot.definitions());
        }
    }

    /** A committed Alias has one stable SQLite identity even when an editor supplies a detached replacement. */
    private static boolean sameIdentity(Alias first, Alias second)
    {
        return first != null && second != null && first.getId() > Alias.UNASSIGNED_ID &&
            first.getId() == second.getId();
    }

    private static void validateDistinctAliases(List<Alias> aliases)
    {
        Set<Alias> instances = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Long> persistedIds = new HashSet<>();

        for(Alias alias: aliases)
        {
            if(alias.getId() <= Alias.UNASSIGNED_ID || !instances.add(alias) || !persistedIds.add(alias.getId()))
            {
                throw new IllegalArgumentException("Active aliases require unique durable identities");
            }
        }
    }

    /**
     * Retrieves all aliases that match the alias list and have at least one alias ID of the specified type
     * @param aliasListName to search
     * @param type to find
     * @return list of aliases
     */
    public List<Alias> getAliases(String aliasListName, AliasIDType type)
    {
        List<Alias> aliases = new ArrayList<>();
        AliasListDefinition definition = getAliasListDefinition(aliasListName);

        if(definition == null)
        {
            return aliases;
        }

        for(Alias alias: mSnapshot.aliases())
        {
            if(alias.belongsTo(definition))
            {
                AliasID aliasID = alias.getMatchIdentifier();

                if(aliasID != null && aliasID.getType() == type)
                {
                    aliases.add(alias);
                }
            }
        }

        return aliases;
    }

    /**
     * Indicates that an Alias or an Alias List unmatched-talkgroup policy references the specified broadcast stream.
     * @param configurationId stable broadcast-configuration identity to check
     * @return true if the broadcast channel is non-null, non-empty and at least one alias is configured to stream to
     * the specified stream name.
     */
    public boolean hasBroadcastConfigurationReferences(String configurationId)
    {
        if(configurationId == null || configurationId.isBlank())
        {
            return false;
        }

        for(Alias alias: mSnapshot.aliases())
        {
            if(alias.hasBroadcastConfiguration(configurationId))
            {
                return true;
            }
        }

        for(AliasListDefinition definition: mSnapshot.definitions())
        {
            if(definition.getUnmatchedTalkgroupPolicy().getStreamDestinations().stream()
                .anyMatch(destination -> configurationId.equals(destination.getConfigurationId())) ||
                definition.getNewAliasBehavior().getStreamDestinations().stream()
                    .anyMatch(destination -> configurationId.equals(destination.getConfigurationId())))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Runtime accepts only current aliases with one valid matcher supported by their protocol-owned list.
     */
    private Alias validateAndBind(Alias alias)
    {
        if(alias == null)
        {
            throw new IllegalArgumentException("Alias cannot be null");
        }

        if(alias.getId() <= Alias.UNASSIGNED_ID || alias.getAliasListId() <= Alias.UNASSIGNED_ALIAS_LIST_ID)
        {
            throw new IllegalArgumentException("Active aliases require durable Alias and Alias List identities");
        }

        AliasListDefinition definition = getAliasListDefinition(alias.getAliasListId());

        if(definition == null)
        {
            throw new IllegalArgumentException("Alias [" + alias.getName() +
                "] must reference an existing alias-list definition");
        }

        if(definition.getFamily() == null)
        {
            throw new IllegalArgumentException("Alias list [" + definition.getName() +
                "] must declare a protocol family");
        }

        AliasID matcher = alias.getMatchIdentifier();
        if(!AliasMatchRegistry.isOperational(definition, matcher))
        {
            throw new IllegalArgumentException("Alias matcher [" + matcher + "] is not supported by alias list [" +
                definition.getName() + "]");
        }

        alias.setAliasListDefinition(definition);
        return alias;
    }

    private void reconcileCachedAliasLists()
    {
        reconcileCachedAliasLists(Set.copyOf(mAliasListMap.keySet()));
    }

    private void reconcileCachedAliasLists(Set<Long> affectedAliasListIds)
    {
        List<AliasList> cachedLists = List.copyOf(mAliasListMap.values());
        Map<Long,List<Alias>> orderedAliasesByList = new HashMap<>();
        for(AliasList aliasList: cachedLists)
        {
            orderedAliasesByList.put(aliasList.getId(), new ArrayList<>());
        }
        for(Alias alias: mSnapshot.aliases())
        {
            List<Alias> ordered = orderedAliasesByList.get(alias.getAliasListId());
            if(ordered != null)
            {
                ordered.add(alias);
            }
        }
        for(AliasList aliasList: cachedLists)
        {
            List<Alias> ordered = orderedAliasesByList.get(aliasList.getId());
            //An order-only publication can change a duplicate matcher's winner without replacing any Alias.
            if(affectedAliasListIds.contains(aliasList.getId()) || !aliasList.aliases().equals(ordered))
            {
                aliasList.reconcileAliases(ordered);
            }
        }
    }
}
