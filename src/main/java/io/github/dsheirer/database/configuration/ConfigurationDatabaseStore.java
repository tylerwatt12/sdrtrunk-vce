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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * SQLite persistence for active channel and stream configuration. The legacy channel-map table remains untouched.
 */
public class ConfigurationDatabaseStore
{
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

        clearConfigurationState(connection);
        insertChannels(connection, configuration.channels());
        insertBroadcastConfigurations(connection, configuration.broadcastConfigurations());
    }

    /**
     * Clears channel references to deleted Alias Lists without rewriting unrelated channel or stream rows. The scalar
     * column is authoritative when loading, and the matching JSON display field is removed in the same statement.
     */
    public void clearAliasListAssignments(Connection connection, Collection<String> aliasListNames)
        throws SQLException
    {
        if(connection == null || connection.getAutoCommit())
        {
            throw new IllegalArgumentException("Alias-list assignment updates require a caller-owned transaction");
        }
        if(aliasListNames == null || aliasListNames.isEmpty())
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE configuration_channel
            SET alias_list_name = NULL,
                config_json = json_remove(config_json, '$.aliasListName')
            WHERE alias_list_name = ? COLLATE NOCASE
            """))
        {
            for(String name: aliasListNames)
            {
                if(name == null || name.isBlank())
                {
                    throw new IllegalArgumentException("Deleted Alias-list names must be nonblank");
                }
                statement.setString(1, name.strip());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Replaces only broadcast streams as one part of a caller-owned transaction. */
    public void replaceBroadcastConfigurations(Connection connection,
                                               List<BroadcastConfiguration> configurations)
        throws IOException, SQLException
    {
        if(connection == null || connection.getAutoCommit())
        {
            throw new IllegalArgumentException("Broadcast configuration writes require a caller-owned transaction");
        }
        if(configurations == null)
        {
            throw new IllegalArgumentException("Broadcast configurations cannot be null");
        }

        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM configuration_broadcast_stream");
        }
        insertBroadcastConfigurations(connection, configurations);
    }

    /**
     * Persists a stream rename without changing the live configuration before the surrounding Alias transaction
     * commits. Stream names are unique in the active broadcast model, so exactly one persisted row must match.
     */
    public void replaceBroadcastConfigurationsWithRename(Connection connection,
                                                          List<BroadcastConfiguration> configurations,
                                                          String previousName, String updatedName)
        throws IOException, SQLException
    {
        if(previousName == null || previousName.isBlank() || updatedName == null || updatedName.isBlank())
        {
            throw new IllegalArgumentException("Broadcast rename names must be nonblank");
        }

        replaceBroadcastConfigurations(connection, configurations);
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE configuration_broadcast_stream
            SET name = ?, config_json = json_set(config_json, '$.name', ?)
            WHERE name = ?
            """))
        {
            statement.setString(1, updatedName);
            statement.setString(2, updatedName);
            statement.setString(3, previousName);
            if(statement.executeUpdate() != 1)
            {
                throw new SQLException("Expected exactly one broadcast stream named [" + previousName + "]");
            }
        }
    }

    private List<Channel> loadChannels(Connection connection) throws SQLException, IOException
    {
        List<Channel> channels = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT configuration_id, channel_kind, system_name, site_name, name, alias_list_name, radres_guid,
                   auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz, frequency_count,
                   recording_enabled, event_logging_enabled, config_json
            FROM configuration_channel
            ORDER BY sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                String json = resultSet.getString("config_json");
                String configurationId = requireConfigurationId(json, resultSet.getString("configuration_id"));
                Channel channel = mObjectMapper.readValue(json, Channel.class);

                if(!configurationId.equals(channel.getConfigurationId()))
                {
                    throw new IOException("Channel configuration identity changed while decoding persisted JSON");
                }

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
                channel.setRadresGuid(resultSet.getString("radres_guid"));
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
            SELECT config_json
            FROM configuration_broadcast_stream
            ORDER BY sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                String json = resultSet.getString("config_json");
                configurations.add(mObjectMapper.readValue(json, BroadcastConfiguration.class));
            }
        }

        return configurations;
    }

    private void clearConfigurationState(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM configuration_broadcast_stream");
            statement.executeUpdate("DELETE FROM configuration_channel");
        }
    }

    private void insertChannels(Connection connection, List<Channel> channels) throws SQLException, IOException
    {
        int sortOrder = 0;

        for(Channel channel: channels)
        {
            if(ChannelConfigurationPolicy.isRetired(channel))
            {
                throw new IOException("Retired channel configuration cannot be stored in the active database");
            }

            ChannelConfigurationPolicy.ChannelKind channelKind =
                ChannelConfigurationPolicy.requireChannelKind(channel);
            //Conventional routing is owned by configuration_id. radres_guid remains separate correlation metadata
            //required by RadioResolve call uploads, so the lazy getter deliberately assigns it before scalar/JSON
            //serialization and both persisted representations receive the same value.
            String radresGuid = channel.getRadresGuid();
            ConfigurationChannelProjection projection = ConfigurationChannelProjection.from(channel);

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO configuration_channel (
                    configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_name,
                    radres_guid,
                    auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz,
                    frequency_count, recording_enabled, event_logging_enabled, config_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """))
            {
                statement.setString(1, requireCanonicalConfigurationId(channel.getConfigurationId()));
                statement.setString(2, channelKind.name());
                statement.setInt(3, sortOrder++);
                statement.setString(4, channel.getSystem());
                statement.setString(5, channel.getSite());
                statement.setString(6, channel.getName());
                statement.setString(7, channel.getAliasListName());
                statement.setString(8, radresGuid);
                statement.setInt(9, channel.getAutoStart() ? 1 : 0);
                setInteger(statement, 10, channel.getAutoStartOrder());
                projection.bind(statement, 11);
                statement.setString(17, mObjectMapper.writeValueAsString(channel));
                statement.executeUpdate();
            }
        }
    }

    private void insertBroadcastConfigurations(Connection connection, List<BroadcastConfiguration> configurations)
        throws SQLException, IOException
    {
        int sortOrder = 0;

        for(BroadcastConfiguration configuration: configurations)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO configuration_broadcast_stream (
                    sort_order, name, server_type, enabled, host, port, delay_ms, maximum_recording_age_ms, config_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """))
            {
                statement.setInt(1, sortOrder++);
                statement.setString(2, configuration.getName());
                statement.setString(3, configuration.getBroadcastServerType() != null ?
                    configuration.getBroadcastServerType().name() : null);
                statement.setInt(4, configuration.isEnabled() ? 1 : 0);
                statement.setString(5, configuration.getHost());
                statement.setInt(6, configuration.getPort());
                statement.setLong(7, configuration.getDelay());
                statement.setLong(8, configuration.getMaximumRecordingAge());
                statement.setString(9, mObjectMapper.writeValueAsString(configuration));
                statement.executeUpdate();
            }
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

    private String requireConfigurationId(String json, String scalar) throws IOException
    {
        String jsonId = mObjectMapper.readTree(json).path("configurationId").textValue();
        String canonicalScalar = requireCanonicalConfigurationId(scalar);

        if(!canonicalScalar.equals(requireCanonicalConfigurationId(jsonId)) || !canonicalScalar.equals(jsonId))
        {
            throw new IOException("Channel configuration identity scalar does not match config_json");
        }

        return canonicalScalar;
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

}
