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
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.source.config.SourceConfigRecording;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

/**
 * The JSON-authoritative channel projections that serve indexed production queries. Decoder, NXDN address-domain,
 * and source subtype details remain authoritative in {@code config_json}, and these scalars must exactly match that
 * document.
 * {@code configuration_channel.channel_kind} is different: it is the row-owned topology classification, derived by
 * the shared channel policy when saving and checked against the decoded configuration when loading.
 */
public record ConfigurationChannelProjection(String decoderType, int addressDomainCode, Long primaryFrequencyHz)
{
    public static final int ADDRESS_DOMAIN_STANDARD = 0;
    public static final int ADDRESS_DOMAIN_NXDN_TYPE_C = 1;
    public static final int ADDRESS_DOMAIN_NXDN_TYPE_D = 2;

    public static ConfigurationChannelProjection from(Channel channel)
    {
        Objects.requireNonNull(channel, "Channel cannot be null");
        DecodeConfiguration decodeConfiguration = channel.getDecodeConfiguration();
        String decoderType = decodeConfiguration != null && decodeConfiguration.getDecoderType() != null ?
            decodeConfiguration.getDecoderType().name() : null;
        int addressDomainCode = decodeConfiguration instanceof DecodeConfigNXDN nxdn &&
            nxdn.getTransmissionMode() != null && nxdn.getTransmissionMode().isTypeD() ?
            ADDRESS_DOMAIN_NXDN_TYPE_D : decodeConfiguration instanceof DecodeConfigNXDN ?
                ADDRESS_DOMAIN_NXDN_TYPE_C : ADDRESS_DOMAIN_STANDARD;
        return new ConfigurationChannelProjection(decoderType, addressDomainCode,
            primaryFrequency(channel.getSourceConfiguration()));
    }

    public static ConfigurationChannelProjection read(ResultSet resultSet) throws SQLException, IOException
    {
        Objects.requireNonNull(resultSet, "Result set cannot be null");
        return new ConfigurationChannelProjection(nullableText(resultSet, "decoder_type"),
            Math.toIntExact(requiredInteger(resultSet, "address_domain_code")),
            nullableInteger(resultSet, "primary_frequency_hz"));
    }

    public static boolean readBooleanFlag(ResultSet resultSet, String column) throws SQLException, IOException
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

    public void bind(PreparedStatement statement, int firstParameter) throws SQLException
    {
        Objects.requireNonNull(statement, "Statement cannot be null");
        statement.setString(firstParameter, decoderType);
        statement.setInt(firstParameter + 1, addressDomainCode);
        if(primaryFrequencyHz != null)
        {
            statement.setLong(firstParameter + 2, primaryFrequencyHz);
        }
        else
        {
            statement.setNull(firstParameter + 2, Types.INTEGER);
        }
    }

    public void requireMatches(ConfigurationChannelProjection persisted, String context) throws IOException
    {
        Objects.requireNonNull(persisted, "Persisted projection cannot be null");
        if(!Objects.equals(decoderType, persisted.decoderType))
        {
            throw new IOException(context + " decoder_type projection does not match config_json");
        }
        if(addressDomainCode != persisted.addressDomainCode)
        {
            throw new IOException(context + " address_domain_code projection does not match config_json");
        }
        if(!Objects.equals(primaryFrequencyHz, persisted.primaryFrequencyHz))
        {
            throw new IOException(context + " primary_frequency_hz projection does not match config_json");
        }
    }

    private static Long primaryFrequency(SourceConfiguration configuration)
    {
        if(configuration instanceof SourceConfigTuner tuner)
        {
            long frequency = tuner.getFrequency();
            return frequency > 0 ? frequency : null;
        }
        else if(configuration instanceof SourceConfigTunerMultipleFrequency multiple)
        {
            long frequency = multiple.getPreferredFrequency();
            return frequency > 0 ? frequency : null;
        }
        else if(configuration instanceof SourceConfigRecording recording)
        {
            long frequency = recording.getFrequency();
            return frequency > 0 ? frequency : null;
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
}
