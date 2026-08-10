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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ObservableList<Alias> mAliases = FXCollections.observableArrayList(Alias.extractor());
    private final ObservableList<Alias> mReadOnlyAliases = FXCollections.unmodifiableObservableList(mAliases);
    private final ObservableList<String> mAliasListNames = FXCollections.observableArrayList();
    private final ObservableList<AliasListDefinition> mAliasListDefinitions = FXCollections.observableArrayList();
    private final ObservableList<AliasListDefinition> mReadOnlyAliasListDefinitions =
        FXCollections.unmodifiableObservableList(mAliasListDefinitions);
    private final Map<String,AliasList> mAliasListMap = new HashMap<>();
    private boolean mReconcilingAliasLists;

    public AliasModel()
    {
        //Register a listener to detect alias changes and broadcast change events to cause configuration save requests
        mAliases.addListener(new AliasListChangeListener());
    }

    public ObservableList<Alias> aliasList()
    {
        return mReadOnlyAliases;
    }

    public ObservableList<String> aliasListNames()
    {
        return mAliasListNames;
    }

    /**
     * Persisted alias-list definitions. Names remain available separately for existing UI bindings.
     */
    public ObservableList<AliasListDefinition> aliasListDefinitions()
    {
        return mReadOnlyAliasListDefinitions;
    }

    public void setAliasListDefinitions(Collection<AliasListDefinition> definitions)
    {
        mAliasListDefinitions.setAll(definitions != null ? definitions : List.of());
        refreshAliasListNames();
    }

    public void addAliasListDefinition(AliasListDefinition definition)
    {
        if(definition == null)
        {
            return;
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
    public void removeAliasListDefinition(AliasListDefinition definition)
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
        mAliasListNames.clear();

        for(AliasListDefinition definition: mAliasListDefinitions)
        {
            if(definition.getName() != null && !definition.getName().isEmpty() &&
                !mAliasListNames.contains(definition.getName()))
            {
                mAliasListNames.add(definition.getName());
            }
        }

    }

    /**
     * Discards an empty deleted list after its configuration transaction commits. Package-private so alias
     * administration can retain the live lookup instance until a failed save can no longer require rollback.
     */
    void discardAliasListCache(String aliasListName)
    {
        if(aliasListName != null)
        {
            mAliasListMap.remove(aliasListName);
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
        List<String> groupNames = new ArrayList<>();

        for(Alias alias : mAliases)
        {
            if(alias.hasGroup() && !groupNames.contains(alias.getGroup()))
            {
                groupNames.add(alias.getGroup());
            }
        }

        Collections.sort(groupNames);

        return groupNames;
    }

    /**
     * Bulk loading of aliases
     */
    public void addAliases(List<Alias> aliases)
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
            if(id <= Alias.UNASSIGNED_ID || !replacementIds.contains(id) || retainedIds.add(id))
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
    public void addAlias(Alias alias)
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
    public void removeAlias(Alias alias)
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
    public void removeAliases(List<Alias> aliases)
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

    /**
     * A persisted Alias has one stable SQLite identity even when an editor or API supplies a detached replacement
     * object. New, unsaved aliases remain identity-based so independently created rows can coexist before saving.
     */
    private static boolean sameIdentity(Alias first, Alias second)
    {
        return first == second || first != null && second != null &&
            first.getId() > Alias.UNASSIGNED_ID && first.getId() == second.getId();
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

    /**
     * Restores the pre-mutation order after a failed durable transaction, retaining the first original row for any
     * transient duplicate positive ID while preserving independent unsaved rows.
     */
    void restoreAliases(List<Alias> aliases)
    {
        Set<Long> persistedIds = new HashSet<>();
        Set<Alias> instances = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Alias> restored = new ArrayList<>(aliases.size());

        for(Alias alias: aliases)
        {
            Alias validated = validateAndBind(alias);
            if(instances.add(validated) && (validated.getId() <= Alias.UNASSIGNED_ID ||
                persistedIds.add(validated.getId())))
            {
                restored.add(validated);
            }
        }

        mAliases.setAll(restored);
    }

    private static void validateDistinctAliases(List<Alias> aliases)
    {
        Set<Alias> instances = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Long> persistedIds = new HashSet<>();

        for(Alias alias: aliases)
        {
            if(!instances.add(alias) || alias.getId() > Alias.UNASSIGNED_ID && !persistedIds.add(alias.getId()))
            {
                throw new IllegalArgumentException("Aliases to add must have unique identities");
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
     * Indicates that one or more of the aliases managed by this model are configured to stream to the specified
     * broadcast channel argument.
     * @param broadcastChannel to check
     * @return true if the broadcast channel is non-null, non-empty and at least one alias is configured to stream to
     * the specified stream name.
     */
    public boolean hasAliasesWithBroadcastChannel(String broadcastChannel)
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

        AliasListDefinition definition = getAliasListDefinition(alias.getAliasListId());

        if(definition == null && alias.getId() == Alias.UNASSIGNED_ID &&
            alias.getAliasListId() == Alias.UNASSIGNED_ALIAS_LIST_ID)
        {
            definition = getAliasListDefinition(alias.getAliasListName());
        }

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
