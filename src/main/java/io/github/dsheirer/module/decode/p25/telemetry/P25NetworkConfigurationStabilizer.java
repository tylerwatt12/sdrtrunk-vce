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

package io.github.dsheirer.module.decode.p25.telemetry;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.P25SiteIdentifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stabilizes freshly observed P25 RF configuration facts before they are used by external consumers.
 */
public class P25NetworkConfigurationStabilizer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(P25NetworkConfigurationStabilizer.class);
    static final int STATIC_OBSERVATION_THRESHOLD = 3;
    static final long STATIC_MINIMUM_AGE_MILLISECONDS = TimeUnit.SECONDS.toMillis(30);
    static final int DYNAMIC_OBSERVATION_THRESHOLD = 2;
    static final long DYNAMIC_MINIMUM_AGE_MILLISECONDS = TimeUnit.SECONDS.toMillis(10);
    static final int UI_OBSERVATION_THRESHOLD = 3;
    static final long UI_MINIMUM_AGE_MILLISECONDS = TimeUnit.SECONDS.toMillis(10);
    static final long CANDIDATE_EXPIRATION_MILLISECONDS = TimeUnit.MINUTES.toMillis(10);
    static final int MAXIMUM_STABLE_CONTROL_CHANNEL_FREQUENCIES = 12;

    private final String mDecoder;
    private final P25StableFactTracker<P25NetworkConfigurationSnapshot.Network> mNetwork =
        new P25StableFactTracker<>(P25NetworkConfigurationStabilizer::objectKey);
    private final P25StableFactTracker<P25NetworkConfigurationSnapshot.CurrentSite> mCurrentSite =
        new P25StableFactTracker<>(P25NetworkConfigurationStabilizer::objectKey);
    private final Map<String,P25StableFactTracker<P25NetworkConfigurationSnapshot.Channel>> mChannels = new TreeMap<>();
    private final Map<String,P25StableFactTracker<P25NetworkConfigurationSnapshot.NeighborSite>> mNeighborSites =
        new TreeMap<>();
    private final Map<String,P25StableFactTracker<P25NetworkConfigurationSnapshot.FrequencyBand>> mFrequencyBands =
        new TreeMap<>();
    private final Map<String,P25StableFactTracker<P25NetworkConfigurationSnapshot.PatchGroup>> mPatchGroups =
        new TreeMap<>();
    private final Map<String,P25StableFactTracker<P25NetworkConfigurationSnapshot.TalkerAlias>> mTalkerAliases =
        new TreeMap<>();
    private final P25StableFactTracker<IChannelDescriptor> mCurrentControlChannel =
        new P25StableFactTracker<>(P25NetworkConfigurationStabilizer::channelDescriptorKey);
    private final Map<String,P25StableFactTracker<IChannelDescriptor>> mSecondaryControlChannels = new TreeMap<>();
    private final P25StableFactTracker<Identifier<?>> mWacn = new P25StableFactTracker<>(
        P25NetworkConfigurationStabilizer::identifierKey);
    private final P25StableFactTracker<Identifier<?>> mSystem = new P25StableFactTracker<>(
        P25NetworkConfigurationStabilizer::identifierKey);
    private final P25StableFactTracker<Identifier<?>> mRfss = new P25StableFactTracker<>(
        P25NetworkConfigurationStabilizer::identifierKey);
    private final P25StableFactTracker<Identifier<?>> mSite = new P25StableFactTracker<>(
        P25NetworkConfigurationStabilizer::identifierKey);
    private final Set<Long> mRejectedControlChannelFrequencies = new TreeSet<>();

    /**
     * Constructs a stabilizer for the decoder.
     * @param decoder decoder name to use in stable snapshots.
     */
    public P25NetworkConfigurationStabilizer(String decoder)
    {
        mDecoder = decoder;
    }

    /**
     * Resets all candidate and stable state.
     */
    public synchronized void reset()
    {
        mNetwork.reset();
        mCurrentSite.reset();
        mChannels.clear();
        mNeighborSites.clear();
        mFrequencyBands.clear();
        mPatchGroups.clear();
        mTalkerAliases.clear();
        mCurrentControlChannel.reset();
        mSecondaryControlChannels.clear();
        mWacn.reset();
        mSystem.reset();
        mRfss.reset();
        mSite.reset();
        mRejectedControlChannelFrequencies.clear();
    }

    /**
     * Observes a fresh, message-scoped snapshot.
     * @param observation freshly decoded facts.
     * @param timestamp observation timestamp.
     */
    public synchronized void observe(P25NetworkConfigurationSnapshot observation, long timestamp)
    {
        expireCandidates(timestamp);

        if(observation == null)
        {
            return;
        }

        observeStatic(mNetwork, observation.network(), timestamp);
        observeStatic(mCurrentSite, observation.currentSite(), timestamp);

        for(P25NetworkConfigurationSnapshot.Channel channel: list(observation.channels()))
        {
            observeChannel(channel, timestamp);
        }

        for(P25NetworkConfigurationSnapshot.NeighborSite neighborSite: list(observation.neighborSites()))
        {
            observeStatic(mNeighborSites, neighborSiteKey(neighborSite), neighborSite, timestamp);
        }

        for(P25NetworkConfigurationSnapshot.FrequencyBand frequencyBand: list(observation.frequencyBands()))
        {
            observeStatic(mFrequencyBands, frequencyBandKey(frequencyBand), frequencyBand, timestamp);
        }

        observePatchGroups(observation.patchGroups(), timestamp);

        for(P25NetworkConfigurationSnapshot.TalkerAlias talkerAlias: list(observation.talkerAliases()))
        {
            observeTalkerAlias(talkerAlias, timestamp);
        }
    }

    /**
     * Observes one or more patch groups from a fresh decoded message.
     */
    public synchronized void observePatchGroups(List<P25NetworkConfigurationSnapshot.PatchGroup> patchGroups,
                                                long timestamp)
    {
        expireCandidates(timestamp);

        for(P25NetworkConfigurationSnapshot.PatchGroup patchGroup: list(patchGroups))
        {
            observeDynamic(mPatchGroups, patchGroupKey(patchGroup), patchGroup, timestamp);
        }
    }

    /**
     * Observes a patch group identifier from a fresh decoded message.
     */
    public synchronized void observePatchGroup(PatchGroupIdentifier patchGroupIdentifier, long timestamp)
    {
        if(patchGroupIdentifier != null)
        {
            observePatchGroups(List.of(toSnapshot(patchGroupIdentifier)), timestamp);
        }
    }

    /**
     * Observes all patch group identifiers in a fresh decoded message.
     */
    public synchronized void observePatchGroupsFromIdentifiers(List<Identifier> identifiers, long timestamp)
    {
        if(identifiers == null || identifiers.isEmpty())
        {
            return;
        }

        List<P25NetworkConfigurationSnapshot.PatchGroup> patchGroups = new ArrayList<>();

        for(Identifier identifier: identifiers)
        {
            if(identifier instanceof PatchGroupIdentifier patchGroupIdentifier)
            {
                patchGroups.add(toSnapshot(patchGroupIdentifier));
            }
        }

        observePatchGroups(patchGroups, timestamp);
    }

    /**
     * Removes a patch group when a fresh deactivate/delete message is decoded.
     */
    public synchronized void removePatchGroup(PatchGroupIdentifier patchGroupIdentifier)
    {
        if(patchGroupIdentifier != null)
        {
            mPatchGroups.remove(patchGroupKey(toSnapshot(patchGroupIdentifier)));
        }
    }

    /**
     * Removes all patch group identifiers in a fresh decoded message.
     */
    public synchronized void removePatchGroupsFromIdentifiers(List<Identifier> identifiers)
    {
        if(identifiers == null || identifiers.isEmpty())
        {
            return;
        }

        for(Identifier identifier: identifiers)
        {
            if(identifier instanceof PatchGroupIdentifier patchGroupIdentifier)
            {
                removePatchGroup(patchGroupIdentifier);
            }
        }
    }

    /**
     * Observes a talker alias from a fresh decoded message.
     */
    public synchronized void observeTalkerAlias(int radio, String alias, long timestamp)
    {
        if(radio > 0 && alias != null && !alias.isBlank())
        {
            observeTalkerAlias(new P25NetworkConfigurationSnapshot.TalkerAlias(radio, alias), timestamp);
        }
    }

    /**
     * Observes a decoded current control channel and returns the stable value for UI/control-channel consumers.
     */
    public synchronized IChannelDescriptor observeCurrentControlChannel(IChannelDescriptor channel, long timestamp)
    {
        expireCandidates(timestamp);
        mCurrentControlChannel.observe(channel, timestamp, UI_OBSERVATION_THRESHOLD, UI_MINIMUM_AGE_MILLISECONDS,
            CANDIDATE_EXPIRATION_MILLISECONDS, true, ignored -> true);
        return mCurrentControlChannel.getStableValue();
    }

    /**
     * Observes a decoded secondary/alternate control channel and returns the stable value for that channel.
     */
    public synchronized IChannelDescriptor observeSecondaryControlChannel(IChannelDescriptor channel, long timestamp)
    {
        expireCandidates(timestamp);
        String key = channelDescriptorKey(channel);

        if(key == null)
        {
            return null;
        }

        P25StableFactTracker<IChannelDescriptor> tracker = mSecondaryControlChannels.computeIfAbsent(key,
            ignored -> new P25StableFactTracker<>(P25NetworkConfigurationStabilizer::channelDescriptorKey));
        tracker.observe(channel, timestamp, UI_OBSERVATION_THRESHOLD, UI_MINIMUM_AGE_MILLISECONDS,
            CANDIDATE_EXPIRATION_MILLISECONDS, false, ignored -> true);
        return tracker.getStableValue();
    }

    /**
     * Observes decoded P25 site identifiers and returns the stable combined identifier.
     */
    public synchronized P25SiteIdentifier observeSiteIdentifier(P25SiteIdentifier siteIdentifier, long timestamp)
    {
        expireCandidates(timestamp);

        if(siteIdentifier != null)
        {
            observeUiFact(mWacn, siteIdentifier.getWacn(), timestamp, true);
            observeUiFact(mSystem, siteIdentifier.getSystem(), timestamp, true);
            observeUiFact(mRfss, siteIdentifier.getRfss(), timestamp, true);
            observeUiFact(mSite, siteIdentifier.getSite(), timestamp, true);
        }

        return getStableSiteIdentifier();
    }

    /**
     * Stable P25 site identifiers learned from network/RFSS status messages.
     */
    public synchronized P25SiteIdentifier getStableSiteIdentifier()
    {
        if(mWacn.getStableValue() != null || mSystem.getStableValue() != null ||
            mRfss.getStableValue() != null || mSite.getStableValue() != null)
        {
            return new P25SiteIdentifier(mWacn.getStableValue(), mSystem.getStableValue(),
                mRfss.getStableValue(), mSite.getStableValue());
        }

        return null;
    }

    /**
     * Stable current-site primary and secondary control channel downlink frequencies.
     */
    public synchronized Set<Long> getStableCurrentSiteControlFrequencies()
    {
        Set<Long> frequencies = new TreeSet<>();

        for(P25StableFactTracker<P25NetworkConfigurationSnapshot.Channel> tracker: mChannels.values())
        {
            P25NetworkConfigurationSnapshot.Channel channel = tracker.getStableValue();

            if(isControlChannel(channel) && channel.downlink() != null && channel.downlink() > 0)
            {
                frequencies.add(channel.downlink());
            }
        }

        return frequencies;
    }

    /**
     * Current stable snapshot.
     */
    public synchronized P25NetworkConfigurationSnapshot getSnapshot()
    {
        return new P25NetworkConfigurationSnapshot(mDecoder, mNetwork.getStableValue(), mCurrentSite.getStableValue(),
            stableValues(mChannels), stableValues(mNeighborSites), stableValues(mFrequencyBands),
            stableValues(mPatchGroups), stableValues(mTalkerAliases));
    }

    private <T> void observeStatic(P25StableFactTracker<T> tracker, T value, long timestamp)
    {
        tracker.observe(value, timestamp, STATIC_OBSERVATION_THRESHOLD, STATIC_MINIMUM_AGE_MILLISECONDS,
            CANDIDATE_EXPIRATION_MILLISECONDS, true, ignored -> true);
    }

    private <T> void observeStatic(Map<String,P25StableFactTracker<T>> trackers, String key, T value, long timestamp)
    {
        if(key != null)
        {
            observeStatic(trackers.computeIfAbsent(key, ignored -> new P25StableFactTracker<>(
                P25NetworkConfigurationStabilizer::objectKey)), value, timestamp);
        }
    }

    private <T> void observeDynamic(Map<String,P25StableFactTracker<T>> trackers, String key, T value, long timestamp)
    {
        if(key != null)
        {
            trackers.computeIfAbsent(key, ignored -> new P25StableFactTracker<>(
                    P25NetworkConfigurationStabilizer::objectKey))
                .observe(value, timestamp, DYNAMIC_OBSERVATION_THRESHOLD, DYNAMIC_MINIMUM_AGE_MILLISECONDS,
                    CANDIDATE_EXPIRATION_MILLISECONDS, false, ignored -> true);
        }
    }

    private <T> void observeUiFact(P25StableFactTracker<T> tracker, T value, long timestamp, boolean promoteFirstValue)
    {
        tracker.observe(value, timestamp, UI_OBSERVATION_THRESHOLD, UI_MINIMUM_AGE_MILLISECONDS,
            CANDIDATE_EXPIRATION_MILLISECONDS, promoteFirstValue, ignored -> true);
    }

    private void observeChannel(P25NetworkConfigurationSnapshot.Channel channel, long timestamp)
    {
        String key = channelKey(channel);

        if(key == null)
        {
            return;
        }

        P25StableFactTracker<P25NetworkConfigurationSnapshot.Channel> tracker =
            mChannels.computeIfAbsent(key, ignored -> new P25StableFactTracker<>(
                P25NetworkConfigurationStabilizer::objectKey));

        P25StableFactTracker.Result result = tracker.observe(channel, timestamp, STATIC_OBSERVATION_THRESHOLD,
            STATIC_MINIMUM_AGE_MILLISECONDS, CANDIDATE_EXPIRATION_MILLISECONDS, true, this::allowChannelPromotion);

        if(result == P25StableFactTracker.Result.PROMOTED && isControlChannel(channel) && channel.downlink() != null &&
            channel.downlink() > 0)
        {
            LOGGER.info("Promoted stable P25 control channel candidate [{}] role [{}]", channel.downlink(),
                channel.role());
        }
    }

    private void observeTalkerAlias(P25NetworkConfigurationSnapshot.TalkerAlias talkerAlias, long timestamp)
    {
        observeDynamic(mTalkerAliases, talkerAliasKey(talkerAlias), talkerAlias, timestamp);
    }

    private boolean allowChannelPromotion(P25NetworkConfigurationSnapshot.Channel channel)
    {
        if(!isControlChannel(channel) || channel.downlink() == null)
        {
            return true;
        }

        if(channel.downlink() <= 0)
        {
            return false;
        }

        if(hasStableControlFrequency(channel.downlink()))
        {
            return true;
        }

        if(stableControlFrequencyCount() < MAXIMUM_STABLE_CONTROL_CHANNEL_FREQUENCIES)
        {
            return true;
        }

        if(mRejectedControlChannelFrequencies.add(channel.downlink()))
        {
            LOGGER.warn("Rejected P25 control channel candidate [{}]; stable control channel cap [{}] reached",
                channel.downlink(), MAXIMUM_STABLE_CONTROL_CHANNEL_FREQUENCIES);
        }

        return false;
    }

    private boolean hasStableControlFrequency(long frequency)
    {
        for(P25StableFactTracker<P25NetworkConfigurationSnapshot.Channel> tracker: mChannels.values())
        {
            P25NetworkConfigurationSnapshot.Channel stable = tracker.getStableValue();

            if(isControlChannel(stable) && stable.downlink() != null && stable.downlink() > 0 &&
                stable.downlink() == frequency)
            {
                return true;
            }
        }

        return false;
    }

    private int stableControlFrequencyCount()
    {
        return getStableCurrentSiteControlFrequencies().size();
    }

    private void expireCandidates(long timestamp)
    {
        expireCandidate(mNetwork, timestamp);
        expireCandidate(mCurrentSite, timestamp);
        expireCandidates(mChannels, timestamp, true);
        expireCandidates(mNeighborSites, timestamp, false);
        expireCandidates(mFrequencyBands, timestamp, false);
        expireCandidates(mPatchGroups, timestamp, false);
        expireCandidates(mTalkerAliases, timestamp, false);
        expireCandidate(mCurrentControlChannel, timestamp);
        expireCandidates(mSecondaryControlChannels, timestamp, false);
        expireCandidate(mWacn, timestamp);
        expireCandidate(mSystem, timestamp);
        expireCandidate(mRfss, timestamp);
        expireCandidate(mSite, timestamp);
    }

    private <T> void expireCandidates(Map<String,P25StableFactTracker<T>> trackers, long timestamp,
                                      boolean logControlChannels)
    {
        for(P25StableFactTracker<T> tracker: trackers.values())
        {
            T expired = expireCandidate(tracker, timestamp);

            if(logControlChannels && expired instanceof P25NetworkConfigurationSnapshot.Channel channel &&
                isControlChannel(channel) && channel.downlink() != null && channel.downlink() > 0)
            {
                LOGGER.info("Expired unconfirmed P25 control channel candidate [{}] role [{}]", channel.downlink(),
                    channel.role());
            }
        }
    }

    private <T> T expireCandidate(P25StableFactTracker<T> tracker, long timestamp)
    {
        return tracker.expireCandidate(timestamp, CANDIDATE_EXPIRATION_MILLISECONDS);
    }

    private static boolean isControlChannel(P25NetworkConfigurationSnapshot.Channel channel)
    {
        return channel != null && ("primary_control".equals(channel.role()) ||
            "secondary_control".equals(channel.role()));
    }

    private static String channelKey(P25NetworkConfigurationSnapshot.Channel channel)
    {
        if(channel == null || channel.role() == null)
        {
            return null;
        }

        if(channel.downlink() != null)
        {
            return channel.role() + ":" + channel.downlink();
        }

        return channel.role() + ":" + channel.descriptor();
    }

    private static String channelDescriptorKey(IChannelDescriptor channel)
    {
        if(channel == null || channel.getDownlinkFrequency() <= 0)
        {
            return null;
        }

        return channel.getDownlinkFrequency() + ":" + channel.getUplinkFrequency() + ":" + channel;
    }

    private static String identifierKey(Identifier<?> identifier)
    {
        if(identifier == null)
        {
            return null;
        }

        return identifier.getIdentifierClass() + ":" + identifier.getForm() + ":" +
            identifier.getRole() + ":" + identifier;
    }

    private static String neighborSiteKey(P25NetworkConfigurationSnapshot.NeighborSite neighborSite)
    {
        if(neighborSite == null)
        {
            return null;
        }

        return value(neighborSite.system()) + ":" + value(neighborSite.nac()) + ":" + value(neighborSite.rfss()) +
            ":" + value(neighborSite.site()) + ":" + value(neighborSite.downlink());
    }

    private static String frequencyBandKey(P25NetworkConfigurationSnapshot.FrequencyBand frequencyBand)
    {
        return frequencyBand != null && frequencyBand.band() != null ? String.valueOf(frequencyBand.band()) : null;
    }

    private static String patchGroupKey(P25NetworkConfigurationSnapshot.PatchGroup patchGroup)
    {
        return patchGroup != null && patchGroup.patchGroup() != null ? String.valueOf(patchGroup.patchGroup()) : null;
    }

    private static String talkerAliasKey(P25NetworkConfigurationSnapshot.TalkerAlias talkerAlias)
    {
        return talkerAlias != null && talkerAlias.radio() != null ? String.valueOf(talkerAlias.radio()) : null;
    }

    private static String value(Object value)
    {
        return value != null ? value.toString() : "";
    }

    private static String objectKey(Object value)
    {
        return value != null ? value.toString() : null;
    }

    private static <T> List<T> stableValues(Map<String,P25StableFactTracker<T>> trackers)
    {
        List<T> values = new ArrayList<>();

        for(P25StableFactTracker<T> tracker: trackers.values())
        {
            T value = tracker.getStableValue();

            if(value != null)
            {
                values.add(value);
            }
        }

        return values;
    }

    private static <T> List<T> list(List<T> values)
    {
        return values != null ? values : Collections.emptyList();
    }

    private static P25NetworkConfigurationSnapshot.PatchGroup toSnapshot(PatchGroupIdentifier patchGroupIdentifier)
    {
        PatchGroup patchGroup = patchGroupIdentifier.getValue();
        return new P25NetworkConfigurationSnapshot.PatchGroup(patchGroup.getPatchGroup().getValue(),
            patchGroup.getVersion(),
            patchGroup.getPatchedTalkgroupIdentifiers().stream().map(TalkgroupIdentifier::getValue).sorted().toList(),
            patchGroup.getPatchedRadioIdentifiers().stream().map(RadioIdentifier::getValue).sorted().toList());
    }
}
