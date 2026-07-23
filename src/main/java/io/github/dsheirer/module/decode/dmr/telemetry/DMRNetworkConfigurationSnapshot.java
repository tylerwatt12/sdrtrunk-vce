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

package io.github.dsheirer.module.decode.dmr.telemetry;

import io.github.dsheirer.metadata.site.SiteMetadataSnapshot;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;

/**
 * Structured DMR network configuration learned over the air.
 */
public record DMRNetworkConfigurationSnapshot(String decoder, String variant, Integer network, Integer site,
                                              String brand, String model, String mode, String channelType,
                                              Integer colorCodeTimeslot1, Integer colorCodeTimeslot2,
                                              List<Channel> channels, List<NeighborSite> neighborSites)
    implements SiteMetadataSnapshot
{
    public DMRNetworkConfigurationSnapshot
    {
        channels = channels == null ? List.of() : List.copyOf(channels);
        neighborSites = neighborSites == null ? List.of() : List.copyOf(neighborSites);
    }

    @Override
    public Protocol protocol()
    {
        return Protocol.DMR;
    }

    @Override
    public boolean isUseful()
    {
        return network != null || site != null || brand != null || model != null || mode != null ||
            channelType != null || !channels.isEmpty() || !neighborSites.isEmpty();
    }

    /**
     * Observed DMR logical channel/timeslot and any resolved frequency mapping.
     */
    public record Channel(String descriptor, Integer logicalChannelNumber, Integer timeslot,
                          Long downlink, Long uplink)
    {
    }

    /**
     * DMR neighbor site. Some vendor formats only advertise a site number, so other fields may be null.
     */
    public record NeighborSite(String variant, Integer network, Integer site, String model,
                               Integer logicalChannelNumber, Long downlink, Long uplink,
                               Boolean networkConnectionActive, Integer confirmedPriority,
                               Integer adjacentPriority)
    {
    }
}
