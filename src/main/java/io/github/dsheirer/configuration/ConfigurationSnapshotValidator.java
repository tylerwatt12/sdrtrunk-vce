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
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.icon.IconDatabaseStore;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.source.tuner.configuration.TunerSettings;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared semantic validation for a complete Alpha SQLite configuration snapshot.
 *
 * <p>Schema and {@code json_valid()} checks cannot prove that Jackson can materialize each saved configuration or
 * that its durable identities and Alias List assignments are usable. The Application Migrator runs this validator
 * on its private staged database before promotion. Normal runtime currently performs the same checks while loading
 * {@link ConfigurationManager}; keeping this boundary independent avoids starting against a newly promoted database
 * that the runtime cannot load.</p>
 */
public final class ConfigurationSnapshotValidator
{
    private static final Map<String,String> JSON_COLUMNS = Map.of(
        "configuration_channel", "config_json",
        "configuration_channel_map", "config_json",
        "configuration_broadcast_stream", "config_json",
        "application_settings", "settings_json",
        "application_icons", "icons_json");

    private ConfigurationSnapshotValidator()
    {
    }

    /**
     * Loads every persisted Alias, channel, and broadcast configuration and applies the normal-startup semantic
     * checks. The staged database is private and quiescent while this method runs.
     */
    public static void validateDatabaseForStartup(Path database) throws IOException, SQLException
    {
        if(database == null)
        {
            throw new IllegalArgumentException("Configuration database path cannot be null");
        }

        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            validatePersistedJson(connection);
        }

        validateKnownRuntimePayloads(database);

        ConfigurationState state = new ConfigurationDatabaseStore(database).loadConfigurationState();
        AliasDatabaseStore aliasStore = new AliasDatabaseStore(database);
        List<AliasListDefinition> definitions = aliasStore.loadAliasListDefinitions();

        //Materialize every Alias too. AliasDatabaseStore validates persisted IDs, list ownership, matcher
        //compatibility, appearance data, routes, and scan-list-era priority constraints as it loads.
        aliasStore.loadAliases(definitions);
        state.setAliasListDefinitions(definitions);
        validateForStartup(state);
    }

    /**
     * Validates every persisted JSON value without assuming the current Alias/P25 schema. This is used only for an
     * exact legacy-format safety backup after the format catalog and migration-step source invariants have already
     * accepted that database.
     */
    public static void validatePersistedJson(Path database) throws IOException, SQLException
    {
        if(database == null)
        {
            throw new IllegalArgumentException("Configuration database path cannot be null");
        }

        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            validatePersistedJson(connection);
        }
    }

    /**
     * Checks every JSON-bearing format-2 column, including rows that the active runtime does not currently
     * materialize. A retired channel-map row or an unknown settings/icon key must not bypass staged validation and
     * leave malformed JSON in the promoted database.
     */
    static void validatePersistedJson(Connection connection) throws SQLException
    {
        for(Map.Entry<String,String> jsonColumn: JSON_COLUMNS.entrySet())
        {
            String table = jsonColumn.getKey();
            String column = jsonColumn.getValue();

            try(PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE json_valid(" + column + ") <> 1");
                ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next() && resultSet.getLong(1) > 0)
                {
                    throw new SQLException("Persisted JSON is invalid in " + table + "." + column);
                }
            }
        }
    }

    /** Materializes each well-known settings/icon payload with the same store and target type used at runtime. */
    static void validateKnownRuntimePayloads(Path database) throws IOException, SQLException
    {
        ApplicationSettingsStore settingsStore = new ApplicationSettingsStore(database);
        settingsStore.load(ApplicationSettingsStore.UI_SETTINGS, Settings.class);

        //The runtime intentionally tolerates and later removes individual tuner entries for retired or unavailable
        //implementations. Preserve that compatibility policy, while still requiring the payload root and known list
        //shapes to deserialize through TunerSettingsDeserializer.
        settingsStore.load(ApplicationSettingsStore.TUNER_SETTINGS, TunerSettings.class);

        //IconDatabaseStore materializes only the runtime-owned "default" key. Unknown extension keys remain
        //syntax-only, matching the generic keyed-settings policy.
        new IconDatabaseStore(database).loadIcons();
    }

    /** Normal startup refuses missing/generated or duplicate persisted configuration identities. */
    public static void validateForStartup(ConfigurationState state)
    {
        if(state == null)
        {
            throw new IllegalArgumentException("Configuration snapshot cannot be null");
        }

        List<AliasListDefinition> definitions =
            state.getAliasListDefinitions() != null ? state.getAliasListDefinitions() : List.of();
        List<Channel> channels = state.getChannels() != null ? state.getChannels() : List.of();
        List<BroadcastConfiguration> broadcasts = state.getBroadcastConfigurations() != null ?
            state.getBroadcastConfigurations() : List.of();
        validateAliasListAssignments(definitions, channels);
        validateConfigurationIdentities(channels, broadcasts);
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
            if(channel == null)
            {
                throw new IllegalArgumentException("Saved channel configurations cannot be null");
            }

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
                                                        List<BroadcastConfiguration> broadcasts)
    {
        Set<String> channelIdentities = new HashSet<>();
        for(Channel channel: channels)
        {
            String identity = channel.getConfigurationId();
            if(channel.isConfigurationIdPersistenceRequired() || !channelIdentities.add(identity))
            {
                throw new IllegalArgumentException("Saved channel configuration identities require the " +
                    "Application Migrator");
            }
        }

        Set<String> providerIdentities = new HashSet<>();
        for(BroadcastConfiguration configuration: broadcasts)
        {
            if(configuration == null)
            {
                throw new IllegalArgumentException("Saved broadcast configurations cannot be null");
            }

            String identity = configuration.getConfigurationId();
            if(configuration.isConfigurationIdPersistenceRequired() || !providerIdentities.add(identity))
            {
                throw new IllegalArgumentException("Saved broadcast configuration identities require the " +
                    "Application Migrator");
            }
        }
    }
}
