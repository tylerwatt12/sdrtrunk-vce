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

package io.github.dsheirer.configuration;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared cross-table validation for complete current configuration snapshots. */
public final class ConfigurationSnapshotValidator
{
    private ConfigurationSnapshotValidator()
    {
    }

    /** Validates a proposed write while allowing new generated identities to become durable. */
    public static void validateForWrite(ConfigurationSnapshot snapshot)
    {
        validate(snapshot, false);
    }

    /** Normal startup is validation-only and refuses identities that still require first persistence. */
    public static void validateForStartup(ConfigurationSnapshot snapshot)
    {
        validate(snapshot, true);
    }

    private static void validate(ConfigurationSnapshot snapshot, boolean requirePersistedIdentities)
    {
        if(snapshot == null)
        {
            throw new IllegalArgumentException("Configuration snapshot cannot be null");
        }

        validateAliasListAssignments(snapshot.aliasListDefinitions(), snapshot.channels());
        validateConfigurationIdentities(snapshot.channels(), snapshot.broadcastConfigurations(),
            requirePersistedIdentities);
    }

    /** Validates the existing delayed channel and stream write path without loading unrelated Alias rows. */
    public static void validateChannelAndBroadcastWrite(List<AliasListDefinition> definitions,
                                                        List<Channel> channels,
                                                        List<BroadcastConfiguration> broadcastConfigurations)
    {
        validateAliasListAssignments(definitions, channels);
        validateConfigurationIdentities(channels, broadcastConfigurations, false);
    }

    private static void validateAliasListAssignments(List<AliasListDefinition> definitions,
                                                     List<Channel> channels)
    {
        Map<String,AliasListDefinition> definitionsByName = new HashMap<>();
        for(AliasListDefinition definition: definitions)
        {
            if(definition == null || definition.getName() == null)
            {
                throw new IllegalArgumentException("Alias-list definitions and names cannot be null");
            }

            String key = definition.getName().trim().toLowerCase(Locale.US);
            if(definitionsByName.put(key, definition) != null)
            {
                throw new IllegalArgumentException("Duplicate Alias List name [" + definition.getName() + "]");
            }
        }

        for(Channel channel: channels)
        {
            String aliasListName = channel.getAliasListName();
            if(aliasListName == null || aliasListName.isBlank())
            {
                continue;
            }

            AliasListDefinition definition =
                definitionsByName.get(aliasListName.trim().toLowerCase(Locale.US));
            if(definition == null || !aliasListName.equals(definition.getName()) ||
                channel.getDecodeConfiguration() == null ||
                !AliasMatchRegistry.isChannelCompatible(definition,
                    channel.getDecodeConfiguration().getDecoderType()))
            {
                throw new IllegalArgumentException("Channel [" + channel.getName() +
                    "] references incompatible Alias List [" + aliasListName + "]");
            }
        }
    }

    private static void validateConfigurationIdentities(List<Channel> channels,
                                                        List<BroadcastConfiguration> broadcastConfigurations,
                                                        boolean requirePersistedIdentities)
    {
        Set<String> channelIdentities = new HashSet<>();
        for(Channel channel: channels)
        {
            String identity = channel.getConfigurationId();
            if(!channelIdentities.add(identity) || requirePersistedIdentities &&
                channel.isConfigurationIdPersistenceRequired())
            {
                throw new IllegalArgumentException("Saved channel configuration identities require the " +
                    "Application Migrator");
            }
        }

        Set<String> providerIdentities = new HashSet<>();
        for(BroadcastConfiguration configuration: broadcastConfigurations)
        {
            String identity = configuration.getConfigurationId();
            if(!providerIdentities.add(identity) || requirePersistedIdentities &&
                configuration.isConfigurationIdPersistenceRequired())
            {
                throw new IllegalArgumentException("Saved broadcast configuration identities require the " +
                    "Application Migrator");
            }
        }
    }
}
