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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
        validateBroadcastAssignments(snapshot.aliases(), snapshot.aliasListDefinitions(),
            snapshot.broadcastConfigurations());
    }

    /** Validates the existing delayed channel and stream write path without loading unrelated Alias rows. */
    public static void validateChannelAndBroadcastWrite(List<Alias> aliases,
                                                        List<AliasListDefinition> definitions,
                                                        List<Channel> channels,
                                                        List<BroadcastConfiguration> broadcastConfigurations)
    {
        validateAliasListAssignments(definitions, channels);
        validateConfigurationIdentities(channels, broadcastConfigurations, false);
        validateBroadcastAssignments(aliases, definitions, broadcastConfigurations);
    }

    private static void validateBroadcastAssignments(List<Alias> aliases,
                                                     List<AliasListDefinition> definitions,
                                                     List<BroadcastConfiguration> broadcastConfigurations)
    {
        Set<String> configured = new HashSet<>();
        for(BroadcastConfiguration configuration: broadcastConfigurations)
        {
            configured.add(configuration.getConfigurationId());
        }

        for(Alias alias: aliases)
        {
            requireConfiguredDestinations(alias.getBroadcastChannels(), configured,
                "Alias [" + alias.getName() + "]");
        }
        for(AliasListDefinition definition: definitions)
        {
            requireConfiguredDestinations(definition.getUnmatchedTalkgroupPolicy().getStreamDestinations(),
                configured, "Alias List [" + definition.getName() + "]");
        }
    }

    private static void requireConfiguredDestinations(Iterable<BroadcastChannel> destinations,
                                                       Set<String> configured, String owner)
    {
        for(BroadcastChannel destination: destinations)
        {
            if(destination == null || !destination.isValid() ||
                !configured.contains(destination.getConfigurationId()))
            {
                throw new IllegalArgumentException(owner +
                    " references a broadcast destination that does not exist");
            }
        }
    }

    private static void validateAliasListAssignments(List<AliasListDefinition> definitions,
                                                     List<Channel> channels)
    {
        Map<Long,AliasListDefinition> definitionsById = new HashMap<>();
        for(AliasListDefinition definition: definitions)
        {
            if(definition == null || definition.getName() == null ||
                definition.getId() <= AliasListDefinition.UNASSIGNED_ID)
            {
                throw new IllegalArgumentException("Alias-list definitions require durable IDs and names");
            }

            if(definitionsById.put(definition.getId(), definition) != null)
            {
                throw new IllegalArgumentException("Duplicate Alias List ID [" + definition.getId() + "]");
            }
        }

        for(Channel channel: channels)
        {
            long aliasListId = channel.getAliasListId();
            if(aliasListId <= AliasListDefinition.UNASSIGNED_ID)
            {
                if(channel.getAliasListName() != null && !channel.getAliasListName().isBlank())
                {
                    throw new IllegalArgumentException("Channel [" + channel.getName() +
                        "] has an Alias List name without a durable Alias List ID");
                }
                continue;
            }

            AliasListDefinition definition = definitionsById.get(aliasListId);
            if(definition == null || !definition.getName().equals(channel.getAliasListName()) ||
                channel.getDecodeConfiguration() == null ||
                !AliasMatchRegistry.isChannelCompatible(definition,
                    channel.getDecodeConfiguration().getDecoderType()))
            {
                throw new IllegalArgumentException("Channel [" + channel.getName() +
                    "] references incompatible Alias List ID [" + aliasListId + "]");
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
