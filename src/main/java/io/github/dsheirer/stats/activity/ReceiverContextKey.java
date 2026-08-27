/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.controller.channel.ChannelContextKey;

/**
 * Creates stable receiver-context keys without coupling protocol mappers to the persistence format.
 */
final class ReceiverContextKey
{
    private ReceiverContextKey()
    {
    }

    static String trunked(String guid)
    {
        return ChannelContextKey.trunked(guid);
    }

    static String conventional(String configurationId)
    {
        return ChannelContextKey.conventional(configurationId);
    }

    static boolean isConventional(String contextKey)
    {
        if(contextKey == null || !contextKey.startsWith("CONFIGURATION:"))
        {
            return false;
        }

        String configurationId = contextKey.substring("CONFIGURATION:".length());
        return contextKey.equals(conventional(configurationId));
    }
}
