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
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.map.ChannelMap;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.config.SourceConfigRecording;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite persistence for channel, channel-map, and stream configuration.
 */
public class ConfigurationDatabaseStore
{
    private static final Logger mLog = LoggerFactory.getLogger(ConfigurationDatabaseStore.class);
    private static final String CONFIGURATION_STATE_INITIALIZED_KEY = "configuration_state_initialized";
    private static final String TRUE = "true";

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

    public boolean isInitialized() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            return TRUE.equalsIgnoreCase(getMetadata(connection, CONFIGURATION_STATE_INITIALIZED_KEY));
        }
    }

    public ConfigurationState loadConfigurationState() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            ConfigurationState state = new ConfigurationState();
            state.setChannels(loadChannels(connection));
            state.setChannelMaps(loadChannelMaps(connection));
            state.setBroadcastConfigurations(loadBroadcastConfigurations(connection));
            return state;
        }
    }

    public void replaceConfigurationState(ConfigurationState state) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try
            {
                clearConfigurationState(connection);
                insertChannels(connection, state.getChannels());
                insertChannelMaps(connection, state.getChannelMaps());
                insertBroadcastConfigurations(connection, state.getBroadcastConfigurations());
                updateMetadata(connection, CONFIGURATION_STATE_INITIALIZED_KEY, TRUE);
                connection.commit();
            }
            catch(SQLException | IOException e)
            {
                connection.rollback();
                throw e;
            }
            finally
            {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private List<Channel> loadChannels(Connection connection) throws SQLException, IOException
    {
        List<Channel> channels = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT system_name, site_name, name, alias_list_name, radres_guid, auto_start, auto_start_order, config_json
            FROM configuration_channel
            ORDER BY sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                Channel channel = mObjectMapper.readValue(resultSet.getString("config_json"), Channel.class);
                channel.setSystem(resultSet.getString("system_name"));
                channel.setSite(resultSet.getString("site_name"));
                channel.setName(resultSet.getString("name"));
                channel.setAliasListName(resultSet.getString("alias_list_name"));
                channel.setRadresGuid(resultSet.getString("radres_guid"));
                channel.setAutoStart(resultSet.getInt("auto_start") == 1);
                channel.setAutoStartOrder(getInteger(resultSet, "auto_start_order"));
                channels.add(channel);
            }
        }

        return channels;
    }

    private List<ChannelMap> loadChannelMaps(Connection connection) throws SQLException, IOException
    {
        List<ChannelMap> channelMaps = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT config_json
            FROM configuration_channel_map
            ORDER BY sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                channelMaps.add(mObjectMapper.readValue(resultSet.getString("config_json"), ChannelMap.class));
            }
        }

        return channelMaps;
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

                try
                {
                    configurations.add(mObjectMapper.readValue(json, BroadcastConfiguration.class));
                }
                catch(InvalidTypeIdException e)
                {
                    mLog.warn("Skipping retired or unsupported stream configuration from SQLite database: {}",
                        e.getTypeId());
                }
            }
        }

        return configurations;
    }

    private void clearConfigurationState(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM configuration_broadcast_stream");
            statement.executeUpdate("DELETE FROM configuration_channel_map");
            statement.executeUpdate("DELETE FROM configuration_channel");
        }
    }

    private void insertChannels(Connection connection, List<Channel> channels) throws SQLException, IOException
    {
        int sortOrder = 0;

        for(Channel channel: channels)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO configuration_channel (
                    sort_order, system_name, site_name, name, alias_list_name, radres_guid,
                    auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz,
                    frequency_count, recording_enabled, event_logging_enabled, config_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """))
            {
                List<Long> frequencies = channel.getFrequencyList();
                statement.setInt(1, sortOrder++);
                statement.setString(2, channel.getSystem());
                statement.setString(3, channel.getSite());
                statement.setString(4, channel.getName());
                statement.setString(5, channel.getAliasListName());
                statement.setString(6, channel.getRadresGuid());
                statement.setInt(7, channel.getAutoStart() ? 1 : 0);
                setInteger(statement, 8, channel.getAutoStartOrder());
                statement.setString(9, decoderType(channel));
                statement.setString(10, sourceType(channel));
                setLong(statement, 11, primaryFrequency(channel));
                statement.setInt(12, frequencies != null ? frequencies.size() : 0);
                statement.setInt(13, hasRecorders(channel) ? 1 : 0);
                statement.setInt(14, hasEventLoggers(channel) ? 1 : 0);
                statement.setString(15, mObjectMapper.writeValueAsString(channel));
                statement.executeUpdate();
            }
        }
    }

    private void insertChannelMaps(Connection connection, List<ChannelMap> channelMaps) throws SQLException, IOException
    {
        int sortOrder = 0;

        for(ChannelMap channelMap: channelMaps)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO configuration_channel_map (sort_order, name, config_json)
                VALUES (?, ?, ?)
                """))
            {
                statement.setInt(1, sortOrder++);
                statement.setString(2, channelMap.getName());
                statement.setString(3, mObjectMapper.writeValueAsString(channelMap));
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

    private static String decoderType(Channel channel)
    {
        DecodeConfiguration configuration = channel.getDecodeConfiguration();
        return configuration != null && configuration.getDecoderType() != null ?
            configuration.getDecoderType().name() : null;
    }

    private static String sourceType(Channel channel)
    {
        SourceConfiguration configuration = channel.getSourceConfiguration();
        return configuration != null && configuration.getSourceType() != null ?
            configuration.getSourceType().name() : null;
    }

    private static Long primaryFrequency(Channel channel)
    {
        SourceConfiguration configuration = channel.getSourceConfiguration();

        if(configuration instanceof SourceConfigTuner tuner)
        {
            return tuner.getFrequency();
        }
        else if(configuration instanceof SourceConfigTunerMultipleFrequency multiple)
        {
            long frequency = multiple.getPreferredFrequency();
            return frequency > 0 ? frequency : null;
        }
        else if(configuration instanceof SourceConfigRecording recording)
        {
            return recording.getFrequency();
        }

        return null;
    }

    private static boolean hasRecorders(Channel channel)
    {
        RecordConfiguration configuration = channel.getRecordConfiguration();
        return configuration != null && configuration.getRecorders() != null && !configuration.getRecorders().isEmpty();
    }

    private static boolean hasEventLoggers(Channel channel)
    {
        EventLogConfiguration configuration = channel.getEventLogConfiguration();
        return configuration != null && configuration.getLoggers() != null && !configuration.getLoggers().isEmpty();
    }

    private String getMetadata(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT value FROM database_metadata WHERE key = ?
            """))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return resultSet.getString("value");
                }
            }
        }

        return null;
    }

    private void updateMetadata(Connection connection, String key, String value) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata (key, value, updated_at_ms)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET
                value = excluded.value,
                updated_at_ms = excluded.updated_at_ms
            """))
        {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
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

    private static Integer getInteger(ResultSet resultSet, String column) throws SQLException
    {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }
}
