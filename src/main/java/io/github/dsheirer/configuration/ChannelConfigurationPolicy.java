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
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.source.SourceType;
import io.github.dsheirer.source.config.SourceConfiguration;

/**
 * Shared policy for separating active channel configuration from compatibility-only migration input. The staged
 * migrator uses the string-based form before deserializing an old row, while active import and runtime paths use the
 * channel form.
 */
public final class ChannelConfigurationPolicy
{
    public enum ChannelKind
    {
        TRUNKED,
        CONVENTIONAL
    }

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
     * Tests exact legacy type names stored beside channel JSON. Only these explicitly declared retired values may be
     * dropped without decoding their opaque document. Unknown, case-folded, or malformed values are refused by the
     * staged migration instead of becoming a permanent runtime compatibility layer.
     */
    public static boolean isRetiredPersisted(String decoderTypeName, String sourceTypeName)
    {
        return DecoderType.MPT1327.name().equals(decoderTypeName) || SourceType.MIXER.name().equals(sourceTypeName);
    }

    /**
     * Classifies a saved channel from its decoded configuration. This is the one authoritative rule used both when
     * persisting current channels and when the staged migrator projects the format-5 scalar.
     */
    public static ChannelKind requireChannelKind(Channel channel)
    {
        if(!isActive(channel))
        {
            throw new IllegalArgumentException("Only active channel configurations can be classified");
        }

        if(channel.getDecodeConfiguration() instanceof DecodeConfigDMR dmr)
        {
            return dmr.isTrunked() ? ChannelKind.TRUNKED : ChannelKind.CONVENTIONAL;
        }

        if(channel.getDecodeConfiguration() instanceof DecodeConfigNXDN nxdn)
        {
            return nxdn.isTrunked() ? ChannelKind.TRUNKED : ChannelKind.CONVENTIONAL;
        }

        return switch(channel.getDecodeConfiguration().getDecoderType())
        {
            case P25_PHASE1, P25_PHASE2 -> ChannelKind.TRUNKED;
            case AM, NBFM, P25_CONVENTIONAL -> ChannelKind.CONVENTIONAL;
            default -> throw new IllegalArgumentException("Unsupported primary decoder cannot be classified: " +
                channel.getDecodeConfiguration().getDecoderType());
        };
    }

}
