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

    static String configured(String guid, String configurationId)
    {
        return ChannelContextKey.configured(guid, configurationId);
    }

    static String guid(String guid)
    {
        String usableGuid = nonBlank(guid);
        return usableGuid != null ? "GUID:" + usableGuid : null;
    }

    static String conventional(P25ActivityLogRecords.ContextKind contextKind, String protocol, Long frequencyHertz,
                               String channelName)
    {
        return conventional(contextKind, protocol, frequencyHertz, channelName, false);
    }

    static String conventionalWithChannelName(P25ActivityLogRecords.ContextKind contextKind, String protocol,
                                              Long frequencyHertz, String channelName)
    {
        return conventional(contextKind, protocol, frequencyHertz, channelName, true);
    }

    private static String conventional(P25ActivityLogRecords.ContextKind contextKind, String protocol,
                                       Long frequencyHertz, String channelName, boolean includeChannelName)
    {
        if(contextKind == null || protocol == null || protocol.isBlank())
        {
            return null;
        }

        if(frequencyHertz != null && frequencyHertz > 0)
        {
            String key = contextKind.name() + ":" + protocol + ":" + frequencyHertz;
            String usableChannelName = nonBlank(channelName);
            return includeChannelName && usableChannelName != null ? key + ":" + usableChannelName : key;
        }

        String usableChannelName = nonBlank(channelName);
        return usableChannelName != null ? contextKind.name() + ":" + protocol + ":" + usableChannelName : null;
    }

    private static String nonBlank(String value)
    {
        return value != null && !value.isBlank() ? value : null;
    }
}
