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

package io.github.dsheirer.database.configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * SQLite persistence for active channel and stream configuration. The legacy channel-map table remains untouched.
 */
public class ConfigurationDatabaseStore
{
    /**
     * Channel fields whose sole persisted authority is the relational row. Legacy JSON aliases are included so a
     * nonstandard writer cannot bypass the same one-fact/one-owner rule.
     */
    private static final List<String> CHANNEL_ROW_OWNED_JSON_FIELDS = List.of(
        "configurationId", "system", "site", "name", "aliasListId", "aliasListName", "radioResolveId",
        "radresGuid", "radres_guid", "autoStart", "enabled", "autoStartOrder", "order", "channelType");
    private final Path mDatabasePath;
    private final ObjectMapper mObjectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(SerializationFeature.INDENT_OUTPUT);

    public ConfigurationDatabaseStore(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    public Path getDatabasePath()
    {
        return mDatabasePath;
    }

    public ChannelAndBroadcastConfiguration load() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            return load(connection);
        }
    }

    /** Loads the channel and stream portion of a snapshot from one caller-owned database view. */
    public ChannelAndBroadcastConfiguration load(Connection connection) throws IOException, SQLException
    {
        if(connection == null)
        {
            throw new IllegalArgumentException("Connection cannot be null");
        }
        return new ChannelAndBroadcastConfiguration(loadChannels(connection),
            loadBroadcastConfigurations(connection));
    }

    /**
     * Saves channels and streams using the caller-owned repository transaction so every reference and its Alias List
     * definition becomes visible in one commit.
     *
     * @param connection open connection with auto-commit disabled
     */
    public void replace(Connection connection, ChannelAndBroadcastConfiguration configuration)
        throws IOException, SQLException
    {
        if(connection == null || connection.getAutoCommit())
        {
            throw new IllegalArgumentException("Configuration snapshot writes require a caller-owned transaction");
        }

        if(configuration == null)
        {
            throw new IllegalArgumentException("Channel and broadcast configuration cannot be null");
        }

        replaceChannels(connection, configuration.channels());
        replaceBroadcastConfigurations(connection, configuration.broadcastConfigurations());
    }

    /**
     * Clears channel references to deleted Alias Lists without rewriting unrelated channel or stream rows.
     */
    public void clearAliasListAssignments(Connection connection, Collection<Long> aliasListIds)
        throws SQLException
    {
        if(connection == null || connection.getAutoCommit())
        {
            throw new IllegalArgumentException("Alias-list assignment updates require a caller-owned transaction");
        }
        if(aliasListIds == null || aliasListIds.isEmpty())
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE configuration_channel
            SET alias_list_id = NULL
            WHERE alias_list_id = ?
            """))
        {
            for(Long aliasListId: aliasListIds)
            {
                if(aliasListId == null || aliasListId <= AliasListDefinition.UNASSIGNED_ID)
                {
                    throw new IllegalArgumentException("Deleted Alias-list IDs must be positive");
                }
                statement.setLong(1, aliasListId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<Channel> loadChannels(Connection connection) throws SQLException, IOException
    {
        List<Channel> channels = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT channel.configuration_id, channel.channel_kind, channel.system_name, channel.site_name,
                   channel.name, channel.alias_list_id, list.name AS alias_list_name, channel.radioresolve_id,
                   channel.auto_start, channel.auto_start_order, channel.decoder_type,
                   channel.primary_frequency_hz, channel.config_json
            FROM configuration_channel channel
            LEFT JOIN alias_list list ON list.id = channel.alias_list_id
            ORDER BY channel.sort_order, channel.id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                String json = resultSet.getString("config_json");
                requireAbsent(json, CHANNEL_ROW_OWNED_JSON_FIELDS, "configuration_channel.config_json");
                String configurationId = requireCanonicalConfigurationId(resultSet.getString("configuration_id"));
                Channel channel = mObjectMapper.readValue(json, Channel.class);
                channel.setConfigurationId(configurationId);

                if(!ChannelConfigurationPolicy.requireChannelKind(channel).name()
                    .equals(resultSet.getString("channel_kind")))
                {
                    throw new IOException("Channel kind scalar does not match config_json");
                }

                ConfigurationChannelProjection.from(channel).requireMatches(
                    ConfigurationChannelProjection.read(resultSet), "Channel " + configurationId);
                channel.setSystem(resultSet.getString("system_name"));
                channel.setSite(resultSet.getString("site_name"));
                channel.setName(resultSet.getString("name"));
                channel.setAliasListName(resultSet.getString("alias_list_name"));
                Long aliasListId = readNullableLong(resultSet, "alias_list_id");
                channel.setAliasListId(aliasListId != null ? aliasListId : AliasListDefinition.UNASSIGNED_ID);
                channel.setRadioResolveId(resultSet.getString("radioresolve_id"));
                channel.setAutoStart(ConfigurationChannelProjection.readBooleanFlag(resultSet, "auto_start"));
                channel.setAutoStartOrder(ConfigurationChannelProjection.readNullableInt(resultSet,
                    "auto_start_order"));
                channels.add(channel);
            }
        }

        return channels;
    }

    private List<BroadcastConfiguration> loadBroadcastConfigurations(Connection connection)
        throws SQLException, IOException
    {
        List<BroadcastConfiguration> configurations = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT configuration_id, config_json
            FROM configuration_broadcast_stream
            ORDER BY sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                String json = resultSet.getString("config_json");
                requireAbsent(json, "configurationId", "configuration_broadcast_stream.config_json");
                BroadcastConfiguration configuration =
                    mObjectMapper.readValue(json, BroadcastConfiguration.class);
                configuration.setConfigurationId(
                    requireCanonicalConfigurationId(resultSet.getString("configuration_id")));
                configurations.add(configuration);
            }
        }

        return configurations;
    }

    private void replaceChannels(Connection connection, List<Channel> channels) throws SQLException, IOException
    {
        Map<String,StoredChannelClassification> stored = storedChannelClassifications(connection);
        Set<String> retainedIds = new HashSet<>();

        for(Channel channel: channels)
        {
            String configurationId = requireCanonicalConfigurationId(channel.getConfigurationId());
            if(!retainedIds.add(configurationId))
            {
                throw new IOException("Duplicate channel configuration identity: " + configurationId);
            }
        }

        deleteMissing(connection, "configuration_channel", retainedIds);

        //A stable channel can be renamed or retuned without losing its history. Changing its channel kind or decoder
        //changes the meaning of that history, so replace only that row and let the receiver-channel FK discard the
        //now-incompatible derived data.
        try(PreparedStatement delete = connection.prepareStatement(
            "DELETE FROM configuration_channel WHERE configuration_id=?"))
        {
            for(Channel channel: channels)
            {
                String configurationId = requireCanonicalConfigurationId(channel.getConfigurationId());
                StoredChannelClassification previous = stored.get(configurationId);
                String channelKind = ChannelConfigurationPolicy.requireChannelKind(channel).name();
                String decoderType = ConfigurationChannelProjection.from(channel).decoderType();
                if(previous != null && (!previous.channelKind().equals(channelKind) ||
                    !Objects.equals(previous.decoderType(), decoderType)))
                {
                    delete.setString(1, configurationId);
                    delete.addBatch();
                }
            }
            delete.executeBatch();
        }

        //Release correlation-ID uniqueness before applying the complete accepted snapshot. This permits two existing
        //channels to swap RadioResolve IDs in one atomic save without deleting either stable channel row.
        try(PreparedStatement clear = connection.prepareStatement(
            "UPDATE configuration_channel SET radioresolve_id=NULL WHERE configuration_id=?"))
        {
            for(String configurationId: retainedIds)
            {
                clear.setString(1, configurationId);
                clear.addBatch();
            }
            clear.executeBatch();
        }

        upsertChannels(connection, channels);
    }

    private void upsertChannels(Connection connection, List<Channel> channels) throws SQLException, IOException
    {
        int sortOrder = 0;

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_channel (
                configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_id,
                radioresolve_id, auto_start, auto_start_order, decoder_type, primary_frequency_hz, config_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(configuration_id) DO UPDATE SET
                channel_kind=excluded.channel_kind,
                sort_order=excluded.sort_order,
                system_name=excluded.system_name,
                site_name=excluded.site_name,
                name=excluded.name,
                alias_list_id=excluded.alias_list_id,
                radioresolve_id=excluded.radioresolve_id,
                auto_start=excluded.auto_start,
                auto_start_order=excluded.auto_start_order,
                decoder_type=excluded.decoder_type,
                primary_frequency_hz=excluded.primary_frequency_hz,
                config_json=excluded.config_json
            """))
        {
            for(Channel channel: channels)
            {
                if(ChannelConfigurationPolicy.isRetired(channel))
                {
                    throw new IOException("Retired channel configuration cannot be stored in the active database");
                }

                ChannelConfigurationPolicy.ChannelKind channelKind =
                    ChannelConfigurationPolicy.requireChannelKind(channel);
                //RadioResolve correlation is independent from channel identity and omitted from config_json.
                String radioResolveId = channel.getRadioResolveId();
                ConfigurationChannelProjection projection = ConfigurationChannelProjection.from(channel);

                statement.setString(1, requireCanonicalConfigurationId(channel.getConfigurationId()));
                statement.setString(2, channelKind.name());
                statement.setInt(3, sortOrder++);
                statement.setString(4, channel.getSystem());
                statement.setString(5, channel.getSite());
                statement.setString(6, channel.getName());
                setLong(statement, 7, channel.getAliasListId() > AliasListDefinition.UNASSIGNED_ID ?
                    channel.getAliasListId() : null);
                statement.setString(8, radioResolveId);
                statement.setInt(9, channel.getAutoStart() ? 1 : 0);
                setInteger(statement, 10, channel.getAutoStartOrder());
                projection.bind(statement, 11);
                statement.setString(13, channelPayload(channel));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceBroadcastConfigurations(Connection connection, List<BroadcastConfiguration> configurations)
        throws SQLException, IOException
    {
        Set<String> retainedIds = new HashSet<>();
        for(BroadcastConfiguration configuration: configurations)
        {
            String configurationId = requireCanonicalConfigurationId(configuration.getConfigurationId());
            if(!retainedIds.add(configurationId))
            {
                throw new IOException("Duplicate broadcast configuration identity: " + configurationId);
            }
        }

        deleteMissing(connection, "configuration_broadcast_stream", retainedIds);
        int sortOrder = 0;

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_broadcast_stream (
                configuration_id, sort_order, config_json
            ) VALUES (?, ?, ?)
            ON CONFLICT(configuration_id) DO UPDATE SET
                sort_order=excluded.sort_order,
                config_json=excluded.config_json
            """))
        {
            for(BroadcastConfiguration configuration: configurations)
            {
                statement.setString(1, requireCanonicalConfigurationId(configuration.getConfigurationId()));
                statement.setInt(2, sortOrder++);
                statement.setString(3, broadcastPayload(configuration));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Map<String,StoredChannelClassification> storedChannelClassifications(Connection connection)
        throws SQLException
    {
        Map<String,StoredChannelClassification> stored = new HashMap<>();
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT configuration_id, channel_kind, decoder_type FROM configuration_channel");
            ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                stored.put(rows.getString("configuration_id"), new StoredChannelClassification(
                    rows.getString("channel_kind"), rows.getString("decoder_type")));
            }
        }
        return Map.copyOf(stored);
    }

    private static void deleteMissing(Connection connection, String table, Set<String> retainedIds)
        throws SQLException
    {
        List<String> removed = new ArrayList<>();
        try(PreparedStatement query = connection.prepareStatement("SELECT configuration_id FROM " + table);
            ResultSet rows = query.executeQuery())
        {
            while(rows.next())
            {
                String configurationId = rows.getString(1);
                if(!retainedIds.contains(configurationId))
                {
                    removed.add(configurationId);
                }
            }
        }

        try(PreparedStatement delete = connection.prepareStatement(
            "DELETE FROM " + table + " WHERE configuration_id=?"))
        {
            for(String configurationId: removed)
            {
                delete.setString(1, configurationId);
                delete.addBatch();
            }
            delete.executeBatch();
        }
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException
    {
        if(value != null)
        {
            statement.setInt(index, value);
        }
        else
        {
            statement.setNull(index, Types.INTEGER);
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException
    {
        if(value != null)
        {
            statement.setLong(index, value);
        }
        else
        {
            statement.setNull(index, Types.INTEGER);
        }
    }

    private String channelPayload(Channel channel) throws IOException
    {
        ObjectNode payload = mObjectMapper.valueToTree(channel);
        payload.remove(CHANNEL_ROW_OWNED_JSON_FIELDS);
        return mObjectMapper.writeValueAsString(payload);
    }

    private String broadcastPayload(BroadcastConfiguration configuration) throws IOException
    {
        ObjectNode payload = mObjectMapper.valueToTree(configuration);
        payload.remove("configurationId");
        return mObjectMapper.writeValueAsString(payload);
    }

    private void requireAbsent(String json, String property, String source) throws IOException
    {
        requireAbsent(json, List.of(property), source);
    }

    private void requireAbsent(String json, List<String> properties, String source) throws IOException
    {
        JsonNode payload = mObjectMapper.readTree(json);
        for(String property: properties)
        {
            if(payload.has(property))
            {
                throw new IOException(source + " must not duplicate relational field [" + property + "]");
            }
        }
    }

    private static Long readNullableLong(ResultSet resultSet, String column) throws SQLException, IOException
    {
        Object value = resultSet.getObject(column);
        if(value == null)
        {
            return null;
        }
        if(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
        {
            return ((Number)value).longValue();
        }
        throw new IOException("configuration_channel " + column + " is not stored as an integer");
    }

    private static String requireCanonicalConfigurationId(String value) throws IOException
    {
        try
        {
            String canonical = UUID.fromString(value).toString();

            if(!canonical.equals(value))
            {
                throw new IllegalArgumentException("not canonical");
            }

            return canonical;
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            throw new IOException("Channel configuration identity must be a canonical lowercase UUID", exception);
        }
    }

    private record StoredChannelClassification(String channelKind, String decoderType)
    {
    }

}
