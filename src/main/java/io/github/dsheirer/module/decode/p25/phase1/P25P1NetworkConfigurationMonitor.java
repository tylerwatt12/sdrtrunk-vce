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
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SystemServiceBroadcast;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.commons.lang3.StringUtils;

/**
 * Tracks the network configuration details of a P25 Phase 1 network from the broadcast messages
 */
public class P25P1NetworkConfigurationMonitor
{
    private static final String CHANNEL_LABEL = " CHANNEL:";
    private static final String DOWNLINK_LABEL = " DOWNLINK:";
    private static final String UPLINK_LABEL = " UPLINK:";
    private static final String SYSTEM_LABEL = " SYSTEM:";
    private static final String NAC_LABEL = " NAC:";
    private static final String RFSS_LABEL = " RFSS:";
    private static final String SITE_LABEL = " SITE:";
    private static final String LRA_LABEL = " LRA:";
    private static final String PRIMARY_CONTROL_CHANNEL_LABEL = "  PRI CONTROL CHANNEL:";
    private static final String AVAILABLE_SERVICES_LABEL = "  AVAILABLE SERVICES:";
    private static final String SUPPORTED_SERVICES_LABEL = "  SUPPORTED SERVICES:";
    private static final String UNKNOWN_LABEL = "  UNKNOWN";
    private static final String STATUS_LABEL = "  STATUS:";
    private static final String WACN_LABEL = "  WACN:";
    private static final String SECONDARY_CONTROL_CHANNEL_LABEL = "  SEC CONTROL CHANNEL:";
    private static final String CURRENT_FDMA_DATA_CHANNEL_LABEL = "  CURRENT FDMA DATA CHANNEL:";
    private static final String ACTIVE_TDMA_DATA_CHANNEL_LABEL = "  ACTIVE TDMA DATA CHANNEL:";

    private Map<Integer,IFrequencyBand> mFrequencyBandMap = new HashMap<>();

    //Network Status Messages
    private AMBTCNetworkStatusBroadcast mAMBTCNetworkStatusBroadcast;
    private NetworkStatusBroadcast mTSBKNetworkStatusBroadcast;
    private LCNetworkStatusBroadcast mLCNetworkStatusBroadcast;
    private LCNetworkStatusBroadcastExplicit mLCNetworkStatusBroadcastExplicit;

    //Current Site Status Messages
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

    /**
     * Constructs a network configuration monitor.
     *
     * @param modulation type used by the decoder
     */
    public P25P1NetworkConfigurationMonitor(Modulation modulation)
    {
        mModulation = modulation;
    }

    /**
     * Processes TSBK network configuration messages
     */
    public void process(TSBKMessage tsbk)
    {
        switch(tsbk.getOpcode())
        {
            case OSP_IDENTIFIER_UPDATE, OSP_IDENTIFIER_UPDATE_TDMA, OSP_IDENTIFIER_UPDATE_VHF_UHF_BANDS:
                if(tsbk instanceof IFrequencyBand frequencyBand)
                {
                    mFrequencyBandMap.put(frequencyBand.getIdentifier(), frequencyBand);
                }
                break;
            case OSP_NETWORK_STATUS_BROADCAST:
                if(tsbk instanceof NetworkStatusBroadcast networkStatusBroadcast)
                {
                    mTSBKNetworkStatusBroadcast = networkStatusBroadcast;
                }
                break;
            case OSP_SYSTEM_SERVICE_BROADCAST:
                if(tsbk instanceof SystemServiceBroadcast systemServiceBroadcast)
                {
                    mTSBKSystemServiceBroadcast = systemServiceBroadcast;
                }
                break;
            case OSP_RFSS_STATUS_BROADCAST:
                if(tsbk instanceof RFSSStatusBroadcast rfssStatusBroadcast)
                {
                    mTSBKRFSSStatusBroadcast = rfssStatusBroadcast;
                }
                break;
            case OSP_SECONDARY_CONTROL_CHANNEL_BROADCAST:
                if(tsbk instanceof SecondaryControlChannelBroadcast sccb)
                {
                    for(IChannelDescriptor secondaryControlChannel : sccb.getChannels())
                    {
                        mSecondaryControlChannels.put(secondaryControlChannel.toString(), secondaryControlChannel);
                    }
                }
                break;
            case OSP_SECONDARY_CONTROL_CHANNEL_BROADCAST_EXPLICIT:
                if(tsbk instanceof SecondaryControlChannelBroadcastExplicit sccbe)
                {
                    IChannelDescriptor channel = sccbe.getChannel();
                    mSecondaryControlChannels.put(channel.toString(), channel);
                }
                break;
            case OSP_ADJACENT_STATUS_BROADCAST:
                if(tsbk instanceof AdjacentStatusBroadcast asb)
                {
                    mTSBKNeighborSites.put((int)asb.getSite().getValue(), asb);
                }
                break;
            case OSP_SNDCP_DATA_CHANNEL_ANNOUNCEMENT_EXPLICIT:
                if(tsbk instanceof SNDCPDataChannelAnnouncementExplicit sndcpDataChannelAnnouncementExplicit)
                {
                    mSNDCPDataChannel = sndcpDataChannelAnnouncementExplicit;
                }
                break;
            case MOTOROLA_OSP_BASE_STATION_ID:
                if(tsbk instanceof MotorolaBaseStationId motorolaBaseStationId)
                {
                    mMotorolaBaseStationId = motorolaBaseStationId;
                }
                break;
            case MOTOROLA_OSP_TDMA_DATA_CHANNEL:
                if(tsbk instanceof MotorolaExplicitTDMADataChannelAnnouncement tdma && tdma.hasChannel())
                {
                    mTDMADataChannelMap.put(tdma.getChannel(), tdma);
                }
                break;
            default:
                break;
        }
    }

    /**
     * Processes Alternate Multi-Block Trunking Control (AMBTC) messages for network configuration details
     */
    public void process(AMBTCMessage ambtc)
    {
        switch(ambtc.getHeader().getOpcode())
        {
            case OSP_ADJACENT_STATUS_BROADCAST:
                if(ambtc instanceof AMBTCAdjacentStatusBroadcast aasb)
                {
                    mAMBTCNeighborSites.put((int)aasb.getSite().getValue(), aasb);
                }
                break;
            case OSP_NETWORK_STATUS_BROADCAST:
                if(ambtc instanceof AMBTCNetworkStatusBroadcast ambtcNetworkStatusBroadcast)
                {
                    mAMBTCNetworkStatusBroadcast = ambtcNetworkStatusBroadcast;
                }
                break;
            case OSP_RFSS_STATUS_BROADCAST:
                if(ambtc instanceof AMBTCRFSSStatusBroadcast ambtcRfssStatusBroadcast)
                {
                    mAMBTCRFSSStatusBroadcast = ambtcRfssStatusBroadcast;
                }
                break;
            default:
                break;
//TODO: process the rest of the messages here
        }
    }

    /**
     * Processes Link Control Word (LCW) messages with network configuration details
     */
    public void process(LinkControlWord lcw)
    {
        if(lcw.isValid())
        {
            switch(lcw.getOpcode())
            {
                case ADJACENT_SITE_STATUS_BROADCAST:
                    if(lcw instanceof LCAdjacentSiteStatusBroadcast assb)
                    {
                        mLCNeighborSites.put((int)assb.getSite().getValue(), assb);
                    }
                    break;
                case ADJACENT_SITE_STATUS_BROADCAST_EXPLICIT:
                    if(lcw instanceof LCAdjacentSiteStatusBroadcastExplicit assbe)
                    {
                        mLCNeighborSitesExplicit.put((int)assbe.getSite().getValue(), assbe);
                    }
                    break;
                case CHANNEL_IDENTIFIER_UPDATE, CHANNEL_IDENTIFIER_UPDATE_VU:
                    if(lcw instanceof IFrequencyBand band)
                    {
                        mFrequencyBandMap.put(band.getIdentifier(), band);
                    }
                    break;
                case NETWORK_STATUS_BROADCAST:
                    if(lcw instanceof LCNetworkStatusBroadcast networkStatusBroadcast)
                    {
                        mLCNetworkStatusBroadcast = networkStatusBroadcast;
                    }
                    break;
                case NETWORK_STATUS_BROADCAST_EXPLICIT:
                    if(lcw instanceof LCNetworkStatusBroadcastExplicit networkStatusBroadcastExplicit)
                    {
                        mLCNetworkStatusBroadcastExplicit = networkStatusBroadcastExplicit;
                    }
                    break;
                case RFSS_STATUS_BROADCAST:
                    if(lcw instanceof LCRFSSStatusBroadcast rfssStatusBroadcast)
                    {
                        mLCRFSSStatusBroadcast = rfssStatusBroadcast;
                    }
                    break;
                case RFSS_STATUS_BROADCAST_EXPLICIT:
                    if(lcw instanceof LCRFSSStatusBroadcastExplicit rfssStatusBroadcastExplicit)
                    {
                        mLCRFSSStatusBroadcastExplicit = rfssStatusBroadcastExplicit;
                    }
                    break;
                case SECONDARY_CONTROL_CHANNEL_BROADCAST:
                    if(lcw instanceof LCSecondaryControlChannelBroadcast sccb)
                    {
                        for(IChannelDescriptor channel : sccb.getChannels())
                        {
                            mSecondaryControlChannels.put(channel.toString(), channel);
                        }
                    }
                    break;
                case SECONDARY_CONTROL_CHANNEL_BROADCAST_EXPLICIT:
                    if(lcw instanceof LCSecondaryControlChannelBroadcastExplicit sccb)
                    {
                        for(IChannelDescriptor channel : sccb.getChannels())
                        {
                            mSecondaryControlChannels.put(channel.toString(), channel);
                        }
                    }
                    break;
                case SYSTEM_SERVICE_BROADCAST:
                    if(lcw instanceof LCSystemServiceBroadcast systemServiceBroadcast)
                    {
                        mLCSystemServiceBroadcast = systemServiceBroadcast;
                    }
                    break;
                default:
                    break;
            }

        }

    }

    public void reset()
    {
        mFrequencyBandMap.clear();
        mAMBTCNetworkStatusBroadcast = null;
        mTSBKNetworkStatusBroadcast = null;
        mLCNetworkStatusBroadcast = null;
        mLCNetworkStatusBroadcastExplicit = null;
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
    }

    /**
     * Formats the identifier with an appended hexadecimal value when the identifier is an integer
     * @param identifier to format
     * @param width of the hex value with zero pre-padding
     * @return formatted identifier
     */
    private String format(Identifier identifier, int width)
    {
        if(identifier.getValue() instanceof Integer integer)
        {
            String hex = StringUtils.leftPad(Integer.toHexString(integer), width, '0');

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
            sb.append(WACN_LABEL).append(format(mTSBKNetworkStatusBroadcast.getWacn(), 5));
            sb.append(SYSTEM_LABEL).append(format(mTSBKNetworkStatusBroadcast.getSystem(), 3));
            sb.append(NAC_LABEL).append(format(mTSBKNetworkStatusBroadcast.getNAC(), 3));
            sb.append(LRA_LABEL).append(format(mTSBKNetworkStatusBroadcast.getLocationRegistrationArea(), 2));
        }
        else if(mAMBTCNetworkStatusBroadcast != null)
        {
            sb.append(WACN_LABEL).append(format(mAMBTCNetworkStatusBroadcast.getWacn(), 5));
            sb.append(SYSTEM_LABEL).append(format(mAMBTCNetworkStatusBroadcast.getSystem(), 3));
            sb.append(NAC_LABEL).append(format(mAMBTCNetworkStatusBroadcast.getNAC(), 3));
        }
        else if(mLCNetworkStatusBroadcast != null)
        {
            sb.append(WACN_LABEL).append(format(mLCNetworkStatusBroadcast.getWACN(), 5));
            sb.append(SYSTEM_LABEL).append(format(mLCNetworkStatusBroadcast.getSystem(), 3));
        }
        else if(mLCNetworkStatusBroadcastExplicit != null)
        {
            sb.append(WACN_LABEL).append(format(mLCNetworkStatusBroadcastExplicit.getWACN(), 5));
            sb.append(SYSTEM_LABEL).append(format(mLCNetworkStatusBroadcastExplicit.getSystem(), 3));
        }
        else
        {
            sb.append(UNKNOWN_LABEL);
        }

        sb.append("\n\nCurrent Site\n");

        if(mTSBKRFSSStatusBroadcast != null)
        {
            sb.append(" " + SYSTEM_LABEL).append(format(mTSBKRFSSStatusBroadcast.getSystem(), 3));
            sb.append(NAC_LABEL).append(format(mTSBKRFSSStatusBroadcast.getNAC(), 3));
            sb.append(RFSS_LABEL).append(format(mTSBKRFSSStatusBroadcast.getRfss(), 2));
            sb.append(SITE_LABEL).append(format(mTSBKRFSSStatusBroadcast.getSite(), 2));
            sb.append(LRA_LABEL).append(format(mTSBKRFSSStatusBroadcast.getLocationRegistrationArea(), 2));
            sb.append(STATUS_LABEL).append(mTSBKRFSSStatusBroadcast.isActiveNetworkConnectionToRfssControllerSite() ?
                "ACTIVE RFSS NETWORK CONNECTION\n" : "\n");
            sb.append(PRIMARY_CONTROL_CHANNEL_LABEL).append(mTSBKRFSSStatusBroadcast.getChannel());
            sb.append(DOWNLINK_LABEL).append(mTSBKRFSSStatusBroadcast.getChannel().getDownlinkFrequency());
            sb.append(UPLINK_LABEL).append(mTSBKRFSSStatusBroadcast.getChannel().getUplinkFrequency()).append("\n");
        }
        else if(mLCRFSSStatusBroadcast != null)
        {
            sb.append(" " + SYSTEM_LABEL).append(format(mLCRFSSStatusBroadcast.getSystem(), 3));
            sb.append(RFSS_LABEL).append(format(mLCRFSSStatusBroadcast.getRfss(), 2));
            sb.append(SITE_LABEL).append(format(mLCRFSSStatusBroadcast.getSite(), 2));
            sb.append(LRA_LABEL).append(format(mLCRFSSStatusBroadcast.getLocationRegistrationArea(), 2)).append("\n");
            sb.append(PRIMARY_CONTROL_CHANNEL_LABEL).append(mLCRFSSStatusBroadcast.getChannel());
            sb.append(DOWNLINK_LABEL).append(mLCRFSSStatusBroadcast.getChannel().getDownlinkFrequency());
            sb.append(UPLINK_LABEL).append(mLCRFSSStatusBroadcast.getChannel().getUplinkFrequency()).append("\n");
        }
        else if(mLCRFSSStatusBroadcastExplicit != null)
        {
            sb.append(" " + RFSS_LABEL).append(mLCRFSSStatusBroadcastExplicit.getRfss());
            sb.append(SITE_LABEL).append(format(mLCRFSSStatusBroadcastExplicit.getSite(), 2));
            sb.append(LRA_LABEL).append(format(mLCRFSSStatusBroadcastExplicit.getLocationRegistrationArea(), 2)).append("\n");
            sb.append(PRIMARY_CONTROL_CHANNEL_LABEL).append(mLCRFSSStatusBroadcastExplicit.getChannel());
            sb.append(DOWNLINK_LABEL).append(mLCRFSSStatusBroadcastExplicit.getChannel().getDownlinkFrequency());
            sb.append(UPLINK_LABEL).append(mLCRFSSStatusBroadcastExplicit.getChannel().getUplinkFrequency()).append("\n");
        }
        else if(mAMBTCRFSSStatusBroadcast != null)
        {
            sb.append(" " + SYSTEM_LABEL).append(format(mAMBTCRFSSStatusBroadcast.getSystem(), 3));
            sb.append(NAC_LABEL).append(format(mAMBTCRFSSStatusBroadcast.getNAC(), 3));
            sb.append(RFSS_LABEL).append(format(mAMBTCRFSSStatusBroadcast.getRFSS(), 2));
            sb.append(SITE_LABEL).append(format(mAMBTCRFSSStatusBroadcast.getSite(), 2));
            sb.append(LRA_LABEL).append(format(mAMBTCRFSSStatusBroadcast.getLRA(), 2));
            sb.append(STATUS_LABEL).append(mAMBTCRFSSStatusBroadcast.isActiveNetworkConnectionToRfssControllerSite() ?
                "ACTIVE RFSS NETWORK CONNECTION\n" : "\n");
            sb.append(PRIMARY_CONTROL_CHANNEL_LABEL).append(mAMBTCRFSSStatusBroadcast.getChannel());
            sb.append(DOWNLINK_LABEL).append(mAMBTCRFSSStatusBroadcast.getChannel().getDownlinkFrequency());
            sb.append(UPLINK_LABEL).append(mAMBTCRFSSStatusBroadcast.getChannel().getUplinkFrequency()).append("\n");
        }
        else
        {
            sb.append(UNKNOWN_LABEL);
        }

        if(!mSecondaryControlChannels.isEmpty())
        {
            mSecondaryControlChannels
                    .entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByKey())
                    .filter(Objects::nonNull)
                    .forEach(entry -> {
                        sb.append(SECONDARY_CONTROL_CHANNEL_LABEL).append(entry.getValue());
                        sb.append(DOWNLINK_LABEL).append(entry.getValue().getDownlinkFrequency());
                        sb.append(UPLINK_LABEL).append(entry.getValue().getUplinkFrequency()).append("\n");
                    });
        }

        if(mSNDCPDataChannel != null)
        {
            sb.append(CURRENT_FDMA_DATA_CHANNEL_LABEL).append(mSNDCPDataChannel.getChannel());
            sb.append(DOWNLINK_LABEL).append(mSNDCPDataChannel.getChannel().getDownlinkFrequency());
            sb.append(UPLINK_LABEL).append(mSNDCPDataChannel.getChannel().getUplinkFrequency()).append("\n");
        }

        if(!mTDMADataChannelMap.isEmpty())
        {
            for(Map.Entry<APCO25Channel, MotorolaExplicitTDMADataChannelAnnouncement> entry: mTDMADataChannelMap.entrySet())
            {
                sb.append(ACTIVE_TDMA_DATA_CHANNEL_LABEL).append(entry.getKey());
                sb.append(DOWNLINK_LABEL).append(entry.getKey().getDownlinkFrequency());
                sb.append(UPLINK_LABEL).append(entry.getKey().getUplinkFrequency()).append("\n");
            }
        }

        if(mMotorolaBaseStationId != null)
        {
            sb.append("  STATION ID/LICENSE: ").append(mMotorolaBaseStationId.getCWID()).append("\n");
        }

        if(mTSBKSystemServiceBroadcast != null)
        {
            sb.append(AVAILABLE_SERVICES_LABEL).append(mTSBKSystemServiceBroadcast.getAvailableServices());
            sb.append(SUPPORTED_SERVICES_LABEL).append(mTSBKSystemServiceBroadcast.getSupportedServices());
        }
        else if(mLCSystemServiceBroadcast != null)
        {
            sb.append(AVAILABLE_SERVICES_LABEL).append(mLCSystemServiceBroadcast.getAvailableServices());
            sb.append(SUPPORTED_SERVICES_LABEL).append(mLCSystemServiceBroadcast.getSupportedServices());
        }

        sb.append("\nNeighbor Sites\n");
        Set<Integer> sites = new TreeSet<>();
        sites.addAll(mAMBTCNeighborSites.keySet());
        sites.addAll(mLCNeighborSites.keySet());
        sites.addAll(mLCNeighborSitesExplicit.keySet());
        sites.addAll(mTSBKNeighborSites.keySet());

        if(sites.isEmpty())
        {
            sb.append(UNKNOWN_LABEL);
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
                            sb.append(" " + SYSTEM_LABEL).append(format(ambtc.getSystem(), 3));
                            sb.append(NAC_LABEL).append(format(ambtc.getNAC(), 3));
                            sb.append(RFSS_LABEL).append(format(ambtc.getRfss(), 2));
                            sb.append(SITE_LABEL).append(format(ambtc.getSite(), 2));
                            sb.append(LRA_LABEL).append(format(ambtc.getLocationRegistrationArea(), 2));
                            sb.append(CHANNEL_LABEL).append(ambtc.getChannel());
                            sb.append(DOWNLINK_LABEL).append(ambtc.getChannel().getDownlinkFrequency());
                            sb.append(UPLINK_LABEL).append(ambtc.getChannel().getUplinkFrequency()).append("\n");
                        }
                        if(mLCNeighborSites.containsKey(site))
                        {
                            LCAdjacentSiteStatusBroadcast lc = mLCNeighborSites.get(site);
                            sb.append(" " + SYSTEM_LABEL).append(format(lc.getSystem(), 3));
                            sb.append(RFSS_LABEL).append(format(lc.getRfss(), 2));
                            sb.append(SITE_LABEL).append(format(lc.getSite(), 2));
                            sb.append(LRA_LABEL).append(format(lc.getLocationRegistrationArea(), 2));
                            sb.append(CHANNEL_LABEL).append(lc.getChannel());
                            sb.append(DOWNLINK_LABEL).append(lc.getChannel().getDownlinkFrequency());
                            sb.append(UPLINK_LABEL).append(lc.getChannel().getUplinkFrequency()).append("\n");

                        }
                        if(mLCNeighborSitesExplicit.containsKey(site))
                        {
                            LCAdjacentSiteStatusBroadcastExplicit lce = mLCNeighborSitesExplicit.get(site);
                            sb.append("  SYSTEM:---");
                            sb.append(RFSS_LABEL).append(format(lce.getRfss(), 2));
                            sb.append(SITE_LABEL).append(format(lce.getSite(), 2));
                            sb.append(LRA_LABEL).append(format(lce.getLocationRegistrationArea(), 2));
                            sb.append(CHANNEL_LABEL).append(lce.getChannel());
                            sb.append(DOWNLINK_LABEL).append(lce.getChannel().getDownlinkFrequency());
                            sb.append(UPLINK_LABEL).append(lce.getChannel().getUplinkFrequency()).append("\n");
                        }
                        if(mTSBKNeighborSites.containsKey(site))
                        {
                            AdjacentStatusBroadcast asb = mTSBKNeighborSites.get(site);
                            sb.append(" " + SYSTEM_LABEL).append(format(asb.getSystem(), 3));
                            sb.append(NAC_LABEL).append(format(asb.getNAC(), 3));
                            sb.append(RFSS_LABEL).append(format(asb.getRfss(), 2));
                            sb.append(SITE_LABEL).append(format(asb.getSite(), 2));
                            sb.append(LRA_LABEL).append(format(asb.getLocationRegistrationArea(), 2));
                            sb.append(CHANNEL_LABEL).append(asb.getChannel());
                            sb.append(DOWNLINK_LABEL).append(asb.getChannel().getDownlinkFrequency());
                            sb.append(UPLINK_LABEL).append(asb.getChannel().getUplinkFrequency());
                            sb.append(" STATUS:").append(asb.getSiteFlags()).append("\n");
                        }
                    });
        }

        sb.append("\nFrequency Bands\n");
        if(mFrequencyBandMap.isEmpty())
        {
            sb.append(UNKNOWN_LABEL);
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
