/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.controller.channel;

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
        return channel != null ? configured(channel.hasRadresGuid() ? channel.getRadresGuid() : null,
            channel.getConfigurationId()) : null;
    }

    public static String configured(String guid, String configurationId)
    {
        String usableGuid = nonBlank(guid);

        if(usableGuid != null)
        {
            return "GUID:" + usableGuid;
        }

        String usableConfigurationId = nonBlank(configurationId);
        return usableConfigurationId != null ? "CONFIGURATION:" + usableConfigurationId : null;
    }

    private static String nonBlank(String value)
    {
        return value != null && !value.isBlank() ? value : null;
    }
}
