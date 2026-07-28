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
import java.util.Objects;
import java.util.Set;

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
     * Structural metadata used for change detection without per-observation freshness timestamps.
     */
    public DMRNetworkConfigurationSnapshot withoutFreshness()
    {
        return new DMRNetworkConfigurationSnapshot(decoder, variant, network, site, brand, model, mode, channelType,
            colorCodeTimeslot1, colorCodeTimeslot2,
            channels.stream().map(channel -> channel != null ? channel.withoutFreshness() : null).toList(),
            neighborSites.stream().map(neighbor -> neighbor != null ? neighbor.withoutFreshness() : null).toList());
    }

    /**
     * How an observed channel is being used by the local site.
     */
    public enum ChannelRole
    {
        OBSERVED,
        CONTROL,
        TRAFFIC
    }

    /**
     * Source of a resolved frequency.  Most DMR messages carry only an LCN/timeslot, which SDRTrunk resolves through
     * the configured channel map.  Some multi-block Tier III messages carry an absolute frequency over the air.
     */
    public enum FrequencySource
    {
        UNRESOLVED,
        CONFIGURED_MAP,
        OVER_THE_AIR
    }

    /**
     * Observed DMR logical channel/timeslot and any resolved frequency mapping.
     */
    public record Channel(String descriptor, Integer logicalChannelNumber, Integer timeslot,
                          Long downlink, Long uplink, Set<ChannelRole> roles, FrequencySource frequencySource,
                          long observedAtEpochMilliseconds)
    {
        public Channel
        {
            roles = roles == null || roles.isEmpty() ? Set.of(ChannelRole.OBSERVED) : Set.copyOf(roles);
            frequencySource = frequencySource == null ? FrequencySource.UNRESOLVED : frequencySource;
        }

        /**
         * Compatibility constructor for callers that do not track observation time.
         */
        public Channel(String descriptor, Integer logicalChannelNumber, Integer timeslot,
                       Long downlink, Long uplink, Set<ChannelRole> roles, FrequencySource frequencySource)
        {
            this(descriptor, logicalChannelNumber, timeslot, downlink, uplink, roles, frequencySource, 0);
        }

        /**
         * Convenience constructor for an observation with one channel use.
         */
        public Channel(String descriptor, Integer logicalChannelNumber, Integer timeslot,
                       Long downlink, Long uplink, ChannelRole role, FrequencySource frequencySource)
        {
            this(descriptor, logicalChannelNumber, timeslot, downlink, uplink,
                Set.of(role == null ? ChannelRole.OBSERVED : role), frequencySource, 0);
        }

        /**
         * Convenience constructor for a timestamped observation with one channel use.
         */
        public Channel(String descriptor, Integer logicalChannelNumber, Integer timeslot,
                       Long downlink, Long uplink, ChannelRole role, FrequencySource frequencySource,
                       long observedAtEpochMilliseconds)
        {
            this(descriptor, logicalChannelNumber, timeslot, downlink, uplink,
                Set.of(role == null ? ChannelRole.OBSERVED : role), frequencySource,
                observedAtEpochMilliseconds);
        }

        /**
         * Compatibility constructor for snapshots that do not specify channel use or frequency provenance.
         */
        public Channel(String descriptor, Integer logicalChannelNumber, Integer timeslot,
                       Long downlink, Long uplink)
        {
            this(descriptor, logicalChannelNumber, timeslot, downlink, uplink,
                ChannelRole.OBSERVED, FrequencySource.UNRESOLVED, 0);
        }

        /**
         * Primary channel use retained for compatibility with callers that only need one label.
         */
        public ChannelRole role()
        {
            if(roles.contains(ChannelRole.TRAFFIC))
            {
                return ChannelRole.TRAFFIC;
            }
            else if(roles.contains(ChannelRole.CONTROL))
            {
                return ChannelRole.CONTROL;
            }

            return ChannelRole.OBSERVED;
        }

        Channel withoutFreshness()
        {
            return new Channel(descriptor, logicalChannelNumber, timeslot, downlink, uplink, roles, frequencySource,
                0);
        }

        /**
         * Observation time is telemetry freshness, not a structural configuration change. Excluding it keeps repeated
         * observations on the bounded site-metadata heartbeat instead of triggering an immediate publish for each
         * decoded message.
         */
        @Override
        public boolean equals(Object object)
        {
            return object == this ||
                object instanceof Channel other &&
                    Objects.equals(descriptor, other.descriptor) &&
                    Objects.equals(logicalChannelNumber, other.logicalChannelNumber) &&
                    Objects.equals(timeslot, other.timeslot) &&
                    Objects.equals(downlink, other.downlink) &&
                    Objects.equals(uplink, other.uplink) &&
                    Objects.equals(roles, other.roles) &&
                    frequencySource == other.frequencySource;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(descriptor, logicalChannelNumber, timeslot, downlink, uplink, roles,
                frequencySource);
        }
    }

    /**
     * DMR neighbor site. Some vendor formats only advertise a site number, so other fields may be null.
     */
    public record NeighborSite(String variant, Integer network, Integer site, String model,
                               Integer logicalChannelNumber, Long downlink, Long uplink,
                               Boolean networkConnectionActive, Integer confirmedPriority,
                               Integer adjacentPriority, long observedAtEpochMilliseconds)
    {
        /**
         * Compatibility constructor for callers that do not track observation time.
         */
        public NeighborSite(String variant, Integer network, Integer site, String model,
                            Integer logicalChannelNumber, Long downlink, Long uplink,
                            Boolean networkConnectionActive, Integer confirmedPriority,
                            Integer adjacentPriority)
        {
            this(variant, network, site, model, logicalChannelNumber, downlink, uplink,
                networkConnectionActive, confirmedPriority, adjacentPriority, 0);
        }

        NeighborSite withoutFreshness()
        {
            return new NeighborSite(variant, network, site, model, logicalChannelNumber, downlink, uplink,
                networkConnectionActive, confirmedPriority, adjacentPriority, 0);
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
                    Objects.equals(network, other.network) &&
                    Objects.equals(site, other.site) &&
                    Objects.equals(model, other.model) &&
                    Objects.equals(logicalChannelNumber, other.logicalChannelNumber) &&
                    Objects.equals(downlink, other.downlink) &&
                    Objects.equals(uplink, other.uplink) &&
                    Objects.equals(networkConnectionActive, other.networkConnectionActive) &&
                    Objects.equals(confirmedPriority, other.confirmedPriority) &&
                    Objects.equals(adjacentPriority, other.adjacentPriority);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(variant, network, site, model, logicalChannelNumber, downlink, uplink,
                networkConnectionActive, confirmedPriority, adjacentPriority);
        }
    }
}
