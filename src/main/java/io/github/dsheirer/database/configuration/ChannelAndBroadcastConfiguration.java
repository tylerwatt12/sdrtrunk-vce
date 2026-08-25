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

package io.github.dsheirer.database.configuration;

import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import java.util.List;
import java.util.Objects;

/** Channel and broadcast-stream portion of a durable configuration snapshot. */
public record ChannelAndBroadcastConfiguration(List<Channel> channels,
                                               List<BroadcastConfiguration> broadcastConfigurations)
{
    public ChannelAndBroadcastConfiguration
    {
        channels = List.copyOf(Objects.requireNonNull(channels, "Channels cannot be null"));
        broadcastConfigurations = List.copyOf(Objects.requireNonNull(broadcastConfigurations,
            "Broadcast configurations cannot be null"));
    }
}
