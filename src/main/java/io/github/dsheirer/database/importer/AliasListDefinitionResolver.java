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
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts legacy name-only alias lists into protocol-owned list definitions.
 *
 * <p>Only complete, operational rows cross this import boundary. Everything else is omitted instead of creating
 * compatibility or review state in the current schema.</p>
 */
public final class AliasListDefinitionResolver
{
    private static final String LEGACY_NO_ALIAS_LIST = "(No Alias List)";

    private AliasListDefinitionResolver()
    {
    }

    /**
     * Normalizes legacy list protocol families in-place before the state is written to the current alias schema.
     */
    public static void normalizeLegacyState(ConfigurationState state)
    {
        if(state == null)
        {
            return;
        }

        List<Alias> sourceAliases = state.getAliases() != null ? state.getAliases() : List.of();
        List<Channel> channels = state.getChannels() != null ? state.getChannels() : List.of();
        LinkedHashMap<String,AliasListDefinition> definitionsByLegacyName = new LinkedHashMap<>();
        for(Channel channel: channels)
        {
            if(channel == null || isNoList(channel.getAliasListName()))
            {
                continue;
            }

            AliasListDefinition proposed = definition(channel);

            if(proposed == null)
            {
                channel.setAliasListName(null);
                continue;
            }

            String legacyName = normalize(channel.getAliasListName());
            AliasListDefinition definition = definitionsByLegacyName.get(legacyName);

            if(definition == null)
            {
                definition = proposed;
                definitionsByLegacyName.put(legacyName, definition);
                channel.setAliasListName(definition.getName());
            }
            else if(sameFamily(definition, proposed))
            {
                channel.setAliasListName(definition.getName());
            }
            else
            {
                //A current list has one protocol family. Keep the first family and leave ambiguous legacy data in the
                //source backup instead of manufacturing duplicate lists and aliases.
                channel.setAliasListName(null);
            }
        }

        inferUnassignedListFamilies(sourceAliases, definitionsByLegacyName);

        List<AliasListDefinition> definitions = new ArrayList<>(definitionsByLegacyName.values());
        List<Alias> importedAliases = new ArrayList<>();
        for(Alias alias: sourceAliases)
        {
            if(alias == null)
            {
                continue;
            }

            AliasID matcher = alias.getMatchIdentifier();
            AliasListDefinition definition = definitionsByLegacyName.get(normalize(alias.getAliasListName()));

            if(definition == null || !AliasMatchRegistry.isOperational(definition, matcher))
            {
                continue;
            }

            alias.setAliasListDefinition(definition);
            importedAliases.add(alias);
        }

        state.setAliases(importedAliases);
        state.setAliasListDefinitions(definitions);
    }

    /**
     * Infers a family for an unassigned legacy list only when all usable matchers agree on exactly one family.
     * Protocol-specific matchers can narrow generic matchers such as tones and status values; mixed or still-ambiguous
     * lists remain omitted.
     */
    private static void inferUnassignedListFamilies(List<Alias> aliases,
                                                    Map<String,AliasListDefinition> definitionsByLegacyName)
    {
        Map<String,EnumSet<AliasListFamily>> candidatesByLegacyName = new LinkedHashMap<>();
        Map<String,String> displayNames = new LinkedHashMap<>();

        for(Alias alias: aliases)
        {
            String listName = alias != null ? alias.getAliasListName() : null;

            if(isNoList(listName))
            {
                continue;
            }

            String legacyName = normalize(listName);

            if(definitionsByLegacyName.containsKey(legacyName))
            {
                continue;
            }

            EnumSet<AliasListFamily> matcherFamilies = supportedFamilies(alias.getMatchIdentifier());

            if(matcherFamilies.isEmpty())
            {
                continue;
            }

            displayNames.putIfAbsent(legacyName, displayName(listName));
            EnumSet<AliasListFamily> candidates = candidatesByLegacyName.computeIfAbsent(legacyName,
                _ -> EnumSet.allOf(AliasListFamily.class));
            candidates.retainAll(matcherFamilies);
        }

        for(Map.Entry<String,EnumSet<AliasListFamily>> entry: candidatesByLegacyName.entrySet())
        {
            if(entry.getValue().size() == 1)
            {
                definitionsByLegacyName.put(entry.getKey(),
                    new AliasListDefinition(displayNames.get(entry.getKey()), entry.getValue().iterator().next()));
            }
        }
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

    private static AliasListDefinition definition(Channel channel)
    {
        DecoderType primary = channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        AliasListFamily family = AliasMatchRegistry.familyFor(primary);

        if(family == null)
        {
            return null;
        }

        return new AliasListDefinition(displayName(channel.getAliasListName()), family);
    }

    private static boolean sameFamily(AliasListDefinition first, AliasListDefinition second)
    {
        return first.getFamily() == second.getFamily();
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

}
