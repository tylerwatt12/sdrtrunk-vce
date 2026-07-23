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

package io.github.dsheirer.module.decode.dmr;

import io.github.dsheirer.identifier.site.SiteIdentifier;
import io.github.dsheirer.module.decode.dmr.channel.DMRChannel;
import io.github.dsheirer.module.decode.dmr.identifier.DMRNetwork;
import io.github.dsheirer.module.decode.dmr.identifier.DMRSite;
import io.github.dsheirer.module.decode.dmr.message.DMRMessage;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.hytera.HyteraAdjacentSiteInformation;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.hytera.HyteraAnnouncement;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityMaxAloha;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.ConnectPlusNeighborReport;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.ConnectPlusVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Aloha;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.AdjacentSiteInformation;
import io.github.dsheirer.module.decode.dmr.message.data.lc.LCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ConnectPlusControlChannel;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ConnectPlusTrafficChannel;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ControlChannelSystemParameters;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.TrafficChannelSystemParameters;
import io.github.dsheirer.module.decode.dmr.message.type.Model;
import io.github.dsheirer.module.decode.dmr.message.type.SystemIdentityCode;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks the network configuration details of a DMR network from the broadcast messages
 */
public class DMRNetworkConfigurationMonitor
{
    private static final String BRAND_MOTOROLA_CONNECT_PLUS = "Motorola Connect+";
    private static final String BRAND_TIER_3_TRUNKING = "Tier III Trunking";
    private static final String BRAND_MOTOROLA_CAPACITY_MAX_TIER_3_TRUNKING = "Capacity Max Tier III Trunking";
    private static final String BRAND_HYTERA_TIER_3_TRUNKING = "Hytera Tier III Trunking";
    private static final String MODE_CAPACITY_MAX_OPEN_SYSTEM = "Open System";
    private static final String MODE_CAPACITY_MAX_ADVANTAGE = "Advantage";
    private static final String CHANNEL_TYPE_CONTROL = "Control";
    private static final String CHANNEL_TYPE_TRAFFIC = "Traffic";

    private List<SiteIdentifier> mNeighborSites = new ArrayList<>();
    private Map<Integer,AdjacentSiteInformation> mTier3NeighborSites = new HashMap<>();
    private Map<Integer,DMRChannel> mObservedChannelMap = new HashMap<>();
    private DMRNetwork mDMRNetwork;
    private DMRSite mDMRSite;
    private Model mTier3Model;
    private String mBrand;
    private String mMode;
    private String mChannelType;
    private Integer mColorCodeTS1;
    private Integer mColorCodeTS2;

    /**
     * Immutable structured snapshot of the network configuration observed so far.
     */
    public synchronized DMRNetworkConfigurationSnapshot getSnapshot()
    {
        List<DMRNetworkConfigurationSnapshot.Channel> channels = mObservedChannelMap.values().stream()
            .sorted(Comparator.comparingInt(DMRChannel::getChannelNumber)
                .thenComparingInt(DMRChannel::getTimeslot))
            .map(channel -> new DMRNetworkConfigurationSnapshot.Channel(
                channel.getClass().getSimpleName(), channel.getChannelNumber(), channel.getTimeslot(),
                positive(channel.getDownlinkFrequency()), positive(channel.getUplinkFrequency())))
            .toList();
        List<DMRNetworkConfigurationSnapshot.NeighborSite> neighbors = new ArrayList<>();

        mNeighborSites.stream()
            .sorted(Comparator.comparingInt(site -> site.getValue()))
            .forEach(site -> neighbors.add(new DMRNetworkConfigurationSnapshot.NeighborSite(
                "CONNECT_PLUS", null, site.getValue(), null, null, null, null, null, null, null)));

        mTier3NeighborSites.values().stream()
            .sorted(Comparator.comparingInt(neighbor ->
                neighbor.getNeighborSystemIdentityCode().getSite().getValue()))
            .forEach(neighbor -> {
                SystemIdentityCode identity = neighbor.getNeighborSystemIdentityCode();
                DMRChannel channel = neighbor.getNeighborChannel();
                neighbors.add(new DMRNetworkConfigurationSnapshot.NeighborSite(
                    "TIER_III", value(identity.getNetwork()), value(identity.getSite()),
                    identity.getModel() != null ? identity.getModel().name() : null,
                    neighbor.getNeighborChannelNumber(), positive(channel.getDownlinkFrequency()),
                    positive(channel.getUplinkFrequency()),
                    neighbor.hasNetworkConnectionStatus() ? neighbor.isActiveNetworkConnection() : null,
                    neighbor.getConfirmedChannelPriority(), neighbor.getAdjacentChannelPriority()));
            });

        return new DMRNetworkConfigurationSnapshot("DMR", getVariant(), value(mDMRNetwork), value(mDMRSite),
            mBrand, mTier3Model != null ? mTier3Model.name() : null, mMode, mChannelType,
            mColorCodeTS1, mColorCodeTS2, channels, neighbors);
    }

    private String getVariant()
    {
        if(BRAND_MOTOROLA_CONNECT_PLUS.equals(mBrand))
        {
            return "CONNECT_PLUS";
        }
        else if(BRAND_MOTOROLA_CAPACITY_MAX_TIER_3_TRUNKING.equals(mBrand))
        {
            return "CAPACITY_MAX";
        }
        else if(BRAND_HYTERA_TIER_3_TRUNKING.equals(mBrand))
        {
            return "HYTERA_TIER_III";
        }
        else if(!mNeighborSites.isEmpty())
        {
            return "CONNECT_PLUS";
        }
        else if(mBrand != null || mTier3Model != null || mDMRNetwork != null || mDMRSite != null)
        {
            return "TIER_III";
        }
        else if(!mTier3NeighborSites.isEmpty())
        {
            return "TIER_III";
        }

        return null;
    }

    private static Integer value(io.github.dsheirer.identifier.integer.IntegerIdentifier identifier)
    {
        return identifier != null ? identifier.getValue() : null;
    }

    private static Long positive(long frequency)
    {
        return frequency > 0 ? frequency : null;
    }

    /**
     * Process a DMR message
     * @param message to process that has already been checked for isValid()
     */
    public synchronized void process(DMRMessage message)
    {
        if(message instanceof CSBKMessage csbk)
        {
            process(csbk);
        }
        else if(message instanceof LCMessage lc)
        {
            process(lc);
        }

        if(message instanceof DataMessage dm)
        {
            process(dm);
        }
    }

    /**
     * Processes data messages to capture the color code for each timeslot.
     * @param dm data message
     */
    public synchronized void process(DataMessage dm)
    {
        if(dm.getTimeslot() == 1)
        {
            mColorCodeTS1 = dm.getSlotType().getColorCode();
        }
        else if(dm.getTimeslot() == 2)
        {
            mColorCodeTS2 = dm.getSlotType().getColorCode();
        }
    }

    /**
     * Processes link control messages
     */
    public synchronized void process(LCMessage linkControl)
    {
        switch(linkControl.getOpcode())
        {
            case FULL_CAPACITY_MAX_GROUP_VOICE_CHANNEL_USER,
                FULL_CAPACITY_MAX_TALKER_ALIAS,
                FULL_CAPACITY_MAX_TALKER_ALIAS_CONTINUATION:
                mBrand = BRAND_MOTOROLA_CAPACITY_MAX_TIER_3_TRUNKING;
                break;

            case SHORT_CONNECT_PLUS_CONTROL_CHANNEL:
                if(linkControl instanceof ConnectPlusControlChannel cpcc)
                {
                    mDMRNetwork = cpcc.getNetwork();
                    mDMRSite = cpcc.getSite();
                    mChannelType = CHANNEL_TYPE_CONTROL;
                    mBrand = BRAND_MOTOROLA_CONNECT_PLUS;
                }
                break;
            case SHORT_CONNECT_PLUS_TRAFFIC_CHANNEL:
                if(linkControl instanceof ConnectPlusTrafficChannel cptc)
                {
                    mDMRNetwork = cptc.getNetwork();
                    mDMRSite = cptc.getSite();
                    mChannelType = CHANNEL_TYPE_TRAFFIC;
                    mBrand = BRAND_MOTOROLA_CONNECT_PLUS;
                }
                break;
            case SHORT_STANDARD_CONTROL_CHANNEL_SYSTEM_PARAMETERS:
                if(linkControl instanceof ControlChannelSystemParameters cc)
                {
                    SystemIdentityCode sic = cc.getSystemIdentityCode();
                    mTier3Model = sic.getModel();
                    mDMRNetwork = sic.getNetwork();
                    mDMRSite = sic.getSite();
                    mChannelType = CHANNEL_TYPE_CONTROL;

                    if(mBrand == null)
                    {
                        mBrand = BRAND_TIER_3_TRUNKING;
                    }
                }
                break;
            case SHORT_STANDARD_TRAFFIC_CHANNEL_SYSTEM_PARAMETERS:
                if(linkControl instanceof TrafficChannelSystemParameters tc)
                {
                    SystemIdentityCode sic = tc.getSystemIdentityCode();
                    mTier3Model = sic.getModel();
                    mDMRNetwork = sic.getNetwork();
                    mDMRSite = sic.getSite();
                    mChannelType = CHANNEL_TYPE_TRAFFIC;
                }

                if(mBrand == null)
                {
                    mBrand = BRAND_TIER_3_TRUNKING;
                }
                break;
            default:
                break;
        }
    }

    /**
     * Processes Control Signalling Blocks (CSBK)
     */
    public synchronized void process(CSBKMessage csbk)
    {
        switch(csbk.getOpcode())
        {
            case STANDARD_ALOHA:
                if(csbk instanceof Aloha aloha)
                {
                    if(mDMRNetwork == null || mDMRSite == null)
                    {
                        SystemIdentityCode sic = aloha.getSystemIdentityCode();

                        if(mDMRNetwork == null)
                        {
                            mDMRNetwork = sic.getNetwork();
                        }
                        if(mDMRSite == null)
                        {
                            mDMRSite = sic.getSite();
                        }
                        if(mTier3Model == null)
                        {
                            mTier3Model = sic.getModel();
                        }
                    }

                    if(mBrand == null)
                    {
                        mBrand = BRAND_TIER_3_TRUNKING;
                    }
                }
                break;
            case STANDARD_ANNOUNCEMENT:
                if(csbk instanceof AdjacentSiteInformation neighbor)
                {
                    mTier3NeighborSites.put(neighbor.getNeighborSystemIdentityCode().getSite().getValue(), neighbor);
                }
                break;
            case HYTERA_08_ANNOUNCEMENT,
                HYTERA_68_ANNOUNCEMENT:
                if(csbk instanceof HyteraAnnouncement ha)
                {
                    if(mBrand == null)
                    {
                        mBrand = BRAND_HYTERA_TIER_3_TRUNKING;
                    }

                    if(mDMRNetwork == null)
                    {
                        mDMRNetwork = ha.getSystemIdentityCode().getNetwork();
                    }
                    if(mDMRSite == null)
                    {
                        mDMRSite = ha.getSystemIdentityCode().getSite();
                    }
                    if(mTier3Model == null)
                    {
                        mTier3Model = ha.getSystemIdentityCode().getModel();
                    }

                    mBrand = BRAND_HYTERA_TIER_3_TRUNKING;
                }
                if(csbk instanceof HyteraAdjacentSiteInformation hasi)
                {
                    int site = hasi.getNeighborSystemIdentityCode().getSite().getValue();

                    if(!mTier3NeighborSites.containsKey(site))
                    {
                        mTier3NeighborSites.put(site, hasi);
                    }
                }
                break;
            case MOTOROLA_CAPMAX_ALOHA:
                if(csbk instanceof CapacityMaxAloha capacityMaxAloha)
                {
                    if(mDMRNetwork == null || mDMRSite == null)
                    {
                        SystemIdentityCode sic = capacityMaxAloha.getSystemIdentityCode();

                        if(mDMRNetwork == null)
                        {
                            mDMRNetwork = sic.getNetwork();
                        }
                        if(mDMRSite == null)
                        {
                            mDMRSite = sic.getSite();
                        }
                        if(mTier3Model == null)
                        {
                            mTier3Model = sic.getModel();
                        }
                    }

                    mChannelType = CHANNEL_TYPE_CONTROL;
                    mBrand = BRAND_MOTOROLA_CAPACITY_MAX_TIER_3_TRUNKING;
                }
                break;
            case MOTOROLA_CAPMAX_CHANNEL_UPDATE_ADVANTAGE_MODE:
                mBrand = BRAND_MOTOROLA_CAPACITY_MAX_TIER_3_TRUNKING;
                mMode = MODE_CAPACITY_MAX_ADVANTAGE;
                break;
            case MOTOROLA_CAPMAX_CHANNEL_UPDATE_OPEN_MODE:
                mBrand = BRAND_MOTOROLA_CAPACITY_MAX_TIER_3_TRUNKING;
                mMode = MODE_CAPACITY_MAX_OPEN_SYSTEM;
                break;
            case MOTOROLA_CONPLUS_NEIGHBOR_REPORT:
                if(mNeighborSites.isEmpty() && csbk instanceof ConnectPlusNeighborReport cpnr)
                {
                    mNeighborSites.addAll(cpnr.getNeighbors());
                }
                mBrand = BRAND_MOTOROLA_CONNECT_PLUS;
                break;
            case MOTOROLA_CONPLUS_VOICE_CHANNEL_USER:
                if(csbk instanceof ConnectPlusVoiceChannelUser cpvcu)
                {
                    DMRChannel channel = cpvcu.getChannel();
                    addDmrChannel(channel);
                }
                mBrand = BRAND_MOTOROLA_CONNECT_PLUS;
                break;
            default:
                break;
        }
    }

    /**
     * Adds the DMR channel to the observed channel map
     */
    private void addDmrChannel(DMRChannel dmrChannel)
    {
        mObservedChannelMap.put(dmrChannel.getValue(), dmrChannel);
    }

}
