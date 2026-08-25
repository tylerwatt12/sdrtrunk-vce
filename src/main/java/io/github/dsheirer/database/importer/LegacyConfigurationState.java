/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.database.importer;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.configuration.ConfigurationSnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Mutable import-only model used while legacy XML is normalized and merged.
 *
 * <p>Legacy listen flags have no current runtime equivalent. They remain attached to source object identities until
 * durable Alias and Alias List IDs are assigned, and are then projected onto the Default scan list.</p>
 */
final class LegacyConfigurationState
{
    private List<Alias> mAliases = new ArrayList<>();
    private List<AliasListDefinition> mAliasListDefinitions = new ArrayList<>();
    private List<BroadcastConfiguration> mBroadcastConfigurations = new ArrayList<>();
    private List<Channel> mChannels = new ArrayList<>();
    private ScanListConfiguration mScanListConfiguration;
    private final Map<Alias,Boolean> mLegacyAliasListenEnabled = new IdentityHashMap<>();
    private final Map<AliasListDefinition,Boolean> mLegacyAliasListListenEnabled = new IdentityHashMap<>();

    static LegacyConfigurationState from(ConfigurationSnapshot snapshot)
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        state.setAliasListDefinitions(snapshot.aliasListDefinitions());
        state.setAliases(snapshot.aliases());
        state.setScanListConfiguration(snapshot.scanListConfiguration());
        state.setChannels(snapshot.channels());
        state.setBroadcastConfigurations(snapshot.broadcastConfigurations());
        return state;
    }

    /** Assigns missing durable IDs and resolves legacy listen flags into one complete current snapshot. */
    ConfigurationSnapshot toConfigurationSnapshot(ConfigurationRepository repository)
    {
        if(mScanListConfiguration == null)
        {
            throw new IllegalStateException("Legacy configuration must be merged with current scan lists first");
        }

        assignMissingIdentities(repository);
        return new ConfigurationSnapshot(mAliasListDefinitions, mAliases, applyLegacyListenIntent(), mChannels,
            mBroadcastConfigurations);
    }

    private void assignMissingIdentities(ConfigurationRepository repository)
    {
        List<AliasListDefinition> missingDefinitions = mAliasListDefinitions.stream()
            .filter(definition -> definition.getId() <= AliasListDefinition.UNASSIGNED_ID).toList();
        List<Long> definitionIds = repository.nextAliasListIds(mAliasListDefinitions.stream()
            .map(AliasListDefinition::getId).toList(), missingDefinitions.size());
        for(int index = 0; index < missingDefinitions.size(); index++)
        {
            missingDefinitions.get(index).setId(definitionIds.get(index));
        }

        Map<String,AliasListDefinition> definitionsByName = new LinkedHashMap<>();
        for(AliasListDefinition definition: mAliasListDefinitions)
        {
            String key = normalizeAliasListName(definition.getName());
            AliasListDefinition existing = definitionsByName.putIfAbsent(key, definition);
            if(existing != null)
            {
                throw new IllegalStateException("Legacy Alias Lists [" + existing.getName() + "] and [" +
                    definition.getName() + "] have the same canonical name");
            }
        }
        for(Alias alias: mAliases)
        {
            String aliasListName = alias.getAliasListName();
            AliasListDefinition definition = aliasListName != null ?
                definitionsByName.get(normalizeAliasListName(aliasListName)) : null;
            if(definition == null)
            {
                throw new IllegalStateException("Legacy Alias [" + alias.getName() +
                    "] has no resolved Alias List");
            }
            alias.setAliasListDefinition(definition);
        }

        List<Alias> missingAliases = mAliases.stream()
            .filter(alias -> alias.getId() <= Alias.UNASSIGNED_ID).toList();
        List<Long> aliasIds = repository.nextAliasIds(mAliases.stream().map(Alias::getId).toList(),
            missingAliases.size());
        for(int index = 0; index < missingAliases.size(); index++)
        {
            missingAliases.get(index).setId(aliasIds.get(index));
        }
    }

    private static String normalizeAliasListName(String name)
    {
        return name != null ? name.trim().toLowerCase(Locale.ROOT) : "";
    }

    private ScanListConfiguration applyLegacyListenIntent()
    {
        Map<Long,Set<Long>> aliases = mutable(mScanListConfiguration.aliasMemberships());
        Map<Long,Set<Long>> unmatched = mutable(mScanListConfiguration.unmatchedAliasListMemberships());
        ScanList defaultScanList = mScanListConfiguration.defaultScanList();

        mAliases.forEach(alias -> getLegacyAliasListenEnabled(alias).ifPresent(enabled ->
        {
            if(enabled)
            {
                aliases.put(alias.getId(), Set.of(defaultScanList.getId()));
            }
            else
            {
                aliases.remove(alias.getId());
            }
        }));
        mAliasListDefinitions.forEach(definition -> getLegacyAliasListListenEnabled(definition).ifPresent(enabled ->
        {
            if(enabled)
            {
                unmatched.put(definition.getId(), Set.of(defaultScanList.getId()));
            }
            else
            {
                unmatched.remove(definition.getId());
            }
        }));
        return new ScanListConfiguration(mScanListConfiguration.scanLists(), aliases, unmatched);
    }

    private static Map<Long,Set<Long>> mutable(Map<Long,Set<Long>> source)
    {
        Map<Long,Set<Long>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, new LinkedHashSet<>(value)));
        return copy;
    }

    public List<Alias> getAliases()
    {
        return mAliases;
    }

    public void setAliases(List<Alias> aliases)
    {
        mAliases = aliases != null ? aliases : new ArrayList<>();
    }

    public List<AliasListDefinition> getAliasListDefinitions()
    {
        return mAliasListDefinitions;
    }

    public void setAliasListDefinitions(List<AliasListDefinition> aliasListDefinitions)
    {
        mAliasListDefinitions = aliasListDefinitions != null ? aliasListDefinitions : new ArrayList<>();
    }

    public ScanListConfiguration getScanListConfiguration()
    {
        return mScanListConfiguration;
    }

    public void setScanListConfiguration(ScanListConfiguration scanListConfiguration)
    {
        mScanListConfiguration = scanListConfiguration;
    }

    public void setLegacyAliasListenEnabled(Alias alias, boolean enabled)
    {
        if(alias != null)
        {
            mLegacyAliasListenEnabled.put(alias, enabled);
        }
    }

    public Optional<Boolean> getLegacyAliasListenEnabled(Alias alias)
    {
        return Optional.ofNullable(mLegacyAliasListenEnabled.get(alias));
    }

    public void setLegacyAliasListListenEnabled(AliasListDefinition definition, boolean enabled)
    {
        if(definition != null)
        {
            mLegacyAliasListListenEnabled.put(definition, enabled);
        }
    }

    public Optional<Boolean> getLegacyAliasListListenEnabled(AliasListDefinition definition)
    {
        return Optional.ofNullable(mLegacyAliasListListenEnabled.get(definition));
    }

    public List<Channel> getChannels()
    {
        return mChannels;
    }

    public void setChannels(List<Channel> channels)
    {
        mChannels = channels != null ? channels : new ArrayList<>();
    }

    public List<BroadcastConfiguration> getBroadcastConfigurations()
    {
        return mBroadcastConfigurations;
    }

    public void setBroadcastConfigurations(List<BroadcastConfiguration> configurations)
    {
        mBroadcastConfigurations = configurations != null ? configurations : new ArrayList<>();
    }
}
