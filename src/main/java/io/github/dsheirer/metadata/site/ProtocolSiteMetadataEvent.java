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

package io.github.dsheirer.metadata.site;

import io.github.dsheirer.controller.channel.Channel;

/**
 * Protocol-neutral site metadata event for live consumers.
 */
public record ProtocolSiteMetadataEvent(Channel channel, SiteMetadataSnapshot snapshot,
                                        long observedAtEpochMilliseconds)
{
    public boolean isUseful()
    {
        return channel != null && snapshot != null && snapshot.isUseful();
    }
}
