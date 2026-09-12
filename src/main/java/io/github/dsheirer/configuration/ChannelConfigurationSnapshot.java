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

package io.github.dsheirer.configuration;

import io.github.dsheirer.controller.channel.Channel;
import java.util.List;
import java.util.Objects;

/** Immutable container for one detached, ordered channel configuration. */
public record ChannelConfigurationSnapshot(List<Channel> channels)
{
    public ChannelConfigurationSnapshot
    {
        channels = List.copyOf(Objects.requireNonNull(channels, "Channels cannot be null"));
    }
}
