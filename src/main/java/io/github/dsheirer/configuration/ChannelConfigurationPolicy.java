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

package io.github.dsheirer.configuration;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.source.SourceType;
import io.github.dsheirer.source.config.SourceConfiguration;

/**
 * Shared policy for separating active channel configuration from compatibility-only saved data.  Persistence uses
 * the string-based form before deserializing a row, while import and runtime paths use the channel form.
 */
public final class ChannelConfigurationPolicy
{
    private ChannelConfigurationPolicy()
    {
    }

    public static boolean isActive(Channel channel)
    {
        if(channel == null || channel.getDecodeConfiguration() == null)
        {
            return false;
        }

        DecoderType decoderType = channel.getDecodeConfiguration().getDecoderType();
        SourceConfiguration sourceConfiguration = channel.getSourceConfiguration();
        SourceType sourceType = sourceConfiguration != null ? sourceConfiguration.getSourceType() : null;
        return decoderType != null && decoderType.isActive() && sourceType != null && sourceType.isActive();
    }

    public static boolean isRetired(Channel channel)
    {
        if(channel == null)
        {
            return false;
        }

        DecoderType decoderType = channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        SourceType sourceType = channel.getSourceConfiguration() != null ?
            channel.getSourceConfiguration().getSourceType() : null;
        return (decoderType != null && decoderType.isRetiredCompatibility()) ||
            (sourceType != null && sourceType.isRetiredCompatibility());
    }

    /**
     * Tests the normalized type names stored beside channel JSON.  Only explicitly declared compatibility values are
     * retained. Unknown or removed types belong in an exact-state staged migration instead of becoming a permanent
     * runtime compatibility layer.
     */
    public static boolean isRetiredPersisted(String decoderTypeName, String sourceTypeName)
    {
        DecoderType decoderType = parse(DecoderType.class, decoderTypeName);
        SourceType sourceType = parse(SourceType.class, sourceTypeName);
        return (decoderType != null && decoderType.isRetiredCompatibility()) ||
            (sourceType != null && sourceType.isRetiredCompatibility());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value)
    {
        if(value == null || value.isBlank())
        {
            return null;
        }

        try
        {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        }
        catch(IllegalArgumentException e)
        {
            return null;
        }
    }
}
