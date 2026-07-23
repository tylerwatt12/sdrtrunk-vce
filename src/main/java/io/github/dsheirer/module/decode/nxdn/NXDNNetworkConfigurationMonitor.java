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

package io.github.dsheirer.module.decode.nxdn;

import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannel;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelDFA;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelLookup;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNLayer3Message;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.AdjacentSiteInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.AdjacentSiteInformationTypeD;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.ControlChannelInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.DigitalStationIDInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.FailureStatusInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.Neighbor;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.ServiceInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.SiteInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.RepeaterFree;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.RepeaterHaltCWID;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.RepeaterIdle;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.SiteID;
import io.github.dsheirer.module.decode.nxdn.layer3.type.ChannelStructure;
import io.github.dsheirer.module.decode.nxdn.layer3.type.LocationID;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Processes NXDN Layer 3 messages to assemble a snapshot of the site's broadcast configuration details
 */
public class NXDNNetworkConfigurationMonitor
{
    private ControlChannelInformation mControlChannelInformation;
    private DigitalStationIDInformation mDigitalStationIDInformation;
    private FailureStatusInformation mFailureStatusInformation;
    private ServiceInformation mServiceInformation;
    private SiteInformation mSiteInformation;
    private Map<Integer, Neighbor> mNeighborMap = new HashMap<>();

    private AdjacentSiteInformationTypeD mTypeDNeighborA;
    private AdjacentSiteInformationTypeD mTypeDNeighborB;
    private Integer mTypeDRepeater;
    private String mTypeDRepeaterStatus;
    private List<Integer> mTypeDObservedRepeaters = new ArrayList<>();
    private SiteID mTypeDSiteID;
    private Integer mRAN;
    private boolean mObservedTypeD;

    /**
     * Constructs an instance
     */
    public NXDNNetworkConfigurationMonitor()
    {
    }

    /**
     * Immutable structured snapshot of the network configuration observed so far.
     */
    public NXDNNetworkConfigurationSnapshot getSnapshot()
    {
        LocationID currentLocation = getCurrentLocation();
        List<String> services = getServices();
        List<String> restrictions = getRestrictions();
        List<NXDNNetworkConfigurationSnapshot.Channel> controlChannels = getControlChannels();
        List<NXDNNetworkConfigurationSnapshot.NeighborSite> neighbors = getNeighbors();
        List<Integer> repeaters = mTypeDObservedRepeaters.stream().distinct().sorted().toList();
        NXDNNetworkConfigurationSnapshot.Station station = mDigitalStationIDInformation != null ?
            new NXDNNetworkConfigurationSnapshot.Station(mDigitalStationIDInformation.getCharacters(),
                mDigitalStationIDInformation.isValidCharacterCRC(),
                mDigitalStationIDInformation.getStationIDOption().toString()) : null;
        NXDNNetworkConfigurationSnapshot.SiteConfiguration siteConfiguration = null;

        if(mSiteInformation != null)
        {
            ChannelStructure structure = mSiteInformation.getChannelStructure();
            siteConfiguration = new NXDNNetworkConfigurationSnapshot.SiteConfiguration(
                mSiteInformation.getVersionNumber(), mSiteInformation.getAdjacentSiteAllocation(),
                mSiteInformation.getChannelAccessInformation().toString(),
                structure.getNumberOfBCCHFramesPerSuperFrame(), structure.getNumberOfGroupsPerRCCH(),
                structure.getNumberOfPagingFrames(), structure.getNumberOfMultiPurposeFrames(),
                structure.getNumberOfGroupIterationsPerSuperframe());
        }

        NXDNNetworkConfigurationSnapshot.FailureStatus failureStatus = mFailureStatusInformation != null ?
            new NXDNNetworkConfigurationSnapshot.FailureStatus(location(mFailureStatusInformation.getLocationID()),
                mFailureStatusInformation.getCallTimer().toString()) : null;

        return new NXDNNetworkConfigurationSnapshot("NXDN", getVariant(), mRAN, location(currentLocation),
            mTypeDSiteID != null ? mTypeDSiteID.getSite() : null,
            mTypeDSiteID != null ? mTypeDSiteID.getSiteType().name() : null,
            station, siteConfiguration, services, restrictions, failureStatus, controlChannels, neighbors,
            mTypeDRepeater, mTypeDRepeaterStatus, repeaters);
    }

    private String getVariant()
    {
        if(mObservedTypeD || mTypeDSiteID != null || mTypeDRepeater != null ||
            mTypeDNeighborA != null || mTypeDNeighborB != null)
        {
            return "TYPE_D";
        }

        return getCurrentLocation() != null || mControlChannelInformation != null || mSiteInformation != null ||
            mDigitalStationIDInformation != null || !mNeighborMap.isEmpty() ? "TYPE_C" : null;
    }

    private LocationID getCurrentLocation()
    {
        if(mSiteInformation != null)
        {
            return mSiteInformation.getLocationID();
        }
        else if(mControlChannelInformation != null)
        {
            return mControlChannelInformation.getLocationID();
        }
        else if(mServiceInformation != null)
        {
            return mServiceInformation.getLocationID();
        }
        else if(mFailureStatusInformation != null)
        {
            return mFailureStatusInformation.getLocationID();
        }

        return null;
    }

    private List<String> getServices()
    {
        if(mServiceInformation != null)
        {
            return mServiceInformation.getServiceInformation().getServices().stream()
                .map(Object::toString).toList();
        }
        else if(mSiteInformation != null)
        {
            return mSiteInformation.getServiceInformation().getServices().stream()
                .map(Object::toString).toList();
        }

        return List.of();
    }

    private List<String> getRestrictions()
    {
        if(mServiceInformation != null)
        {
            return List.copyOf(mServiceInformation.getRestrictionInformation().getRestrictions());
        }
        else if(mSiteInformation != null)
        {
            return List.copyOf(mSiteInformation.getRestrictionInformation().getRestrictions());
        }

        return List.of();
    }

    private List<NXDNNetworkConfigurationSnapshot.Channel> getControlChannels()
    {
        List<NXDNNetworkConfigurationSnapshot.Channel> channels = new ArrayList<>();

        if(mControlChannelInformation != null)
        {
            if(mControlChannelInformation.hasChannel1())
            {
                channels.add(channel("CONTROL_1", mControlChannelInformation.getChannel1(),
                    mControlChannelInformation.getFlags().name()));
            }

            if(mControlChannelInformation.hasChannel2())
            {
                channels.add(channel("CONTROL_2", mControlChannelInformation.getChannel2(),
                    mControlChannelInformation.getFlags().name()));
            }
        }
        else if(mSiteInformation != null)
        {
            if(mSiteInformation.hasChannel1())
            {
                channels.add(channel("CONTROL_1", mSiteInformation.getChannel1(), null));
            }

            if(mSiteInformation.hasChannel2())
            {
                channels.add(channel("CONTROL_2", mSiteInformation.getChannel2(), null));
            }
        }

        return channels;
    }

    private List<NXDNNetworkConfigurationSnapshot.NeighborSite> getNeighbors()
    {
        List<NXDNNetworkConfigurationSnapshot.NeighborSite> neighbors = new ArrayList<>();
        mNeighborMap.values().stream().sorted(java.util.Comparator.comparingInt(Neighbor::id))
            .forEach(neighbor -> neighbors.add(new NXDNNetworkConfigurationSnapshot.NeighborSite(
                "TYPE_C", neighbor.id(), location(neighbor.locationID()),
                channel("CONTROL", neighbor.channel(), null), null)));
        addTypeDNeighbors(neighbors, mTypeDNeighborA);
        addTypeDNeighbors(neighbors, mTypeDNeighborB);
        return neighbors;
    }

    private void addTypeDNeighbors(List<NXDNNetworkConfigurationSnapshot.NeighborSite> neighbors,
                                   AdjacentSiteInformationTypeD adjacent)
    {
        if(adjacent == null)
        {
            return;
        }

        neighbors.add(new NXDNNetworkConfigurationSnapshot.NeighborSite("TYPE_D", null,
            typeDLocation(adjacent.getSystemID1(), adjacent.getSite1().getValue()), null,
            adjacent.getSiteOption1().isIsolatedSite()));

        if(adjacent.hasSite2())
        {
            neighbors.add(new NXDNNetworkConfigurationSnapshot.NeighborSite("TYPE_D", null,
                typeDLocation(adjacent.getSystemID2(), adjacent.getSite2().getValue()), null,
                adjacent.getSiteOption2().isIsolatedSite()));
        }
    }

    private static NXDNNetworkConfigurationSnapshot.Location typeDLocation(LocationID system, int site)
    {
        return new NXDNNetworkConfigurationSnapshot.Location("TYPE_D",
            system.getSystem().getValue(), site, system.getSiteOrIntegrator().getValue());
    }

    private static NXDNNetworkConfigurationSnapshot.Location location(LocationID location)
    {
        if(location == null)
        {
            return null;
        }

        String category = location.getCategory().getValue();
        boolean typeD = "TYPE-D".equals(category);
        return new NXDNNetworkConfigurationSnapshot.Location(category, location.getSystem().getValue(),
            typeD ? null : location.getSiteOrIntegrator().getValue(),
            typeD ? location.getSiteOrIntegrator().getValue() : null);
    }

    private static NXDNNetworkConfigurationSnapshot.Channel channel(String role, NXDNChannel channel,
                                                                     String notification)
    {
        if(channel == null)
        {
            return null;
        }

        if(channel instanceof NXDNChannelDFA dfa)
        {
            return new NXDNNetworkConfigurationSnapshot.Channel(role, "DFA", null,
                dfa.getOutboundChannelNumber(), dfa.getInboundChannelNumber(),
                dfa.getBandwidth().name(), positive(dfa.getDownlinkFrequency()),
                positive(dfa.getUplinkFrequency()), notification);
        }
        else if(channel instanceof NXDNChannelLookup lookup)
        {
            return new NXDNNetworkConfigurationSnapshot.Channel(role, "CHANNEL", lookup.getChannelNumber(),
                null, null, null, positive(lookup.getDownlinkFrequency()),
                positive(lookup.getUplinkFrequency()), notification);
        }

        return new NXDNNetworkConfigurationSnapshot.Channel(role, channel.getClass().getSimpleName(),
            null, null, null, null, positive(channel.getDownlinkFrequency()),
            positive(channel.getUplinkFrequency()), notification);
    }

    private static Long positive(long frequency)
    {
        return frequency > 0 ? frequency : null;
    }

    public String getSummary()
    {
        StringBuilder sb = new StringBuilder();

        if(mDigitalStationIDInformation != null)
        {
            sb.append("Current Channel Station ID\n  ").append(mDigitalStationIDInformation).append("\n");
        }
        if(mSiteInformation != null)
        {
            sb.append("\nCurrent Site\n  ").append(mSiteInformation).append("\n");
        }
        else if(mTypeDSiteID != null)
        {
            sb.append("\nCurrent Site\n  ").append(mTypeDSiteID).append("\n");
        }
        if(mServiceInformation != null)
        {
            sb.append("\nCurrent Site Services\n  ").append(mServiceInformation).append("\n");
        }
        if(mFailureStatusInformation != null)
        {
            sb.append("\nFailure Status\n  ").append(mFailureStatusInformation).append("\n");
        }
        if(mControlChannelInformation != null)
        {
            sb.append("\nCurrent Site Control Channel\n  ").append(mControlChannelInformation).append("\n");
        }

        if(mTypeDRepeater != null)
        {
            sb.append("\nType-D Current Repeater: ").append(mTypeDRepeater).append("\n");
        }

        if(!mTypeDObservedRepeaters.isEmpty())
        {
            Collections.sort(mTypeDObservedRepeaters);
            sb.append("\nType-D Observed Repeater Numbers:").append(mTypeDObservedRepeaters).append("\n");
        }

        if(!mNeighborMap.isEmpty())
        {
            sb.append("\nNeighbor Sites\n");
            List<Integer> ids = new ArrayList<>(mNeighborMap.keySet());
            Collections.sort(ids);

            for(Integer id : ids)
            {
                sb.append("  ").append(mNeighborMap.get(id)).append("\n");
            }
        }
        else if(mTypeDNeighborA != null || mTypeDNeighborB != null)
        {
            sb.append("\nNeighbor Sites\n");

            if(mTypeDNeighborA != null)
            {
                sb.append("  ").append(mTypeDNeighborA.getSystemID1()).append(" SITE:").append(mTypeDNeighborA.getSite1()).append("\n");

                if(mTypeDNeighborA.hasSite2())
                {
                    sb.append("  ").append(mTypeDNeighborA.getSystemID2()).append(" SITE:").append(mTypeDNeighborA.getSite2()).append("\n");
                }
            }
            if(mTypeDNeighborB != null)
            {
                sb.append("  ").append(mTypeDNeighborB.getSystemID1()).append(" SITE:").append(mTypeDNeighborB.getSite1()).append("\n");

                if(mTypeDNeighborB.hasSite2())
                {
                    sb.append("  ").append(mTypeDNeighborB.getSystemID2()).append(" SITE:").append(mTypeDNeighborB.getSite2()).append("\n");
                }
            }
        }
        else
        {
            sb.append("\nNeighbor Sites\n  NONE\n");
        }

        return sb.toString();
    }

    public void process(NXDNLayer3Message layer3)
    {
        if(layer3.hasRAN())
        {
            mRAN = layer3.getRAN();
        }

        mObservedTypeD |= layer3.isTypeD();

        switch(layer3.getMessageType())
        {
            case CONTROL_OUT_23_BC_DIGITAL_STATION_ID_INFORMATION:
            case TRAFFIC_OUT_23_BC_DIGITAL_STATION_ID_INFORMATION:
            case TYPE_D_OUT_23_BC_DIGITAL_STATION_ID:
                if(layer3 instanceof DigitalStationIDInformation dsii)
                {
                    mDigitalStationIDInformation = dsii;
                }
                break;
            case CONTROL_OUT_24_BC_SITE_INFORMATION:
            case TRAFFIC_OUT_24_BC_SITE_INFORMATION:
                mSiteInformation = (SiteInformation) layer3;
                break;
            case CONTROL_OUT_25_BC_SERVICE_INFORMATION:
            case TRAFFIC_OUT_25_BC_SERVICE_INFORMATION:
            case TYPE_D_OUT_25_BC_SERVICE_INFORMATION:
                if(layer3 instanceof ServiceInformation sii)
                {
                    mServiceInformation = sii;
                }
                break;
            case CONTROL_OUT_26_BC_CONTROL_CHANNEL_INFORMATION:
            case TRAFFIC_OUT_26_BC_CONTROL_CHANNEL_INFORMATION:
                mControlChannelInformation = (ControlChannelInformation) layer3;
                break;
            case CONTROL_OUT_28_BC_FAILURE_STATUS_INFORMATION:
            case TRAFFIC_OUT_28_BC_FAILURE_STATUS_INFORMATION:
                mFailureStatusInformation = (FailureStatusInformation) layer3;
                break;
            case CONTROL_OUT_27_BC_ADJACENT_SITE_INFORMATION:
            case TRAFFIC_OUT_27_BC_ADJACENT_SITE_INFORMATION:
                if(layer3 instanceof AdjacentSiteInformation adjacent)
                {
                    if(adjacent.hasChannel1())
                    {
                        Neighbor n1 = adjacent.getNeighbor1();

                        if(n1 != null)
                        {
                            mNeighborMap.put(n1.id(), n1);

                            if(adjacent.hasChannel2())
                            {
                                Neighbor n2 = adjacent.getNeighbor2();

                                if(n2 != null)
                                {
                                    mNeighborMap.put(n2.id(), n2);

                                    if(adjacent.hasChannel3())
                                    {
                                        Neighbor n3 = adjacent.getNeighbor3();

                                        if(n3 != null)
                                        {
                                            mNeighborMap.put(n3.id(), n3);

                                            if(adjacent.hasChannel4())
                                            {
                                                Neighbor n4 = adjacent.getNeighbor4();

                                                if(n4 != null)
                                                {
                                                    mNeighborMap.put(n4.id(), n4);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            case TYPE_D_OUT_27_BC_ADJACENT_SITE_INFORMATION:
                if(layer3 instanceof AdjacentSiteInformationTypeD atd)
                {
                    if(atd.isIndex())
                    {
                        mTypeDNeighborB = atd;
                    }
                    else
                    {
                        mTypeDNeighborA = atd;
                    }
                }
                break;
            case TYPE_D_SCCH_OUT_INFO_4_REPEATER_IDLE:
                if(layer3 instanceof RepeaterIdle ri)
                {
                    mTypeDRepeater = ri.getRepeater();
                    mTypeDRepeaterStatus = "IDLE";
                    addObservedRepeater(ri.getRepeater2());
                }
                break;
            case TYPE_D_SCCH_OUT_INFO_4_REPEATER_FREE:
                if(layer3 instanceof RepeaterFree free)
                {
                    mTypeDRepeater = null;
                    mTypeDRepeaterStatus = "FREE";
                    addObservedRepeater(free.getRepeater());
                    addObservedRepeater(free.getRepeater2());
                }
                break;
            case TYPE_D_SCCH_OUT_INFO_4_REPEATER_HALT:
                if(layer3 instanceof RepeaterHaltCWID halt)
                {
                    mTypeDRepeater = halt.getRepeater();
                    mTypeDRepeaterStatus = "HALTED_CWID";
                    addObservedRepeater(halt.getRepeater2());
                }
                break;
            case TYPE_D_SCCH_OUT_INFO_4_SITE_ID:
                if(layer3 instanceof SiteID siteID)
                {
                    mTypeDSiteID = siteID;
                }
                break;
        }
    }

    private void addObservedRepeater(int repeater)
    {
        if(repeater > 0 && !mTypeDObservedRepeaters.contains(repeater))
        {
            mTypeDObservedRepeaters.add(repeater);
        }
    }
}
