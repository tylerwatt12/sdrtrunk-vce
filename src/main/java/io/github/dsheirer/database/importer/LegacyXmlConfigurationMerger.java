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
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Merges a legacy XML playlist into configuration loaded from SQLite.
 */
public final class LegacyXmlConfigurationMerger
{
    private LegacyXmlConfigurationMerger()
    {
    }

    public enum ConflictPolicy
    {
        SKIP("Skip imported conflicts"),
        RENAME("Keep both and rename imported items"),
        REPLACE("Replace existing conflicts");

        private final String mLabel;

        ConflictPolicy(String label)
        {
            mLabel = label;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }

    public static Preview preview(ConfigurationState existing, ConfigurationState imported)
    {
        Set<String> channelKeys = new HashSet<>();
        for(Channel channel: existing.getChannels())
        {
            channelKeys.add(channelKey(channel));
        }

        Set<String> streamNames = names(existing.getBroadcastConfigurations().stream()
            .map(BroadcastConfiguration::getName).toList());
        Set<String> aliasLists = names(existing.getAliases().stream().map(Alias::getAliasListName).toList());

        int channelConflicts = 0;
        for(Channel channel: imported.getChannels())
        {
            if(!channelKeys.add(channelKey(channel)))
            {
                channelConflicts++;
            }
        }

        int streamConflicts = countAndAddConflicts(streamNames,
            imported.getBroadcastConfigurations().stream().map(BroadcastConfiguration::getName).toList());
        int aliasListConflicts = (int)imported.getAliases().stream().map(Alias::getAliasListName)
            .map(LegacyXmlConfigurationMerger::normalize).distinct().filter(aliasLists::contains).count();

        return new Preview(imported.getAliases().size(), imported.getChannels().size(),
            imported.getBroadcastConfigurations().size(), aliasListConflicts, channelConflicts, streamConflicts);
    }

    public static MergeResult merge(ConfigurationState existing, ConfigurationState imported, ConflictPolicy policy)
    {
        ConfigurationState merged = new ConfigurationState();
        merged.setVersion(ConfigurationManager.CONFIGURATION_CURRENT_VERSION);
        merged.setAliases(new ArrayList<>(existing.getAliases()));
        merged.setChannels(new ArrayList<>(existing.getChannels()));
        merged.setBroadcastConfigurations(new ArrayList<>(existing.getBroadcastConfigurations()));

        MutableSummary summary = new MutableSummary();
        Map<String,String> aliasListRenames = mergeAliases(merged.getAliases(), imported.getAliases(), policy, summary);
        applyAliasListRenames(imported.getChannels(), aliasListRenames);

        Map<String,String> streamRenames = mergeStreams(merged.getBroadcastConfigurations(),
            imported.getBroadcastConfigurations(), policy, summary);
        applyStreamRenames(imported.getAliases(), streamRenames);

        mergeChannels(merged.getChannels(), imported.getChannels(), policy, summary);
        return new MergeResult(merged, summary.toSummary());
    }

    private static Map<String,String> mergeAliases(List<Alias> existing, List<Alias> imported,
                                                    ConflictPolicy policy, MutableSummary summary)
    {
        Map<String,List<Alias>> importedLists = new LinkedHashMap<>();
        for(Alias alias: imported)
        {
            importedLists.computeIfAbsent(normalize(alias.getAliasListName()), ignored -> new ArrayList<>()).add(alias);
        }

        Set<String> used = names(existing.stream().map(Alias::getAliasListName).toList());
        Map<String,String> renames = new HashMap<>();

        for(Map.Entry<String,List<Alias>> entry: importedLists.entrySet())
        {
            List<Alias> aliases = entry.getValue();
            boolean conflict = used.contains(entry.getKey());

            if(conflict && policy == ConflictPolicy.SKIP)
            {
                summary.skipped += aliases.size();
                continue;
            }

            if(conflict && policy == ConflictPolicy.REPLACE)
            {
                existing.removeIf(alias -> normalize(alias.getAliasListName()).equals(entry.getKey()));
                summary.replaced += aliases.size();
            }
            else if(conflict)
            {
                String original = aliases.get(0).getAliasListName();
                String renamed = uniqueName(original, used);
                aliases.forEach(alias -> alias.setAliasListName(renamed));
                renames.put(entry.getKey(), renamed);
                summary.renamed += aliases.size();
            }
            else
            {
                summary.added += aliases.size();
            }

            existing.addAll(aliases);
            used.add(normalize(aliases.get(0).getAliasListName()));
        }

        return renames;
    }

    private static Map<String,String> mergeStreams(List<BroadcastConfiguration> existing,
                                                    List<BroadcastConfiguration> imported, ConflictPolicy policy,
                                                    MutableSummary summary)
    {
        Set<String> used = names(existing.stream().map(BroadcastConfiguration::getName).toList());
        Map<String,String> renames = new HashMap<>();

        for(BroadcastConfiguration stream: imported)
        {
            String key = normalize(stream.getName());
            boolean conflict = used.contains(key);

            if(conflict && policy == ConflictPolicy.SKIP)
            {
                summary.skipped++;
                continue;
            }

            if(conflict && policy == ConflictPolicy.REPLACE)
            {
                existing.removeIf(item -> normalize(item.getName()).equals(key));
                summary.replaced++;
            }
            else if(conflict)
            {
                String renamed = uniqueName(stream.getName(), used);
                renames.put(key, renamed);
                stream.setName(renamed);
                summary.renamed++;
            }
            else
            {
                summary.added++;
            }

            existing.add(stream);
            used.add(normalize(stream.getName()));
        }

        return renames;
    }

    private static void mergeChannels(List<Channel> existing, List<Channel> imported, ConflictPolicy policy,
                                      MutableSummary summary)
    {
        Set<String> used = new HashSet<>();
        Set<String> guids = new HashSet<>();
        for(Channel channel: existing)
        {
            used.add(channelKey(channel));
            if(channel.hasRadresGuid())
            {
                guids.add(channel.getRadresGuid());
            }
        }

        for(Channel channel: imported)
        {
            String key = channelKey(channel);
            boolean conflict = used.contains(key);

            if(conflict && policy == ConflictPolicy.SKIP)
            {
                summary.skipped++;
                continue;
            }

            if(conflict && policy == ConflictPolicy.REPLACE)
            {
                List<Channel> replacedChannels = existing.stream().filter(item -> channelKey(item).equals(key))
                    .toList();
                for(Channel replacedChannel: replacedChannels)
                {
                    if(replacedChannel.hasRadresGuid())
                    {
                        guids.remove(replacedChannel.getRadresGuid());
                    }
                }
                existing.removeAll(replacedChannels);
                summary.replaced++;
            }
            else if(conflict)
            {
                Set<String> namesAtSite = new HashSet<>();
                for(Channel item: existing)
                {
                    if(normalize(item.getSystem()).equals(normalize(channel.getSystem())) &&
                        normalize(item.getSite()).equals(normalize(channel.getSite())))
                    {
                        namesAtSite.add(normalize(item.getName()));
                    }
                }

                channel.setName(uniqueName(channel.getName(), namesAtSite));
                summary.renamed++;
            }
            else
            {
                summary.added++;
            }

            String guid = channel.getRadresGuid();
            if(guid != null && guids.contains(guid))
            {
                channel.setRadresGuid(null);
                guid = channel.getRadresGuid();
            }

            if(guid != null)
            {
                guids.add(guid);
            }

            existing.add(channel);
            used.add(channelKey(channel));
        }
    }

    private static void applyAliasListRenames(List<Channel> channels, Map<String,String> renames)
    {
        for(Channel channel: channels)
        {
            String renamed = renames.get(normalize(channel.getAliasListName()));
            if(renamed != null)
            {
                channel.setAliasListName(renamed);
            }
        }
    }

    private static void applyStreamRenames(List<Alias> aliases, Map<String,String> renames)
    {
        for(Alias alias: aliases)
        {
            for(AliasID identifier: alias.getAliasIdentifiers())
            {
                if(identifier instanceof BroadcastChannel broadcastChannel)
                {
                    String renamed = renames.get(normalize(broadcastChannel.getChannelName()));
                    if(renamed != null)
                    {
                        broadcastChannel.setChannelName(renamed);
                    }
                }
            }
        }
    }

    private static Set<String> names(List<String> values)
    {
        Set<String> names = new HashSet<>();
        values.forEach(value -> names.add(normalize(value)));
        return names;
    }

    private static int countAndAddConflicts(Set<String> used, List<String> imported)
    {
        int conflicts = 0;

        for(String value: imported)
        {
            if(!used.add(normalize(value)))
            {
                conflicts++;
            }
        }

        return conflicts;
    }

    private static String uniqueName(String requested, Set<String> used)
    {
        String base = requested == null || requested.isBlank() ? "Imported" : requested.trim();
        String candidate = base + " (Imported)";
        int suffix = 2;

        while(used.contains(normalize(candidate)))
        {
            candidate = base + " (Imported " + suffix++ + ")";
        }

        return candidate;
    }

    private static String channelKey(Channel channel)
    {
        return normalize(channel.getSystem()) + '\u0000' + normalize(channel.getSite()) + '\u0000' +
            normalize(channel.getName());
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record Preview(int aliasCount, int channelCount, int streamCount, int aliasListConflicts,
                          int channelConflicts, int streamConflicts)
    {
        public int totalConflicts()
        {
            return aliasListConflicts + channelConflicts + streamConflicts;
        }
    }

    public record Summary(int added, int renamed, int replaced, int skipped)
    {
    }

    public record MergeResult(ConfigurationState configurationState, Summary summary)
    {
    }

    private static class MutableSummary
    {
        private int added;
        private int renamed;
        private int replaced;
        private int skipped;

        private Summary toSummary()
        {
            return new Summary(added, renamed, replaced, skipped);
        }
    }
}
