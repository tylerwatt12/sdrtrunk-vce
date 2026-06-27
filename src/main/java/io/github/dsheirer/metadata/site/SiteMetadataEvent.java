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
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;

/**
 * Stable, session-observed site metadata for an external consumer.
 */
public record SiteMetadataEvent(Channel channel, P25NetworkConfigurationSnapshot snapshot,
                                long observedAtEpochMilliseconds)
{
    public boolean isUseful()
    {
        return channel != null && snapshot != null && snapshot.isUseful();
    }
}
