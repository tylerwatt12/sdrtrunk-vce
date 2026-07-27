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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Converts legacy name-only alias lists into system-owned list definitions.
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
     * Normalizes legacy list ownership in-place before the state is written to the current alias schema.
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
            else if(sameOwner(definition, proposed))
            {
                channel.setAliasListName(definition.getName());
            }
            else
            {
                //A current list has one owner. Keep the first owner and leave ambiguous legacy data in the source
                //backup instead of manufacturing duplicate lists and aliases.
                channel.setAliasListName(null);
            }
        }

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

    private static AliasListDefinition definition(Channel channel)
    {
        DecoderType primary = channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        AliasListFamily family = AliasMatchRegistry.familyFor(primary);
        String systemName = trimToNull(channel.getSystem());

        if(systemName == null || family == null)
        {
            return null;
        }

        return new AliasListDefinition(displayName(channel.getAliasListName()), systemName, family);
    }

    private static boolean sameOwner(AliasListDefinition first, AliasListDefinition second)
    {
        return first.getFamily() == second.getFamily() &&
            first.getSystemName().equalsIgnoreCase(second.getSystemName());
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
