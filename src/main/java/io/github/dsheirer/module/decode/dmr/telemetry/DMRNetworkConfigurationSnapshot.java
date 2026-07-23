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
                          Long downlink, Long uplink, Set<ChannelRole> roles, FrequencySource frequencySource)
    {
        public Channel
        {
            roles = roles == null || roles.isEmpty() ? Set.of(ChannelRole.OBSERVED) : Set.copyOf(roles);
            frequencySource = frequencySource == null ? FrequencySource.UNRESOLVED : frequencySource;
        }

        /**
         * Convenience constructor for an observation with one channel use.
         */
        public Channel(String descriptor, Integer logicalChannelNumber, Integer timeslot,
                       Long downlink, Long uplink, ChannelRole role, FrequencySource frequencySource)
        {
            this(descriptor, logicalChannelNumber, timeslot, downlink, uplink,
                Set.of(role == null ? ChannelRole.OBSERVED : role), frequencySource);
        }

        /**
         * Compatibility constructor for snapshots that do not specify channel use or frequency provenance.
         */
        public Channel(String descriptor, Integer logicalChannelNumber, Integer timeslot,
                       Long downlink, Long uplink)
        {
            this(descriptor, logicalChannelNumber, timeslot, downlink, uplink,
                ChannelRole.OBSERVED, FrequencySource.UNRESOLVED);
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
