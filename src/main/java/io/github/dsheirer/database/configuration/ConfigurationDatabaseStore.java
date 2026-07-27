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
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
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

/**
 * SQLite persistence for active channel and stream configuration. Retired channel rows and the legacy channel-map
 * table remain untouched compatibility data.
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

    public ConfigurationState loadConfigurationState() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            ConfigurationState state = new ConfigurationState();
            state.setChannels(loadChannels(connection));
            state.setBroadcastConfigurations(loadBroadcastConfigurations(connection));
            return state;
        }
    }

    /**
     * Saves channels and streams using a caller-owned transaction. Import workflows use this overload together with
     * {@link io.github.dsheirer.database.alias.AliasDatabaseStore#replaceAliases(Connection, List, List)} so every
     * reference and its alias-list definition becomes visible in one commit.
     *
     * @param connection open connection with auto-commit disabled
     */
    public void replaceConfigurationState(Connection connection, ConfigurationState state)
        throws IOException, SQLException
    {
        if(connection == null || connection.getAutoCommit())
        {
            throw new IllegalArgumentException("Configuration snapshot writes require a caller-owned transaction");
        }

        List<RetainedChannelRow> retainedChannels = loadRetainedChannelRows(connection);
        clearConfigurationState(connection);
        restoreRetainedChannelRows(connection, retainedChannels);
        insertChannels(connection, state.getChannels());
        insertBroadcastConfigurations(connection, state.getBroadcastConfigurations());
    }

    private List<Channel> loadChannels(Connection connection) throws SQLException, IOException
    {
        List<Channel> channels = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT system_name, site_name, name, alias_list_name, radres_guid, auto_start, auto_start_order,
                   decoder_type, source_type, config_json
            FROM configuration_channel
            ORDER BY sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                if(ChannelConfigurationPolicy.isRetiredPersisted(resultSet.getString("decoder_type"),
                    resultSet.getString("source_type")))
                {
                    continue;
                }

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

    private List<RetainedChannelRow> loadRetainedChannelRows(Connection connection) throws SQLException
    {
        List<RetainedChannelRow> rows = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, sort_order, system_name, site_name, name, alias_list_name, radres_guid, auto_start,
                   auto_start_order, decoder_type, source_type, primary_frequency_hz, frequency_count,
                   recording_enabled, event_logging_enabled, config_json
            FROM configuration_channel
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                String decoderType = resultSet.getString("decoder_type");
                String sourceType = resultSet.getString("source_type");

                if(ChannelConfigurationPolicy.isRetiredPersisted(decoderType, sourceType))
                {
                    rows.add(new RetainedChannelRow(resultSet.getLong("id"), resultSet.getInt("sort_order"),
                        resultSet.getString("system_name"), resultSet.getString("site_name"),
                        resultSet.getString("name"), resultSet.getString("alias_list_name"),
                        resultSet.getString("radres_guid"), resultSet.getInt("auto_start"),
                        getInteger(resultSet, "auto_start_order"), decoderType, sourceType,
                        getLong(resultSet, "primary_frequency_hz"), resultSet.getInt("frequency_count"),
                        resultSet.getInt("recording_enabled"), resultSet.getInt("event_logging_enabled"),
                        resultSet.getString("config_json")));
                }
            }
        }

        return rows;
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

    private void restoreRetainedChannelRows(Connection connection, List<RetainedChannelRow> rows) throws SQLException
    {
        for(RetainedChannelRow row: rows)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO configuration_channel (
                    id, sort_order, system_name, site_name, name, alias_list_name, radres_guid, auto_start,
                    auto_start_order, decoder_type, source_type, primary_frequency_hz, frequency_count,
                    recording_enabled, event_logging_enabled, config_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """))
            {
                statement.setLong(1, row.id());
                statement.setInt(2, row.sortOrder());
                statement.setString(3, row.system());
                statement.setString(4, row.site());
                statement.setString(5, row.name());
                statement.setString(6, row.aliasListName());
                statement.setString(7, row.radresGuid());
                statement.setInt(8, row.autoStart());
                setInteger(statement, 9, row.autoStartOrder());
                statement.setString(10, row.decoderType());
                statement.setString(11, row.sourceType());
                setLong(statement, 12, row.primaryFrequency());
                statement.setInt(13, row.frequencyCount());
                statement.setInt(14, row.recordingEnabled());
                statement.setInt(15, row.eventLoggingEnabled());
                statement.setString(16, row.configJson());
                statement.executeUpdate();
            }
        }
    }

    private void insertChannels(Connection connection, List<Channel> channels) throws SQLException, IOException
    {
        int sortOrder = 0;

        for(Channel channel: channels)
        {
            if(ChannelConfigurationPolicy.isRetired(channel))
            {
                continue;
            }

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

    private static Long getLong(ResultSet resultSet, String column) throws SQLException
    {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private record RetainedChannelRow(long id, int sortOrder, String system, String site, String name,
                                      String aliasListName, String radresGuid, int autoStart, Integer autoStartOrder,
                                      String decoderType, String sourceType, Long primaryFrequency,
                                      int frequencyCount, int recordingEnabled, int eventLoggingEnabled,
                                      String configJson)
    {
    }
}
