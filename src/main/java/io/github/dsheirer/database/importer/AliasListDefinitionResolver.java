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
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts legacy name-only alias lists into protocol-owned list definitions.
 *
 * <p>Only complete, operational rows cross this import boundary. Everything else is omitted instead of creating
 * compatibility or review state in the current schema.</p>
 */
final class AliasListDefinitionResolver
{
    private static final String LEGACY_NO_ALIAS_LIST = "(No Alias List)";
    private static final String IMPORTED_UNASSIGNED = "Imported Unassigned";

    private AliasListDefinitionResolver()
    {
    }

    /**
     * Normalizes legacy list protocol families in-place before the state is written to the current alias schema.
     */
    static void normalizeLegacyState(LegacyConfigurationState state)
    {
        if(state == null)
        {
            return;
        }

        List<Alias> sourceAliases = state.getAliases() != null ? state.getAliases() : List.of();
        List<Channel> channels = state.getChannels() != null ? state.getChannels() : List.of();
        LinkedHashMap<String,LegacyListGroup> groups = new LinkedHashMap<>();
        List<ChannelClaim> channelClaims = new ArrayList<>();

        for(Channel channel: channels)
        {
            if(channel == null)
            {
                continue;
            }

            AliasListFamily family = family(channel);

            if(family == null)
            {
                channel.setAliasListName(null);
                continue;
            }

            if(!isNoList(channel.getAliasListName()))
            {
                LegacyListGroup group = group(groups, channel.getAliasListName());
                group.mClaimedFamilies.add(family);
                channelClaims.add(new ChannelClaim(channel, group, family));
            }
        }

        for(Alias alias: sourceAliases)
        {
            if(alias == null)
            {
                continue;
            }

            LegacyListGroup group = group(groups, alias.getAliasListName());
            group.addMatcherFamilies(supportedFamilies(alias.getMatchIdentifier()));
        }

        for(LegacyListGroup group: groups.values())
        {
            group.resolveFamilies();
        }

        Set<String> usedNames = reserveSingleFamilyNames(groups.values());
        List<AliasListDefinition> definitions = new ArrayList<>();

        for(LegacyListGroup group: groups.values())
        {
            boolean split = group.mNoList || group.mFamilies.size() > 1;
            String baseName = group.mNoList ? IMPORTED_UNASSIGNED : group.mName;

            for(AliasListFamily family: group.mFamilies)
            {
                String name = split ? uniqueFamilyName(baseName, family, usedNames) : baseName;
                AliasListDefinition definition = new AliasListDefinition(name, family);
                group.mDefinitions.put(family, definition);
                definitions.add(definition);
                state.setLegacyAliasListListenEnabled(definition, true);
            }
        }

        for(ChannelClaim claim: channelClaims)
        {
            AliasListDefinition definition = claim.mGroup.mDefinitions.get(claim.mFamily);

            if(definition == null)
            {
                claim.mChannel.setAliasListName(null);
            }
            else
            {
                claim.mChannel.setAliasListName(definition.getName());
            }
        }

        List<Alias> importedAliases = new ArrayList<>();
        for(Alias alias: sourceAliases)
        {
            if(alias == null)
            {
                continue;
            }

            AliasID matcher = alias.getMatchIdentifier();
            LegacyListGroup group = groups.get(groupKey(alias.getAliasListName()));

            if(group == null)
            {
                continue;
            }

            List<AliasListDefinition> targets = group.mDefinitions.values().stream()
                .filter(definition -> AliasMatchRegistry.isOperational(definition, matcher))
                .toList();

            for(int index = 0; index < targets.size(); index++)
            {
                Alias imported = index == 0 ? alias : AliasFactory.copyOf(alias);
                imported.setAliasListDefinition(targets.get(index));
                importedAliases.add(imported);
                state.getLegacyAliasListenEnabled(alias).ifPresent(enabled ->
                    state.setLegacyAliasListenEnabled(imported, enabled));
            }
        }

        convertUnambiguousCatchAllAliases(state, importedAliases, definitions);

        state.setAliases(importedAliases);
        state.setAliasListDefinitions(definitions);
    }

    /**
     * Converts the old full-range alias convention into the list-owned unmatched talkgroup policy.  A normal range
     * remains a normal alias.  Ambiguous legacy configurations are also left untouched so import never guesses which
     * behavior the administrator intended.
     */
    private static void convertUnambiguousCatchAllAliases(LegacyConfigurationState state, List<Alias> aliases,
                                                           List<AliasListDefinition> definitions)
    {
        for(AliasListDefinition definition: definitions)
        {
            List<Alias> candidates = aliases.stream()
                .filter(alias -> definition.getName().equals(alias.getAliasListName()))
                .filter(alias -> AliasMatchRegistry.isUnmatchedTalkgroupCatchAll(definition,
                    alias.getMatchIdentifier()))
                .toList();

            if(candidates.size() != 1)
            {
                continue;
            }

            Alias catchAll = candidates.getFirst();

            //A fixed Stream As value would collapse every received talkgroup into one false identity.  List-owned
            //policy also has no appearance fields, so preserve styled legacy rows rather than silently discard
            //administrator-owned description, grouping, color, or icon metadata.
            if(catchAll.getStreamTalkgroupAlias() != null || hasCustomAppearance(catchAll))
            {
                continue;
            }

            definition.setUnmatchedTalkgroupPolicy(new UnmatchedTalkgroupPolicy(catchAll.isRecordable(),
                catchAll.getBroadcastChannels().stream()
                    .map(BroadcastChannel::getChannelName).toList()));
            state.getLegacyAliasListenEnabled(catchAll).ifPresent(enabled ->
                state.setLegacyAliasListListenEnabled(definition, enabled));
            aliases.remove(catchAll);
        }
    }

    private static boolean hasCustomAppearance(Alias alias)
    {
        return (alias.getDescription() != null && !alias.getDescription().isBlank()) ||
            (alias.getGroup() != null && !alias.getGroup().isBlank()) || alias.getColor() != 0 ||
            (alias.getIconName() != null && !alias.getIconName().isBlank());
    }

    private static Set<String> reserveSingleFamilyNames(Iterable<LegacyListGroup> groups)
    {
        Set<String> usedNames = new LinkedHashSet<>();

        for(LegacyListGroup group: groups)
        {
            if(!group.mNoList && group.mFamilies.size() == 1)
            {
                usedNames.add(normalize(group.mName));
            }
        }

        return usedNames;
    }

    private static String uniqueFamilyName(String baseName, AliasListFamily family, Set<String> usedNames)
    {
        String familyName = baseName + " [" + family.name() + ']';
        String candidate = familyName;
        int suffix = 2;

        while(!usedNames.add(normalize(candidate)))
        {
            candidate = familyName + ' ' + suffix++;
        }

        return candidate;
    }

    private static EnumSet<AliasListFamily> supportedFamilies(AliasID matcher)
    {
        EnumSet<AliasListFamily> families = EnumSet.noneOf(AliasListFamily.class);

        for(AliasListFamily family: AliasListFamily.values())
        {
            if(AliasMatchRegistry.isOperational(new AliasListDefinition("Legacy", family), matcher))
            {
                families.add(family);
            }
        }

        return families;
    }

    private static AliasListFamily family(Channel channel)
    {
        DecoderType primary = channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        return AliasMatchRegistry.familyFor(primary);
    }

    private static LegacyListGroup group(Map<String,LegacyListGroup> groups, String legacyName)
    {
        String key = groupKey(legacyName);
        return groups.computeIfAbsent(key,
            ignored -> new LegacyListGroup(displayName(legacyName), isNoList(legacyName)));
    }

    private static String groupKey(String value)
    {
        return normalize(isNoList(value) ? LEGACY_NO_ALIAS_LIST : value);
    }

    private static boolean isNoList(String value)
    {
        return value == null || value.isBlank() ||
            LEGACY_NO_ALIAS_LIST.equalsIgnoreCase(value.trim());
    }

    private static String displayName(String value)
    {
        String trimmed = trimToNull(value);
        return trimmed != null ? trimmed : LEGACY_NO_ALIAS_LIST;
    }

    private static String trimToNull(String value)
    {
        if(value == null)
        {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalize(String value)
    {
        return displayName(value).toLowerCase(Locale.ROOT);
    }

    private static final class LegacyListGroup
    {
        private final String mName;
        private final boolean mNoList;
        private final EnumSet<AliasListFamily> mClaimedFamilies = EnumSet.noneOf(AliasListFamily.class);
        private final EnumSet<AliasListFamily> mInferredFamilies = EnumSet.noneOf(AliasListFamily.class);
        private final List<EnumSet<AliasListFamily>> mAmbiguousMatcherFamilies = new ArrayList<>();
        private final EnumSet<AliasListFamily> mFamilies = EnumSet.noneOf(AliasListFamily.class);
        private final Map<AliasListFamily,AliasListDefinition> mDefinitions =
            new EnumMap<>(AliasListFamily.class);

        private LegacyListGroup(String name, boolean noList)
        {
            mName = name;
            mNoList = noList;
        }

        private void addMatcherFamilies(EnumSet<AliasListFamily> families)
        {
            if(families.size() == 1)
            {
                mInferredFamilies.addAll(families);
            }
            else if(!families.isEmpty())
            {
                mAmbiguousMatcherFamilies.add(EnumSet.copyOf(families));
            }
        }

        private void resolveFamilies()
        {
            mFamilies.addAll(mClaimedFamilies);
            mFamilies.addAll(mInferredFamilies);

            if(mFamilies.isEmpty() && !mAmbiguousMatcherFamilies.isEmpty())
            {
                EnumSet<AliasListFamily> common = EnumSet.copyOf(mAmbiguousMatcherFamilies.getFirst());

                for(int index = 1; index < mAmbiguousMatcherFamilies.size(); index++)
                {
                    common.retainAll(mAmbiguousMatcherFamilies.get(index));
                }

                mFamilies.addAll(common);
            }

            for(EnumSet<AliasListFamily> matcherFamilies: mAmbiguousMatcherFamilies)
            {
                if(mFamilies.stream().noneMatch(matcherFamilies::contains))
                {
                    //No claimed or protocol-specific family can hold this matcher. Add the first compatible family
                    //in enum order so that the matcher is preserved without manufacturing unnecessary extra lists.
                    mFamilies.add(matcherFamilies.iterator().next());
                }
            }
        }
    }

    private static final class ChannelClaim
    {
        private final Channel mChannel;
        private final LegacyListGroup mGroup;
        private final AliasListFamily mFamily;

        private ChannelClaim(Channel channel, LegacyListGroup group, AliasListFamily family)
        {
            mChannel = channel;
            mGroup = group;
            mFamily = family;
        }
    }

}
