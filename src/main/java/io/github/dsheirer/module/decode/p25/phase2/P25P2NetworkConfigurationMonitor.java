/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.module.decode.p25.phase2;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.P25FrequencyBandValidator;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.AdjacentStatusBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.AdjacentStatusBroadcastExtendedExplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.AdjacentStatusBroadcastImplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.FrequencyBandUpdate;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.FrequencyBandUpdateTDMAAbbreviated;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.FrequencyBandUpdateTDMAExtended;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.FrequencyBandUpdateVUHF;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructure;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.NetworkStatusBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.NetworkStatusBroadcastImplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.RfssStatusBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.RfssStatusBroadcastImplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.SNDCPDataChannelAnnouncement;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.SecondaryControlChannelBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.SecondaryControlChannelBroadcastImplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.SynchronizationBroadcast;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.SystemServiceBroadcast;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationStabilizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks the network configuration details of a P25 Phase 2 network from the broadcast messages
 */
public class P25P2NetworkConfigurationMonitor
{
    private final static Logger mLog = LoggerFactory.getLogger(P25P2NetworkConfigurationMonitor.class);

    private Map<Integer,IFrequencyBand> mFrequencyBandMap = new HashMap<>();

    //Network Status Messages
    private NetworkStatusBroadcastImplicit mNetworkStatusBroadcastImplicit;
    private NetworkStatusBroadcastExplicit mNetworkStatusBroadcastExplicit;
    private MacMessage mSynchronizationBroadcastMessage;
    private SynchronizationBroadcast mSynchronizationBroadcast;

    //Current Site Status Messages
    private RfssStatusBroadcastImplicit mRFSSStatusBroadcastImplicit;
    private RfssStatusBroadcastExplicit mRFSSStatusBroadcastExplicit;

    //Current Site Secondary Control Channels
    private Map<String,IChannelDescriptor> mSecondaryControlChannels = new TreeMap<>();

    //SNDCP Data Channel
    private SNDCPDataChannelAnnouncement mSNDCPDataChannelAnnouncement;

    //Current Site Services
    private SystemServiceBroadcast mSystemServiceBroadcast;
    private P25NetworkConfigurationStabilizer mNetworkConfigurationStabilizer;

    //Neighbor Sites
    private Map<Integer, AdjacentStatusBroadcastImplicit> mNeighborSitesAbbreviated = new HashMap<>();
    private Map<Integer, AdjacentStatusBroadcastExplicit> mNeighborSitesExtended = new HashMap<>();
    private Map<Integer, AdjacentStatusBroadcastExtendedExplicit> mNeighborSitesExtendedExplicit = new HashMap<>();

    /**
     * Constructs an instance.
     */
    public P25P2NetworkConfigurationMonitor()
    {
        this(new P25NetworkConfigurationStabilizer("P25_PHASE_2"));
    }

    /**
     * Constructs an instance.
     *
     * @param stabilizer shared P25 fact stabilizer for UI and metadata consumers
     */
    public P25P2NetworkConfigurationMonitor(P25NetworkConfigurationStabilizer stabilizer)
    {
        mNetworkConfigurationStabilizer = stabilizer != null ? stabilizer :
            new P25NetworkConfigurationStabilizer("P25_PHASE_2");
    }

    /**
     * Formats the identifier with an appended hexadecimal value when the identifier is an integer
     * @param identifier to format
     * @param width of the hex value with zero pre-padding
     * @return formatted identifier
     */
    private String format(Identifier identifier, int width)
    {
        if(identifier.getValue() instanceof Integer)
        {
            String hex = StringUtils.leftPad(Integer.toHexString((Integer)identifier.getValue()), width, '0');

            return hex.toUpperCase() + "[" + identifier.getValue() + "]";
        }
        else
        {
            return identifier.toString();
        }
    }

    /**
     * Processes network configuration messages.
     *
     * Note: message is expected to be valid (ie message.isValid() = true)
     */
    public P25NetworkConfigurationSnapshot processMacMessage(MacMessage message)
    {
        MacStructure mac = message.getMacStructure();

        switch((mac.getOpcode()))
        {
            case PHASE1_70_SYNCHRONIZATION_BROADCAST:
                if(mac instanceof SynchronizationBroadcast synchronizationBroadcast)
                {
                    mSynchronizationBroadcastMessage = message;
                    mSynchronizationBroadcast = synchronizationBroadcast;
                }
                break;
            case PHASE1_73_IDENTIFIER_UPDATE_TDMA_ABBREVIATED:
                if(mac instanceof FrequencyBandUpdateTDMAAbbreviated tdma)
                {
                    return processFrequencyBand(tdma);
                }
                break;
            case PHASE1_74_IDENTIFIER_UPDATE_V_UHF:
                if(mac instanceof FrequencyBandUpdateVUHF vhf)
                {
                    return processFrequencyBand(vhf);
                }
                break;
            case PHASE1_78_SYSTEM_SERVICE_BROADCAST:
                if(mac instanceof SystemServiceBroadcast ssb)
                {
                    mSystemServiceBroadcast = ssb;
                }
                break;
            case PHASE1_79_SECONDARY_CONTROL_CHANNEL_BROADCAST_IMPLICIT:
                if(mac instanceof SecondaryControlChannelBroadcastImplicit sccba)
                {
                    List<P25NetworkConfigurationSnapshot.Channel> channels = new ArrayList<>();

                    for(IChannelDescriptor channel: sccba.getChannels())
                    {
                        addSecondaryControlChannel(channels, channel);
                    }

                    return observation(null, null, channels, Collections.emptyList(), Collections.emptyList());
                }
                break;
            case PHASE1_7A_RFSS_STATUS_BROADCAST_IMPLICIT:
                if(mac instanceof RfssStatusBroadcastImplicit rsbe)
                {
                    mRFSSStatusBroadcastImplicit = rsbe;
                    return observation(null, getCurrentSiteSnapshot(rsbe),
                        getChannelSnapshots("primary_control", rsbe.getChannel()), Collections.emptyList(),
                        Collections.emptyList());
                }
                break;
            case PHASE1_7B_NETWORK_STATUS_BROADCAST_IMPLICIT:
                if(mac instanceof NetworkStatusBroadcastImplicit nsbe)
                {
                    mNetworkStatusBroadcastImplicit = nsbe;
                    return observation(getNetworkSnapshot(nsbe), null, Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList());
                }
                break;
            case PHASE1_7C_ADJACENT_STATUS_BROADCAST_IMPLICIT:
                if(mac instanceof AdjacentStatusBroadcastImplicit asba)
                {
                    mNeighborSitesAbbreviated.put((int)asba.getSite().getValue(), asba);
                    return observation(null, null, Collections.emptyList(), List.of(getNeighborSiteSnapshot(asba)),
                        Collections.emptyList());
                }
                break;
            case PHASE1_7D_IDENTIFIER_UPDATE:
                if(mac instanceof FrequencyBandUpdate band)
                {
                    return processFrequencyBand(band);
                }
                break;
            case PHASE1_D6_SNDCP_DATA_CHANNEL_ANNOUNCEMENT:
                if(mac instanceof SNDCPDataChannelAnnouncement s)
                {
                    mSNDCPDataChannelAnnouncement = s;
                    return observation(null, null, getChannelSnapshots("fdma_data", s.getChannel()),
                        Collections.emptyList(), Collections.emptyList());
                }
                break;
            case PHASE1_E9_SECONDARY_CONTROL_CHANNEL_BROADCAST_EXPLICIT:
                if(mac instanceof SecondaryControlChannelBroadcastExplicit sccbe)
                {
                    List<P25NetworkConfigurationSnapshot.Channel> channels = new ArrayList<>();

                    for(IChannelDescriptor channel: sccbe.getChannels())
                    {
                        addSecondaryControlChannel(channels, channel);
                    }

                    return observation(null, null, channels, Collections.emptyList(), Collections.emptyList());
                }
                break;
            case PHASE1_F3_IDENTIFIER_UPDATE_TDMA_EXTENDED:
                if(mac instanceof FrequencyBandUpdateTDMAExtended tdma)
                {
                    return processFrequencyBand(tdma);
                }
                break;
            case PHASE1_FA_RFSS_STATUS_BROADCAST_EXPLICIT:
                if(mac instanceof RfssStatusBroadcastExplicit rsbe)
                {
                    mRFSSStatusBroadcastExplicit = rsbe;
                    return observation(null, getCurrentSiteSnapshot(rsbe),
                        getChannelSnapshots("primary_control", rsbe.getChannel()), Collections.emptyList(),
                        Collections.emptyList());
                }
                break;
            case PHASE1_FB_NETWORK_STATUS_BROADCAST_EXPLICIT:
                if(mac instanceof NetworkStatusBroadcastExplicit nsbe)
                {
                    mNetworkStatusBroadcastExplicit = nsbe;
                    return observation(getNetworkSnapshot(nsbe), null, Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList());
                }
                break;
            case PHASE1_FC_ADJACENT_STATUS_BROADCAST_EXPLICIT:
                if(mac instanceof AdjacentStatusBroadcastExplicit asbe)
                {
                    mNeighborSitesExtended.put((int)asbe.getSite().getValue(), asbe);
                    return observation(null, null, Collections.emptyList(), List.of(getNeighborSiteSnapshot(asbe)),
                        Collections.emptyList());
                }
            case PHASE1_FE_ADJACENT_STATUS_BROADCAST_EXTENDED_EXPLICIT:
                if(mac instanceof AdjacentStatusBroadcastExtendedExplicit a)
                {
                    mNeighborSitesExtendedExplicit.put(a.getSite().getValue(), a);
                    return observation(null, null, Collections.emptyList(), List.of(getNeighborSiteSnapshot(a)),
                        Collections.emptyList());
                }
                break;
        }

        return null;
    }

    public void reset()
    {
        mFrequencyBandMap.clear();
        mNetworkStatusBroadcastImplicit = null;
        mNetworkStatusBroadcastExplicit = null;
        mSynchronizationBroadcastMessage = null;
        mSynchronizationBroadcast = null;
        mRFSSStatusBroadcastImplicit = null;
        mRFSSStatusBroadcastExplicit = null;
        mSecondaryControlChannels.clear();
        mSNDCPDataChannelAnnouncement = null;
        mSystemServiceBroadcast = null;
        mNeighborSitesAbbreviated.clear();
        mNeighborSitesExtended.clear();
        mNeighborSitesExtendedExplicit.clear();
        mNetworkConfigurationStabilizer.reset();
    }

    /**
     * Current-site primary and secondary control channel downlink frequencies.
     */
    public Set<Long> getCurrentSiteControlFrequencies()
    {
        Set<Long> frequencies = new TreeSet<>();

        if(mRFSSStatusBroadcastImplicit != null)
        {
            addFrequency(frequencies, mRFSSStatusBroadcastImplicit.getChannel());
        }
        else if(mRFSSStatusBroadcastExplicit != null)
        {
            addFrequency(frequencies, mRFSSStatusBroadcastExplicit.getChannel());
        }

        for(IChannelDescriptor channel: mSecondaryControlChannels.values())
        {
            addFrequency(frequencies, channel);
        }

        return frequencies;
    }

    private void addFrequency(Set<Long> frequencies, IChannelDescriptor channel)
    {
        if(P25FrequencyBandValidator.isResolvedChannel(channel))
        {
            frequencies.add(channel.getDownlinkFrequency());
        }
    }

    /**
     * Structured network configuration snapshot for external telemetry integrations.
     */
    public P25NetworkConfigurationSnapshot getSnapshot()
    {
        P25NetworkConfigurationSnapshot.Network network = getNetworkSnapshot();
        List<P25NetworkConfigurationSnapshot.Channel> channels = new ArrayList<>();
        P25NetworkConfigurationSnapshot.CurrentSite currentSite = getCurrentSiteSnapshot(channels);

        if(mSNDCPDataChannelAnnouncement != null)
        {
            addChannelSnapshot(channels, "fdma_data", mSNDCPDataChannelAnnouncement.getChannel());
        }

        for(IChannelDescriptor secondaryControlChannel: mSecondaryControlChannels.values())
        {
            addChannelSnapshot(channels, "secondary_control", secondaryControlChannel);
        }

        return new P25NetworkConfigurationSnapshot("P25_PHASE_2", network, currentSite, channels,
            getNeighborSiteSnapshots(), getFrequencyBandSnapshots(), Collections.emptyList(), Collections.emptyList());
    }

    private P25NetworkConfigurationSnapshot observation(P25NetworkConfigurationSnapshot.Network network,
                                                        P25NetworkConfigurationSnapshot.CurrentSite currentSite,
                                                        List<P25NetworkConfigurationSnapshot.Channel> channels,
                                                        List<P25NetworkConfigurationSnapshot.NeighborSite> neighborSites,
                                                        List<P25NetworkConfigurationSnapshot.FrequencyBand> frequencyBands)
    {
        return new P25NetworkConfigurationSnapshot("P25_PHASE_2", network, currentSite, channels, neighborSites,
            frequencyBands, Collections.emptyList(), Collections.emptyList());
    }

    private P25NetworkConfigurationSnapshot.Network getNetworkSnapshot()
    {
        if(mNetworkStatusBroadcastImplicit != null)
        {
            return getNetworkSnapshot(mNetworkStatusBroadcastImplicit);
        }
        else if(mNetworkStatusBroadcastExplicit != null)
        {
            return getNetworkSnapshot(mNetworkStatusBroadcastExplicit);
        }

        return null;
    }

    private P25NetworkConfigurationSnapshot.Network getNetworkSnapshot(
        NetworkStatusBroadcastImplicit networkStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.Network(intValue(networkStatusBroadcast.getWACN()),
            intValue(networkStatusBroadcast.getSystem()), intValue(networkStatusBroadcast.getNAC()),
            intValue(networkStatusBroadcast.getLRA()));
    }

    private P25NetworkConfigurationSnapshot.Network getNetworkSnapshot(
        NetworkStatusBroadcastExplicit networkStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.Network(intValue(networkStatusBroadcast.getWACN()),
            intValue(networkStatusBroadcast.getSystem()), intValue(networkStatusBroadcast.getNAC()),
            intValue(networkStatusBroadcast.getLRA()));
    }

    private P25NetworkConfigurationSnapshot.CurrentSite getCurrentSiteSnapshot(
        List<P25NetworkConfigurationSnapshot.Channel> channels)
    {
        if(mRFSSStatusBroadcastImplicit != null)
        {
            addChannelSnapshot(channels, "primary_control", mRFSSStatusBroadcastImplicit.getChannel());
            return getCurrentSiteSnapshot(mRFSSStatusBroadcastImplicit);
        }
        else if(mRFSSStatusBroadcastExplicit != null)
        {
            addChannelSnapshot(channels, "primary_control", mRFSSStatusBroadcastExplicit.getChannel());
            return getCurrentSiteSnapshot(mRFSSStatusBroadcastExplicit);
        }

        return null;
    }

    private P25NetworkConfigurationSnapshot.CurrentSite getCurrentSiteSnapshot(
        RfssStatusBroadcastImplicit rfssStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.CurrentSite(intValue(rfssStatusBroadcast.getSystem()), null,
            intValue(rfssStatusBroadcast.getRFSS()), intValue(rfssStatusBroadcast.getSite()),
            intValue(rfssStatusBroadcast.getLRA()), null);
    }

    private P25NetworkConfigurationSnapshot.CurrentSite getCurrentSiteSnapshot(
        RfssStatusBroadcastExplicit rfssStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.CurrentSite(intValue(rfssStatusBroadcast.getSystem()), null,
            intValue(rfssStatusBroadcast.getRFSS()), intValue(rfssStatusBroadcast.getSite()),
            intValue(rfssStatusBroadcast.getLRA()), null);
    }

    private List<P25NetworkConfigurationSnapshot.NeighborSite> getNeighborSiteSnapshots()
    {
        List<P25NetworkConfigurationSnapshot.NeighborSite> neighbors = new ArrayList<>();
        Set<Integer> sites = new TreeSet<>();
        sites.addAll(mNeighborSitesAbbreviated.keySet());
        sites.addAll(mNeighborSitesExtended.keySet());
        sites.addAll(mNeighborSitesExtendedExplicit.keySet());

        for(Integer site: sites)
        {
            if(mNeighborSitesAbbreviated.containsKey(site))
            {
                AdjacentStatusBroadcastImplicit asb = mNeighborSitesAbbreviated.get(site);
                neighbors.add(getNeighborSiteSnapshot(asb));
            }
            else if(mNeighborSitesExtended.containsKey(site))
            {
                AdjacentStatusBroadcastExplicit asb = mNeighborSitesExtended.get(site);
                neighbors.add(getNeighborSiteSnapshot(asb));
            }
            else if(mNeighborSitesExtendedExplicit.containsKey(site))
            {
                AdjacentStatusBroadcastExtendedExplicit asb = mNeighborSitesExtendedExplicit.get(site);
                neighbors.add(getNeighborSiteSnapshot(asb));
            }
        }

        return neighbors;
    }

    private P25NetworkConfigurationSnapshot.NeighborSite getNeighborSiteSnapshot(
        AdjacentStatusBroadcastImplicit adjacentStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.NeighborSite(intValue(adjacentStatusBroadcast.getSystem()), null,
            intValue(adjacentStatusBroadcast.getRFSS()), intValue(adjacentStatusBroadcast.getSite()),
            intValue(adjacentStatusBroadcast.getLRA()), channelName(adjacentStatusBroadcast.getChannel()),
            downlink(adjacentStatusBroadcast.getChannel()), uplink(adjacentStatusBroadcast.getChannel()),
            String.valueOf(adjacentStatusBroadcast.getSiteFlags()));
    }

    private P25NetworkConfigurationSnapshot.NeighborSite getNeighborSiteSnapshot(
        AdjacentStatusBroadcastExplicit adjacentStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.NeighborSite(intValue(adjacentStatusBroadcast.getSystem()), null,
            intValue(adjacentStatusBroadcast.getRFSS()), intValue(adjacentStatusBroadcast.getSite()),
            intValue(adjacentStatusBroadcast.getLRA()), channelName(adjacentStatusBroadcast.getChannel()),
            downlink(adjacentStatusBroadcast.getChannel()), uplink(adjacentStatusBroadcast.getChannel()),
            String.valueOf(adjacentStatusBroadcast.getSiteFlags()));
    }

    private P25NetworkConfigurationSnapshot.NeighborSite getNeighborSiteSnapshot(
        AdjacentStatusBroadcastExtendedExplicit adjacentStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.NeighborSite(intValue(adjacentStatusBroadcast.getSystem()), null,
            intValue(adjacentStatusBroadcast.getRFSS()), intValue(adjacentStatusBroadcast.getSite()),
            intValue(adjacentStatusBroadcast.getLRA()), channelName(adjacentStatusBroadcast.getChannel()),
            downlink(adjacentStatusBroadcast.getChannel()), uplink(adjacentStatusBroadcast.getChannel()),
            String.valueOf(adjacentStatusBroadcast.getSiteFlags()));
    }

    private List<P25NetworkConfigurationSnapshot.FrequencyBand> getFrequencyBandSnapshots()
    {
        return mFrequencyBandMap.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(entry -> getFrequencyBandSnapshot(entry.getValue())).toList();
    }

    private P25NetworkConfigurationSnapshot.FrequencyBand getFrequencyBandSnapshot(IFrequencyBand band)
    {
        return new P25NetworkConfigurationSnapshot.FrequencyBand(band.getIdentifier(), band.isTDMA(),
            band.getBaseFrequency(), band.getBandwidth(), band.getChannelSpacing(), band.getTransmitOffset(),
            band.getTimeslotCount());
    }

    private P25NetworkConfigurationSnapshot.Channel getChannelSnapshot(String role, IChannelDescriptor channel)
    {
        if(!P25FrequencyBandValidator.isResolvedChannel(channel))
        {
            return null;
        }

        return new P25NetworkConfigurationSnapshot.Channel(role, channelName(channel), downlink(channel), uplink(channel),
            channel != null ? channel.isTDMAChannel() : null, channel != null ? channel.getTimeslotCount() : null);
    }

    private List<P25NetworkConfigurationSnapshot.Channel> getChannelSnapshots(String role, IChannelDescriptor channel)
    {
        List<P25NetworkConfigurationSnapshot.Channel> channels = new ArrayList<>();
        addChannelSnapshot(channels, role, channel);
        return channels;
    }

    private void addChannelSnapshot(List<P25NetworkConfigurationSnapshot.Channel> channels, String role,
                                    IChannelDescriptor channel)
    {
        P25NetworkConfigurationSnapshot.Channel snapshot = getChannelSnapshot(role, channel);

        if(snapshot != null)
        {
            channels.add(snapshot);
        }
    }

    private void addSecondaryControlChannel(List<P25NetworkConfigurationSnapshot.Channel> channels,
                                            IChannelDescriptor channel)
    {
        if(P25FrequencyBandValidator.isResolvedChannel(channel))
        {
            mSecondaryControlChannels.put(channel.toString(), channel);
            addChannelSnapshot(channels, "secondary_control", channel);
        }
    }

    private P25NetworkConfigurationSnapshot processFrequencyBand(IFrequencyBand frequencyBand)
    {
        P25FrequencyBandValidator.RegistrationResult result =
            P25FrequencyBandValidator.register(mFrequencyBandMap, frequencyBand);

        if(result.replaced())
        {
            mLog.warn("P25 P2 network frequency band replacing existing:{} with candidate:{}",
                P25FrequencyBandValidator.describe(result.existing()),
                P25FrequencyBandValidator.describe(frequencyBand));
        }
        else if(!result.accepted())
        {
            mLog.warn("P25 P2 network frequency band rejected {} correctedBits:{} - {}{}",
                P25FrequencyBandValidator.describe(frequencyBand),
                P25FrequencyBandValidator.getCorrectedBitCount(frequencyBand),
                result.rejectReason().getDescription(),
                result.existing() != null ? " existing:" + P25FrequencyBandValidator.describe(result.existing()) : "");
            return null;
        }

        return observation(null, null, Collections.emptyList(), Collections.emptyList(),
            List.of(getFrequencyBandSnapshot(frequencyBand)));
    }

    private String channelName(IChannelDescriptor channel)
    {
        return channel != null ? channel.toString() : null;
    }

    private Long downlink(IChannelDescriptor channel)
    {
        return channel != null ? channel.getDownlinkFrequency() : null;
    }

    private Long uplink(IChannelDescriptor channel)
    {
        return channel != null ? channel.getUplinkFrequency() : null;
    }

    private Integer intValue(Object value)
    {
        if(value instanceof Identifier identifier)
        {
            return intValue(identifier.getValue());
        }
        else if(value instanceof Number number)
        {
            return number.intValue();
        }

        return null;
    }

    public String getActivitySummary()
    {
        StringBuilder sb = new StringBuilder();

        sb.append("Activity Summary - Decoder:P25 Phase 2");

        sb.append("\n\nNetwork\n");
        if(mNetworkStatusBroadcastImplicit != null)
        {
            sb.append("  WACN:").append(format(mNetworkStatusBroadcastImplicit.getWACN(), 5));
            sb.append(" SYSTEM:").append(format(mNetworkStatusBroadcastImplicit.getSystem(), 3));
            sb.append(" NAC:").append(format(mNetworkStatusBroadcastImplicit.getNAC(), 3));
            sb.append(" LRA:").append(format(mNetworkStatusBroadcastImplicit.getLRA(), 2));
        }
        else if(mNetworkStatusBroadcastExplicit != null)
        {
            sb.append("  WACN:").append(format(mNetworkStatusBroadcastExplicit.getWACN(), 5));
            sb.append(" SYSTEM:").append(format(mNetworkStatusBroadcastExplicit.getSystem(), 3));
            sb.append(" NAC:").append(format(mNetworkStatusBroadcastExplicit.getNAC(), 3));
            sb.append(" LRA:").append(format(mNetworkStatusBroadcastExplicit.getLRA(), 2));
        }
        else
        {
            sb.append("  UNKNOWN");
        }

        appendSynchronizationBroadcast(sb);

        sb.append("\n\nCurrent Site\n");
        if(mRFSSStatusBroadcastImplicit != null)
        {
            sb.append("  SYSTEM:").append(format(mRFSSStatusBroadcastImplicit.getSystem(), 3));
            sb.append(" RFSS:").append(format(mRFSSStatusBroadcastImplicit.getRFSS(), 2));
            sb.append(" SITE:").append(format(mRFSSStatusBroadcastImplicit.getSite(), 2));
            sb.append(" LRA:").append(format(mRFSSStatusBroadcastImplicit.getLRA(), 2));
            sb.append("  PRI CONTROL CHANNEL:").append(mRFSSStatusBroadcastImplicit.getChannel());
            sb.append(" DOWNLINK:").append(mRFSSStatusBroadcastImplicit.getChannel().getDownlinkFrequency());
            sb.append(" UPLINK:").append(mRFSSStatusBroadcastImplicit.getChannel().getUplinkFrequency()).append("\n");
        }
        else if(mRFSSStatusBroadcastExplicit != null)
        {
            sb.append("  SYSTEM:").append(format(mRFSSStatusBroadcastExplicit.getSystem(), 3));
            sb.append(" RFSS:").append(format(mRFSSStatusBroadcastExplicit.getRFSS(), 2));
            sb.append(" SITE:").append(format(mRFSSStatusBroadcastExplicit.getSite(), 2));
            sb.append(" LRA:").append(format(mRFSSStatusBroadcastExplicit.getLRA(), 2));
            sb.append("  PRI CONTROL CHANNEL:").append(mRFSSStatusBroadcastExplicit.getChannel());
            sb.append(" DOWNLINK:").append(mRFSSStatusBroadcastExplicit.getChannel().getDownlinkFrequency());
            sb.append(" UPLINK:").append(mRFSSStatusBroadcastExplicit.getChannel().getUplinkFrequency()).append("\n");
        }
        else
        {
            sb.append("  UNKNOWN\n");
        }

        if(mSNDCPDataChannelAnnouncement != null)
        {
            sb.append(" DATA CHANNEL:").append(mSNDCPDataChannelAnnouncement.getChannel()).append("\n");
        }

        if(!mSecondaryControlChannels.isEmpty())
        {
            mSecondaryControlChannels.entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByKey())
                    .filter(Objects::nonNull)
                    .forEach(entry -> {
                        sb.append("  SEC CONTROL CHANNEL:").append(entry.getValue());
                        sb.append(" DOWNLINK:").append(entry.getValue().getDownlinkFrequency());
                        sb.append(" UPLINK:").append(entry.getValue().getUplinkFrequency()).append("\n");
                    });
        }

        if(mSystemServiceBroadcast != null)
        {
            sb.append("  AVAILABLE SERVICES:").append(mSystemServiceBroadcast.getAvailableServices());
            sb.append("  SUPPORTED SERVICES:").append(mSystemServiceBroadcast.getSupportedServices());
        }


        sb.append("\nNeighbor Sites\n");
        Set<Integer> sites = new TreeSet<>();
        sites.addAll(mNeighborSitesAbbreviated.keySet());
        sites.addAll(mNeighborSitesExtended.keySet());
        sites.addAll(mNeighborSitesExtendedExplicit.keySet());

        if(sites.isEmpty())
        {
            sb.append("  UNKNOWN");
        }
        else
        {
            sites.stream()
                    .sorted()
                    .forEach(site -> {
                        if(mNeighborSitesAbbreviated.containsKey(site))
                        {
                            AdjacentStatusBroadcastImplicit asb = mNeighborSitesAbbreviated.get(site);
                            sb.append("  SYSTEM:").append(format(asb.getSystem(), 3));
                            sb.append(" RFSS:").append(format(asb.getRFSS(), 2));
                            sb.append(" SITE:").append(format(asb.getSite(), 2));
                            sb.append(" LRA:").append(format(asb.getLRA(), 2));
                            sb.append(" CHANNEL:").append(asb.getChannel());
                            sb.append(" DOWNLINK:").append(asb.getChannel().getDownlinkFrequency());
                            sb.append(" UPLINK:").append(asb.getChannel().getUplinkFrequency());
                            sb.append(" STATUS:").append(asb.getSiteFlags()).append("\n");
                        }
                        else if(mNeighborSitesExtended.containsKey(site))
                        {
                            AdjacentStatusBroadcastExplicit asb = mNeighborSitesExtended.get(site);
                            sb.append("  SYSTEM:").append(format(asb.getSystem(), 3));
                            sb.append(" RFSS:").append(format(asb.getRFSS(), 2));
                            sb.append(" SITE:").append(format(asb.getSite(), 2));
                            sb.append(" LRA:").append(format(asb.getLRA(), 2));
                            sb.append(" CHANNEL:").append(asb.getChannel());
                            sb.append(" DOWNLINK:").append(asb.getChannel().getDownlinkFrequency());
                            sb.append(" UPLINK:").append(asb.getChannel().getUplinkFrequency());
                            sb.append(" STATUS:").append(asb.getSiteFlags()).append("\n");
                        }
                        else if(mNeighborSitesExtendedExplicit.containsKey(site))
                        {
                            AdjacentStatusBroadcastExtendedExplicit asb = mNeighborSitesExtendedExplicit.get(site);
                            sb.append("  SYSTEM:").append(format(asb.getSystem(), 3));
                            sb.append(" RFSS:").append(format(asb.getRFSS(), 2));
                            sb.append(" SITE:").append(format(asb.getSite(), 2));
                            sb.append(" LRA:").append(format(asb.getLRA(), 2));
                            sb.append(" CHANNEL:").append(asb.getChannel());
                            sb.append(" DOWNLINK:").append(asb.getChannel().getDownlinkFrequency());
                            sb.append(" UPLINK:").append(asb.getChannel().getUplinkFrequency());
                            sb.append(" STATUS:").append(asb.getSiteFlags()).append("\n");
                        }
                        else
                        {
                            sb.append(" SITE:").append(site).append(" NOT FOUND IN NEIGHBOR SITE MAPS\n");
                        }
                    });
        }

        sb.append("\nFrequency Bands\n");
        if(mFrequencyBandMap.isEmpty())
        {
            sb.append("  UNKNOWN");
        }
        else
        {
            mFrequencyBandMap.entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sb.append("  ").append(formatFrequencyBand(entry.getValue())).append("\n"));
        }

        return sb.toString();
    }

    /**
     * Appends the last observed synchronization broadcast timing details for debugging over-the-air time.
     */
    private void appendSynchronizationBroadcast(StringBuilder sb)
    {
        sb.append("\n\nLast Sync Broadcast\n");

        if(mSynchronizationBroadcast != null && mSynchronizationBroadcastMessage != null)
        {
            sb.append("  SYSTEM UTC:").append(Instant.ofEpochMilli(mSynchronizationBroadcast.getSystemTime()));
            sb.append("  MESSAGE TIME:").append(Instant.ofEpochMilli(mSynchronizationBroadcastMessage.getTimestamp()));
            sb.append("\n  OFFSET MS:")
                    .append(mSynchronizationBroadcast.getSystemTime() - mSynchronizationBroadcastMessage.getTimestamp());
            sb.append("  USABLE FOR CLOCK:")
                    .append(!mSynchronizationBroadcast.isSystemTimeNotLockedToExternalReference() &&
                            mSynchronizationBroadcast.isMicroslotsLockedToMinuteRollover());
            sb.append("  SYSTEM LOCKED:")
                    .append(!mSynchronizationBroadcast.isSystemTimeNotLockedToExternalReference());
            sb.append("  MICROSLOTS LOCKED:")
                    .append(mSynchronizationBroadcast.isMicroslotsLockedToMinuteRollover());
            sb.append("\n  MICROSLOTS:").append(mSynchronizationBroadcast.getMicroSlots());
            sb.append("  MS INTO MINUTE:").append(mSynchronizationBroadcast.getMilliSeconds());
            sb.append("  LOCAL OFFSET VALID:").append(mSynchronizationBroadcast.isValidLocalTimeOffset());
            sb.append("  LOCAL OFFSET:").append(mSynchronizationBroadcast.getTimeZone().getID());
            sb.append("\n  RAW:").append(mSynchronizationBroadcastMessage.getMessage().toHexString());
        }
        else
        {
            sb.append("  NONE OBSERVED");
        }
    }

    /**
     * Formats a frequency band
     */
    private String formatFrequencyBand(IFrequencyBand band)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("BAND:").append(band.getIdentifier());
        sb.append(" ").append(band.isTDMA() ? "TDMA" : "FDMA");
        sb.append(" BASE:").append(band.getBaseFrequency());
        sb.append(" BANDWIDTH:").append(band.getBandwidth());
        sb.append(" SPACING:").append(band.getChannelSpacing());
        sb.append(" TRANSMIT OFFSET:").append(band.getTransmitOffset());

        if(band.isTDMA())
        {
            sb.append(" TIMESLOTS:").append(band.getTimeslotCount());
        }

        return sb.toString();
    }

}
