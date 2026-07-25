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
import io.github.dsheirer.metadata.site.FactConfirmationPolicy;
import io.github.dsheirer.metadata.site.StableFactTracker;
import io.github.dsheirer.module.decode.dmr.channel.DMRAbsoluteChannel;
import io.github.dsheirer.module.decode.dmr.channel.DMRChannel;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.identifier.DMRNetwork;
import io.github.dsheirer.module.decode.dmr.identifier.DMRSite;
import io.github.dsheirer.module.decode.dmr.message.DMRMessage;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.hytera.HyteraAdjacentSiteInformation;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.hytera.HyteraAnnouncement;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityMaxAdvantageModeVoiceChannelUpdate;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityMaxAloha;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityMaxOpenModeVoiceChannelUpdate;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityPlusNeighbors;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityPlusSiteStatus;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.ConnectPlusDataChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.ConnectPlusNeighborReport;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.ConnectPlusOTAAnnouncement;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.ConnectPlusVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Aloha;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Clear;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.MoveTSCC;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.AdjacentSiteInformation;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.AnnounceChannelFrequency;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.AnnounceWithdrawTSCC;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.ChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.lc.LCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.motorola.CapacityPlusWideAreaVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.CapacityPlusRestChannel;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ConnectPlusControlChannel;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ConnectPlusTrafficChannel;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ControlChannelSystemParameters;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.TrafficChannelSystemParameters;
import io.github.dsheirer.module.decode.dmr.message.type.Model;
import io.github.dsheirer.module.decode.dmr.message.type.SystemIdentityCode;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks the network configuration details of a DMR network from the broadcast messages
 */
public class DMRNetworkConfigurationMonitor
{
    private static final FactConfirmationPolicy INITIAL_FAMILY_POLICY =
        new FactConfirmationPolicy(1, 0L, 60_000L, true);
    private static final FactConfirmationPolicy REPLACEMENT_FAMILY_POLICY =
        new FactConfirmationPolicy(3, 10_000L, 60_000L, false);
    private static final FactConfirmationPolicy OVER_THE_AIR_CHANNEL_POLICY =
        new FactConfirmationPolicy(2, 5_000L, 30_000L, false);
    private static final FactConfirmationPolicy UNRESOLVED_CHANNEL_POLICY =
        new FactConfirmationPolicy(3, 10_000L, 30_000L, false);
    private static final String BRAND_MOTOROLA_CONNECT_PLUS = "Motorola Connect+";
    private static final String BRAND_MOTOROLA_CAPACITY_PLUS = "Motorola Capacity+";
    private static final String BRAND_TIER_3_TRUNKING = "Tier III Trunking";
    private static final String BRAND_MOTOROLA_CAPACITY_MAX_TIER_3_TRUNKING = "Capacity Max Tier III Trunking";
    private static final String BRAND_HYTERA_TIER_3_TRUNKING = "Hytera Tier III Trunking";
    private static final String MODE_CAPACITY_MAX_OPEN_SYSTEM = "Open System";
    private static final String MODE_CAPACITY_MAX_ADVANTAGE = "Advantage";
    private static final String CHANNEL_TYPE_CONTROL = "Control";
    private static final String CHANNEL_TYPE_TRAFFIC = "Traffic";

    private Map<Integer,ObservedValue<SiteIdentifier>> mNeighborSites = new HashMap<>();
    private Map<Integer,ObservedValue<AdjacentSiteInformation>> mTier3NeighborSites = new HashMap<>();
    private Map<ChannelKey,ObservedChannel> mObservedChannelMap = new HashMap<>();
    private Map<ChannelKey,StableFactTracker<ObservedChannel,ChannelFactKey>> mChannelFactTrackers = new HashMap<>();
    private Map<Integer,LearnedFrequency> mOverTheAirFrequencyMap = new HashMap<>();
    private final List<TimeslotFrequency> mTimeslotFrequencies;
    private DMRNetwork mDMRNetwork;
    private DMRSite mDMRSite;
    private Model mTier3Model;
    private String mBrand;
    private String mMode;
    private String mChannelType;
    private Integer mColorCodeTS1;
    private Integer mColorCodeTS2;
    private final StableFactTracker<NetworkFamily,NetworkFamily> mNetworkFamilyTracker =
        new StableFactTracker<>(family -> family);

    public DMRNetworkConfigurationMonitor()
    {
        this(List.of());
    }

    /**
     * Constructs a monitor with a defensive copy of the configured DMR LCN-to-frequency map.
     */
    public DMRNetworkConfigurationMonitor(List<TimeslotFrequency> timeslotFrequencies)
    {
        mTimeslotFrequencies = timeslotFrequencies == null ? List.of() : timeslotFrequencies.stream()
            .filter(frequency -> frequency != null)
            .map(TimeslotFrequency::copy)
            .toList();
    }

    /**
     * Immutable structured snapshot of the network configuration observed so far.
     */
    public synchronized DMRNetworkConfigurationSnapshot getSnapshot()
    {
        List<DMRNetworkConfigurationSnapshot.Channel> channels = mObservedChannelMap.values().stream()
            .sorted(Comparator.comparingInt((ObservedChannel observed) -> observed.channel().getChannelNumber())
                .thenComparingInt(observed -> observed.channel().getTimeslot()))
            .map(observed -> new DMRNetworkConfigurationSnapshot.Channel(
                observed.channel().getClass().getSimpleName(), observed.channel().getChannelNumber(),
                observed.channel().getTimeslot(), positive(observed.channel().getDownlinkFrequency()),
                positive(observed.channel().getUplinkFrequency()), observed.roles(), observed.frequencySource(),
                observed.observedAtEpochMilliseconds()))
            .toList();
        List<DMRNetworkConfigurationSnapshot.NeighborSite> neighbors = new ArrayList<>();

        mNeighborSites.values().stream()
            .sorted(Comparator.comparingInt(observed -> observed.value().getValue()))
            .forEach(observed -> neighbors.add(new DMRNetworkConfigurationSnapshot.NeighborSite(
                "CONNECT_PLUS", null, observed.value().getValue(), null, null, null, null, null, null, null,
                observed.observedAtEpochMilliseconds())));

        mTier3NeighborSites.values().stream()
            .sorted(Comparator.comparingInt(observed ->
                observed.value().getNeighborSystemIdentityCode().getSite().getValue()))
            .forEach(observed -> {
                AdjacentSiteInformation neighbor = observed.value();
                SystemIdentityCode identity = neighbor.getNeighborSystemIdentityCode();
                DMRChannel channel = neighbor.getNeighborChannel();
                neighbors.add(new DMRNetworkConfigurationSnapshot.NeighborSite(
                    "TIER_III", value(identity.getNetwork()), value(identity.getSite()),
                    identity.getModel() != null ? identity.getModel().name() : null,
                    neighbor.getNeighborChannelNumber(), positive(channel.getDownlinkFrequency()),
                    positive(channel.getUplinkFrequency()),
                    neighbor.hasNetworkConnectionStatus() ? neighbor.isActiveNetworkConnection() : null,
                    neighbor.getConfirmedChannelPriority(), neighbor.getAdjacentChannelPriority(),
                    observed.observedAtEpochMilliseconds()));
            });

        return new DMRNetworkConfigurationSnapshot("DMR", getVariant(), value(mDMRNetwork), value(mDMRSite),
            mBrand, mTier3Model != null ? mTier3Model.name() : null, mMode, mChannelType,
            mColorCodeTS1, mColorCodeTS2, channels, neighbors);
    }

    private String getVariant()
    {
        NetworkFamily family = mNetworkFamilyTracker.getStableValue();
        return family != null ? family.name() : null;
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
        if(!acceptFamily(classify(linkControl), linkControl.getTimestamp()))
        {
            return;
        }

        if(linkControl instanceof CapacityPlusRestChannel restChannel)
        {
            mDMRSite = restChannel.getSite();
            mBrand = BRAND_MOTOROLA_CAPACITY_PLUS;
            mChannelType = CHANNEL_TYPE_CONTROL;
            addDmrChannel(restChannel.getRestChannel(),
                DMRNetworkConfigurationSnapshot.ChannelRole.CONTROL, linkControl.getTimestamp());
        }
        else if(linkControl instanceof CapacityPlusWideAreaVoiceChannelUser voiceChannelUser &&
            voiceChannelUser.hasRestChannel())
        {
            mBrand = BRAND_MOTOROLA_CAPACITY_PLUS;
            addDmrChannel(voiceChannelUser.getRestChannel(),
                DMRNetworkConfigurationSnapshot.ChannelRole.CONTROL, linkControl.getTimestamp());
        }

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
        if(!acceptFamily(classify(csbk), csbk.getTimestamp()))
        {
            return;
        }

        captureObservedChannels(csbk);

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
                    int site = neighbor.getNeighborSystemIdentityCode().getSite().getValue();
                    mTier3NeighborSites.merge(site, new ObservedValue<>(neighbor, csbk.getTimestamp()),
                        ObservedValue::merge);
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
                    mTier3NeighborSites.compute(site, (key, existing) -> existing == null ?
                        new ObservedValue<>(hasi, csbk.getTimestamp()) :
                        new ObservedValue<>(existing.value(),
                            Math.max(existing.observedAtEpochMilliseconds(), csbk.getTimestamp())));
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
                if(csbk instanceof ConnectPlusNeighborReport cpnr)
                {
                    for(SiteIdentifier site: cpnr.getNeighbors())
                    {
                        mNeighborSites.merge(site.getValue(), new ObservedValue<>(site, csbk.getTimestamp()),
                            ObservedValue::merge);
                    }
                }
                mBrand = BRAND_MOTOROLA_CONNECT_PLUS;
                break;
            case MOTOROLA_CONPLUS_VOICE_CHANNEL_USER:
                mBrand = BRAND_MOTOROLA_CONNECT_PLUS;
                break;
            default:
                break;
        }
    }

    /**
     * Captures channels that describe the local site.  Neighbor-site and vote-now channels are intentionally handled
     * separately and are not included in this map.
     */
    private void captureObservedChannels(CSBKMessage csbk)
    {
        long observedAt = csbk.getTimestamp();

        if(csbk instanceof ChannelGrant grant)
        {
            addDmrChannel(grant.getChannel(), DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC, observedAt);
        }
        else if(csbk instanceof Clear clear)
        {
            addDmrChannel(clear.getMoveToChannel(), DMRNetworkConfigurationSnapshot.ChannelRole.CONTROL, observedAt);
        }
        else if(csbk instanceof MoveTSCC move)
        {
            addDmrChannel(move.getChannel(), DMRNetworkConfigurationSnapshot.ChannelRole.CONTROL, observedAt);
        }
        else if(csbk instanceof AnnounceChannelFrequency announcement &&
            announcement.hasAbsoluteChannelParameters())
        {
            addDmrChannel(announcement.getAbsoluteChannelParameters().getChannel(),
                DMRNetworkConfigurationSnapshot.ChannelRole.OBSERVED, observedAt);
        }
        else if(csbk instanceof AnnounceWithdrawTSCC announcement)
        {
            if(announcement.hasChannel1() && announcement.isChannel1Add())
            {
                addDmrChannel(announcement.getChannel1(), DMRNetworkConfigurationSnapshot.ChannelRole.CONTROL,
                    observedAt);
            }
            if(announcement.hasChannel2() && announcement.isChannel2Add())
            {
                addDmrChannel(announcement.getChannel2(), DMRNetworkConfigurationSnapshot.ChannelRole.CONTROL,
                    observedAt);
            }
        }
        else if(csbk instanceof CapacityMaxOpenModeVoiceChannelUpdate update)
        {
            if(update.hasTimeslot1())
            {
                addDmrChannel(update.getChannelTS1(), DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC,
                    observedAt);
            }
            if(update.hasTimeslot2())
            {
                addDmrChannel(update.getChannelTS2(), DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC,
                    observedAt);
            }
        }
        else if(csbk instanceof CapacityMaxAdvantageModeVoiceChannelUpdate update)
        {
            if(update.hasChannel1Timeslot1())
            {
                addDmrChannel(update.getChannel1TS1(), DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC,
                    observedAt);
            }
            if(update.hasChannel1Timeslot2())
            {
                addDmrChannel(update.getChannel1TS2(), DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC,
                    observedAt);
            }
            if(update.hasChannel2Timeslot1())
            {
                addDmrChannel(update.getChannel2TS1(), DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC,
                    observedAt);
            }
            if(update.hasChannel2Timeslot2())
            {
                addDmrChannel(update.getChannel2TS2(), DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC,
                    observedAt);
            }
        }
        else if(csbk instanceof CapacityPlusSiteStatus status)
        {
            mBrand = BRAND_MOTOROLA_CAPACITY_PLUS;
            mChannelType = CHANNEL_TYPE_CONTROL;
            addDmrChannel(status.getRestChannel(), DMRNetworkConfigurationSnapshot.ChannelRole.CONTROL, observedAt);
            status.getActiveLsnMap().values().forEach(channel ->
                addDmrChannel(channel, DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC, observedAt));
        }
        else if(csbk instanceof CapacityPlusNeighbors neighbors)
        {
            mBrand = BRAND_MOTOROLA_CAPACITY_PLUS;
            mDMRSite = neighbors.getSite();
            mChannelType = CHANNEL_TYPE_CONTROL;
            addDmrChannel(neighbors.getRestChannel(), DMRNetworkConfigurationSnapshot.ChannelRole.CONTROL,
                observedAt);
        }
        else if(csbk instanceof ConnectPlusVoiceChannelUser voice)
        {
            addDmrChannel(voice.getChannel(), DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC, observedAt);
        }
        else if(csbk instanceof ConnectPlusDataChannelGrant data)
        {
            addDmrChannel(data.getChannel(), DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC, observedAt);
        }
        else if(csbk instanceof ConnectPlusOTAAnnouncement ota)
        {
            addDmrChannel(ota.getDataChannel(), DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC, observedAt);
        }
    }

    /**
     * Adds or improves an observed DMR channel.  Absolute over-the-air frequency data outranks a configured mapping,
     * and a configured mapping outranks an unresolved LCN.  A later unresolved message can therefore never erase a
     * useful frequency.
     */
    private void addDmrChannel(DMRChannel dmrChannel, DMRNetworkConfigurationSnapshot.ChannelRole role,
                               long observedAtEpochMilliseconds)
    {
        if(dmrChannel == null)
        {
            return;
        }

        boolean absolute = dmrChannel instanceof DMRAbsoluteChannel;
        boolean hasFrequency = dmrChannel.getDownlinkFrequency() > 0 || dmrChannel.getUplinkFrequency() > 0;
        boolean confirmedLearnedFrequency = false;

        if(!absolute && mOverTheAirFrequencyMap.containsKey(dmrChannel.getChannelNumber()))
        {
            dmrChannel = mOverTheAirFrequencyMap.get(dmrChannel.getChannelNumber())
                .channel(dmrChannel.getChannelNumber(), dmrChannel.getTimeslot());
            absolute = true;
            hasFrequency = true;
            confirmedLearnedFrequency = true;
        }
        else if(!mTimeslotFrequencies.isEmpty())
        {
            dmrChannel.apply(mTimeslotFrequencies);
            hasFrequency = dmrChannel.getDownlinkFrequency() > 0 || dmrChannel.getUplinkFrequency() > 0;
        }

        DMRNetworkConfigurationSnapshot.FrequencySource frequencySource =
            absolute && hasFrequency ?
                DMRNetworkConfigurationSnapshot.FrequencySource.OVER_THE_AIR :
                dmrChannel.getTimeslotFrequency() != null && hasFrequency ?
                    DMRNetworkConfigurationSnapshot.FrequencySource.CONFIGURED_MAP :
                    DMRNetworkConfigurationSnapshot.FrequencySource.UNRESOLVED;
        ChannelKey key = new ChannelKey(dmrChannel.getChannelNumber(), dmrChannel.getTimeslot());
        ObservedChannel candidate = new ObservedChannel(dmrChannel, EnumSet.of(role), frequencySource,
            observedAtEpochMilliseconds);

        if(frequencySource != DMRNetworkConfigurationSnapshot.FrequencySource.CONFIGURED_MAP &&
            !confirmedLearnedFrequency)
        {
            FactConfirmationPolicy policy =
                frequencySource == DMRNetworkConfigurationSnapshot.FrequencySource.OVER_THE_AIR ?
                    OVER_THE_AIR_CHANNEL_POLICY : UNRESOLVED_CHANNEL_POLICY;
            StableFactTracker<ObservedChannel,ChannelFactKey> tracker = mChannelFactTrackers.computeIfAbsent(key,
                ignored -> new StableFactTracker<>(ChannelFactKey::from));
            tracker.observe(candidate, observedAtEpochMilliseconds, policy, ignored -> true);

            if(!tracker.hasStableValue())
            {
                return;
            }

            candidate = tracker.getStableValue();
        }

        if(frequencySource == DMRNetworkConfigurationSnapshot.FrequencySource.OVER_THE_AIR && absolute && hasFrequency)
        {
            learnOverTheAirFrequency(candidate.channel());
        }

        mObservedChannelMap.merge(key, candidate, ObservedChannel::merge);
    }

    /**
     * Retains an absolute LCN frequency so it can resolve later observations on either timeslot, and upgrades any
     * already-observed unresolved/configured instances of the same LCN.
     */
    private void learnOverTheAirFrequency(DMRChannel absoluteChannel)
    {
        LearnedFrequency learned = new LearnedFrequency(absoluteChannel.getDownlinkFrequency(),
            absoluteChannel.getUplinkFrequency());
        mOverTheAirFrequencyMap.put(absoluteChannel.getChannelNumber(), learned);
        mObservedChannelMap.replaceAll((key, observed) -> {
            if(key.logicalChannelNumber() == absoluteChannel.getChannelNumber())
            {
                return new ObservedChannel(learned.channel(key.logicalChannelNumber(), key.timeslot()),
                    observed.roles(), DMRNetworkConfigurationSnapshot.FrequencySource.OVER_THE_AIR,
                    observed.observedAtEpochMilliseconds());
            }

            return observed;
        });
    }

    private record ChannelKey(int logicalChannelNumber, int timeslot)
    {
    }

    private record LearnedFrequency(long downlink, long uplink)
    {
        private DMRAbsoluteChannel channel(int logicalChannelNumber, int timeslot)
        {
            return new DMRAbsoluteChannel(logicalChannelNumber, timeslot, downlink, uplink);
        }
    }

    private record ObservedChannel(DMRChannel channel, Set<DMRNetworkConfigurationSnapshot.ChannelRole> roles,
                                   DMRNetworkConfigurationSnapshot.FrequencySource frequencySource,
                                   long observedAtEpochMilliseconds)
    {
        private ObservedChannel
        {
            roles = Set.copyOf(roles);
        }

        private ObservedChannel merge(ObservedChannel candidate)
        {
            int candidateRank = sourceRank(candidate.frequencySource());
            int currentRank = sourceRank(frequencySource);
            ObservedChannel frequencyWinner = candidateRank > currentRank ||
                candidateRank == currentRank &&
                    candidate.observedAtEpochMilliseconds() >= observedAtEpochMilliseconds ?
                candidate : this;
            EnumSet<DMRNetworkConfigurationSnapshot.ChannelRole> mergedRoles =
                EnumSet.copyOf(roles);
            mergedRoles.addAll(candidate.roles());
            return new ObservedChannel(frequencyWinner.channel(), mergedRoles, frequencyWinner.frequencySource(),
                Math.max(observedAtEpochMilliseconds, candidate.observedAtEpochMilliseconds()));
        }

        private static int sourceRank(DMRNetworkConfigurationSnapshot.FrequencySource source)
        {
            return switch(source)
            {
                case OVER_THE_AIR -> 2;
                case CONFIGURED_MAP -> 1;
                case UNRESOLVED -> 0;
            };
        }

    }

    private record ObservedValue<T>(T value, long observedAtEpochMilliseconds)
    {
        private ObservedValue<T> merge(ObservedValue<T> candidate)
        {
            return candidate.observedAtEpochMilliseconds() >= observedAtEpochMilliseconds ? candidate : this;
        }
    }

    private boolean acceptFamily(NetworkFamily family, long timestamp)
    {
        if(family == null)
        {
            return true;
        }

        NetworkFamily previous = mNetworkFamilyTracker.getStableValue();

        //Generic Tier III messages are also used by the more specific Capacity Max and Hytera variants. They are
        //compatible evidence, not a reason to downgrade an established specific family or erase its challenger.
        if(family == NetworkFamily.TIER_III &&
            (previous == NetworkFamily.TIER_III || previous == NetworkFamily.CAPACITY_MAX ||
                previous == NetworkFamily.HYTERA_TIER_III))
        {
            return true;
        }

        FactConfirmationPolicy policy = previous == null ? INITIAL_FAMILY_POLICY : REPLACEMENT_FAMILY_POLICY;
        StableFactTracker.Result result = mNetworkFamilyTracker.observe(family, timestamp, policy, ignored -> true);
        NetworkFamily current = mNetworkFamilyTracker.getStableValue();

        if(result == StableFactTracker.Result.PROMOTED && previous != null && previous != current)
        {
            clearFamilySpecificFacts();
        }

        return current == family;
    }

    private void clearFamilySpecificFacts()
    {
        mNeighborSites.clear();
        mTier3NeighborSites.clear();
        mObservedChannelMap.clear();
        mChannelFactTrackers.clear();
        mOverTheAirFrequencyMap.clear();
        mDMRNetwork = null;
        mDMRSite = null;
        mTier3Model = null;
        mBrand = null;
        mMode = null;
        mChannelType = null;
    }

    private static NetworkFamily classify(LCMessage message)
    {
        if(message instanceof CapacityPlusRestChannel || message instanceof CapacityPlusWideAreaVoiceChannelUser)
        {
            return NetworkFamily.CAPACITY_PLUS;
        }
        else if(message instanceof ConnectPlusControlChannel || message instanceof ConnectPlusTrafficChannel)
        {
            return NetworkFamily.CONNECT_PLUS;
        }

        return switch(message.getOpcode())
        {
            case FULL_CAPACITY_MAX_GROUP_VOICE_CHANNEL_USER,
                FULL_CAPACITY_MAX_TALKER_ALIAS,
                FULL_CAPACITY_MAX_TALKER_ALIAS_CONTINUATION -> NetworkFamily.CAPACITY_MAX;
            case SHORT_STANDARD_CONTROL_CHANNEL_SYSTEM_PARAMETERS,
                SHORT_STANDARD_TRAFFIC_CHANNEL_SYSTEM_PARAMETERS -> NetworkFamily.TIER_III;
            default -> null;
        };
    }

    private static NetworkFamily classify(CSBKMessage message)
    {
        if(message instanceof HyteraAnnouncement || message instanceof HyteraAdjacentSiteInformation)
        {
            return NetworkFamily.HYTERA_TIER_III;
        }
        else if(message instanceof CapacityMaxAloha || message instanceof CapacityMaxOpenModeVoiceChannelUpdate ||
            message instanceof CapacityMaxAdvantageModeVoiceChannelUpdate)
        {
            return NetworkFamily.CAPACITY_MAX;
        }
        else if(message instanceof CapacityPlusSiteStatus || message instanceof CapacityPlusNeighbors)
        {
            return NetworkFamily.CAPACITY_PLUS;
        }
        else if(message instanceof ConnectPlusNeighborReport || message instanceof ConnectPlusVoiceChannelUser ||
            message instanceof ConnectPlusDataChannelGrant || message instanceof ConnectPlusOTAAnnouncement)
        {
            return NetworkFamily.CONNECT_PLUS;
        }
        else if(message instanceof Aloha || message instanceof AdjacentSiteInformation ||
            message instanceof AnnounceChannelFrequency || message instanceof AnnounceWithdrawTSCC ||
            message instanceof ChannelGrant || message instanceof Clear || message instanceof MoveTSCC)
        {
            return NetworkFamily.TIER_III;
        }

        return null;
    }

    private enum NetworkFamily
    {
        TIER_III,
        CONNECT_PLUS,
        CAPACITY_PLUS,
        CAPACITY_MAX,
        HYTERA_TIER_III
    }

    private record ChannelFactKey(int logicalChannelNumber, int timeslot, long downlink, long uplink,
                                  DMRNetworkConfigurationSnapshot.FrequencySource source)
    {
        private static ChannelFactKey from(ObservedChannel observed)
        {
            return new ChannelFactKey(observed.channel().getChannelNumber(), observed.channel().getTimeslot(),
                observed.channel().getDownlinkFrequency(), observed.channel().getUplinkFrequency(),
                observed.frequencySource());
        }
    }

}
