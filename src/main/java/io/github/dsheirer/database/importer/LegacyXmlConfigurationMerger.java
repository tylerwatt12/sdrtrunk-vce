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

package io.github.dsheirer.database.importer;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasFactory;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Non-destructively merges a normalized legacy XML configuration into current SQLite configuration.
 *
 * <p>Current configuration always wins. Imported alias lists, channels, and streams with case-insensitive name
 * conflicts are retained under a unique {@code (Imported)} name. This keeps the merge compatible with the current
 * one-owner alias-list schema without changing or deleting existing administrator configuration.</p>
 */
public final class LegacyXmlConfigurationMerger
{
    private LegacyXmlConfigurationMerger()
    {
    }

    /**
     * Describes the imported content and the names that will need to change.
     */
    public static Preview preview(ConfigurationState existing, ConfigurationState imported)
    {
        ConfigurationState currentState = state(existing);
        ConfigurationState importedState = state(imported);

        Set<String> aliasListNames = normalizedDefinitionNames(currentState.getAliasListDefinitions());
        int aliasListConflicts = 0;

        for(AliasListDefinition definition: nonNullDefinitions(importedState.getAliasListDefinitions()))
        {
            NameReservation reservation = reserveName(definition.getName(), aliasListNames);

            if(reservation.renamed())
            {
                aliasListConflicts++;
            }
        }

        Set<String> channelKeys = channelKeys(currentState.getChannels());
        int channelConflicts = 0;

        for(Channel channel: nonNullChannels(importedState.getChannels()))
        {
            NameReservation reservation = reserveChannelName(channel, channelKeys);

            if(reservation.renamed())
            {
                channelConflicts++;
            }
        }

        Set<String> streamNames = normalizedStreamNames(currentState.getBroadcastConfigurations());
        int streamConflicts = 0;

        for(BroadcastConfiguration stream: nonNullStreams(importedState.getBroadcastConfigurations()))
        {
            NameReservation reservation = reserveName(stream.getName(), streamNames);

            if(reservation.renamed())
            {
                streamConflicts++;
            }
        }

        return new Preview(nonNullDefinitions(importedState.getAliasListDefinitions()).size(),
            nonNullAliases(importedState.getAliases()).size(), nonNullChannels(importedState.getChannels()).size(),
            nonNullStreams(importedState.getBroadcastConfigurations()).size(), aliasListConflicts, channelConflicts,
            streamConflicts);
    }

    /**
     * Adds every valid imported item while preserving all existing configuration.
     */
    public static MergeResult merge(ConfigurationState existing, ConfigurationState imported)
    {
        ConfigurationState currentState = state(existing);
        ConfigurationState importedState = state(imported);
        ConfigurationState merged = new ConfigurationState();

        List<AliasListDefinition> mergedDefinitions =
            new ArrayList<>(nonNullDefinitions(currentState.getAliasListDefinitions()));
        List<Alias> mergedAliases = new ArrayList<>(nonNullAliases(currentState.getAliases()));
        List<Channel> mergedChannels = new ArrayList<>(nonNullChannels(currentState.getChannels()));
        List<BroadcastConfiguration> mergedStreams =
            new ArrayList<>(nonNullStreams(currentState.getBroadcastConfigurations()));

        Set<String> aliasListNames = normalizedDefinitionNames(mergedDefinitions);
        Map<String,AliasListDefinition> importedDefinitionsByOriginalName = new HashMap<>();
        int renamedAliasLists = 0;

        for(AliasListDefinition sourceDefinition: nonNullDefinitions(importedState.getAliasListDefinitions()))
        {
            NameReservation reservation = reserveName(sourceDefinition.getName(), aliasListNames);
            AliasListDefinition importedDefinition = new AliasListDefinition(reservation.name(),
                sourceDefinition.getSystemName(), sourceDefinition.getFamily());
            mergedDefinitions.add(importedDefinition);
            importedDefinitionsByOriginalName.putIfAbsent(normalize(sourceDefinition.getName()), importedDefinition);

            if(reservation.renamed())
            {
                renamedAliasLists++;
            }
        }

        Set<String> streamNames = normalizedStreamNames(mergedStreams);
        Set<String> streamConfigurationIds = configurationIds(mergedStreams);
        Map<String,String> importedStreamNames = new HashMap<>();
        int renamedStreams = 0;
        int importedStreamCount = 0;

        for(BroadcastConfiguration stream: nonNullStreams(importedState.getBroadcastConfigurations()))
        {
            String originalName = stream.getName();
            NameReservation reservation = reserveName(originalName, streamNames);
            stream.setName(reservation.name());
            regenerateUniqueConfigurationId(stream, streamConfigurationIds);
            mergedStreams.add(stream);
            importedStreamNames.putIfAbsent(normalize(originalName), reservation.name());
            importedStreamCount++;

            if(reservation.renamed())
            {
                renamedStreams++;
            }
        }

        int importedAliasCount = 0;

        for(Alias sourceAlias: nonNullAliases(importedState.getAliases()))
        {
            AliasListDefinition importedDefinition =
                importedDefinitionsByOriginalName.get(normalize(sourceAlias.getAliasListName()));

            if(importedDefinition == null)
            {
                throw new IllegalArgumentException("Imported alias [" + sourceAlias.getName() +
                    "] has no imported alias-list definition");
            }

            Alias importedAlias = AliasFactory.copyOf(sourceAlias);
            importedAlias.setAliasListDefinition(importedDefinition);
            updateBroadcastRoutes(importedAlias, importedStreamNames);
            mergedAliases.add(importedAlias);
            importedAliasCount++;
        }

        Set<String> channelKeys = channelKeys(mergedChannels);
        Set<String> channelConfigurationIds = channelConfigurationIds(mergedChannels);
        Set<String> radioReferenceGuids = radioReferenceGuids(mergedChannels);
        int renamedChannels = 0;
        int importedChannelCount = 0;

        for(Channel channel: nonNullChannels(importedState.getChannels()))
        {
            String aliasListName = channel.getAliasListName();

            if(aliasListName != null)
            {
                AliasListDefinition importedDefinition =
                    importedDefinitionsByOriginalName.get(normalize(aliasListName));

                if(importedDefinition != null)
                {
                    channel.setAliasListName(importedDefinition.getName());
                }
            }

            NameReservation reservation = reserveChannelName(channel, channelKeys);
            channel.setName(reservation.name());
            regenerateUniqueConfigurationId(channel, channelConfigurationIds);

            if(channel.hasRadresGuid())
            {
                String guid = normalize(channel.getRadresGuid());

                if(!radioReferenceGuids.add(guid))
                {
                    channel.setRadresGuid(null);
                }
            }

            mergedChannels.add(channel);
            importedChannelCount++;

            if(reservation.renamed())
            {
                renamedChannels++;
            }
        }

        merged.setAliasListDefinitions(mergedDefinitions);
        merged.setAliases(mergedAliases);
        merged.setChannels(mergedChannels);
        merged.setBroadcastConfigurations(mergedStreams);

        Summary summary = new Summary(
            nonNullDefinitions(importedState.getAliasListDefinitions()).size(),
            importedAliasCount, importedChannelCount, importedStreamCount,
            renamedAliasLists, renamedChannels, renamedStreams);
        return new MergeResult(merged, summary);
    }

    private static void updateBroadcastRoutes(Alias alias, Map<String,String> importedStreamNames)
    {
        for(BroadcastChannel route: alias.broadcastChannels())
        {
            String importedName = importedStreamNames.get(normalize(route.getChannelName()));

            if(importedName != null)
            {
                route.setChannelName(importedName);
            }
        }
    }

    private static void regenerateUniqueConfigurationId(Channel channel, Set<String> used)
    {
        do
        {
            channel.regenerateConfigurationId();
        }
        while(!used.add(channel.getConfigurationId()));
    }

    private static void regenerateUniqueConfigurationId(BroadcastConfiguration stream, Set<String> used)
    {
        do
        {
            stream.regenerateConfigurationId();
        }
        while(!used.add(stream.getConfigurationId()));
    }

    private static Set<String> configurationIds(List<BroadcastConfiguration> streams)
    {
        Set<String> identifiers = new HashSet<>();

        for(BroadcastConfiguration stream: nonNullStreams(streams))
        {
            identifiers.add(stream.getConfigurationId());
        }

        return identifiers;
    }

    private static Set<String> channelConfigurationIds(List<Channel> channels)
    {
        Set<String> identifiers = new HashSet<>();

        for(Channel channel: nonNullChannels(channels))
        {
            identifiers.add(channel.getConfigurationId());
        }

        return identifiers;
    }

    private static Set<String> radioReferenceGuids(List<Channel> channels)
    {
        Set<String> identifiers = new HashSet<>();

        for(Channel channel: nonNullChannels(channels))
        {
            if(channel.hasRadresGuid())
            {
                identifiers.add(normalize(channel.getRadresGuid()));
            }
        }

        return identifiers;
    }

    private static Set<String> normalizedDefinitionNames(List<AliasListDefinition> definitions)
    {
        Set<String> names = new HashSet<>();

        for(AliasListDefinition definition: nonNullDefinitions(definitions))
        {
            names.add(normalize(definition.getName()));
        }

        return names;
    }

    private static Set<String> normalizedStreamNames(List<BroadcastConfiguration> streams)
    {
        Set<String> names = new HashSet<>();

        for(BroadcastConfiguration stream: nonNullStreams(streams))
        {
            names.add(normalize(stream.getName()));
        }

        return names;
    }

    private static Set<String> channelKeys(List<Channel> channels)
    {
        Set<String> keys = new HashSet<>();

        for(Channel channel: nonNullChannels(channels))
        {
            keys.add(channelKey(channel, channel.getName()));
        }

        return keys;
    }

    private static NameReservation reserveName(String requested, Set<String> used)
    {
        String displayName = displayName(requested);

        if(used.add(normalize(displayName)))
        {
            return new NameReservation(displayName, false);
        }

        String candidate = displayName + " (Imported)";
        int suffix = 2;

        while(!used.add(normalize(candidate)))
        {
            candidate = displayName + " (Imported " + suffix++ + ")";
        }

        return new NameReservation(candidate, true);
    }

    private static NameReservation reserveChannelName(Channel channel, Set<String> used)
    {
        String displayName = displayName(channel.getName());

        if(used.add(channelKey(channel, displayName)))
        {
            return new NameReservation(displayName, false);
        }

        String candidate = displayName + " (Imported)";
        int suffix = 2;

        while(!used.add(channelKey(channel, candidate)))
        {
            candidate = displayName + " (Imported " + suffix++ + ")";
        }

        return new NameReservation(candidate, true);
    }

    private static String channelKey(Channel channel, String name)
    {
        return normalize(channel.getSystem()) + '\u0000' + normalize(channel.getSite()) + '\u0000' + normalize(name);
    }

    private static String displayName(String requested)
    {
        return requested == null || requested.isBlank() ? "Imported" : requested.trim();
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static ConfigurationState state(ConfigurationState state)
    {
        return state != null ? state : new ConfigurationState();
    }

    private static List<AliasListDefinition> nonNullDefinitions(List<AliasListDefinition> definitions)
    {
        return definitions != null ? definitions.stream().filter(definition -> definition != null).toList() :
            List.of();
    }

    private static List<Alias> nonNullAliases(List<Alias> aliases)
    {
        return aliases != null ? aliases.stream().filter(alias -> alias != null).toList() : List.of();
    }

    private static List<Channel> nonNullChannels(List<Channel> channels)
    {
        return channels != null ? channels.stream().filter(channel -> channel != null).toList() : List.of();
    }

    private static List<BroadcastConfiguration> nonNullStreams(List<BroadcastConfiguration> streams)
    {
        return streams != null ? streams.stream().filter(stream -> stream != null).toList() : List.of();
    }

    private record NameReservation(String name, boolean renamed)
    {
    }

    public record Preview(int aliasListCount, int aliasCount, int channelCount, int streamCount,
                          int aliasListConflicts, int channelConflicts, int streamConflicts)
    {
        public int totalConflicts()
        {
            return aliasListConflicts + channelConflicts + streamConflicts;
        }
    }

    public record Summary(int aliasListCount, int aliasCount, int channelCount, int streamCount,
                          int renamedAliasLists, int renamedChannels, int renamedStreams)
    {
        public int totalImported()
        {
            return aliasListCount + aliasCount + channelCount + streamCount;
        }

        public int totalRenamed()
        {
            return renamedAliasLists + renamedChannels + renamedStreams;
        }
    }

    public record MergeResult(ConfigurationState configurationState, Summary summary)
    {
    }
}
