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
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alias Model contains all aliases and is responsible for creation and management of alias lists.  Alias lists are a
 * set of aliases that all share a common alias list name and can be attached to a decoding channel for aliasing
 * identifiers produced by channel decoder(s).
 */
public class AliasModel
{
    private static final Logger mLog = LoggerFactory.getLogger(AliasModel.class);
    private final ObservableList<Alias> mAliases = FXCollections.observableArrayList();
    private final ObservableList<Alias> mReadOnlyAliases = FXCollections.unmodifiableObservableList(mAliases);
    private final ObservableList<String> mAliasListNames = FXCollections.observableArrayList();
    private final ObservableList<String> mReadOnlyAliasListNames =
        FXCollections.unmodifiableObservableList(mAliasListNames);
    private final ObservableList<AliasListDefinition> mAliasListDefinitions = FXCollections.observableArrayList();
    private final ObservableList<AliasListDefinition> mReadOnlyAliasListDefinitions =
        FXCollections.unmodifiableObservableList(mAliasListDefinitions);
    private final Map<String,AliasList> mAliasListMap = new HashMap<>();
    private boolean mReconcilingAliasLists;

    public AliasModel()
    {
        //Keep cached decoder lookup lists synchronized with explicit model publications.
        mAliases.addListener(new AliasListChangeListener());
    }

    public ObservableList<Alias> aliasList()
    {
        return mReadOnlyAliases;
    }

    public ObservableList<String> aliasListNames()
    {
        return mReadOnlyAliasListNames;
    }

    /**
     * Persisted alias-list definitions. Names remain available separately for existing UI bindings.
     */
    public ObservableList<AliasListDefinition> aliasListDefinitions()
    {
        return mReadOnlyAliasListDefinitions;
    }

    void setAliasListDefinitions(Collection<AliasListDefinition> definitions)
    {
        List<AliasListDefinition> prepared = definitions != null ? List.copyOf(definitions) : List.of();
        validateCommittedDefinitions(prepared);
        mAliasListDefinitions.setAll(prepared);
        refreshAliasListNames();
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
        mAliases.stream().map(Alias::getId).forEach(allAliasIds::add);
        publishCommittedConfiguration(definitions, aliases, allAliasIds, true);
    }

    /**
     * Publishes the changed portion of an already committed configuration while retaining unchanged live Alias
     * instances and alias-list names. This keeps decoder lookups and JavaFX selection stable across unrelated edits.
     */
    public void publishCommittedConfiguration(Collection<AliasListDefinition> definitions,
                                               Collection<Alias> aliases, Collection<Long> changedAliasIds,
                                               boolean definitionsChanged)
    {
        List<AliasListDefinition> committedDefinitions =
            definitions != null ? List.copyOf(definitions) : List.of();
        List<Alias> committedAliases = aliases != null ? List.copyOf(aliases) : List.of();
        Map<Long,AliasListDefinition> committedDefinitionsById = validateCommittedDefinitions(committedDefinitions);
        Set<Long> changedIds = changedAliasIds != null ? Set.copyOf(changedAliasIds) : Set.of();

        if(!definitionsChanged && !sameDefinitions(mAliasListDefinitions, committedDefinitions))
        {
            throw new IllegalArgumentException("Committed Alias List changes must be declared before publication");
        }

        List<AliasListDefinition> publishedDefinitions = definitionsChanged ? committedDefinitions :
            List.copyOf(mAliasListDefinitions);
        Map<Long,AliasListDefinition> publishedDefinitionsById = definitionsChanged ? committedDefinitionsById :
            validateCommittedDefinitions(publishedDefinitions);

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

            alias.setAliasListDefinition(definition);
        }

        Map<Long,Alias> liveAliasesById = new HashMap<>();
        for(Alias alias: mAliases)
        {
            liveAliasesById.put(alias.getId(), alias);
        }

        Set<Long> removedIds = new HashSet<>(liveAliasesById.keySet());
        removedIds.removeAll(aliasIds);
        if(!changedIds.containsAll(removedIds))
        {
            throw new IllegalArgumentException("Committed Alias removals must be declared before publication");
        }

        if(definitionsChanged)
        {
            for(Map.Entry<String,AliasList> entry: List.copyOf(mAliasListMap.entrySet()))
            {
                AliasListDefinition replacement = committedDefinitions.stream()
                    .filter(definition -> definition.getName().equals(entry.getKey())).findFirst().orElse(null);
                if(replacement != null)
                {
                    entry.getValue().replaceDefinition(replacement);
                }
            }

            mAliasListDefinitions.setAll(committedDefinitions);
            refreshAliasListNames();
        }

        List<Alias> publishedAliases = new ArrayList<>(committedAliases.size());
        for(Alias committedAlias: committedAliases)
        {
            Alias liveAlias = liveAliasesById.get(committedAlias.getId());
            Alias published = liveAlias == null || changedIds.contains(committedAlias.getId()) ? committedAlias :
                liveAlias;
            if(definitionsChanged && published == liveAlias)
            {
                published.setAliasListDefinition(publishedDefinitionsById.get(published.getAliasListId()));
            }
            publishedAliases.add(published);
        }

        reconcilePublishedAliases(publishedAliases);
        mAliasListMap.keySet().removeIf(name -> getAliasListDefinition(name) == null);
    }

    private void reconcilePublishedAliases(List<Alias> desired)
    {
        Set<Long> desiredIds = desired.stream().map(Alias::getId).collect(java.util.stream.Collectors.toSet());
        mAliases.removeIf(alias -> !desiredIds.contains(alias.getId()));

        for(int index = 0; index < desired.size(); index++)
        {
            Alias replacement = desired.get(index);
            if(index >= mAliases.size())
            {
                mAliases.add(replacement);
            }
            else if(mAliases.get(index).getId() == replacement.getId())
            {
                if(mAliases.get(index) != replacement)
                {
                    mAliases.set(index, replacement);
                }
            }
            else
            {
                int existingIndex = indexOfAliasId(mAliases, replacement.getId(), index + 1);
                if(existingIndex >= 0)
                {
                    mAliases.remove(existingIndex);
                }
                mAliases.add(index, replacement);
            }
        }

        while(mAliases.size() > desired.size())
        {
            mAliases.removeLast();
        }
    }

    private static int indexOfAliasId(List<Alias> aliases, long aliasId, int start)
    {
        for(int index = start; index < aliases.size(); index++)
        {
            if(aliases.get(index).getId() == aliasId)
            {
                return index;
            }
        }
        return -1;
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
            if(first.getId() != second.getId() || !Objects.equals(first.getName(), second.getName()) ||
                first.getFamily() != second.getFamily() ||
                !Objects.equals(first.getUnmatchedTalkgroupPolicy(), second.getUnmatchedTalkgroupPolicy()))
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

        AliasListDefinition existing = definition.getId() > AliasListDefinition.UNASSIGNED_ID ?
            getAliasListDefinition(definition.getId()) : getAliasListDefinition(definition.getName());

        if(existing == null)
        {
            mAliasListDefinitions.add(definition);
        }
        else if(existing != definition)
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
        if(definition != null && mAliasListDefinitions.remove(definition))
        {
            refreshAliasListNames();
        }
    }

    public AliasListDefinition getAliasListDefinition(long id)
    {
        if(id <= AliasListDefinition.UNASSIGNED_ID)
        {
            return null;
        }

        return mAliasListDefinitions.stream().filter(definition -> definition.getId() == id).findFirst().orElse(null);
    }

    public AliasListDefinition getAliasListDefinition(String name)
    {
        if(name == null || name.isEmpty())
        {
            return null;
        }

        return mAliasListDefinitions.stream().filter(definition ->
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
     * Assigns the compatible visible factory Alias List to a newly constructed channel when one is available.
     * Existing channel assignments are not changed by this helper.
     */
    public boolean assignDefaultAliasList(Channel channel)
    {
        DecoderType decoderType = channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        AliasListDefinition definition = getDefaultAliasListDefinition(decoderType);

        if(definition != null)
        {
            channel.setAliasListName(definition.getName());
            return true;
        }

        return false;
    }

    public AliasListDefinition getAliasListDefinition(Alias alias)
    {
        if(alias == null)
        {
            return null;
        }

        return alias.getAliasListId() > AliasListDefinition.UNASSIGNED_ID ?
            getAliasListDefinition(alias.getAliasListId()) :
            getAliasListDefinition(alias.getAliasListName());
    }

    /**
     * Clears and reloads the list of alias list names from the current set of aliases.
     */
    public void refreshAliasListNames()
    {
        List<String> names = new ArrayList<>();
        for(AliasListDefinition definition: mAliasListDefinitions)
        {
            if(definition.getName() != null && !definition.getName().isEmpty() &&
                !names.contains(definition.getName()))
            {
                names.add(definition.getName());
            }
        }

        if(!mAliasListNames.equals(names))
        {
            mAliasListNames.setAll(names);
        }
    }

    /**
     * Unmodifiable list of all aliases currently in the model
     */
    public List<Alias> getAliases()
    {
        return Collections.unmodifiableList(mAliases);
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

        return mAliases.stream().filter(alias -> alias.getId() == aliasId).findFirst().orElse(null);
    }

    /**
     * Removes all aliases from the list and broadcasts the alias delete event for each
     */
    public void clear()
    {
        mAliases.clear();
        mAliasListNames.clear();
        mAliasListDefinitions.clear();
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
     * @param configurationIdentifier containing the name of an alias list
     * @return alias list or null.
     */
    public AliasList getAliasList(AliasListConfigurationIdentifier configurationIdentifier)
    {
        if(configurationIdentifier != null && configurationIdentifier.isValid())
        {
            return getAliasList(configurationIdentifier.getValue());
        }

        return null;
    }

    /**
     * Creates a new alias list containing all aliases that match the alias name, or returns a previously created and
     * cached alias list.  Returned alias list is automatically registered as a listener to this model so that any
     * updates to the list by the user will automatically be reflected in constructed alias lists.
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

        AliasList mapValue = mAliasListMap.get(definition.getName());
        if (mapValue != null)
        {
            return mapValue;
        }

        AliasList aliasList = new AliasList(definition);
        List<Alias> matchingAliases = new ArrayList<>();

        for(Alias alias: mAliases)
        {
            if(alias.belongsTo(definition))
            {
                matchingAliases.add(alias);
            }
        }

        aliasList.addAliases(matchingAliases);
        mAliasListMap.put(definition.getName(), aliasList);

        return aliasList;
    }

    public AliasList getAliasList(AliasListDefinition definition)
    {
        return definition != null ? getAliasList(definition.getName()) : null;
    }

    /**
     * Returns the configured list only when its protocol family matches the channel decoder. Invalid or stale
     * assignments receive an empty list.
     */
    public AliasList getAliasListForChannel(Channel channel)
    {
        if(channel == null || channel.getAliasListName() == null || channel.getAliasListName().isBlank())
        {
            return AliasList.empty(channel != null ? channel.getAliasListName() : null);
        }

        AliasListDefinition definition = getAliasListDefinition(channel.getAliasListName());

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
            getAliasListDefinition(channel.getAliasListName()));
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

        for(Alias alias : mAliases)
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

        if(validated.size() == 1 && countSameIdentities(validated.getFirst()) <= 1)
        {
            Alias alias = validated.getFirst();
            int existingIndex = indexOfSameIdentity(mAliases, alias);
            if(existingIndex >= 0)
            {
                mAliases.set(existingIndex, alias);
            }
            else
            {
                mAliases.add(alias);
            }
            return;
        }

        List<Alias> rebuilt = replacementSnapshot(validated);
        mAliases.setAll(rebuilt);
    }

    private List<Alias> replacementSnapshot(List<Alias> replacements)
    {
        Set<Long> replacementIds = new HashSet<>();
        replacements.stream().map(Alias::getId).filter(id -> id > Alias.UNASSIGNED_ID)
            .forEach(replacementIds::add);
        Set<Long> retainedIds = new HashSet<>();
        List<Alias> rebuilt = new ArrayList<>(mAliases.size() + replacements.size());

        for(Alias existing: mAliases)
        {
            long id = existing.getId();
            if(!replacementIds.contains(id) || retainedIds.add(id))
            {
                rebuilt.add(existing);
            }
        }

        for(Alias alias: replacements)
        {
            int existingIndex = indexOfSameIdentity(rebuilt, alias);
            if(existingIndex >= 0)
            {
                rebuilt.set(existingIndex, alias);
            }
            else
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
            mAliases.removeIf(existing -> sameIdentity(existing, alias));
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

            mAliases.removeIf(existing -> instances.contains(existing) ||
                existing.getId() > Alias.UNASSIGNED_ID && persistedIds.contains(existing.getId()));
        }
    }

    /** A committed Alias has one stable SQLite identity even when an editor supplies a detached replacement. */
    private static boolean sameIdentity(Alias first, Alias second)
    {
        return first != null && second != null && first.getId() > Alias.UNASSIGNED_ID &&
            first.getId() == second.getId();
    }

    private int countSameIdentities(Alias alias)
    {
        int count = 0;
        for(Alias existing: mAliases)
        {
            if(sameIdentity(existing, alias))
            {
                count++;
            }
        }
        return count;
    }

    private static int indexOfSameIdentity(List<Alias> aliases, Alias alias)
    {
        for(int index = 0; index < aliases.size(); index++)
        {
            if(sameIdentity(aliases.get(index), alias))
            {
                return index;
            }
        }

        return -1;
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

        for(Alias alias: mAliases)
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
     * @param broadcastChannel to check
     * @return true if the broadcast channel is non-null, non-empty and at least one alias is configured to stream to
     * the specified stream name.
     */
    public boolean hasBroadcastChannelReferences(String broadcastChannel)
    {
        if(broadcastChannel == null || broadcastChannel.isEmpty())
        {
            return false;
        }

        for(Alias alias: mAliases)
        {
            if(alias.hasBroadcastChannel(broadcastChannel))
            {
                return true;
            }
        }

        for(AliasListDefinition definition: mAliasListDefinitions)
        {
            if(definition.getUnmatchedTalkgroupPolicy().getStreamDestinationNames().contains(broadcastChannel))
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

    /**
     * Monitors alias additions and removals and updates alias lists
     */
    public class AliasListChangeListener implements ListChangeListener<Alias>
    {
        @Override
        public void onChanged(ListChangeListener.Change<? extends Alias> change)
        {
            if(mReconcilingAliasLists)
            {
                return;
            }

            boolean changed = false;
            while(change.next())
            {
                changed |= change.wasAdded() || change.wasRemoved() || change.wasUpdated() || change.wasPermutated();
            }

            if(changed)
            {
                mReconcilingAliasLists = true;
                try
                {
                    List<Alias> orderedAliases = List.copyOf(mAliases);
                    for(AliasList aliasList: List.copyOf(mAliasListMap.values()))
                    {
                        aliasList.reconcileAliases(orderedAliases);
                    }
                }
                finally
                {
                    mReconcilingAliasLists = false;
                }
            }
        }
    }
}
