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

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.metadata.site.FactConfirmationPolicy;
import io.github.dsheirer.metadata.site.StableFactTracker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
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
    static final long DISCOVERY_WINDOW_MILLISECONDS = TimeUnit.SECONDS.toMillis(60);
    static final int GUARDED_STATIC_OBSERVATION_THRESHOLD = 3;
    static final long GUARDED_STATIC_MINIMUM_AGE_MILLISECONDS = TimeUnit.SECONDS.toMillis(60);
    static final int DYNAMIC_OBSERVATION_THRESHOLD = 2;
    static final long DYNAMIC_MINIMUM_AGE_MILLISECONDS = TimeUnit.SECONDS.toMillis(10);
    static final long CANDIDATE_EXPIRATION_MILLISECONDS = TimeUnit.MINUTES.toMillis(10);
    static final long STABLE_BROADCAST_FACT_EXPIRATION_MILLISECONDS = TimeUnit.MINUTES.toMillis(10);
    static final int MAXIMUM_STABLE_CONTROL_CHANNEL_FREQUENCIES = 8;
    private static final int MAXIMUM_REJECTED_CONTROL_CHANNEL_FREQUENCIES = 64;
    private static final FactConfirmationPolicy IMMEDIATE_DISCOVERY_POLICY =
        new FactConfirmationPolicy(1, 0, CANDIDATE_EXPIRATION_MILLISECONDS, true);
    private static final FactConfirmationPolicy GUARDED_STATIC_POLICY =
        new FactConfirmationPolicy(GUARDED_STATIC_OBSERVATION_THRESHOLD,
            GUARDED_STATIC_MINIMUM_AGE_MILLISECONDS, CANDIDATE_EXPIRATION_MILLISECONDS, false);
    private static final FactConfirmationPolicy DYNAMIC_POLICY =
        new FactConfirmationPolicy(DYNAMIC_OBSERVATION_THRESHOLD, DYNAMIC_MINIMUM_AGE_MILLISECONDS,
            CANDIDATE_EXPIRATION_MILLISECONDS, false);

    private final String mDecoder;
    private final StableFactTracker<P25NetworkConfigurationSnapshot.Network,
        P25NetworkConfigurationSnapshot.Network> mNetwork = tracker();
    private final StableFactTracker<P25NetworkConfigurationSnapshot.CurrentSite,
        P25NetworkConfigurationSnapshot.CurrentSite> mCurrentSite = tracker();
    private final StableFactTracker<P25NetworkConfigurationSnapshot.SiteStatus,
        P25NetworkConfigurationSnapshot.SiteStatus> mSiteStatus = tracker();
    private final Map<String,StableFactTracker<P25NetworkConfigurationSnapshot.Channel,
        P25NetworkConfigurationSnapshot.Channel>> mChannels = new TreeMap<>();
    private final Map<String,StableFactTracker<P25NetworkConfigurationSnapshot.NeighborSite,
        P25NetworkConfigurationSnapshot.NeighborSite>> mNeighborSites =
        new TreeMap<>();
    private final Map<String,StableFactTracker<P25NetworkConfigurationSnapshot.FrequencyBand,
        P25NetworkConfigurationSnapshot.FrequencyBand>> mFrequencyBands =
        new TreeMap<>();
    private final Map<String,StableFactTracker<P25NetworkConfigurationSnapshot.ForeignSystemBand,
        P25NetworkConfigurationSnapshot.ForeignSystemBand>>
        mForeignSystemBands = new TreeMap<>();
    private final Map<String,StableFactTracker<P25NetworkConfigurationSnapshot.PatchGroup,
        P25NetworkConfigurationSnapshot.PatchGroup>> mPatchGroups =
        new TreeMap<>();
    private final Map<String,StableFactTracker<P25NetworkConfigurationSnapshot.TalkerAlias,
        P25NetworkConfigurationSnapshot.TalkerAlias>> mTalkerAliases =
        new TreeMap<>();
    private final Set<Long> mRejectedControlChannelFrequencies = new LinkedHashSet<>();
    private long mDiscoveryStartedAt;

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
        mSiteStatus.reset();
        mChannels.clear();
        mNeighborSites.clear();
        mFrequencyBands.clear();
        mForeignSystemBands.clear();
        mPatchGroups.clear();
        mTalkerAliases.clear();
        mRejectedControlChannelFrequencies.clear();
        mDiscoveryStartedAt = 0;
    }

    /**
     * Clears only untrusted candidate observations after a temporary decoder reset.  Promoted site identity and RF
     * facts remain authoritative across short signal, buffer, and USB interruptions, and the initial discovery window
     * is not reopened.
     */
    public synchronized void resetCandidates()
    {
        mNetwork.resetCandidate();
        mCurrentSite.resetCandidate();
        mSiteStatus.resetCandidate();
        resetCandidates(mChannels);
        resetCandidates(mNeighborSites);
        resetCandidates(mFrequencyBands);
        resetCandidates(mForeignSystemBands);
        resetCandidates(mPatchGroups);
        resetCandidates(mTalkerAliases);
    }

    /**
     * Observes a fresh, message-scoped snapshot.
     * @param observation freshly decoded facts.
     * @param timestamp observation timestamp.
     */
    public synchronized void observe(P25NetworkConfigurationSnapshot observation, long timestamp)
    {
        timestamp = observationTimestamp(timestamp);
        expireCandidates(timestamp);

        if(observation == null)
        {
            return;
        }

        observeIdentity(mNetwork, observation.network(), timestamp);
        observeIdentity(mCurrentSite, observation.currentSite(), timestamp);

        if(observation.siteStatus() != null)
        {
            //Phase 2 timeslots have separate monitors, so merge their partial latest-value status into the shared
            //stable snapshot before replacing it.
            P25NetworkConfigurationSnapshot.SiteStatus currentStatus = mSiteStatus.getStableValue();
            P25NetworkConfigurationSnapshot.SiteStatus latestStatus = currentStatus == null ?
                observation.siteStatus() : currentStatus.merge(observation.siteStatus());
            mSiteStatus.reset();
            mSiteStatus.observe(latestStatus, timestamp, IMMEDIATE_DISCOVERY_POLICY, ignored -> true);
        }

        for(P25NetworkConfigurationSnapshot.Channel channel: list(observation.channels()))
        {
            observeChannel(channel, timestamp);
        }

        for(P25NetworkConfigurationSnapshot.NeighborSite neighborSite: list(observation.neighborSites()))
        {
            observeGuarded(mNeighborSites, neighborSiteKey(neighborSite), neighborSite, timestamp);
        }

        for(P25NetworkConfigurationSnapshot.FrequencyBand frequencyBand: list(observation.frequencyBands()))
        {
            observeGuarded(mFrequencyBands, frequencyBandKey(frequencyBand), frequencyBand, timestamp);
        }

        for(P25NetworkConfigurationSnapshot.ForeignSystemBand foreignSystemBand:
            list(observation.foreignSystemBands()))
        {
            observeGuarded(mForeignSystemBands, foreignSystemBandKey(foreignSystemBand), foreignSystemBand,
                timestamp);
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
        timestamp = observationTimestamp(timestamp);
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
            P25NetworkConfigurationSnapshot.PatchGroup snapshot = toSnapshot(patchGroupIdentifier);
            String key = patchGroupKey(snapshot);
            mPatchGroups.remove(key);
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
            timestamp = observationTimestamp(timestamp);
            observeTalkerAlias(new P25NetworkConfigurationSnapshot.TalkerAlias(radio, alias), timestamp);
        }
    }

    /**
     * Stable current-site primary and secondary control channel downlink frequencies.
     */
    public synchronized Set<Long> getStableCurrentSiteControlFrequencies()
    {
        Set<Long> frequencies = new TreeSet<>();

        for(StableFactTracker<P25NetworkConfigurationSnapshot.Channel,
            P25NetworkConfigurationSnapshot.Channel> tracker: mChannels.values())
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
            stableValues(mPatchGroups), stableValues(mTalkerAliases), mSiteStatus.getStableValue(),
            stableValues(mForeignSystemBands));
    }

    private <T> void observeIdentity(StableFactTracker<T,T> tracker, T value, long timestamp)
    {
        if(value != null)
        {
            tracker.observe(value, timestamp,
                isDiscoveryMode(timestamp) ? IMMEDIATE_DISCOVERY_POLICY : GUARDED_STATIC_POLICY, ignored -> true);
        }
    }

    private <T> void observeGuarded(Map<String,StableFactTracker<T,T>> trackers, String key, T value, long timestamp)
    {
        if(key != null && value != null)
        {
            trackers.computeIfAbsent(key, ignored -> tracker())
                .observe(value, timestamp, GUARDED_STATIC_POLICY, ignored -> true);
        }
    }

    private <T> void observeDynamic(Map<String,StableFactTracker<T,T>> trackers, String key, T value, long timestamp)
    {
        if(key != null && value != null)
        {
            trackers.computeIfAbsent(key, ignored -> tracker())
                .observe(value, timestamp, DYNAMIC_POLICY, ignored -> true);
        }
    }

    private void observeChannel(P25NetworkConfigurationSnapshot.Channel channel, long timestamp)
    {
        String key = channelKey(channel);

        if(key == null)
        {
            return;
        }

        StableFactTracker<P25NetworkConfigurationSnapshot.Channel,P25NetworkConfigurationSnapshot.Channel> tracker =
            mChannels.computeIfAbsent(key, ignored -> tracker());

        if(isControlChannel(channel))
        {
            tracker.observeAuthoritative(channel, timestamp, this::allowChannelPromotion);
        }
        else
        {
            tracker.observe(channel, timestamp, GUARDED_STATIC_POLICY, this::allowChannelPromotion);
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

        if(isCurrentControlChannel(channel))
        {
            return true;
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
            trimRejectedControlChannels();
        }

        return false;
    }

    private boolean hasStableControlFrequency(long frequency)
    {
        for(StableFactTracker<P25NetworkConfigurationSnapshot.Channel,
            P25NetworkConfigurationSnapshot.Channel> tracker: mChannels.values())
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
        expireCandidate(mSiteStatus, timestamp);
        mSiteStatus.expireStable(timestamp, STABLE_BROADCAST_FACT_EXPIRATION_MILLISECONDS);
        expireCandidates(mChannels, timestamp);
        expireCandidates(mNeighborSites, timestamp);
        expireCandidates(mFrequencyBands, timestamp);
        expireCandidates(mForeignSystemBands, timestamp);
        expireCandidates(mPatchGroups, timestamp);
        expireCandidates(mTalkerAliases, timestamp);
        expireStableBroadcastFacts(mChannels, timestamp);
        expireStableBroadcastFacts(mNeighborSites, timestamp);
        expireStableBroadcastFacts(mFrequencyBands, timestamp);
        expireStableBroadcastFacts(mForeignSystemBands, timestamp);
    }

    private <T> void expireStableBroadcastFacts(Map<String,StableFactTracker<T,T>> trackers, long timestamp)
    {
        trackers.entrySet().removeIf(entry -> {
            StableFactTracker<T,T> tracker = entry.getValue();
            tracker.expireCandidate(timestamp, CANDIDATE_EXPIRATION_MILLISECONDS);
            tracker.expireStable(timestamp, STABLE_BROADCAST_FACT_EXPIRATION_MILLISECONDS);
            return tracker.isEmpty();
        });
    }

    private <T> void expireCandidates(Map<String,StableFactTracker<T,T>> trackers, long timestamp)
    {
        trackers.entrySet().removeIf(entry -> {
            expireCandidate(entry.getValue(), timestamp);
            return entry.getValue().isEmpty();
        });
    }

    private <T> void resetCandidates(Map<String,StableFactTracker<T,T>> trackers)
    {
        for(StableFactTracker<T,T> tracker: trackers.values())
        {
            tracker.resetCandidate();
        }
    }

    private <T> void expireCandidate(StableFactTracker<T,T> tracker, long timestamp)
    {
        tracker.expireCandidate(timestamp, CANDIDATE_EXPIRATION_MILLISECONDS);
    }

    private static boolean isControlChannel(P25NetworkConfigurationSnapshot.Channel channel)
    {
        return isCurrentControlChannel(channel) || isSecondaryControlChannel(channel);
    }

    private static boolean isCurrentControlChannel(P25NetworkConfigurationSnapshot.Channel channel)
    {
        return channel != null && ("primary_control".equals(channel.role()) ||
            "current_control".equals(channel.role()));
    }

    private static boolean isSecondaryControlChannel(P25NetworkConfigurationSnapshot.Channel channel)
    {
        return channel != null && "secondary_control".equals(channel.role());
    }

    private static String channelKey(P25NetworkConfigurationSnapshot.Channel channel)
    {
        if(channel == null || channel.role() == null)
        {
            return null;
        }

        if(isCurrentControlChannel(channel))
        {
            return channel.role();
        }

        if(channel.downlink() != null)
        {
            return channel.role() + ":" + channel.downlink();
        }

        return channel.role() + ":" + channel.descriptor();
    }

    private long observationTimestamp(long timestamp)
    {
        return timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    private boolean isDiscoveryMode(long timestamp)
    {
        if(mDiscoveryStartedAt <= 0)
        {
            mDiscoveryStartedAt = timestamp;
        }

        return timestamp - mDiscoveryStartedAt <= DISCOVERY_WINDOW_MILLISECONDS;
    }

    private static String neighborSiteKey(P25NetworkConfigurationSnapshot.NeighborSite neighborSite)
    {
        if(neighborSite == null)
        {
            return null;
        }

        return value(neighborSite.system()) + ":" + value(neighborSite.rfss()) + ":" +
            value(neighborSite.site()) + ":" + value(neighborSite.channel());
    }

    private static String frequencyBandKey(P25NetworkConfigurationSnapshot.FrequencyBand frequencyBand)
    {
        return frequencyBand != null && frequencyBand.band() != null ? String.valueOf(frequencyBand.band()) : null;
    }

    private static String foreignSystemBandKey(P25NetworkConfigurationSnapshot.ForeignSystemBand band)
    {
        if(band == null || band.wacn() == null || band.system() == null || band.band() == null)
        {
            return null;
        }

        return band.wacn() + ":" + band.system() + ":" + band.band();
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

    private void trimRejectedControlChannels()
    {
        while(mRejectedControlChannelFrequencies.size() > MAXIMUM_REJECTED_CONTROL_CHANNEL_FREQUENCIES)
        {
            mRejectedControlChannelFrequencies.remove(mRejectedControlChannelFrequencies.iterator().next());
        }
    }

    private static <T> StableFactTracker<T,T> tracker()
    {
        return new StableFactTracker<>(value -> value);
    }

    private static <T> List<T> stableValues(Map<String,StableFactTracker<T,T>> trackers)
    {
        List<T> values = new ArrayList<>();

        for(StableFactTracker<T,T> tracker: trackers.values())
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
