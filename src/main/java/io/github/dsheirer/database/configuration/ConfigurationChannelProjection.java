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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.config.SourceConfigRecording;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic scalar projection of the channel fields used for database lookup and filtering.  The JSON channel is
 * authoritative; these scalars must always be an exact projection of it.
 */
public record ConfigurationChannelProjection(String decoderType, String sourceType, Long primaryFrequencyHz,
                                             int frequencyCount, boolean hasRecorders, boolean hasEventLoggers)
{
    public ConfigurationChannelProjection
    {
        if(frequencyCount < 0)
        {
            throw new IllegalArgumentException("Channel frequency count cannot be negative");
        }
    }

    /** Creates the canonical projection for a decoded channel. */
    public static ConfigurationChannelProjection from(Channel channel)
    {
        Objects.requireNonNull(channel, "Channel cannot be null");
        DecodeConfiguration decodeConfiguration = channel.getDecodeConfiguration();
        SourceConfiguration sourceConfiguration = channel.getSourceConfiguration();
        List<Long> frequencies = channel.getFrequencyList();
        RecordConfiguration recordConfiguration = channel.getRecordConfiguration();
        EventLogConfiguration eventLogConfiguration = channel.getEventLogConfiguration();

        String decoderType = decodeConfiguration != null && decodeConfiguration.getDecoderType() != null ?
            decodeConfiguration.getDecoderType().name() : null;
        String sourceType = sourceConfiguration != null && sourceConfiguration.getSourceType() != null ?
            sourceConfiguration.getSourceType().name() : null;
        Long primaryFrequency = primaryFrequency(sourceConfiguration);
        int frequencyCount = frequencies != null ? frequencies.size() : 0;
        boolean hasRecorders = recordConfiguration != null && recordConfiguration.getRecorders() != null &&
            !recordConfiguration.getRecorders().isEmpty();
        boolean hasEventLoggers = eventLogConfiguration != null && eventLogConfiguration.getLoggers() != null &&
            !eventLogConfiguration.getLoggers().isEmpty();
        return new ConfigurationChannelProjection(decoderType, sourceType, primaryFrequency, frequencyCount,
            hasRecorders, hasEventLoggers);
    }

    /** Reads and strictly types the six persisted projection columns from the current result-set row. */
    public static ConfigurationChannelProjection read(ResultSet resultSet) throws SQLException, IOException
    {
        Objects.requireNonNull(resultSet, "Result set cannot be null");
        long frequencyCount = requiredInteger(resultSet, "frequency_count");
        if(frequencyCount < 0 || frequencyCount > Integer.MAX_VALUE)
        {
            throw new IOException("configuration_channel frequency_count is outside its valid range");
        }

        return new ConfigurationChannelProjection(nullableText(resultSet, "decoder_type"),
            nullableText(resultSet, "source_type"), nullableInteger(resultSet, "primary_frequency_hz"),
            (int)frequencyCount, booleanFlag(resultSet, "recording_enabled"),
            booleanFlag(resultSet, "event_logging_enabled"));
    }

    /** Reads a required SQLite integer flag and refuses coercible text, real, or out-of-domain values. */
    public static boolean readBooleanFlag(ResultSet resultSet, String column) throws SQLException, IOException
    {
        return booleanFlag(resultSet, column);
    }

    /** Reads a nullable SQLite integer whose value must fit the Java channel model exactly. */
    public static Integer readNullableInt(ResultSet resultSet, String column) throws SQLException, IOException
    {
        Long value = nullableInteger(resultSet, column);
        if(value == null)
        {
            return null;
        }
        if(value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
        {
            throw new IOException("configuration_channel " + column + " is outside the supported integer range");
        }
        return value.intValue();
    }

    /** Binds the six projection fields in their schema order beginning at {@code firstParameter}. */
    public void bind(PreparedStatement statement, int firstParameter) throws SQLException
    {
        Objects.requireNonNull(statement, "Statement cannot be null");
        statement.setString(firstParameter, decoderType);
        statement.setString(firstParameter + 1, sourceType);
        if(primaryFrequencyHz != null)
        {
            statement.setLong(firstParameter + 2, primaryFrequencyHz);
        }
        else
        {
            statement.setNull(firstParameter + 2, Types.INTEGER);
        }
        statement.setInt(firstParameter + 3, frequencyCount);
        statement.setInt(firstParameter + 4, hasRecorders ? 1 : 0);
        statement.setInt(firstParameter + 5, hasEventLoggers ? 1 : 0);
    }

    /** Refuses a persisted projection that is not exactly the projection of its decoded JSON channel. */
    public void requireMatches(ConfigurationChannelProjection persisted, String context) throws IOException
    {
        Objects.requireNonNull(persisted, "Persisted projection cannot be null");
        if(equals(persisted))
        {
            return;
        }

        List<String> mismatches = new ArrayList<>(6);
        addMismatch(mismatches, "decoder_type", decoderType, persisted.decoderType);
        addMismatch(mismatches, "source_type", sourceType, persisted.sourceType);
        addMismatch(mismatches, "primary_frequency_hz", primaryFrequencyHz, persisted.primaryFrequencyHz);
        addMismatch(mismatches, "frequency_count", frequencyCount, persisted.frequencyCount);
        addMismatch(mismatches, "recording_enabled", hasRecorders, persisted.hasRecorders);
        addMismatch(mismatches, "event_logging_enabled", hasEventLoggers, persisted.hasEventLoggers);
        throw new IOException(context + " scalar projection does not match config_json: " +
            String.join(", ", mismatches));
    }

    private static Long primaryFrequency(SourceConfiguration configuration)
    {
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

    private static String nullableText(ResultSet resultSet, String column) throws SQLException, IOException
    {
        Object value = resultSet.getObject(column);
        if(value == null || value instanceof String)
        {
            return (String)value;
        }
        throw new IOException("configuration_channel " + column + " is not stored as text");
    }

    private static Long nullableInteger(ResultSet resultSet, String column) throws SQLException, IOException
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

    private static long requiredInteger(ResultSet resultSet, String column) throws SQLException, IOException
    {
        Long value = nullableInteger(resultSet, column);
        if(value == null)
        {
            throw new IOException("configuration_channel " + column + " cannot be null");
        }
        return value;
    }

    private static boolean booleanFlag(ResultSet resultSet, String column) throws SQLException, IOException
    {
        long value = requiredInteger(resultSet, column);
        if(value == 0)
        {
            return false;
        }
        if(value == 1)
        {
            return true;
        }
        throw new IOException("configuration_channel " + column + " must be 0 or 1");
    }

    private static void addMismatch(List<String> mismatches, String column, Object expected, Object persisted)
    {
        if(!Objects.equals(expected, persisted))
        {
            mismatches.add(column);
        }
    }
}
