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

package io.github.dsheirer.module.decode.dmr;

import io.github.dsheirer.controller.channel.event.PreloadDataContent;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import java.util.Objects;

/**
 * Immutable learned DMR network/site state carried only by a Capacity Plus rest-channel handoff.
 */
public class DMRRestChannelNetworkConfigurationPreloadData
    extends PreloadDataContent<DMRNetworkConfigurationSnapshot>
{
    public DMRRestChannelNetworkConfigurationPreloadData(DMRNetworkConfigurationSnapshot snapshot)
    {
        super(Objects.requireNonNull(snapshot, "DMR network configuration snapshot cannot be null"));
    }

    public DMRNetworkConfigurationSnapshot getSnapshot()
    {
        return getData();
    }
}
