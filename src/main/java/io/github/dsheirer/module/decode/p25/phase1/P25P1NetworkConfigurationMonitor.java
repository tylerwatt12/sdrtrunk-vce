/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.P25FrequencyBandValidator;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCAdjacentSiteStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCAdjacentSiteStatusBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCNetworkStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCNetworkStatusBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCRFSSStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCRFSSStatusBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCSecondaryControlChannelBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCSecondaryControlChannelBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCSystemServiceBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCAdjacentStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCNetworkStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCRFSSStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp.MotorolaBaseStationId;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp.MotorolaExplicitTDMADataChannelAnnouncement;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.AdjacentStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.NetworkStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.RFSSStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SNDCPDataChannelAnnouncementExplicit;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SecondaryControlChannelBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SecondaryControlChannelBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SynchronizationBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SystemServiceBroadcast;
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
 * Tracks the network configuration details of a P25 Phase 1 network from the broadcast messages
 */
public class P25P1NetworkConfigurationMonitor
{
    private final static Logger mLog = LoggerFactory.getLogger(P25P1NetworkConfigurationMonitor.class);

    private Map<Integer,IFrequencyBand> mFrequencyBandMap = new HashMap<>();

    //Network Status Messages
    private AMBTCNetworkStatusBroadcast mAMBTCNetworkStatusBroadcast;
    private NetworkStatusBroadcast mTSBKNetworkStatusBroadcast;
    private LCNetworkStatusBroadcast mLCNetworkStatusBroadcast;
    private LCNetworkStatusBroadcastExplicit mLCNetworkStatusBroadcastExplicit;
    private SynchronizationBroadcast mSynchronizationBroadcast;

    //Current Site Status Messagese
    private RFSSStatusBroadcast mTSBKRFSSStatusBroadcast;
    private AMBTCRFSSStatusBroadcast mAMBTCRFSSStatusBroadcast;
    private LCRFSSStatusBroadcast mLCRFSSStatusBroadcast;
    private LCRFSSStatusBroadcastExplicit mLCRFSSStatusBroadcastExplicit;

    //Current Site Secondary Control Channels
    private Map<String,IChannelDescriptor> mSecondaryControlChannels = new TreeMap<>();

    //Current Site Data Channel(s)
    private SNDCPDataChannelAnnouncementExplicit mSNDCPDataChannel;
    private Map<APCO25Channel, MotorolaExplicitTDMADataChannelAnnouncement> mTDMADataChannelMap = new HashMap<>();

    //Current Site Services
    private SystemServiceBroadcast mTSBKSystemServiceBroadcast;
    private LCSystemServiceBroadcast mLCSystemServiceBroadcast;

    //Neighbor Sites
    private Map<Integer,AMBTCAdjacentStatusBroadcast> mAMBTCNeighborSites = new HashMap<>();
    private Map<Integer,LCAdjacentSiteStatusBroadcast> mLCNeighborSites = new HashMap<>();
    private Map<Integer,LCAdjacentSiteStatusBroadcastExplicit> mLCNeighborSitesExplicit = new HashMap<>();
    private Map<Integer,AdjacentStatusBroadcast> mTSBKNeighborSites = new HashMap<>();

    private MotorolaBaseStationId mMotorolaBaseStationId;

    private Modulation mModulation;
    private P25NetworkConfigurationStabilizer mNetworkConfigurationStabilizer;

    /**
     * Constructs a network configuration monitor.
     *
     * @param modulation type used by the decoder
     */
    public P25P1NetworkConfigurationMonitor(Modulation modulation)
    {
        this(modulation, new P25NetworkConfigurationStabilizer("P25_PHASE_1"));
    }

    /**
     * Constructs a network configuration monitor.
     *
     * @param modulation type used by the decoder
     * @param stabilizer shared P25 fact stabilizer for UI and metadata consumers
     */
    public P25P1NetworkConfigurationMonitor(Modulation modulation, P25NetworkConfigurationStabilizer stabilizer)
    {
        mModulation = modulation;
        mNetworkConfigurationStabilizer = stabilizer != null ? stabilizer :
            new P25NetworkConfigurationStabilizer("P25_PHASE_1");
    }

    /**
     * Processes TSBK network configuration messages
     */
    public P25NetworkConfigurationSnapshot process(TSBKMessage tsbk)
    {
        switch(tsbk.getOpcode())
        {
            case OSP_IDENTIFIER_UPDATE:
            case OSP_IDENTIFIER_UPDATE_TDMA:
            case OSP_IDENTIFIER_UPDATE_VHF_UHF_BANDS:
                if(tsbk instanceof IFrequencyBand)
                {
                    IFrequencyBand frequencyBand = (IFrequencyBand)tsbk;
                    return processFrequencyBand(frequencyBand);
                }
                break;
            case OSP_NETWORK_STATUS_BROADCAST:
                if(tsbk instanceof NetworkStatusBroadcast)
                {
                    mTSBKNetworkStatusBroadcast = (NetworkStatusBroadcast)tsbk;
                    return observation(getNetworkSnapshot(mTSBKNetworkStatusBroadcast), null, Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList());
                }
                break;
            case OSP_TDMA_SYNC_BROADCAST:
                if(tsbk instanceof SynchronizationBroadcast synchronizationBroadcast)
                {
                    mSynchronizationBroadcast = synchronizationBroadcast;
                }
                break;
            case OSP_SYSTEM_SERVICE_BROADCAST:
                if(tsbk instanceof SystemServiceBroadcast)
                {
                    mTSBKSystemServiceBroadcast = (SystemServiceBroadcast)tsbk;
                }
                break;
            case OSP_RFSS_STATUS_BROADCAST:
                if(tsbk instanceof RFSSStatusBroadcast)
                {
                    mTSBKRFSSStatusBroadcast = (RFSSStatusBroadcast)tsbk;
                    return observation(null, getCurrentSiteSnapshot(mTSBKRFSSStatusBroadcast),
                        getChannelSnapshots("primary_control", mTSBKRFSSStatusBroadcast.getChannel()),
                        Collections.emptyList(), Collections.emptyList());
                }
                break;
            case OSP_SECONDARY_CONTROL_CHANNEL_BROADCAST:
                if(tsbk instanceof SecondaryControlChannelBroadcast)
                {
                    SecondaryControlChannelBroadcast sccb = (SecondaryControlChannelBroadcast)tsbk;
                    List<P25NetworkConfigurationSnapshot.Channel> channels = new ArrayList<>();

                    for(IChannelDescriptor secondaryControlChannel : sccb.getChannels())
                    {
                        addSecondaryControlChannel(channels, secondaryControlChannel);
                    }

                    return observation(null, null, channels, Collections.emptyList(), Collections.emptyList());
                }
                break;
            case OSP_SECONDARY_CONTROL_CHANNEL_BROADCAST_EXPLICIT:
                if(tsbk instanceof SecondaryControlChannelBroadcastExplicit)
                {
                    SecondaryControlChannelBroadcastExplicit sccbe = (SecondaryControlChannelBroadcastExplicit)tsbk;
                    IChannelDescriptor channel = sccbe.getChannel();
                    List<P25NetworkConfigurationSnapshot.Channel> channels = new ArrayList<>();
                    addSecondaryControlChannel(channels, channel);
                    return observation(null, null, channels,
                        Collections.emptyList(), Collections.emptyList());
                }
                break;
            case OSP_ADJACENT_STATUS_BROADCAST:
                if(tsbk instanceof AdjacentStatusBroadcast)
                {
                    AdjacentStatusBroadcast asb = (AdjacentStatusBroadcast)tsbk;
                    mTSBKNeighborSites.put((int)asb.getSite().getValue(), asb);
                    return observation(null, null, Collections.emptyList(), List.of(getNeighborSiteSnapshot(asb)),
                        Collections.emptyList());
                }
                break;
            case OSP_SNDCP_DATA_CHANNEL_ANNOUNCEMENT_EXPLICIT:
                if(tsbk instanceof SNDCPDataChannelAnnouncementExplicit)
                {
                    mSNDCPDataChannel = (SNDCPDataChannelAnnouncementExplicit)tsbk;
                    return observation(null, null, getChannelSnapshots("fdma_data", mSNDCPDataChannel.getChannel()),
                        Collections.emptyList(), Collections.emptyList());
                }
                break;
            case MOTOROLA_OSP_BASE_STATION_ID:
                if(tsbk instanceof MotorolaBaseStationId)
                {
                    mMotorolaBaseStationId = (MotorolaBaseStationId)tsbk;
                }
                break;
            case MOTOROLA_OSP_TDMA_DATA_CHANNEL:
                if(tsbk instanceof MotorolaExplicitTDMADataChannelAnnouncement tdma && tdma.hasChannel())
                {
                    if(P25FrequencyBandValidator.isResolvedChannel(tdma.getChannel()))
                    {
                        mTDMADataChannelMap.put(tdma.getChannel(), tdma);
                    }

                    return observation(null, null, getChannelSnapshots("tdma_data", tdma.getChannel()),
                        Collections.emptyList(), Collections.emptyList());
                }
                break;
        }

        return null;
    }

    /**
     * Processes Alternate Multi-Block Trunking Control (AMBTC) messages for network configuration details
     */
    public P25NetworkConfigurationSnapshot process(AMBTCMessage ambtc)
    {
        switch(ambtc.getHeader().getOpcode())
        {
            case OSP_ADJACENT_STATUS_BROADCAST:
                if(ambtc instanceof AMBTCAdjacentStatusBroadcast)
                {
                    AMBTCAdjacentStatusBroadcast aasb = (AMBTCAdjacentStatusBroadcast)ambtc;
                    mAMBTCNeighborSites.put((int)aasb.getSite().getValue(), aasb);
                    return observation(null, null, Collections.emptyList(), List.of(getNeighborSiteSnapshot(aasb)),
                        Collections.emptyList());
                }
                break;
            case OSP_NETWORK_STATUS_BROADCAST:
                if(ambtc instanceof AMBTCNetworkStatusBroadcast)
                {
                    mAMBTCNetworkStatusBroadcast = (AMBTCNetworkStatusBroadcast)ambtc;
                    return observation(getNetworkSnapshot(mAMBTCNetworkStatusBroadcast), null, Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList());
                }
                break;
            case OSP_RFSS_STATUS_BROADCAST:
                if(ambtc instanceof AMBTCRFSSStatusBroadcast)
                {
                    mAMBTCRFSSStatusBroadcast = (AMBTCRFSSStatusBroadcast)ambtc;
                    return observation(null, getCurrentSiteSnapshot(mAMBTCRFSSStatusBroadcast),
                        getChannelSnapshots("primary_control", mAMBTCRFSSStatusBroadcast.getChannel()),
                        Collections.emptyList(), Collections.emptyList());
                }
                break;
//TODO: process the rest of the messages here
        }

        return null;
    }

    /**
     * Processes Link Control Word (LCW) messages with network configuration details
     */
    public P25NetworkConfigurationSnapshot process(LinkControlWord lcw)
    {
        if(lcw.isValid())
        {
            long timestamp = Instant.now().toEpochMilli();

            switch(lcw.getOpcode())
            {
                case ADJACENT_SITE_STATUS_BROADCAST:
                    if(lcw instanceof LCAdjacentSiteStatusBroadcast)
                    {
                        LCAdjacentSiteStatusBroadcast assb = (LCAdjacentSiteStatusBroadcast)lcw;
                        mLCNeighborSites.put((int)assb.getSite().getValue(), assb);
                        return observation(null, null, Collections.emptyList(), List.of(getNeighborSiteSnapshot(assb)),
                            Collections.emptyList());
                    }
                    break;
                case ADJACENT_SITE_STATUS_BROADCAST_EXPLICIT:
                    if(lcw instanceof LCAdjacentSiteStatusBroadcastExplicit)
                    {
                        LCAdjacentSiteStatusBroadcastExplicit assbe = (LCAdjacentSiteStatusBroadcastExplicit)lcw;
                        mLCNeighborSitesExplicit.put((int)assbe.getSite().getValue(), assbe);
                        return observation(null, null, Collections.emptyList(), List.of(getNeighborSiteSnapshot(assbe)),
                            Collections.emptyList());
                    }
                    break;
                case CHANNEL_IDENTIFIER_UPDATE:
                case CHANNEL_IDENTIFIER_UPDATE_VU:
                    if(lcw instanceof IFrequencyBand)
                    {
                        IFrequencyBand band = (IFrequencyBand)lcw;
                        return processFrequencyBand(band);
                    }
                    break;
                case NETWORK_STATUS_BROADCAST:
                    if(lcw instanceof LCNetworkStatusBroadcast)
                    {
                        mLCNetworkStatusBroadcast = (LCNetworkStatusBroadcast)lcw;
                        return observation(getNetworkSnapshot(mLCNetworkStatusBroadcast), null, Collections.emptyList(),
                            Collections.emptyList(), Collections.emptyList());
                    }
                    break;
                case NETWORK_STATUS_BROADCAST_EXPLICIT:
                    if(lcw instanceof LCNetworkStatusBroadcastExplicit)
                    {
                        mLCNetworkStatusBroadcastExplicit = (LCNetworkStatusBroadcastExplicit)lcw;
                        return observation(getNetworkSnapshot(mLCNetworkStatusBroadcastExplicit), null,
                            Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
                    }
                    break;
                case RFSS_STATUS_BROADCAST:
                    if(lcw instanceof LCRFSSStatusBroadcast)
                    {
                        mLCRFSSStatusBroadcast = (LCRFSSStatusBroadcast)lcw;
                        return observation(null, getCurrentSiteSnapshot(mLCRFSSStatusBroadcast),
                            getChannelSnapshots("primary_control", mLCRFSSStatusBroadcast.getChannel()),
                            Collections.emptyList(), Collections.emptyList());
                    }
                    break;
                case RFSS_STATUS_BROADCAST_EXPLICIT:
                    if(lcw instanceof LCRFSSStatusBroadcastExplicit)
                    {
                        mLCRFSSStatusBroadcastExplicit = (LCRFSSStatusBroadcastExplicit)lcw;
                        return observation(null, getCurrentSiteSnapshot(mLCRFSSStatusBroadcastExplicit),
                            getChannelSnapshots("primary_control", mLCRFSSStatusBroadcastExplicit.getChannel()),
                            Collections.emptyList(), Collections.emptyList());
                    }
                    break;
                case SECONDARY_CONTROL_CHANNEL_BROADCAST:
                    if(lcw instanceof LCSecondaryControlChannelBroadcast)
                    {
                        LCSecondaryControlChannelBroadcast sccb = (LCSecondaryControlChannelBroadcast)lcw;
                        List<P25NetworkConfigurationSnapshot.Channel> channels = new ArrayList<>();

                        for(IChannelDescriptor channel : sccb.getChannels())
                        {
                            addSecondaryControlChannel(channels, channel);
                        }

                        return observation(null, null, channels, Collections.emptyList(), Collections.emptyList());
                    }
                    break;
                case SECONDARY_CONTROL_CHANNEL_BROADCAST_EXPLICIT:
                    if(lcw instanceof LCSecondaryControlChannelBroadcastExplicit)
                    {
                        LCSecondaryControlChannelBroadcastExplicit sccb = (LCSecondaryControlChannelBroadcastExplicit)lcw;
                        List<P25NetworkConfigurationSnapshot.Channel> channels = new ArrayList<>();

                        for(IChannelDescriptor channel : sccb.getChannels())
                        {
                            addSecondaryControlChannel(channels, channel);
                        }

                        return observation(null, null, channels, Collections.emptyList(), Collections.emptyList());
                    }
                    break;
                case SYSTEM_SERVICE_BROADCAST:
                    if(lcw instanceof LCSystemServiceBroadcast)
                    {
                        mLCSystemServiceBroadcast = (LCSystemServiceBroadcast)lcw;
                    }
                    break;
            }

        }

        return null;
    }

    public void reset()
    {
        mFrequencyBandMap.clear();
        mAMBTCNetworkStatusBroadcast = null;
        mTSBKNetworkStatusBroadcast = null;
        mLCNetworkStatusBroadcast = null;
        mLCNetworkStatusBroadcastExplicit = null;
        mSynchronizationBroadcast = null;
        mTSBKRFSSStatusBroadcast = null;
        mLCRFSSStatusBroadcast = null;
        mLCRFSSStatusBroadcastExplicit = null;
        mSecondaryControlChannels.clear();
        mSNDCPDataChannel = null;
        mTSBKSystemServiceBroadcast = null;
        mLCSystemServiceBroadcast = null;
        mAMBTCNeighborSites = new HashMap<>();
        mLCNeighborSites.clear();
        mLCNeighborSitesExplicit.clear();
        mTSBKNeighborSites.clear();
        mNetworkConfigurationStabilizer.reset();
    }

    /**
     * Current-site primary and secondary control channel downlink frequencies.
     */
    public Set<Long> getCurrentSiteControlFrequencies()
    {
        Set<Long> frequencies = new TreeSet<>();

        if(mTSBKRFSSStatusBroadcast != null)
        {
            addFrequency(frequencies, mTSBKRFSSStatusBroadcast.getChannel());
        }
        else if(mLCRFSSStatusBroadcast != null)
        {
            addFrequency(frequencies, mLCRFSSStatusBroadcast.getChannel());
        }
        else if(mLCRFSSStatusBroadcastExplicit != null)
        {
            addFrequency(frequencies, mLCRFSSStatusBroadcastExplicit.getChannel());
        }
        else if(mAMBTCRFSSStatusBroadcast != null)
        {
            addFrequency(frequencies, mAMBTCRFSSStatusBroadcast.getChannel());
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

        for(IChannelDescriptor secondaryControlChannel: mSecondaryControlChannels.values())
        {
            addChannelSnapshot(channels, "secondary_control", secondaryControlChannel);
        }

        if(mSNDCPDataChannel != null)
        {
            addChannelSnapshot(channels, "fdma_data", mSNDCPDataChannel.getChannel());
        }

        if(!mTDMADataChannelMap.isEmpty())
        {
            mTDMADataChannelMap.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> addChannelSnapshot(channels, "tdma_data", entry.getKey()));
        }

        return new P25NetworkConfigurationSnapshot("P25_PHASE_1", network, currentSite, channels,
            getNeighborSiteSnapshots(), getFrequencyBandSnapshots(), Collections.emptyList(), Collections.emptyList());
    }

    private P25NetworkConfigurationSnapshot observation(P25NetworkConfigurationSnapshot.Network network,
                                                        P25NetworkConfigurationSnapshot.CurrentSite currentSite,
                                                        List<P25NetworkConfigurationSnapshot.Channel> channels,
                                                        List<P25NetworkConfigurationSnapshot.NeighborSite> neighborSites,
                                                        List<P25NetworkConfigurationSnapshot.FrequencyBand> frequencyBands)
    {
        return new P25NetworkConfigurationSnapshot("P25_PHASE_1", network, currentSite, channels, neighborSites,
            frequencyBands, Collections.emptyList(), Collections.emptyList());
    }

    private P25NetworkConfigurationSnapshot.Network getNetworkSnapshot()
    {
        if(mTSBKNetworkStatusBroadcast != null)
        {
            return getNetworkSnapshot(mTSBKNetworkStatusBroadcast);
        }
        else if(mAMBTCNetworkStatusBroadcast != null)
        {
            return getNetworkSnapshot(mAMBTCNetworkStatusBroadcast);
        }
        else if(mLCNetworkStatusBroadcast != null)
        {
            return getNetworkSnapshot(mLCNetworkStatusBroadcast);
        }
        else if(mLCNetworkStatusBroadcastExplicit != null)
        {
            return getNetworkSnapshot(mLCNetworkStatusBroadcastExplicit);
        }

        return null;
    }

    private P25NetworkConfigurationSnapshot.Network getNetworkSnapshot(NetworkStatusBroadcast networkStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.Network(intValue(networkStatusBroadcast.getWacn()),
            intValue(networkStatusBroadcast.getSystem()), intValue(networkStatusBroadcast.getNAC()),
            intValue(networkStatusBroadcast.getLocationRegistrationArea()));
    }

    private P25NetworkConfigurationSnapshot.Network getNetworkSnapshot(AMBTCNetworkStatusBroadcast networkStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.Network(intValue(networkStatusBroadcast.getWacn()),
            intValue(networkStatusBroadcast.getSystem()), intValue(networkStatusBroadcast.getNAC()), null);
    }

    private P25NetworkConfigurationSnapshot.Network getNetworkSnapshot(LCNetworkStatusBroadcast networkStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.Network(intValue(networkStatusBroadcast.getWACN()),
            intValue(networkStatusBroadcast.getSystem()), null, null);
    }

    private P25NetworkConfigurationSnapshot.Network getNetworkSnapshot(
        LCNetworkStatusBroadcastExplicit networkStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.Network(intValue(networkStatusBroadcast.getWACN()),
            intValue(networkStatusBroadcast.getSystem()), null, null);
    }

    private P25NetworkConfigurationSnapshot.CurrentSite getCurrentSiteSnapshot(
        List<P25NetworkConfigurationSnapshot.Channel> channels)
    {
        if(mTSBKRFSSStatusBroadcast != null)
        {
            addChannelSnapshot(channels, "primary_control", mTSBKRFSSStatusBroadcast.getChannel());
            return getCurrentSiteSnapshot(mTSBKRFSSStatusBroadcast);
        }
        else if(mLCRFSSStatusBroadcast != null)
        {
            addChannelSnapshot(channels, "primary_control", mLCRFSSStatusBroadcast.getChannel());
            return getCurrentSiteSnapshot(mLCRFSSStatusBroadcast);
        }
        else if(mLCRFSSStatusBroadcastExplicit != null)
        {
            addChannelSnapshot(channels, "primary_control", mLCRFSSStatusBroadcastExplicit.getChannel());
            return getCurrentSiteSnapshot(mLCRFSSStatusBroadcastExplicit);
        }
        else if(mAMBTCRFSSStatusBroadcast != null)
        {
            addChannelSnapshot(channels, "primary_control", mAMBTCRFSSStatusBroadcast.getChannel());
            return getCurrentSiteSnapshot(mAMBTCRFSSStatusBroadcast);
        }

        return null;
    }

    private P25NetworkConfigurationSnapshot.CurrentSite getCurrentSiteSnapshot(RFSSStatusBroadcast rfssStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.CurrentSite(intValue(rfssStatusBroadcast.getSystem()),
            intValue(rfssStatusBroadcast.getNAC()), intValue(rfssStatusBroadcast.getRfss()),
            intValue(rfssStatusBroadcast.getSite()), intValue(rfssStatusBroadcast.getLocationRegistrationArea()),
            rfssStatusBroadcast.isActiveNetworkConnectionToRfssControllerSite());
    }

    private P25NetworkConfigurationSnapshot.CurrentSite getCurrentSiteSnapshot(LCRFSSStatusBroadcast rfssStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.CurrentSite(intValue(rfssStatusBroadcast.getSystem()), null,
            intValue(rfssStatusBroadcast.getRfss()), intValue(rfssStatusBroadcast.getSite()),
            intValue(rfssStatusBroadcast.getLocationRegistrationArea()), null);
    }

    private P25NetworkConfigurationSnapshot.CurrentSite getCurrentSiteSnapshot(
        LCRFSSStatusBroadcastExplicit rfssStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.CurrentSite(null, null, intValue(rfssStatusBroadcast.getRfss()),
            intValue(rfssStatusBroadcast.getSite()), intValue(rfssStatusBroadcast.getLocationRegistrationArea()), null);
    }

    private P25NetworkConfigurationSnapshot.CurrentSite getCurrentSiteSnapshot(
        AMBTCRFSSStatusBroadcast rfssStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.CurrentSite(intValue(rfssStatusBroadcast.getSystem()),
            intValue(rfssStatusBroadcast.getNAC()), intValue(rfssStatusBroadcast.getRFSS()),
            intValue(rfssStatusBroadcast.getSite()), intValue(rfssStatusBroadcast.getLRA()),
            rfssStatusBroadcast.isActiveNetworkConnectionToRfssControllerSite());
    }

    private List<P25NetworkConfigurationSnapshot.NeighborSite> getNeighborSiteSnapshots()
    {
        List<P25NetworkConfigurationSnapshot.NeighborSite> neighbors = new ArrayList<>();
        Set<Integer> sites = new TreeSet<>();
        sites.addAll(mAMBTCNeighborSites.keySet());
        sites.addAll(mLCNeighborSites.keySet());
        sites.addAll(mLCNeighborSitesExplicit.keySet());
        sites.addAll(mTSBKNeighborSites.keySet());

        for(Integer site: sites)
        {
            if(mAMBTCNeighborSites.containsKey(site))
            {
                AMBTCAdjacentStatusBroadcast ambtc = mAMBTCNeighborSites.get(site);
                neighbors.add(getNeighborSiteSnapshot(ambtc));
            }
            if(mLCNeighborSites.containsKey(site))
            {
                LCAdjacentSiteStatusBroadcast lc = mLCNeighborSites.get(site);
                neighbors.add(getNeighborSiteSnapshot(lc));
            }
            if(mLCNeighborSitesExplicit.containsKey(site))
            {
                LCAdjacentSiteStatusBroadcastExplicit lce = mLCNeighborSitesExplicit.get(site);
                neighbors.add(getNeighborSiteSnapshot(lce));
            }
            if(mTSBKNeighborSites.containsKey(site))
            {
                AdjacentStatusBroadcast asb = mTSBKNeighborSites.get(site);
                neighbors.add(getNeighborSiteSnapshot(asb));
            }
        }

        return neighbors;
    }

    private P25NetworkConfigurationSnapshot.NeighborSite getNeighborSiteSnapshot(
        AMBTCAdjacentStatusBroadcast adjacentStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.NeighborSite(intValue(adjacentStatusBroadcast.getSystem()),
            null, intValue(adjacentStatusBroadcast.getRfss()),
            intValue(adjacentStatusBroadcast.getSite()), intValue(adjacentStatusBroadcast.getLocationRegistrationArea()),
            channelName(adjacentStatusBroadcast.getChannel()), downlink(adjacentStatusBroadcast.getChannel()),
            uplink(adjacentStatusBroadcast.getChannel()), null);
    }

    private P25NetworkConfigurationSnapshot.NeighborSite getNeighborSiteSnapshot(
        LCAdjacentSiteStatusBroadcast adjacentStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.NeighborSite(intValue(adjacentStatusBroadcast.getSystem()), null,
            intValue(adjacentStatusBroadcast.getRfss()), intValue(adjacentStatusBroadcast.getSite()),
            intValue(adjacentStatusBroadcast.getLocationRegistrationArea()), channelName(adjacentStatusBroadcast.getChannel()),
            downlink(adjacentStatusBroadcast.getChannel()), uplink(adjacentStatusBroadcast.getChannel()), null);
    }

    private P25NetworkConfigurationSnapshot.NeighborSite getNeighborSiteSnapshot(
        LCAdjacentSiteStatusBroadcastExplicit adjacentStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.NeighborSite(null, null,
            intValue(adjacentStatusBroadcast.getRfss()), intValue(adjacentStatusBroadcast.getSite()),
            intValue(adjacentStatusBroadcast.getLocationRegistrationArea()), channelName(adjacentStatusBroadcast.getChannel()),
            downlink(adjacentStatusBroadcast.getChannel()), uplink(adjacentStatusBroadcast.getChannel()), null);
    }

    private P25NetworkConfigurationSnapshot.NeighborSite getNeighborSiteSnapshot(
        AdjacentStatusBroadcast adjacentStatusBroadcast)
    {
        return new P25NetworkConfigurationSnapshot.NeighborSite(intValue(adjacentStatusBroadcast.getSystem()),
            null, intValue(adjacentStatusBroadcast.getRfss()),
            intValue(adjacentStatusBroadcast.getSite()), intValue(adjacentStatusBroadcast.getLocationRegistrationArea()),
            channelName(adjacentStatusBroadcast.getChannel()), downlink(adjacentStatusBroadcast.getChannel()),
            uplink(adjacentStatusBroadcast.getChannel()), String.valueOf(adjacentStatusBroadcast.getSiteFlags()));
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
            mLog.warn("P25 P1 network frequency band replacing existing:{} with candidate:{}",
                P25FrequencyBandValidator.describe(result.existing()),
                P25FrequencyBandValidator.describe(frequencyBand));
        }
        else if(!result.accepted())
        {
            mLog.warn("P25 P1 network frequency band rejected {} correctedBits:{} - {}{}",
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

    public String getActivitySummary()
    {
        StringBuilder sb = new StringBuilder();

        sb.append("Activity Summary - Decoder:P25 Phase 1 ").append(mModulation.getLabel());

        sb.append("\n\nNetwork\n");
        if(mTSBKNetworkStatusBroadcast != null)
        {
            sb.append("  WACN:").append(format(mTSBKNetworkStatusBroadcast.getWacn(), 5));
            sb.append(" SYSTEM:").append(format(mTSBKNetworkStatusBroadcast.getSystem(), 3));
            sb.append(" NAC:").append(format(mTSBKNetworkStatusBroadcast.getNAC(), 3));
            sb.append(" LRA:").append(format(mTSBKNetworkStatusBroadcast.getLocationRegistrationArea(), 2));
        }
        else if(mAMBTCNetworkStatusBroadcast != null)
        {
            sb.append("  WACN:").append(format(mAMBTCNetworkStatusBroadcast.getWacn(), 5));
            sb.append(" SYSTEM:").append(format(mAMBTCNetworkStatusBroadcast.getSystem(), 3));
            sb.append(" NAC:").append(format(mAMBTCNetworkStatusBroadcast.getNAC(), 3));
        }
        else if(mLCNetworkStatusBroadcast != null)
        {
            sb.append("  WACN:").append(format(mLCNetworkStatusBroadcast.getWACN(), 5));
            sb.append(" SYSTEM:").append(format(mLCNetworkStatusBroadcast.getSystem(), 3));
        }
        else if(mLCNetworkStatusBroadcastExplicit != null)
        {
            sb.append("  WACN:").append(format(mLCNetworkStatusBroadcastExplicit.getWACN(), 5));
            sb.append(" SYSTEM:").append(format(mLCNetworkStatusBroadcastExplicit.getSystem(), 3));
        }
        else
        {
            sb.append("  UNKNOWN");
        }

        appendSynchronizationBroadcast(sb);

        sb.append("\n\nCurrent Site\n");

        if(mTSBKRFSSStatusBroadcast != null)
        {
            sb.append("  SYSTEM:").append(format(mTSBKRFSSStatusBroadcast.getSystem(), 3));
            sb.append(" NAC:").append(format(mTSBKRFSSStatusBroadcast.getNAC(), 3));
            sb.append(" RFSS:").append(format(mTSBKRFSSStatusBroadcast.getRfss(), 2));
            sb.append(" SITE:").append(format(mTSBKRFSSStatusBroadcast.getSite(), 2));
            sb.append(" LRA:").append(format(mTSBKRFSSStatusBroadcast.getLocationRegistrationArea(), 2));
            sb.append("  STATUS:").append(mTSBKRFSSStatusBroadcast.isActiveNetworkConnectionToRfssControllerSite() ?
                "ACTIVE RFSS NETWORK CONNECTION\n" : "\n");
            sb.append("  PRI CONTROL CHANNEL:").append(mTSBKRFSSStatusBroadcast.getChannel());
            sb.append(" DOWNLINK:").append(mTSBKRFSSStatusBroadcast.getChannel().getDownlinkFrequency());
            sb.append(" UPLINK:").append(mTSBKRFSSStatusBroadcast.getChannel().getUplinkFrequency()).append("\n");
        }
        else if(mLCRFSSStatusBroadcast != null)
        {
            sb.append("  SYSTEM:").append(format(mLCRFSSStatusBroadcast.getSystem(), 3));
            sb.append(" RFSS:").append(format(mLCRFSSStatusBroadcast.getRfss(), 2));
            sb.append(" SITE:").append(format(mLCRFSSStatusBroadcast.getSite(), 2));
            sb.append(" LRA:").append(format(mLCRFSSStatusBroadcast.getLocationRegistrationArea(), 2)).append("\n");
            sb.append("  PRI CONTROL CHANNEL:").append(mLCRFSSStatusBroadcast.getChannel());
            sb.append(" DOWNLINK:").append(mLCRFSSStatusBroadcast.getChannel().getDownlinkFrequency());
            sb.append(" UPLINK:").append(mLCRFSSStatusBroadcast.getChannel().getUplinkFrequency()).append("\n");
        }
        else if(mLCRFSSStatusBroadcastExplicit != null)
        {
            sb.append("  RFSS:").append(mLCRFSSStatusBroadcastExplicit.getRfss());
            sb.append(" SITE:").append(format(mLCRFSSStatusBroadcastExplicit.getSite(), 2));
            sb.append(" LRA:").append(format(mLCRFSSStatusBroadcastExplicit.getLocationRegistrationArea(), 2)).append("\n");
            sb.append("  PRI CONTROL CHANNEL:").append(mLCRFSSStatusBroadcastExplicit.getChannel());
            sb.append(" DOWNLINK:").append(mLCRFSSStatusBroadcastExplicit.getChannel().getDownlinkFrequency());
            sb.append(" UPLINK:").append(mLCRFSSStatusBroadcastExplicit.getChannel().getUplinkFrequency()).append("\n");
        }
        else if(mAMBTCRFSSStatusBroadcast != null)
        {
            sb.append("  SYSTEM:").append(format(mAMBTCRFSSStatusBroadcast.getSystem(), 3));
            sb.append(" NAC:").append(format(mAMBTCRFSSStatusBroadcast.getNAC(), 3));
            sb.append(" RFSS:").append(format(mAMBTCRFSSStatusBroadcast.getRFSS(), 2));
            sb.append(" SITE:").append(format(mAMBTCRFSSStatusBroadcast.getSite(), 2));
            sb.append(" LRA:").append(format(mAMBTCRFSSStatusBroadcast.getLRA(), 2));
            sb.append("  STATUS:").append(mAMBTCRFSSStatusBroadcast.isActiveNetworkConnectionToRfssControllerSite() ?
                "ACTIVE RFSS NETWORK CONNECTION\n" : "\n");
            sb.append("  PRI CONTROL CHANNEL:").append(mAMBTCRFSSStatusBroadcast.getChannel());
            sb.append(" DOWNLINK:").append(mAMBTCRFSSStatusBroadcast.getChannel().getDownlinkFrequency());
            sb.append(" UPLINK:").append(mAMBTCRFSSStatusBroadcast.getChannel().getUplinkFrequency()).append("\n");
        }
        else
        {
            sb.append("  UNKNOWN");
        }

        if(!mSecondaryControlChannels.isEmpty())
        {
            mSecondaryControlChannels
                    .entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByKey())
                    .filter(Objects::nonNull)
                    .forEach(entry -> {
                        sb.append("  SEC CONTROL CHANNEL:").append(entry.getValue());
                        sb.append(" DOWNLINK:").append(entry.getValue().getDownlinkFrequency());
                        sb.append(" UPLINK:").append(entry.getValue().getUplinkFrequency()).append("\n");
                    });
        }

        if(mSNDCPDataChannel != null)
        {
            sb.append("  CURRENT FDMA DATA CHANNEL:").append(mSNDCPDataChannel.getChannel());
            sb.append(" DOWNLINK:").append(mSNDCPDataChannel.getChannel().getDownlinkFrequency());
            sb.append(" UPLINK:").append(mSNDCPDataChannel.getChannel().getUplinkFrequency()).append("\n");
        }

        if(!mTDMADataChannelMap.isEmpty())
        {
            for(Map.Entry<APCO25Channel, MotorolaExplicitTDMADataChannelAnnouncement> entry: mTDMADataChannelMap.entrySet())
            {
                sb.append("  ACTIVE TDMA DATA CHANNEL:").append(entry.getKey());
                sb.append(" DOWNLINK:").append(entry.getKey().getDownlinkFrequency());
                sb.append(" UPLINK:").append(entry.getKey().getUplinkFrequency()).append("\n");
            }
        }

        if(mMotorolaBaseStationId != null)
        {
            sb.append("  STATION ID/LICENSE: ").append(mMotorolaBaseStationId.getCWID()).append("\n");
        }

        if(mTSBKSystemServiceBroadcast != null)
        {
            sb.append("  AVAILABLE SERVICES:").append(mTSBKSystemServiceBroadcast.getAvailableServices());
            sb.append("  SUPPORTED SERVICES:").append(mTSBKSystemServiceBroadcast.getSupportedServices());
        }
        else if(mLCSystemServiceBroadcast != null)
        {
            sb.append("  AVAILABLE SERVICES:").append(mLCSystemServiceBroadcast.getAvailableServices());
            sb.append("  SUPPORTED SERVICES:").append(mLCSystemServiceBroadcast.getSupportedServices());
        }

        sb.append("\nNeighbor Sites\n");
        Set<Integer> sites = new TreeSet<>();
        sites.addAll(mAMBTCNeighborSites.keySet());
        sites.addAll(mLCNeighborSites.keySet());
        sites.addAll(mLCNeighborSitesExplicit.keySet());
        sites.addAll(mTSBKNeighborSites.keySet());

        if(sites.isEmpty())
        {
            sb.append("  UNKNOWN");
        }
        else
        {
            sites
                    .stream()
                    .sorted()
                    .forEach(site -> {
                        if(mAMBTCNeighborSites.containsKey(site))
                        {
                            AMBTCAdjacentStatusBroadcast ambtc = mAMBTCNeighborSites.get(site);
                            sb.append("  SYSTEM:").append(format(ambtc.getSystem(), 3));
                            sb.append(" RFSS:").append(format(ambtc.getRfss(), 2));
                            sb.append(" SITE:").append(format(ambtc.getSite(), 2));
                            sb.append(" LRA:").append(format(ambtc.getLocationRegistrationArea(), 2));
                            sb.append(" CHANNEL:").append(ambtc.getChannel());
                            sb.append(" DOWNLINK:").append(ambtc.getChannel().getDownlinkFrequency());
                            sb.append(" UPLINK:").append(ambtc.getChannel().getUplinkFrequency()).append("\n");
                        }
                        if(mLCNeighborSites.containsKey(site))
                        {
                            LCAdjacentSiteStatusBroadcast lc = mLCNeighborSites.get(site);
                            sb.append("  SYSTEM:").append(format(lc.getSystem(), 3));
                            sb.append(" RFSS:").append(format(lc.getRfss(), 2));
                            sb.append(" SITE:").append(format(lc.getSite(), 2));
                            sb.append(" LRA:").append(format(lc.getLocationRegistrationArea(), 2));
                            sb.append(" CHANNEL:").append(lc.getChannel());
                            sb.append(" DOWNLINK:").append(lc.getChannel().getDownlinkFrequency());
                            sb.append(" UPLINK:").append(lc.getChannel().getUplinkFrequency()).append("\n");

                        }
                        if(mLCNeighborSitesExplicit.containsKey(site))
                        {
                            LCAdjacentSiteStatusBroadcastExplicit lce = mLCNeighborSitesExplicit.get(site);
                            sb.append("  SYSTEM:---");
                            sb.append(" RFSS:").append(format(lce.getRfss(), 2));
                            sb.append(" SITE:").append(format(lce.getSite(), 2));
                            sb.append(" LRA:").append(format(lce.getLocationRegistrationArea(), 2));
                            sb.append(" CHANNEL:").append(lce.getChannel());
                            sb.append(" DOWNLINK:").append(lce.getChannel().getDownlinkFrequency());
                            sb.append(" UPLINK:").append(lce.getChannel().getUplinkFrequency()).append("\n");
                        }
                        if(mTSBKNeighborSites.containsKey(site))
                        {
                            AdjacentStatusBroadcast asb = mTSBKNeighborSites.get(site);
                            sb.append("  SYSTEM:").append(format(asb.getSystem(), 3));
                            sb.append(" RFSS:").append(format(asb.getRfss(), 2));
                            sb.append(" SITE:").append(format(asb.getSite(), 2));
                            sb.append(" LRA:").append(format(asb.getLocationRegistrationArea(), 2));
                            sb.append(" CHANNEL:").append(asb.getChannel());
                            sb.append(" DOWNLINK:").append(asb.getChannel().getDownlinkFrequency());
                            sb.append(" UPLINK:").append(asb.getChannel().getUplinkFrequency());
                            sb.append(" STATUS:").append(asb.getSiteFlags()).append("\n");
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
     * Appends the last observed TDMA synchronization broadcast timing details for debugging over-the-air time.
     */
    private void appendSynchronizationBroadcast(StringBuilder sb)
    {
        sb.append("\n\nLast Sync Broadcast\n");

        if(mSynchronizationBroadcast != null)
        {
            sb.append("  SYSTEM UTC:").append(Instant.ofEpochMilli(mSynchronizationBroadcast.getSystemTime()));
            sb.append("  MESSAGE TIME:").append(Instant.ofEpochMilli(mSynchronizationBroadcast.getTimestamp()));
            sb.append("\n  OFFSET MS:")
                    .append(mSynchronizationBroadcast.getSystemTime() - mSynchronizationBroadcast.getTimestamp());
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
            sb.append("\n  RAW:").append(mSynchronizationBroadcast.getMessage().toHexString());
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
