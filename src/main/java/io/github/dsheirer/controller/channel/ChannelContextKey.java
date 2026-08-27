/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.controller.channel;

import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import java.util.UUID;

/**
 * Creates the stable context key shared by configured-channel activity and browser navigation.
 */
public final class ChannelContextKey
{
    private ChannelContextKey()
    {
    }

    public static String configured(Channel channel)
    {
        if(channel == null)
        {
            return null;
        }

        return switch(ChannelConfigurationPolicy.requireChannelKind(channel))
        {
            case TRUNKED -> trunked(channel.hasRadresGuid() ? channel.getRadresGuid() : null);
            case CONVENTIONAL -> conventional(channel.getConfigurationId());
        };
    }

    public static String trunked(String guid)
    {
        String usableGuid = canonicalUuid(guid);
        return usableGuid != null ? "GUID:" + usableGuid : null;
    }

    public static String conventional(String configurationId)
    {
        String usableConfigurationId = canonicalUuid(configurationId);
        return usableConfigurationId != null ? "CONFIGURATION:" + usableConfigurationId : null;
    }

    private static String canonicalUuid(String value)
    {
        if(value == null || value.isBlank())
        {
            return null;
        }

        String candidate = value.strip();

        try
        {
            String canonical = UUID.fromString(candidate).toString();
            return canonical.equals(candidate) ? canonical : null;
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }
}
