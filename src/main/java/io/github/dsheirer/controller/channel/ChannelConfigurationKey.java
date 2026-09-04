/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.controller.channel;

import java.util.UUID;

/** Validates the saved channel UUID used by activity, navigation, and radio-system ownership. */
public final class ChannelConfigurationKey
{
    private ChannelConfigurationKey()
    {
    }

    public static String configured(Channel channel)
    {
        if(channel == null)
        {
            return null;
        }

        return canonical(channel.getConfigurationId());
    }

    public static String canonical(String configurationId)
    {
        if(configurationId == null || configurationId.isBlank())
        {
            return null;
        }

        String candidate = configurationId.strip();

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
