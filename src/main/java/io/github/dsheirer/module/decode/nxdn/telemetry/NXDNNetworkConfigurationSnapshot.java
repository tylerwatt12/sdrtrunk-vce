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

package io.github.dsheirer.module.decode.nxdn.telemetry;

import io.github.dsheirer.metadata.site.SiteMetadataSnapshot;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;

/**
 * Structured NXDN Type-C or Type-D network configuration learned over the air.
 */
public record NXDNNetworkConfigurationSnapshot(String decoder, String variant, Integer ran,
                                               Location currentLocation, Integer typeDSite,
                                               String typeDSiteType, Station station,
                                               SiteConfiguration siteConfiguration,
                                               List<String> services, List<String> restrictions,
                                               FailureStatus failureStatus, List<Channel> controlChannels,
                                               List<NeighborSite> neighborSites, Integer currentRepeater,
                                               String repeaterStatus, List<Integer> observedRepeaters)
    implements SiteMetadataSnapshot
{
    public NXDNNetworkConfigurationSnapshot
    {
        services = services == null ? List.of() : List.copyOf(services);
        restrictions = restrictions == null ? List.of() : List.copyOf(restrictions);
        controlChannels = controlChannels == null ? List.of() : List.copyOf(controlChannels);
        neighborSites = neighborSites == null ? List.of() : List.copyOf(neighborSites);
        observedRepeaters = observedRepeaters == null ? List.of() : List.copyOf(observedRepeaters);
    }

    @Override
    public Protocol protocol()
    {
        return Protocol.NXDN;
    }

    @Override
    public boolean isUseful()
    {
        return currentLocation != null || typeDSite != null || station != null || siteConfiguration != null ||
            !services.isEmpty() || failureStatus != null || !controlChannels.isEmpty() ||
            !neighborSites.isEmpty() || currentRepeater != null || !observedRepeaters.isEmpty();
    }

    /**
     * NXDN location identity. Type-C uses category/system/site; Type-D uses integrator/system/site.
     */
    public record Location(String category, Integer system, Integer site, Integer integrator)
    {
    }

    public record Station(String identifier, Boolean characterCrcValid, String option)
    {
    }

    public record SiteConfiguration(Integer version, Integer advertisedNeighborCount, String channelAccess,
                                    Integer bcchFramesPerSuperframe, Integer groupsPerRcch,
                                    Integer pagingFrames, Integer multipurposeFrames,
                                    Integer groupIterationsPerSuperframe)
    {
    }

    public record FailureStatus(Location location, String callTimer)
    {
    }

    /**
     * A control channel advertised using channel lookup or direct-frequency assignment.
     */
    public record Channel(String role, String allocation, Integer channelNumber,
                          Integer outboundChannelNumber, Integer inboundChannelNumber,
                          String bandwidth, Long downlink, Long uplink, String notification)
    {
    }

    /**
     * Type-C or Type-D neighbor. Type-D neighbors do not advertise a control channel here.
     */
    public record NeighborSite(String variant, Integer id, Location location, Channel channel,
                               Boolean isolated)
    {
    }
}
