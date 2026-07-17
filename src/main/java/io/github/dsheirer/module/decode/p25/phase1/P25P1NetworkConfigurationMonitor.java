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
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.TimeAndDateAnnouncement;
import io.github.dsheirer.module.decode.p25.reference.Service;
import io.github.dsheirer.module.decode.p25.reference.SystemServiceClass;
import io.github.dsheirer.module.decode.p25.reference.Vendor;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationStabilizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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

    private Map<String,MotorolaBaseStationId> mMotorolaBaseStationIds = new TreeMap<>();
    private P25NetworkConfigurationSnapshot.SiteStatus mSiteStatus;

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
                    return statusObservation(new P25NetworkConfigurationSnapshot.SiteStatus(
                        synchronizationBroadcast.getSystemTime(), synchronizationBroadcast.getMicroSlots(), null,
                        null, null, null, null, null));
                }
                break;
            case OSP_TIME_DATE_ANNOUNCEMENT:
                if(tsbk instanceof TimeAndDateAnnouncement timeAndDate && timeAndDate.hasValidDate() &&
                    timeAndDate.hasValidTime())
                {
                    return statusObservation(new P25NetworkConfigurationSnapshot.SiteStatus(
                        timeAndDate.getDateAndTime().toInstant().toEpochMilli(), null, null, null, null, null, null,
                        null));
                }
                break;
            case OSP_SYSTEM_SERVICE_BROADCAST:
                if(tsbk instanceof SystemServiceBroadcast)
                {
                    mTSBKSystemServiceBroadcast = (SystemServiceBroadcast)tsbk;
                    return statusObservation(serviceStatus(mTSBKSystemServiceBroadcast.getAvailableServices()));
                }
                break;
            case OSP_RFSS_STATUS_BROADCAST:
                if(tsbk instanceof RFSSStatusBroadcast)
                {
                    mTSBKRFSSStatusBroadcast = (RFSSStatusBroadcast)tsbk;
                    return observation(null, getCurrentSiteSnapshot(mTSBKRFSSStatusBroadcast),
                        getChannelSnapshots("primary_control", mTSBKRFSSStatusBroadcast.getChannel()),
                        Collections.emptyList(), Collections.emptyList(),
                        serviceStatus(mTSBKRFSSStatusBroadcast.getSystemServiceClass()));
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
                        Collections.emptyList(), Collections.emptyList(), dataAccessStatus(mSNDCPDataChannel));
                }
                break;
            case MOTOROLA_OSP_BASE_STATION_ID:
                if(tsbk instanceof MotorolaBaseStationId baseStationId && baseStationId.hasChannel())
                {
                    mMotorolaBaseStationIds.put(baseStationId.getChannel().toString(), baseStationId);
                    P25NetworkConfigurationSnapshot.Channel channel = getChannelSnapshot("base_station",
                        baseStationId.getChannel(), baseStationId.getCWID());
                    return observation(null, null, channel != null ? List.of(channel) : Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList());
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
                            Collections.emptyList(), Collections.emptyList(),
                            serviceStatus(mLCRFSSStatusBroadcast.getSystemServiceClass()));
                    }
                    break;
                case RFSS_STATUS_BROADCAST_EXPLICIT:
                    if(lcw instanceof LCRFSSStatusBroadcastExplicit)
                    {
                        mLCRFSSStatusBroadcastExplicit = (LCRFSSStatusBroadcastExplicit)lcw;
                        return observation(null, getCurrentSiteSnapshot(mLCRFSSStatusBroadcastExplicit),
                            getChannelSnapshots("primary_control", mLCRFSSStatusBroadcastExplicit.getChannel()),
                            Collections.emptyList(), Collections.emptyList(),
                            serviceStatus(mLCRFSSStatusBroadcastExplicit.getSystemServiceClass()));
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
                        return statusObservation(serviceStatus(mLCSystemServiceBroadcast.getAvailableServices()));
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
        mAMBTCRFSSStatusBroadcast = null;
        mLCRFSSStatusBroadcast = null;
        mLCRFSSStatusBroadcastExplicit = null;
        mSecondaryControlChannels.clear();
        mSNDCPDataChannel = null;
        mTDMADataChannelMap.clear();
        mTSBKSystemServiceBroadcast = null;
        mLCSystemServiceBroadcast = null;
        mAMBTCNeighborSites = new HashMap<>();
        mLCNeighborSites.clear();
        mLCNeighborSitesExplicit.clear();
        mTSBKNeighborSites.clear();
        mMotorolaBaseStationIds.clear();
        mSiteStatus = null;
        mNetworkConfigurationStabilizer.resetCandidates();
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

        for(MotorolaBaseStationId baseStationId: mMotorolaBaseStationIds.values())
        {
            P25NetworkConfigurationSnapshot.Channel channel = getChannelSnapshot("base_station",
                baseStationId.getChannel(), baseStationId.getCWID());
            if(channel != null)
            {
                channels.add(channel);
            }
        }

        return new P25NetworkConfigurationSnapshot("P25_PHASE_1", network, currentSite, channels,
            getNeighborSiteSnapshots(), getFrequencyBandSnapshots(), Collections.emptyList(), Collections.emptyList(),
            mSiteStatus);
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

    private P25NetworkConfigurationSnapshot observation(P25NetworkConfigurationSnapshot.Network network,
                                                        P25NetworkConfigurationSnapshot.CurrentSite currentSite,
                                                        List<P25NetworkConfigurationSnapshot.Channel> channels,
                                                        List<P25NetworkConfigurationSnapshot.NeighborSite> neighborSites,
                                                        List<P25NetworkConfigurationSnapshot.FrequencyBand> frequencyBands,
                                                        P25NetworkConfigurationSnapshot.SiteStatus status)
    {
        mSiteStatus = mSiteStatus == null ? status : mSiteStatus.merge(status);
        return new P25NetworkConfigurationSnapshot("P25_PHASE_1", network, currentSite, channels, neighborSites,
            frequencyBands, Collections.emptyList(), Collections.emptyList(), mSiteStatus);
    }

    private P25NetworkConfigurationSnapshot statusObservation(P25NetworkConfigurationSnapshot.SiteStatus status)
    {
        return observation(null, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            status);
    }

    public P25NetworkConfigurationSnapshot processVendor(Vendor vendor)
    {
        if(vendor != null && vendor != Vendor.STANDARD && vendor != Vendor.STANDARD_V1 && vendor != Vendor.VUNK)
        {
            return statusObservation(new P25NetworkConfigurationSnapshot.SiteStatus(null, null, null, null, null,
                null, vendor.getValue(), null));
        }

        return null;
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
        return getChannelSnapshot(role, channel, null);
    }

    private P25NetworkConfigurationSnapshot.Channel getChannelSnapshot(String role, IChannelDescriptor channel,
                                                                        String callsign)
    {
        if(!P25FrequencyBandValidator.isResolvedChannel(channel))
        {
            return null;
        }

        return new P25NetworkConfigurationSnapshot.Channel(role, channelName(channel), downlink(channel), uplink(channel),
            channel != null ? channel.isTDMAChannel() : null, channel != null ? channel.getTimeslotCount() : null,
            callsign != null && !callsign.isBlank() ? callsign.trim() : null);
    }

    private P25NetworkConfigurationSnapshot.SiteStatus serviceStatus(SystemServiceClass serviceClass)
    {
        return serviceClass != null ? new P25NetworkConfigurationSnapshot.SiteStatus(null, null,
            serviceClass.hasDataService(), null, null, serviceClass.hasRegistrationService(), null,
            serviceClass.hasVoiceService()) : null;
    }

    private P25NetworkConfigurationSnapshot.SiteStatus serviceStatus(List<Service> services)
    {
        if(services == null)
        {
            return null;
        }

        boolean data = services.contains(Service.GROUP_DATA) || services.contains(Service.INDIVIDUAL_DATA);
        boolean voice = services.contains(Service.GROUP_VOICE) || services.contains(Service.INDIVIDUAL_VOICE) ||
            services.contains(Service.PSTN_TO_UNIT_VOICE) || services.contains(Service.UNIT_TO_PSTN_VOICE);
        return new P25NetworkConfigurationSnapshot.SiteStatus(null, null, data, null, null,
            services.contains(Service.UNIT_REGISTRATION), null, voice);
    }

    private P25NetworkConfigurationSnapshot.SiteStatus dataAccessStatus(
        SNDCPDataChannelAnnouncementExplicit announcement)
    {
        String access = announcement.isAutonomousAccess() ?
            (announcement.isRequestedAccess() ? "Autonomous and by Request" : "Autonomous") :
            (announcement.isRequestedAccess() ? "Request Only" : null);
        return new P25NetworkConfigurationSnapshot.SiteStatus(null, null, true, access, null, null, null, null);
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

        List<P25NetworkConfigurationSnapshot.Channel> callsigns = new ArrayList<>();
        for(MotorolaBaseStationId baseStationId: mMotorolaBaseStationIds.values())
        {
            P25NetworkConfigurationSnapshot.Channel channel = getChannelSnapshot("base_station",
                baseStationId.getChannel(), baseStationId.getCWID());
            if(channel != null)
            {
                callsigns.add(channel);
            }
        }

        return observation(null, null, callsigns, Collections.emptyList(),
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

}
