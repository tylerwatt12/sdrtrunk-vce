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
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.metadata.site.FactConfirmationPolicy;
import io.github.dsheirer.metadata.site.StableFactTracker;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
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
    static final long PATCH_GROUP_FRESHNESS_MILLISECONDS = TimeUnit.SECONDS.toMillis(30);
    static final int MAXIMUM_STABLE_CONTROL_CHANNEL_FREQUENCIES = 8;
    private static final int MAXIMUM_SNAPSHOT_PROJECTION_ATTEMPTS = 2;
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
    private final Runnable mSnapshotCopyHook;
    private final StableFactTracker<P25NetworkConfigurationSnapshot.Network,
        P25NetworkConfigurationSnapshot.Network> mNetwork = tracker();
    private final StableFactTracker<P25NetworkConfigurationSnapshot.CurrentSite,
        P25NetworkConfigurationSnapshot.CurrentSite> mCurrentSite = tracker();
    private final StableFactTracker<P25NetworkConfigurationSnapshot.SiteStatus,
        P25NetworkConfigurationSnapshot.SiteStatus> mSiteStatus = tracker();
    private final Map<String,StableFactTracker<P25NetworkConfigurationSnapshot.Channel,
        P25NetworkConfigurationSnapshot.Channel>> mChannels = new ConcurrentSkipListMap<>();
    private final Map<String,StableFactTracker<P25NetworkConfigurationSnapshot.NeighborSite,
        P25NetworkConfigurationSnapshot.NeighborSite>> mNeighborSites =
        new ConcurrentSkipListMap<>();
    private final Map<String,StableFactTracker<P25NetworkConfigurationSnapshot.FrequencyBand,
        P25NetworkConfigurationSnapshot.FrequencyBand>> mFrequencyBands =
        new ConcurrentSkipListMap<>();
    private final Map<String,StableFactTracker<P25NetworkConfigurationSnapshot.ForeignSystemBand,
        P25NetworkConfigurationSnapshot.ForeignSystemBand>>
        mForeignSystemBands = new ConcurrentSkipListMap<>();
    private final Map<String,StableFactTracker<P25NetworkConfigurationSnapshot.PatchGroup,
        P25NetworkConfigurationSnapshot.PatchGroup>> mPatchGroups =
        new ConcurrentSkipListMap<>();
    private final Map<String,StableFactTracker<P25NetworkConfigurationSnapshot.TalkerAlias,
        P25NetworkConfigurationSnapshot.TalkerAlias>> mTalkerAliases =
        new ConcurrentSkipListMap<>();
    private final Set<Long> mRejectedControlChannelFrequencies = new LinkedHashSet<>();
    private long mDiscoveryStartedAt;
    private volatile PatchCompleteness mPatchCompleteness = PatchCompleteness.EMPTY;
    private volatile long mMutationEpoch;
    private int mMutationDepth;

    /**
     * Constructs a stabilizer for the decoder.
     * @param decoder decoder name to use in stable snapshots.
     */
    public P25NetworkConfigurationStabilizer(String decoder)
    {
        this(decoder, () -> {});
    }

    /** Test seam for proving observer-side projection never owns the decoder monitor. */
    P25NetworkConfigurationStabilizer(String decoder, Runnable snapshotCopyHook)
    {
        mDecoder = decoder;
        mSnapshotCopyHook = snapshotCopyHook != null ? snapshotCopyHook : () -> {};
    }

    /**
     * Resets all candidate and stable state.
     */
    public synchronized void reset()
    {
        beginMutation();

        try
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
            resetPatchCompleteness();
        }
        finally
        {
            endMutation();
        }
    }

    /**
     * Clears only untrusted candidate observations after a temporary decoder reset.  Promoted site identity and RF
     * facts remain authoritative across short signal, buffer, and USB interruptions, and the initial discovery window
     * is not reopened.
     */
    public synchronized void resetCandidates()
    {
        beginMutation();

        try
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
            resetPatchCompleteness();
        }
        finally
        {
            endMutation();
        }
    }

    /**
     * Observes a fresh, message-scoped snapshot.
     * @param observation freshly decoded facts.
     * @param timestamp observation timestamp.
     */
    public synchronized void observe(P25NetworkConfigurationSnapshot observation, long timestamp)
    {
        beginMutation();

        try
        {
            timestamp = observationTimestamp(timestamp);
            expireCandidates(timestamp);

            if(observation == null)
            {
                return;
            }

            noteFreshPatchScopeObservation(timestamp);

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
                P25NetworkConfigurationSnapshot.NeighborSite fact = neighborSite.withoutObservedAt();
                observeGuarded(mNeighborSites, neighborSiteKey(fact), fact, timestamp);
            }

            for(P25NetworkConfigurationSnapshot.FrequencyBand frequencyBand: list(observation.frequencyBands()))
            {
                P25NetworkConfigurationSnapshot.FrequencyBand fact = frequencyBand.withoutObservedAt();
                observeGuarded(mFrequencyBands, frequencyBandKey(fact), fact, timestamp);
            }

            for(P25NetworkConfigurationSnapshot.ForeignSystemBand foreignSystemBand:
                list(observation.foreignSystemBands()))
            {
                P25NetworkConfigurationSnapshot.ForeignSystemBand fact = foreignSystemBand.withoutObservedAt();
                observeGuarded(mForeignSystemBands, foreignSystemBandKey(fact), fact,
                    timestamp);
            }

            observePatchGroupsValue(observation.patchGroups(), timestamp);

            for(P25NetworkConfigurationSnapshot.TalkerAlias talkerAlias: list(observation.talkerAliases()))
            {
                observeTalkerAlias(talkerAlias, timestamp);
            }
        }
        finally
        {
            endMutation();
        }
    }

    /**
     * Observes one or more patch groups from a fresh decoded message.
     */
    public synchronized void observePatchGroups(List<P25NetworkConfigurationSnapshot.PatchGroup> patchGroups,
                                                long timestamp)
    {
        beginMutation();

        try
        {
            timestamp = observationTimestamp(timestamp);
            expireCandidates(timestamp);
            noteFreshPatchScopeObservation(timestamp);
            observePatchGroupsValue(patchGroups, timestamp);
        }
        finally
        {
            endMutation();
        }
    }

    /**
     * Observes a patch group identifier from a fresh decoded message.
     */
    public synchronized void observePatchGroup(PatchGroupIdentifier patchGroupIdentifier, long timestamp)
    {
        beginMutation();

        try
        {
            if(patchGroupIdentifier != null)
            {
                P25NetworkConfigurationSnapshot.PatchGroup snapshot = toSnapshot(patchGroupIdentifier);
                if(snapshot != null)
                {
                    timestamp = observationTimestamp(timestamp);
                    expireCandidates(timestamp);
                    noteFreshPatchScopeObservation(timestamp);
                    observePatchGroupsValue(List.of(snapshot), timestamp);
                }
            }
        }
        finally
        {
            endMutation();
        }
    }

    /**
     * Observes all patch group identifiers in a fresh decoded message.
     */
    public synchronized void observePatchGroupsFromIdentifiers(List<Identifier> identifiers, long timestamp)
    {
        beginMutation();

        try
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
                    P25NetworkConfigurationSnapshot.PatchGroup snapshot = toSnapshot(patchGroupIdentifier);
                    if(snapshot != null)
                    {
                        patchGroups.add(snapshot);
                    }
                }
            }

            timestamp = observationTimestamp(timestamp);
            expireCandidates(timestamp);
            noteFreshPatchScopeObservation(timestamp);
            observePatchGroupsValue(patchGroups, timestamp);
        }
        finally
        {
            endMutation();
        }
    }

    /**
     * Removes a patch group when a fresh deactivate/delete message is decoded.
     */
    public synchronized void removePatchGroup(PatchGroupIdentifier patchGroupIdentifier)
    {
        beginMutation();

        try
        {
            removePatchGroupValue(patchGroupIdentifier);
            resetPatchCompleteness();
        }
        finally
        {
            endMutation();
        }
    }

    /**
     * Removes a patch group using the decoded deactivation timestamp as the complete-snapshot watermark.
     */
    public synchronized void removePatchGroup(PatchGroupIdentifier patchGroupIdentifier, long timestamp)
    {
        beginMutation();

        try
        {
            timestamp = observationTimestamp(timestamp);
            expireCandidates(timestamp);
            noteFreshPatchScopeObservation(timestamp);
            removePatchGroupValue(patchGroupIdentifier);
        }
        finally
        {
            endMutation();
        }
    }

    /**
     * Removes all patch group identifiers in a fresh decoded message.
     */
    public synchronized void removePatchGroupsFromIdentifiers(List<Identifier> identifiers)
    {
        beginMutation();

        try
        {
            removePatchGroupsFromIdentifiersValue(identifiers);
            resetPatchCompleteness();
        }
        finally
        {
            endMutation();
        }
    }

    /**
     * Removes patch groups using the decoded deactivation timestamp as the complete-snapshot watermark.
     */
    public synchronized void removePatchGroupsFromIdentifiers(List<Identifier> identifiers, long timestamp)
    {
        beginMutation();

        try
        {
            timestamp = observationTimestamp(timestamp);
            expireCandidates(timestamp);
            noteFreshPatchScopeObservation(timestamp);
            removePatchGroupsFromIdentifiersValue(identifiers);
        }
        finally
        {
            endMutation();
        }
    }

    /**
     * Observes a talker alias from a fresh decoded message.
     */
    public synchronized void observeTalkerAlias(int radio, String alias, long timestamp)
    {
        beginMutation();

        try
        {
            if(radio > 0 && alias != null && !alias.isBlank())
            {
                timestamp = observationTimestamp(timestamp);
                observeTalkerAlias(new P25NetworkConfigurationSnapshot.TalkerAlias(radio, alias), timestamp);
            }
        }
        finally
        {
            endMutation();
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
     * @return coherent snapshot, or null when decoder mutation overlaps both bounded projection attempts.
     */
    public P25NetworkConfigurationSnapshot getSnapshot()
    {
        //Snapshot projection runs on a bounded observer worker. Stable facts are immutable, atomically published
        //values held in concurrent sorted maps, so no part of this potentially large copy owns the decoder monitor.
        //A bounded optimistic epoch check rejects a mixed projection if a decoder mutation overlaps the copy.
        for(int attempt = 0; attempt < MAXIMUM_SNAPSHOT_PROJECTION_ATTEMPTS; attempt++)
        {
            long before = mMutationEpoch;

            if((before & 1L) != 0)
            {
                continue;
            }

            P25NetworkConfigurationSnapshot snapshot = projectSnapshot();

            if(before == mMutationEpoch)
            {
                return snapshot;
            }
        }

        //Fail closed under sustained mutation. The metadata request drops a null snapshot and a later rate-limited
        //request can retry without ever delaying the decoder callback.
        return null;
    }

    private P25NetworkConfigurationSnapshot projectSnapshot()
    {
        P25NetworkConfigurationSnapshot.Network network = mNetwork.getStableValue();
        P25NetworkConfigurationSnapshot.CurrentSite currentSite = mCurrentSite.getStableValue();
        List<P25NetworkConfigurationSnapshot.Channel> channels =
            stableValues(mChannels, P25NetworkConfigurationSnapshot.Channel::withObservedAt);
        List<P25NetworkConfigurationSnapshot.NeighborSite> neighborSites =
            stableValues(mNeighborSites, P25NetworkConfigurationSnapshot.NeighborSite::withObservedAt);
        List<P25NetworkConfigurationSnapshot.FrequencyBand> frequencyBands =
            stableValues(mFrequencyBands, P25NetworkConfigurationSnapshot.FrequencyBand::withObservedAt);
        List<P25NetworkConfigurationSnapshot.PatchGroup> patchGroups = stableValues(mPatchGroups);

        //Test seam deliberately sits between the patch list and completeness watermark so a concurrent mutation
        //proves that an inconsistent authoritative active-patch snapshot is rejected by the epoch check.
        mSnapshotCopyHook.run();

        List<P25NetworkConfigurationSnapshot.TalkerAlias> talkerAliases =
            stableValues(mTalkerAliases, P25NetworkConfigurationSnapshot.TalkerAlias::withObservedAt);
        P25NetworkConfigurationSnapshot.SiteStatus siteStatus = mSiteStatus.getStableValue();
        List<P25NetworkConfigurationSnapshot.ForeignSystemBand> foreignSystemBands =
            stableValues(mForeignSystemBands, P25NetworkConfigurationSnapshot.ForeignSystemBand::withObservedAt);
        Long activePatchesObservedAt = activePatchesObservedAt();
        return new P25NetworkConfigurationSnapshot(mDecoder, network, currentSite, channels, neighborSites,
            frequencyBands, patchGroups, talkerAliases, siteStatus, foreignSystemBands, activePatchesObservedAt);
    }

    /**
     * Complete identity from the already-stabilized network and current-site facts, without copying snapshot lists.
     */
    public synchronized P25SiteIdentity getStableSiteIdentity()
    {
        return P25SiteIdentity.from(mNetwork.getStableValue(), mCurrentSite.getStableValue());
    }

    /**
     * Indicates if a site-scoped broadcast belongs to the stabilized serving site.
     */
    public boolean matchesStableSite(Identifier<?> rfss, Identifier<?> site)
    {
        P25SiteIdentity identity = P25SiteIdentity.from(mNetwork.getStableValue(), mCurrentSite.getStableValue());
        return identity != null && rfss != null && site != null &&
            Integer.valueOf(identity.rfss()).equals(rfss.getValue()) &&
            Integer.valueOf(identity.site()).equals(site.getValue());
    }

    /**
     * Stable serving-network WACN.  Some valid messages need only this network fact and must not wait for a complete
     * RFSS/site identity.
     */
    public synchronized Integer getStableNetworkWacn()
    {
        P25NetworkConfigurationSnapshot.Network network = mNetwork.getStableValue();
        Integer wacn = network != null ? network.wacn() : null;
        return wacn != null && wacn >= 0 && wacn <= 0xFFFFF ? wacn : null;
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

    private void observePatchGroupsValue(List<P25NetworkConfigurationSnapshot.PatchGroup> patchGroups,
                                         long timestamp)
    {
        for(P25NetworkConfigurationSnapshot.PatchGroup patchGroup: list(patchGroups))
        {
            observeDynamic(mPatchGroups, patchGroupKey(patchGroup), patchGroup, timestamp);
        }
    }

    private void observeChannel(P25NetworkConfigurationSnapshot.Channel channel, long timestamp)
    {
        if(channel != null)
        {
            channel = channel.withoutObservedAt();
        }

        String key = channelKey(channel);

        if(key == null)
        {
            return;
        }

        StableFactTracker<P25NetworkConfigurationSnapshot.Channel,P25NetworkConfigurationSnapshot.Channel> tracker =
            mChannels.computeIfAbsent(key, ignored -> tracker());

        if(isCurrentControlChannel(channel))
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
        if(talkerAlias != null)
        {
            talkerAlias = talkerAlias.withoutObservedAt();
        }

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
        expireStableBroadcastFacts(mPatchGroups, timestamp, PATCH_GROUP_FRESHNESS_MILLISECONDS);
        expireCandidates(mTalkerAliases, timestamp);
        expireStableBroadcastFacts(mChannels, timestamp);
        expireStableBroadcastFacts(mNeighborSites, timestamp);
        expireStableBroadcastFacts(mFrequencyBands, timestamp);
        expireStableBroadcastFacts(mForeignSystemBands, timestamp);
        //Talker aliases are merge-only observations at RadioResolve. Once delivered, absence never deletes the
        //canonical server fact, so retaining every radio ever heard in this receiver-side snapshot is unnecessary
        //and would make callback-time snapshot copies grow without bound.
        expireStableBroadcastFacts(mTalkerAliases, timestamp);
        expirePatchCompleteness(timestamp);
    }

    private <T> void expireStableBroadcastFacts(Map<String,StableFactTracker<T,T>> trackers, long timestamp)
    {
        expireStableBroadcastFacts(trackers, timestamp, STABLE_BROADCAST_FACT_EXPIRATION_MILLISECONDS);
    }

    private <T> void expireStableBroadcastFacts(Map<String,StableFactTracker<T,T>> trackers, long timestamp,
                                                long expirationMilliseconds)
    {
        trackers.entrySet().removeIf(entry -> {
            StableFactTracker<T,T> tracker = entry.getValue();
            tracker.expireCandidate(timestamp, CANDIDATE_EXPIRATION_MILLISECONDS);
            tracker.expireStable(timestamp, expirationMilliseconds);
            return tracker.isEmpty();
        });
    }

    private void noteFreshPatchScopeObservation(long timestamp)
    {
        PatchCompleteness current = mPatchCompleteness;

        if(timestamp <= 0 || timestamp <= current.lastFreshObservationAt())
        {
            return;
        }

        long startedAt = current.startedAt();

        if(startedAt <= 0 || current.lastFreshObservationAt() <= 0 ||
            timestamp - current.lastFreshObservationAt() > PATCH_GROUP_FRESHNESS_MILLISECONDS)
        {
            startedAt = timestamp;
        }

        mPatchCompleteness = new PatchCompleteness(startedAt, timestamp);
    }

    private void expirePatchCompleteness(long timestamp)
    {
        PatchCompleteness current = mPatchCompleteness;

        if(current.lastFreshObservationAt() > 0 &&
            timestamp - current.lastFreshObservationAt() > PATCH_GROUP_FRESHNESS_MILLISECONDS)
        {
            resetPatchCompleteness();
        }
    }

    private void resetPatchCompleteness()
    {
        mPatchCompleteness = PatchCompleteness.EMPTY;
    }

    private Long activePatchesObservedAt()
    {
        PatchCompleteness current = mPatchCompleteness;
        return current.startedAt() > 0 && current.lastFreshObservationAt() >= current.startedAt() &&
            current.lastFreshObservationAt() - current.startedAt() >= DISCOVERY_WINDOW_MILLISECONDS ?
            current.lastFreshObservationAt() : null;
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

        if(channel.descriptor() != null && !channel.descriptor().isBlank())
        {
            //The v3 canonical channel identity is the native descriptor. Role and resolved frequencies are mutable
            //observations of that one channel and must never produce duplicate descriptor rows on the wire.
            return channel.descriptor();
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

    /** Marks the outermost decoder mutation as active for lock-free optimistic snapshot projection. */
    private void beginMutation()
    {
        if(mMutationDepth++ == 0)
        {
            mMutationEpoch++;
        }
    }

    /** Publishes the completion of the outermost decoder mutation. Caller owns this object's decoder monitor. */
    private void endMutation()
    {
        if(--mMutationDepth == 0)
        {
            mMutationEpoch++;
        }
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

        //The v3 canonical neighbor identity is native system/RFSS/site. Its advertised channel can change and is
        //therefore a value update, not a second neighbor.
        return value(neighborSite.system()) + ":" + value(neighborSite.rfss()) + ":" +
            value(neighborSite.site());
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
        return patchGroup != null && patchGroup.localPatchGroupId() != null ?
            String.valueOf(patchGroup.localPatchGroupId()) : null;
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

    private static <T> List<T> stableValues(Map<String,StableFactTracker<T,T>> trackers,
                                            BiFunction<T,Long,T> timestampProjector)
    {
        List<T> values = new ArrayList<>();

        for(StableFactTracker<T,T> tracker: trackers.values())
        {
            StableFactTracker.StableObservation<T,T> stable = tracker.getStableObservation();
            T value = stable.value();
            long lastSeen = stable.lastSeenTimestamp();

            if(value != null && lastSeen > 0)
            {
                values.add(timestampProjector.apply(value, lastSeen));
            }
        }

        return values;
    }

    private record PatchCompleteness(long startedAt, long lastFreshObservationAt)
    {
        private static final PatchCompleteness EMPTY = new PatchCompleteness(0, 0);
    }

    private void removePatchGroupValue(PatchGroupIdentifier patchGroupIdentifier)
    {
        if(patchGroupIdentifier != null)
        {
            P25NetworkConfigurationSnapshot.PatchGroup snapshot = toSnapshot(patchGroupIdentifier);
            if(snapshot != null)
            {
                mPatchGroups.remove(patchGroupKey(snapshot));
            }
        }
    }

    private void removePatchGroupsFromIdentifiersValue(List<Identifier> identifiers)
    {
        if(identifiers == null || identifiers.isEmpty())
        {
            return;
        }

        for(Identifier identifier: identifiers)
        {
            if(identifier instanceof PatchGroupIdentifier patchGroupIdentifier)
            {
                removePatchGroupValue(patchGroupIdentifier);
            }
        }
    }

    private static <T> List<T> list(List<T> values)
    {
        return values != null ? values : Collections.emptyList();
    }

    private static P25NetworkConfigurationSnapshot.PatchGroup toSnapshot(PatchGroupIdentifier patchGroupIdentifier)
    {
        PatchGroup patchGroup = patchGroupIdentifier.getValue();
        if(patchGroup == null || patchGroup.getPatchGroup() == null ||
            patchGroup.getPatchGroup() instanceof FullyQualifiedTalkgroupIdentifier ||
            patchGroup.getPatchGroup().getValue() == null || patchGroup.getPatchGroup().getValue() < 1 ||
            patchGroup.getPatchGroup().getValue() > RadioSystemIdentityKey.MAX_P25_GROUP_ID)
        {
            //This projection is intentionally site-local. Never flatten a canonical home tuple into a false link.
            return null;
        }

        return new P25NetworkConfigurationSnapshot.PatchGroup(patchGroup.getPatchGroup().getValue(),
            patchGroup.getVersion(),
            patchGroup.getPatchedTalkgroupIdentifiers().stream()
                .filter(member -> !(member instanceof FullyQualifiedTalkgroupIdentifier))
                .map(TalkgroupIdentifier::getValue).filter(java.util.Objects::nonNull)
                .filter(value -> value >= 1 && value <= RadioSystemIdentityKey.MAX_P25_GROUP_ID)
                .distinct().sorted().toList(),
            patchGroup.getPatchedRadioIdentifiers().stream()
                .map(RadioIdentifier::getValue).filter(java.util.Objects::nonNull)
                .filter(value -> value >= 1 && value <= RadioSystemIdentityKey.MAX_P25_WORKING_UNIT_ID)
                .distinct().sorted().toList());
    }
}
