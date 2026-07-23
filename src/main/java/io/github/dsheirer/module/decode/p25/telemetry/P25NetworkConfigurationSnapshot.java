/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.telemetry;

import io.github.dsheirer.metadata.site.SiteMetadataSnapshot;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;

/**
 * Structured P25 network configuration snapshot for optional external telemetry integrations.
 */
public record P25NetworkConfigurationSnapshot(String decoder, Network network, CurrentSite currentSite,
                                              List<Channel> channels, List<NeighborSite> neighborSites,
                                              List<FrequencyBand> frequencyBands,
                                              List<PatchGroup> patchGroups,
                                              List<TalkerAlias> talkerAliases,
                                              SiteStatus siteStatus,
                                              List<ForeignSystemBand> foreignSystemBands)
    implements SiteMetadataSnapshot
{
    @Override
    public Protocol protocol()
    {
        return decoder != null && decoder.contains("PHASE_2") ? Protocol.APCO25_PHASE2 : Protocol.APCO25;
    }

    /**
     * Compatibility constructor for snapshot producers that don't provide foreign-system frequency bands.
     */
    public P25NetworkConfigurationSnapshot(String decoder, Network network, CurrentSite currentSite,
                                           List<Channel> channels, List<NeighborSite> neighborSites,
                                           List<FrequencyBand> frequencyBands, List<PatchGroup> patchGroups,
                                           List<TalkerAlias> talkerAliases, SiteStatus siteStatus)
    {
        this(decoder, network, currentSite, channels, neighborSites, frequencyBands, patchGroups, talkerAliases,
            siteStatus, List.of());
    }

    /**
     * Compatibility constructor for snapshot producers that don't provide current site services/status.
     */
    public P25NetworkConfigurationSnapshot(String decoder, Network network, CurrentSite currentSite,
                                           List<Channel> channels, List<NeighborSite> neighborSites,
                                           List<FrequencyBand> frequencyBands, List<PatchGroup> patchGroups,
                                           List<TalkerAlias> talkerAliases)
    {
        this(decoder, network, currentSite, channels, neighborSites, frequencyBands, patchGroups, talkerAliases, null,
            List.of());
    }

    /**
     * Indicates if this snapshot contains enough learned over-the-air configuration to send.
     */
    public boolean isUseful()
    {
        return network != null || currentSite != null || (channels != null && !channels.isEmpty()) ||
            (neighborSites != null && !neighborSites.isEmpty()) ||
            (frequencyBands != null && !frequencyBands.isEmpty()) ||
            (foreignSystemBands != null && !foreignSystemBands.isEmpty()) || siteStatus != null;
    }

    public record Network(Integer wacn, Integer system, Integer nac, Integer lra)
    {
    }

    public record CurrentSite(Integer system, Integer nac, Integer rfss, Integer site, Integer lra,
                              Boolean activeRfssNetworkConnection)
    {
    }

    public record Channel(String role, String descriptor, Long downlink, Long uplink, Boolean tdma,
                          Integer timeslots, String callsign)
    {
        public Channel(String role, String descriptor, Long downlink, Long uplink, Boolean tdma, Integer timeslots)
        {
            this(role, descriptor, downlink, uplink, tdma, timeslots, null);
        }
    }

    /**
     * Latest values broadcast by the current site.  Null means the value has not yet been observed.
     */
    public record SiteStatus(Long broadcastClockEpochMilliseconds, Integer microSlots, Boolean dataService,
                             String dataAccess, Integer wuidLeaseMinutes, Boolean registrationService,
                             Integer mfid, Boolean voiceService)
    {
        /**
         * Applies non-null values from a newer partial broadcast to this latest-value snapshot.
         */
        public SiteStatus merge(SiteStatus newer)
        {
            if(newer == null)
            {
                return this;
            }

            return new SiteStatus(
                newer.broadcastClockEpochMilliseconds != null ? newer.broadcastClockEpochMilliseconds :
                    broadcastClockEpochMilliseconds,
                newer.microSlots != null ? newer.microSlots : microSlots,
                newer.dataService != null ? newer.dataService : dataService,
                newer.dataAccess != null ? newer.dataAccess : dataAccess,
                newer.wuidLeaseMinutes != null ? newer.wuidLeaseMinutes : wuidLeaseMinutes,
                newer.registrationService != null ? newer.registrationService : registrationService,
                newer.mfid != null ? newer.mfid : mfid,
                newer.voiceService != null ? newer.voiceService : voiceService);
        }
    }

    public record NeighborSite(Integer system, Integer nac, Integer rfss, Integer site, Integer lra,
                               String channel, Long downlink, Long uplink, String status)
    {
    }

    public record FrequencyBand(Integer band, Boolean tdma, Long base, Integer bandwidth, Long spacing,
                                Long transmitOffset, Integer timeslots)
    {
    }

    /**
     * Frequency band advertised for a foreign P25 system. Channel type is the compact over-the-air value (0-5),
     * from which access mode, bandwidth, timeslots, and voice rate can be derived without duplicating those values.
     */
    public record ForeignSystemBand(Integer wacn, Integer system, Integer band, Integer channelType,
                                    Long base, Long spacing, Long transmitOffset)
    {
    }

    public record PatchGroup(Integer patchGroup, Integer version, List<Integer> talkgroups, List<Integer> radios)
    {
    }

    public record TalkerAlias(Integer radio, String alias)
    {
    }
}
