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
import java.util.Map;
import java.util.Objects;

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
                                               String repeaterStatus, List<Integer> observedRepeaters,
                                               Map<Integer, Long> observedRepeaterTimestamps)
    implements SiteMetadataSnapshot
{
    public NXDNNetworkConfigurationSnapshot
    {
        services = services == null ? List.of() : List.copyOf(services);
        restrictions = restrictions == null ? List.of() : List.copyOf(restrictions);
        controlChannels = controlChannels == null ? List.of() : List.copyOf(controlChannels);
        neighborSites = neighborSites == null ? List.of() : List.copyOf(neighborSites);
        observedRepeaters = observedRepeaters == null ? List.of() : List.copyOf(observedRepeaters);
        observedRepeaterTimestamps = observedRepeaterTimestamps == null ?
            Map.of() : Map.copyOf(observedRepeaterTimestamps);
    }

    /**
     * Compatibility constructor for callers that do not track per-repeater observation time.
     */
    public NXDNNetworkConfigurationSnapshot(String decoder, String variant, Integer ran,
                                            Location currentLocation, Integer typeDSite,
                                            String typeDSiteType, Station station,
                                            SiteConfiguration siteConfiguration,
                                            List<String> services, List<String> restrictions,
                                            FailureStatus failureStatus, List<Channel> controlChannels,
                                            List<NeighborSite> neighborSites, Integer currentRepeater,
                                            String repeaterStatus, List<Integer> observedRepeaters)
    {
        this(decoder, variant, ran, currentLocation, typeDSite, typeDSiteType, station, siteConfiguration,
            services, restrictions, failureStatus, controlChannels, neighborSites, currentRepeater,
            repeaterStatus, observedRepeaters, Map.of());
    }

    /**
     * Last time a cumulative Type-D repeater fact was actually observed.
     */
    public long observedRepeaterTimestamp(int repeater)
    {
        return observedRepeaterTimestamps.getOrDefault(repeater, 0L);
    }

    /**
     * Per-repeater observation time is freshness telemetry, not a structural configuration change. Excluding only
     * that map keeps repeated observations on the bounded site-metadata heartbeat while a newly observed repeater ID
     * still publishes immediately through {@link #observedRepeaters()}.
     */
    @Override
    public boolean equals(Object object)
    {
        return object == this ||
            object instanceof NXDNNetworkConfigurationSnapshot other &&
                Objects.equals(decoder, other.decoder) &&
                Objects.equals(variant, other.variant) &&
                Objects.equals(ran, other.ran) &&
                Objects.equals(currentLocation, other.currentLocation) &&
                Objects.equals(typeDSite, other.typeDSite) &&
                Objects.equals(typeDSiteType, other.typeDSiteType) &&
                Objects.equals(station, other.station) &&
                Objects.equals(siteConfiguration, other.siteConfiguration) &&
                Objects.equals(services, other.services) &&
                Objects.equals(restrictions, other.restrictions) &&
                Objects.equals(failureStatus, other.failureStatus) &&
                Objects.equals(controlChannels, other.controlChannels) &&
                Objects.equals(neighborSites, other.neighborSites) &&
                Objects.equals(currentRepeater, other.currentRepeater) &&
                Objects.equals(repeaterStatus, other.repeaterStatus) &&
                Objects.equals(observedRepeaters, other.observedRepeaters);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(decoder, variant, ran, currentLocation, typeDSite, typeDSiteType, station,
            siteConfiguration, services, restrictions, failureStatus, controlChannels, neighborSites,
            currentRepeater, repeaterStatus, observedRepeaters);
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
                          String bandwidth, Long downlink, Long uplink, String notification,
                          long observedAtEpochMilliseconds)
    {
        /**
         * Compatibility constructor for callers that do not track observation time.
         */
        public Channel(String role, String allocation, Integer channelNumber,
                       Integer outboundChannelNumber, Integer inboundChannelNumber,
                       String bandwidth, Long downlink, Long uplink, String notification)
        {
            this(role, allocation, channelNumber, outboundChannelNumber, inboundChannelNumber,
                bandwidth, downlink, uplink, notification, 0);
        }

        /**
         * Observation time does not change the channel's structural configuration.
         */
        @Override
        public boolean equals(Object object)
        {
            return object == this ||
                object instanceof Channel other &&
                    Objects.equals(role, other.role) &&
                    Objects.equals(allocation, other.allocation) &&
                    Objects.equals(channelNumber, other.channelNumber) &&
                    Objects.equals(outboundChannelNumber, other.outboundChannelNumber) &&
                    Objects.equals(inboundChannelNumber, other.inboundChannelNumber) &&
                    Objects.equals(bandwidth, other.bandwidth) &&
                    Objects.equals(downlink, other.downlink) &&
                    Objects.equals(uplink, other.uplink) &&
                    Objects.equals(notification, other.notification);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(role, allocation, channelNumber, outboundChannelNumber, inboundChannelNumber,
                bandwidth, downlink, uplink, notification);
        }
    }

    /**
     * Type-C or Type-D neighbor. Type-D neighbors do not advertise a control channel here.
     */
    public record NeighborSite(String variant, Integer id, Location location, Channel channel,
                               Boolean isolated, long observedAtEpochMilliseconds)
    {
        /**
         * Compatibility constructor for callers that do not track observation time.
         */
        public NeighborSite(String variant, Integer id, Location location, Channel channel,
                            Boolean isolated)
        {
            this(variant, id, location, channel, isolated, 0);
        }

        /**
         * Observation time does not change the neighbor's structural identity or advertised state.
         */
        @Override
        public boolean equals(Object object)
        {
            return object == this ||
                object instanceof NeighborSite other &&
                    Objects.equals(variant, other.variant) &&
                    Objects.equals(id, other.id) &&
                    Objects.equals(location, other.location) &&
                    Objects.equals(channel, other.channel) &&
                    Objects.equals(isolated, other.isolated);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(variant, id, location, channel, isolated);
        }
    }
}
