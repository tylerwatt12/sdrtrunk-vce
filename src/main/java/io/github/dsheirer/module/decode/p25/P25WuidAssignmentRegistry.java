/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.radio.ResolvedRadioIdentity;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Receiver-scoped, process-local cache of confirmed P25 working-unit assignments.
 *
 * <p>A registration that explicitly carries both a working ID and canonical subscriber may establish or replace a
 * mapping. Accepted affiliation evidence can only refresh an already-current exact mapping. Clears install
 * process-local tombstones so an older delayed message cannot immediately revive a retired assignment. Nothing in
 * this registry is loaded from or made authoritative by SQLite; a new process relearns assignments from fresh radio
 * traffic.</p>
 *
 * <p>Decoder reads are lock-free and use a fixed number of exact-key probes. Mutation callbacks make one immediate
 * {@link ReentrantLock#tryLock()} attempt. A contended registration or clear, or a saturated index, invalidates the
 * complete cache generation instead of waiting or risking a stale identity. The next accepted registration starts
 * rebuilding the cache.</p>
 */
public final class P25WuidAssignmentRegistry
{
    /** Shortest standards-valid temporary-WUID lease used when no site lease has been observed. */
    static final int DEFAULT_LEASE_MINUTES = 270;
    /** Explicit System Service Broadcast value for a WUID that does not expire. */
    static final int NO_EXPIRY_LEASE_MINUTES = 0;
    static final long DEFAULT_LEASE_MILLISECONDS = DEFAULT_LEASE_MINUTES * 60_000L;
    /** Longest standards-valid temporary-WUID lease. */
    public static final int MAX_LEASE_MINUTES = 7_890;
    public static final long MAX_LEASE_MILLISECONDS = MAX_LEASE_MINUTES * 60_000L;
    private static final int DEFAULT_SLOT_COUNT = 1 << 18;
    private static final int DEFAULT_PROBE_COUNT = 8;
    private static final int EVIDENCE_AFFILIATION = 1;
    private static final int EVIDENCE_REGISTRATION = 2;
    private static final int EVIDENCE_CLEAR = 3;

    private final LookupState mLookupState;
    /** Entries from an older generation are misses and can be reused without clearing the arrays. */
    private final AtomicLong mCacheEpoch = new AtomicLong();
    /** Odd while the single mutation owner is changing indexes; enrichment never crosses a revision. */
    private final AtomicLong mMutationRevision = new AtomicLong();
    private final int mMask;
    private final int mProbeCount;
    private final ReentrantLock mMutationLock = new ReentrantLock();
    private final AtomicLong mDroppedMutations = new AtomicLong();
    private final AtomicLong mSaturatedMutations = new AtomicLong();
    private static final int SOURCE_CAPACITY = 256;
    private static final int SYSTEM_CAPACITY = 256;
    private static final int RECENT_CHANGE_CAPACITY = 256;
    public static final long OBSERVATION_GAP_MILLISECONDS = 60_000L;
    private final AtomicReferenceArray<SourceObservation> mSources = new AtomicReferenceArray<>(SOURCE_CAPACITY);
    private final AtomicReferenceArray<SystemContinuity> mSystemContinuity = new AtomicReferenceArray<>(SYSTEM_CAPACITY);
    private final AtomicReferenceArray<SystemGap> mSystemGaps = new AtomicReferenceArray<>(SYSTEM_CAPACITY);
    private final AtomicReferenceArray<Change> mRecentChanges = new AtomicReferenceArray<>(RECENT_CHANGE_CAPACITY);
    private final AtomicLong mChangeSequence = new AtomicLong();
    private final AtomicLong mScopeRevision = new AtomicLong();
    private volatile Snapshot mLastSnapshot = new Snapshot(0, 0, 0, true, 0, List.of(), List.of(), List.of(), 0, 0);
    private final Runnable mMutationHook;
    private final Runnable mEnrichmentHook;
    private final Runnable mBeforeMutationLockHook;

    /** Kind of accepted assignment observation that may be recorded as ordinary statistics. */
    public enum Evidence
    {
        REGISTRATION,
        AFFILIATION
    }

    /**
     * Positive result from a successful process-local registration or affiliation refresh. This is observation
     * data, not a promise that it has been saved. The normal statistics pipeline may retain it on a best-effort basis
     * without feeding any persistence result back into this registry.
     */
    public record AssignmentObservation(int servingWacn, int servingSystem, int workingId,
                                        P25SubscriberIdentity subscriber, long observedAt, long expiresAt,
                                        Evidence evidence)
    {
        public AssignmentObservation
        {
            if(!validServingSystem(servingWacn, servingSystem) || validWorkingId(workingId) == null ||
                subscriber == null || observedAt <= 0 || expiresAt < observedAt || evidence == null)
            {
                throw new IllegalArgumentException("Invalid P25 WUID assignment observation");
            }
        }
    }


    public enum LeaseSource { ADVERTISED, FALLBACK }
    public enum ChangeReason { REGISTERED, REFRESHED, CLEARED, REASSIGNED, EXPIRED, OBSERVATION_GAP,
        RESET, CONTENTION, SATURATION }

    public record Entry(int servingWacn, int servingSystem, int workingId, P25SubscriberIdentity subscriber,
                        long establishedAt, long confirmedAt, long expiresAt, Evidence evidence,
                        LeaseSource leaseSource, P25SiteIdentity site, String configurationId) {}

    public record Change(long sequence, long changedAt, ChangeReason change, Integer servingWacn,
                         Integer servingSystem, Integer workingId, Integer previousWorkingId,
                         P25SubscriberIdentity subscriber, P25SiteIdentity site, String configurationId)
    {
        public Change(long sequence, long changedAt, ChangeReason change, Integer servingWacn,
                      Integer servingSystem, Integer workingId, Integer previousWorkingId,
                      P25SubscriberIdentity subscriber)
        {
            this(sequence, changedAt, change, servingWacn, servingSystem, workingId, previousWorkingId,
                subscriber, null, null);
        }
    }

    public record SystemObservation(int servingWacn, int servingSystem, boolean observing, long gapAt,
                                    List<String> configurationIds)
    {
        public SystemObservation { configurationIds = List.copyOf(configurationIds); }
    }

    /** An immutable observer view, sampled at asOf. A stale view keeps the last successful sample's time and rows. */
    public record Snapshot(long asOf, long generation, long revision, boolean stale, int usableCount,
                           List<Entry> assignments, List<Change> recentChanges, List<SystemObservation> systems,
                           long droppedMutations, long saturatedMutations)
    {
        public Snapshot
        {
            assignments = List.copyOf(assignments);
            recentChanges = List.copyOf(recentChanges);
            systems = List.copyOf(systems);
        }
    }

    private record ObservationContext(P25SiteIdentity site, String configurationId, LeaseSource leaseSource) {}
    private record SourceObservation(String configurationId, P25SiteIdentity site, long activeSince,
                                     long observedAt, boolean active) {}
    private record SystemGap(int wacn, int system, long gapAt) {}
    private record SystemContinuity(int wacn, int system, long observedAt) {}

    public P25WuidAssignmentRegistry()
    {
        this(DEFAULT_SLOT_COUNT, DEFAULT_PROBE_COUNT, () -> {}, () -> {}, () -> {});
    }

    /** Small-capacity constructor for deterministic saturation tests. */
    P25WuidAssignmentRegistry(int slotCount, int probeCount)
    {
        this(slotCount, probeCount, () -> {}, () -> {}, () -> {});
    }

    /** Test seam for proving that a contended mutation is shed instead of waiting on a decoder callback. */
    P25WuidAssignmentRegistry(int slotCount, int probeCount, Runnable mutationHook)
    {
        this(slotCount, probeCount, mutationHook, () -> {}, () -> {});
    }

    /** Test seam for pausing a lock-free enrichment before it publishes a replacement. */
    P25WuidAssignmentRegistry(int slotCount, int probeCount, Runnable mutationHook, Runnable enrichmentHook)
    {
        this(slotCount, probeCount, mutationHook, enrichmentHook, () -> {});
    }

    /** Test seam for pausing a mutation before its immediate lock attempt. */
    P25WuidAssignmentRegistry(int slotCount, int probeCount, Runnable mutationHook, Runnable enrichmentHook,
                              Runnable beforeMutationLockHook)
    {
        if(Integer.bitCount(slotCount) != 1 || slotCount < 2)
        {
            throw new IllegalArgumentException("Slot count must be a power of two");
        }
        if(probeCount < 1 || probeCount > slotCount)
        {
            throw new IllegalArgumentException("Probe count is outside the registry capacity");
        }

        mLookupState = new LookupState(slotCount);
        mMask = slotCount - 1;
        mProbeCount = probeCount;
        mMutationHook = mutationHook != null ? mutationHook : () -> {};
        mEnrichmentHook = enrichmentHook != null ? enrichmentHook : () -> {};
        mBeforeMutationLockHook = beforeMutationLockHook != null ? beforeMutationLockHook : () -> {};
    }

    /** Establishes or replaces a mapping only when both explicit identity dimensions are present. */
    public AssignmentObservation register(P25SiteIdentity servingSite, P25RadioPresence presence, long observedAt,
                                          Integer leaseMinutes)
    {
        return register(servingSite, presence, observedAt, leaseMinutes, null);
    }

    public AssignmentObservation register(P25SiteIdentity servingSite, P25RadioPresence presence, long observedAt,
                                          Integer leaseMinutes, String configurationId)
    {
        if(servingSite == null || presence == null || presence.workingId() == null || presence.subscriber() == null)
        {
            return null;
        }

        long confirmedAt = usableTimestamp(observedAt);
        if(confirmedAt <= systemGapAt(servingSite.wacn(), servingSite.system()))
        {
            return null;
        }
        if(configurationId != null && !trackedSource(servingSite, configurationId))
        {
            return null;
        }
        long expiresAt = expiresAt(confirmedAt, leaseMinutes);
        long cacheEpoch = mCacheEpoch.get();
        mBeforeMutationLockHook.run();

        if(!mMutationLock.tryLock())
        {
            mDroppedMutations.incrementAndGet();
            invalidateCache(ChangeReason.CONTENTION);
            return null;
        }

        try
        {
            beginMutation();
            mMutationHook.run();
            if(mCacheEpoch.get() != cacheEpoch)
            {
                return null;
            }

            Assignment assignment = register(mLookupState, cacheEpoch, servingSite.wacn(), servingSite.system(),
                presence.workingId(), presence.subscriber(), confirmedAt, expiresAt,
                new ObservationContext(servingSite, configurationId, leaseSource(leaseMinutes)));
            if(assignment != null)
            {
                recordChange(assignment, ChangeReason.REGISTERED, null, confirmedAt);
            }
            return observation(assignment);
        }
        finally
        {
            endMutation();
            mMutationLock.unlock();
        }
    }

    /** Refreshes only an already-current exact WUID/subscriber pair. */
    public AssignmentObservation refresh(P25SiteIdentity servingSite, P25RadioPresence presence, long observedAt,
                                         Integer leaseMinutes)
    {
        return refresh(servingSite, presence, observedAt, leaseMinutes, null);
    }

    public AssignmentObservation refresh(P25SiteIdentity servingSite, P25RadioPresence presence, long observedAt,
                                         Integer leaseMinutes, String configurationId)
    {
        if(servingSite == null || presence == null || presence.workingId() == null)
        {
            return null;
        }

        long confirmedAt = usableTimestamp(observedAt);
        if(confirmedAt <= systemGapAt(servingSite.wacn(), servingSite.system()))
        {
            return null;
        }
        if(configurationId != null && !trackedSource(servingSite, configurationId))
        {
            return null;
        }
        long expiresAt = expiresAt(confirmedAt, leaseMinutes);
        long cacheEpoch = mCacheEpoch.get();
        mBeforeMutationLockHook.run();

        if(!mMutationLock.tryLock())
        {
            //A refresh cannot change identity. Missing it only shortens the existing process-local lease.
            mDroppedMutations.incrementAndGet();
            return null;
        }

        try
        {
            beginMutation();
            mMutationHook.run();
            if(mCacheEpoch.get() != cacheEpoch)
            {
                return null;
            }

            Assignment assignment = refresh(mLookupState, cacheEpoch, servingSite.wacn(), servingSite.system(),
                presence.workingId(), presence.subscriber(), confirmedAt, expiresAt,
                new ObservationContext(servingSite, configurationId, leaseSource(leaseMinutes)));
            if(assignment != null)
            {
                recordChange(assignment, ChangeReason.REFRESHED, null, confirmedAt);
            }
            return observation(assignment);
        }
        finally
        {
            endMutation();
            mMutationLock.unlock();
        }
    }

    /**
     * Clears the current assignment identified by a WUID, canonical subscriber, or both. The clear changes only
     * runtime state and intentionally produces no positive assignment observation.
     */
    public void clear(P25SiteIdentity servingSite, P25RadioPresence presence, long observedAt, Integer leaseMinutes)
    {
        if(servingSite == null || presence == null ||
            presence.workingId() == null && presence.subscriber() == null)
        {
            return;
        }

        long clearedAt = usableTimestamp(observedAt);
        long expiresAt = expiresAt(clearedAt, leaseMinutes);
        long cacheEpoch = mCacheEpoch.get();
        mBeforeMutationLockHook.run();

        if(!mMutationLock.tryLock())
        {
            mDroppedMutations.incrementAndGet();
            invalidateCache(ChangeReason.CONTENTION);
            return;
        }

        try
        {
            beginMutation();
            mMutationHook.run();
            if(mCacheEpoch.get() != cacheEpoch)
            {
                return;
            }

            if(!clear(mLookupState, cacheEpoch, servingSite.wacn(), servingSite.system(), presence.workingId(),
                presence.subscriber(), clearedAt, expiresAt))
            {
                mSaturatedMutations.incrementAndGet();
                invalidateCache(ChangeReason.SATURATION);
            }
        }
        finally
        {
            endMutation();
            mMutationLock.unlock();
        }
    }

    /** Compatibility overload using the conservative lease. */
    public void clear(P25SiteIdentity servingSite, P25RadioPresence presence, long observedAt)
    {
        clear(servingSite, presence, observedAt, null);
    }

    /** Promotes ordinary P25 radio identifiers when a current process-local assignment exists. */
    public void enrich(P25SiteIdentity servingSite, MutableIdentifierCollection identifiers, long observedAt)
    {
        if(servingSite == null || identifiers == null)
        {
            return;
        }

        long cacheEpoch = mCacheEpoch.get();
        long mutationRevision = mMutationRevision.get();
        long scopeRevision = mScopeRevision.get();
        if((mutationRevision & 1L) != 0L)
        {
            return;
        }

        long timestamp = usableTimestamp(observedAt);
        List<Identifier> originalIdentifiers = List.copyOf(identifiers.getIdentifiers());
        for(Identifier<?> identifier: originalIdentifiers)
        {
            if(identifier instanceof RadioIdentifier radio &&
                !(identifier instanceof FullyQualifiedRadioIdentifier) &&
                (identifier.getProtocol() == Protocol.APCO25 || identifier.getProtocol() == Protocol.APCO25_PHASE2))
            {
                Assignment assignment = findWorking(cacheEpoch, servingSite.wacn(), servingSite.system(),
                    radio.getValue(), timestamp);
                if(assignment != null)
                {
                    mEnrichmentHook.run();
                }
                if(assignment != null && lookupStillCurrent(cacheEpoch, mutationRevision, scopeRevision, assignment))
                {
                    P25SubscriberIdentity subscriber = assignment.subscriber();
                    Role role = identifier.getRole();
                    identifiers.update(APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(radio.getValue(),
                        subscriber.homeWacn(), subscriber.homeSystemId(), subscriber.subscriberId(), role,
                        ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT));
                }
            }
        }

        //Identifier collections can contain more than one radio role. Roll back all promotions if a mutation or
        //global or scoped invalidation crossed the operation so callers never receive a mixed cache generation.
        if(mCacheEpoch.get() != cacheEpoch || mMutationRevision.get() != mutationRevision ||
            mScopeRevision.get() != scopeRevision)
        {
            for(Identifier<?> identifier: originalIdentifiers)
            {
                if(identifier instanceof RadioIdentifier && !(identifier instanceof FullyQualifiedRadioIdentifier) &&
                    (identifier.getProtocol() == Protocol.APCO25 ||
                        identifier.getProtocol() == Protocol.APCO25_PHASE2))
                {
                    identifiers.update(identifier);
                }
            }
        }
    }

    /** Returns an enriched immutable collection, or the original when no promotion was available. */
    public IdentifierCollection enrich(P25SiteIdentity servingSite, IdentifierCollection identifiers,
                                       long observedAt)
    {
        if(servingSite == null || identifiers == null)
        {
            return identifiers;
        }

        MutableIdentifierCollection enriched = new MutableIdentifierCollection(identifiers.getIdentifiers(),
            identifiers.getTimeslot());
        enrich(servingSite, enriched, observedAt);
        return enriched.getIdentifiers().equals(identifiers.getIdentifiers()) ? identifiers : enriched;
    }

    /** Drops every process-local assignment without touching persisted statistics. */
    public void reset()
    {
        invalidateCache(ChangeReason.RESET);
    }

    int slotCapacity()
    {
        return mLookupState.byWorkingId().length();
    }

    long droppedMutationCount()
    {
        return mDroppedMutations.get();
    }

    long saturatedMutationCount()
    {
        return mSaturatedMutations.get();
    }


    /** Called by a control-source owner, at most once per second. No allocation of an unbounded source catalog. */
    public void observeSource(P25SiteIdentity site, String configurationId, long observedAt)
    {
        if(site == null || configurationId == null)
        {
            return;
        }
        long timestamp = usableTimestamp(observedAt);
        int first = mix(configurationId.hashCode()) & (SOURCE_CAPACITY - 1);
        int selected = -1;
        for(int probe = 0; probe < DEFAULT_PROBE_COUNT; probe++)
        {
            int index = (first + probe) & (SOURCE_CAPACITY - 1);
            SourceObservation candidate = mSources.get(index);
            if(candidate != null && candidate.configurationId().equals(configurationId))
            {
                selected = index;
                break;
            }
            if(selected < 0 && (candidate == null || !candidate.active()))
            {
                selected = index;
            }
        }
        if(selected < 0)
        {
            mSaturatedMutations.incrementAndGet();
            invalidateCache(ChangeReason.SATURATION);
            return;
        }
        SourceObservation old = mSources.get(selected);
        boolean same = old != null && old.configurationId().equals(configurationId) && sameSystem(old.site(), site);
        if(same && old.active() && timestamp < old.observedAt())
        {
            return;
        }
        if(same && old.active() && timestamp - old.observedAt() > OBSERVATION_GAP_MILLISECONDS &&
            !hasOtherContinuousSource(site, configurationId, old.observedAt(), timestamp))
        {
            invalidateSystem(site, old.observedAt() + OBSERVATION_GAP_MILLISECONDS);
        }
        long activeSince = same && old.active() &&
            timestamp - old.observedAt() <= OBSERVATION_GAP_MILLISECONDS ? old.activeSince() : timestamp;
        SourceObservation next = new SourceObservation(configurationId, site, activeSince, timestamp, true);
        if(mSources.compareAndSet(selected, old, next))
        {
            recordContinuity(site, timestamp);
            mScopeRevision.incrementAndGet();
        }
    }

    /** Losing one site does not retire the system's mappings while another source observed it continuously. */
    public void observationGap(P25SiteIdentity site, String configurationId, long observedAt)
    {
        if(site == null || configurationId == null)
        {
            return;
        }
        long timestamp = usableTimestamp(observedAt);
        int first = mix(configurationId.hashCode()) & (SOURCE_CAPACITY - 1);
        for(int probe = 0; probe < DEFAULT_PROBE_COUNT; probe++)
        {
            int index = (first + probe) & (SOURCE_CAPACITY - 1);
            SourceObservation source = mSources.get(index);
            if(source != null && source.configurationId().equals(configurationId) && sameSystem(source.site(), site))
            {
                if(!source.active() || source.observedAt() > timestamp)
                {
                    return;
                }
                if(!mSources.compareAndSet(index, source, new SourceObservation(configurationId, site,
                    source.activeSince(), source.observedAt(), false)))
                {
                    return;
                }
                mScopeRevision.incrementAndGet();
                break;
            }
        }
        if(!hasOtherContinuousSource(site, configurationId, timestamp, timestamp))
        {
            invalidateSystem(site, timestamp, configurationId);
        }
    }

    private boolean hasOtherContinuousSource(P25SiteIdentity site, String excluded, long before, long now)
    {
        for(int index = 0; index < SOURCE_CAPACITY; index++)
        {
            SourceObservation source = mSources.get(index);
            if(source != null && source.active() && !source.configurationId().equals(excluded) &&
                sameSystem(source.site(), site) && source.activeSince() <= before &&
                source.observedAt() >= now - OBSERVATION_GAP_MILLISECONDS)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean sameSystem(P25SiteIdentity first, P25SiteIdentity second)
    {
        return first != null && second != null && first.wacn() == second.wacn() && first.system() == second.system();
    }

    private void invalidateSystem(P25SiteIdentity site, long timestamp)
    {
        invalidateSystem(site, timestamp, null);
    }

    private void invalidateSystem(P25SiteIdentity site, long timestamp, String configurationId)
    {
        int first = mix(site.wacn() * 31 ^ site.system()) & (SYSTEM_CAPACITY - 1);
        for(int probe = 0; probe < DEFAULT_PROBE_COUNT; probe++)
        {
            int index = (first + probe) & (SYSTEM_CAPACITY - 1);
            SystemGap old = mSystemGaps.get(index);
            if(old == null || old.wacn() == site.wacn() && old.system() == site.system())
            {
                if(old != null && old.gapAt() >= timestamp)
                {
                    return;
                }
                if(mSystemGaps.compareAndSet(index, old, new SystemGap(site.wacn(), site.system(), timestamp)))
                {
                    mScopeRevision.incrementAndGet();
                    appendChange(new Change(mChangeSequence.incrementAndGet(), timestamp, ChangeReason.OBSERVATION_GAP,
                        site.wacn(), site.system(), null, null, null, site, configurationId));
                }
                else
                {
                    invalidateCache(ChangeReason.CONTENTION);
                }
                return;
            }
        }
        invalidateCache(ChangeReason.SATURATION);
    }

    private boolean trackedSource(P25SiteIdentity site, String configurationId)
    {
        int first = mix(configurationId.hashCode()) & (SOURCE_CAPACITY - 1);
        for(int probe = 0; probe < DEFAULT_PROBE_COUNT; probe++)
        {
            SourceObservation source = mSources.get((first + probe) & (SOURCE_CAPACITY - 1));
            if(source != null && source.active() && source.configurationId().equals(configurationId) &&
                sameSystem(source.site(), site))
            {
                return true;
            }
        }
        return false;
    }

    private void recordContinuity(P25SiteIdentity site, long timestamp)
    {
        int first = mix(site.wacn() * 31 ^ site.system()) & (SYSTEM_CAPACITY - 1);
        for(int probe = 0; probe < DEFAULT_PROBE_COUNT; probe++)
        {
            int index = (first + probe) & (SYSTEM_CAPACITY - 1);
            SystemContinuity old = mSystemContinuity.get(index);
            if(old == null || old.wacn() == site.wacn() && old.system() == site.system())
            {
                if(old != null && old.observedAt() >= timestamp)
                {
                    return;
                }
                if(old != null && timestamp - old.observedAt() > OBSERVATION_GAP_MILLISECONDS)
                {
                    invalidateSystem(site, old.observedAt() + OBSERVATION_GAP_MILLISECONDS);
                }
                mSystemContinuity.compareAndSet(index, old, new SystemContinuity(site.wacn(), site.system(), timestamp));
                return;
            }
        }
        mSaturatedMutations.incrementAndGet();
        invalidateCache(ChangeReason.SATURATION);
    }

    /** Fixed-key lookup keeps observation-gap protection active even while web/statistics are disabled. */
    private boolean observationContinuous(int wacn, int system, long timestamp)
    {
        int first = mix(wacn * 31 ^ system) & (SYSTEM_CAPACITY - 1);
        for(int probe = 0; probe < DEFAULT_PROBE_COUNT; probe++)
        {
            SystemContinuity continuity = mSystemContinuity.get((first + probe) & (SYSTEM_CAPACITY - 1));
            if(continuity != null && continuity.wacn() == wacn && continuity.system() == system)
            {
                return timestamp <= continuity.observedAt() ||
                    timestamp - continuity.observedAt() <= OBSERVATION_GAP_MILLISECONDS;
            }
        }
        //Compatibility callers without an attached control source still use ordinary protocol lease semantics.
        return true;
    }

    private long systemGapAt(int wacn, int system)
    {
        int first = mix(wacn * 31 ^ system) & (SYSTEM_CAPACITY - 1);
        for(int probe = 0; probe < DEFAULT_PROBE_COUNT; probe++)
        {
            SystemGap gap = mSystemGaps.get((first + probe) & (SYSTEM_CAPACITY - 1));
            if(gap != null && gap.wacn() == wacn && gap.system() == system)
            {
                return gap.gapAt();
            }
        }
        return 0;
    }

    private static LeaseSource leaseSource(Integer minutes)
    {
        return minutes != null && (minutes == NO_EXPIRY_LEASE_MINUTES ||
            minutes >= DEFAULT_LEASE_MINUTES && minutes <= MAX_LEASE_MINUTES && minutes % 30 == 0) ?
            LeaseSource.ADVERTISED : LeaseSource.FALLBACK;
    }

    private static Entry entry(Assignment assignment)
    {
        ObservationContext context = assignment.context();
        return new Entry(assignment.servingWacn(), assignment.servingSystem(), assignment.workingId(),
            assignment.subscriber(), assignment.establishedAt(), assignment.observedAt(), assignment.expiresAt(),
            assignment.evidencePriority() == EVIDENCE_REGISTRATION ? Evidence.REGISTRATION : Evidence.AFFILIATION,
            context != null ? context.leaseSource() : LeaseSource.FALLBACK,
            context != null ? context.site() : null, context != null ? context.configurationId() : null);
    }

    private void recordChange(Assignment assignment, ChangeReason reason, Integer previousWorkingId, long at)
    {
        appendChange(new Change(mChangeSequence.incrementAndGet(), at, reason, assignment.servingWacn(),
            assignment.servingSystem(), assignment.workingId(), previousWorkingId, assignment.subscriber(),
            assignment.context() != null ? assignment.context().site() : null,
            assignment.context() != null ? assignment.context().configurationId() : null));
    }

    private void appendChange(Change change)
    {
        int index = (int)(change.sequence() & (RECENT_CHANGE_CAPACITY - 1));
        Change previous = mRecentChanges.get(index);
        if(previous == null || previous.sequence() < change.sequence())
        {
            mRecentChanges.compareAndSet(index, previous, change);
        }
    }

    /**
     * Observer-worker-only sampling. Never acquire the decoder mutation lock, spin until quiet, perform I/O, or
     * sample once per browser client. Arrays and attempts are bounded; a busy mutation retains a marked stale view.
     */
    public Snapshot snapshot(long now)
    {
        long timestamp = usableTimestamp(now);
        //The shared worker also detects interrupted control observation. A later source must relearn mappings.
        for(int index = 0; index < SOURCE_CAPACITY; index++)
        {
            SourceObservation source = mSources.get(index);
            if(source != null && source.active() &&
                timestamp - source.observedAt() > OBSERVATION_GAP_MILLISECONDS)
            {
                observationGap(source.site(), source.configurationId(),
                    source.observedAt() + OBSERVATION_GAP_MILLISECONDS);
            }
        }
        for(int attempt = 0; attempt < 2; attempt++)
        {
            long epoch = mCacheEpoch.get();
            long revision = mMutationRevision.get();
            long scopeRevision = mScopeRevision.get();
            if((revision & 1L) != 0)
            {
                continue;
            }
            List<Entry> entries = new ArrayList<>();
            Map<String,List<String>> sourcesBySystem = new HashMap<>();
            Map<String,int[]> systemIdentities = new HashMap<>();
            for(int index = 0; index < mLookupState.byWorkingId().length(); index++)
            {
                Assignment assignment = mLookupState.byWorkingId().get(index);
                if(assignment != null && assignment.cacheEpoch() == epoch && !assignment.cleared() &&
                    assignment.workingId() != null && assignment.subscriber() != null &&
                    assignment.observedAt() <= timestamp && assignment.expiresAt() >= timestamp &&
                    assignment.observedAt() > systemGapAt(assignment.servingWacn(), assignment.servingSystem()) &&
                    observationContinuous(assignment.servingWacn(), assignment.servingSystem(), timestamp))
                {
                    entries.add(entry(assignment));
                    String key = assignment.servingWacn() + ":" + assignment.servingSystem();
                    systemIdentities.putIfAbsent(key, new int[]{assignment.servingWacn(), assignment.servingSystem()});
                }
            }
            for(int index = 0; index < SOURCE_CAPACITY; index++)
            {
                SourceObservation source = mSources.get(index);
                if(source != null)
                {
                    String key = source.site().wacn() + ":" + source.site().system();
                    systemIdentities.putIfAbsent(key, new int[]{source.site().wacn(), source.site().system()});
                    if(source.active())
                    {
                        sourcesBySystem.computeIfAbsent(key, _ -> new ArrayList<>()).add(source.configurationId());
                    }
                }
            }
            for(int index = 0; index < SYSTEM_CAPACITY; index++)
            {
                SystemGap gap = mSystemGaps.get(index);
                if(gap != null)
                {
                    systemIdentities.putIfAbsent(gap.wacn() + ":" + gap.system(), new int[]{gap.wacn(), gap.system()});
                }
            }
            //Expiry is projected once by the observer rather than adding work to every decoder read.
            Snapshot previous = mLastSnapshot;
            List<Entry> expired = new ArrayList<>();
            for(Entry old: previous.assignments())
            {
                if(previous.generation() == epoch && old.expiresAt() < timestamp &&
                    old.expiresAt() >= previous.asOf())
                {
                    Assignment current = exactWorking(epoch, old.servingWacn(), old.servingSystem(), old.workingId());
                    //A sampled lease can have been renewed, cleared or reassigned before the next sample.
                    if(current != null && !current.cleared() && old.subscriber().equals(current.subscriber()) &&
                        current.expiresAt() < timestamp && current.expiresAt() >= previous.asOf() &&
                        current.observedAt() > systemGapAt(current.servingWacn(), current.servingSystem()))
                    {
                        expired.add(entry(current));
                    }
                }
            }
            List<Change> changes = new ArrayList<>();
            for(int index = 0; index < RECENT_CHANGE_CAPACITY; index++)
            {
                Change change = mRecentChanges.get(index);
                if(change != null && change.changedAt() <= timestamp)
                {
                    changes.add(change);
                }
            }
            List<SystemObservation> systems = new ArrayList<>();
            for(Map.Entry<String,int[]> system: systemIdentities.entrySet())
            {
                int[] identity = system.getValue();
                List<String> sources = sourcesBySystem.getOrDefault(system.getKey(), List.of());
                systems.add(new SystemObservation(identity[0], identity[1], !sources.isEmpty(),
                    systemGapAt(identity[0], identity[1]), sources));
            }
            entries.sort(Comparator.comparingInt(Entry::servingWacn).thenComparingInt(Entry::servingSystem)
                .thenComparingInt(Entry::workingId));
            systems.sort(Comparator.comparingInt(SystemObservation::servingWacn)
                .thenComparingInt(SystemObservation::servingSystem));
            if(epoch != mCacheEpoch.get() || revision != mMutationRevision.get() ||
                scopeRevision != mScopeRevision.get())
            {
                continue;
            }
            for(Entry expiration: expired)
            {
                Change change = new Change(mChangeSequence.incrementAndGet(), expiration.expiresAt(),
                    ChangeReason.EXPIRED, expiration.servingWacn(), expiration.servingSystem(),
                    expiration.workingId(), null, expiration.subscriber(), expiration.site(),
                    expiration.configurationId());
                appendChange(change);
                changes.add(change);
            }
            changes.sort(Comparator.comparingLong(Change::sequence).reversed());
            if(changes.size() > RECENT_CHANGE_CAPACITY)
            {
                changes = new ArrayList<>(changes.subList(0, RECENT_CHANGE_CAPACITY));
            }
            Snapshot sampled = new Snapshot(timestamp, epoch, revision, false, entries.size(), entries, changes,
                systems, mDroppedMutations.get(), mSaturatedMutations.get());
            mLastSnapshot = sampled;
            return sampled;
        }
        Snapshot previous = mLastSnapshot;
        return new Snapshot(previous.asOf(), previous.generation(), previous.revision(), true,
            previous.usableCount(), previous.assignments(), previous.recentChanges(), previous.systems(),
            mDroppedMutations.get(), mSaturatedMutations.get());
    }

    private Assignment register(LookupState state, long cacheEpoch, int servingWacn, int servingSystem,
                                int workingId, P25SubscriberIdentity subscriber, long confirmedAt, long expiresAt,
                                ObservationContext context)
    {
        Assignment incoming = new Assignment(servingWacn, servingSystem, workingId, subscriber, confirmedAt,
            EVIDENCE_REGISTRATION, expiresAt, false, cacheEpoch, confirmedAt, context);
        Assignment sameWorking = exactWorking(cacheEpoch, servingWacn, servingSystem, workingId);
        Assignment sameSubscriber = exactSubscriber(cacheEpoch, servingWacn, servingSystem, subscriber);

        if(sameWorking != null && compare(incoming, sameWorking) <= 0 ||
            sameSubscriber != null && compare(incoming, sameSubscriber) <= 0)
        {
            return null;
        }

        if(sameWorking != null && !sameWorking.cleared() && subscriber.equals(sameWorking.subscriber()) &&
            sameWorking.expiresAt() >= confirmedAt &&
            sameWorking.observedAt() > systemGapAt(servingWacn, servingSystem))
        {
            incoming = new Assignment(servingWacn, servingSystem, workingId, subscriber, confirmedAt,
                EVIDENCE_REGISTRATION, expiresAt, false, cacheEpoch, sameWorking.establishedAt(), context);
        }

        int workingSlot = writableSlot(state.byWorkingId(), workingHash(incoming), incoming, confirmedAt, true,
            cacheEpoch);
        int subscriberSlot = writableSlot(state.bySubscriber(), subscriberHash(incoming), incoming, confirmedAt,
            false, cacheEpoch);
        if(workingSlot < 0 || subscriberSlot < 0)
        {
            mSaturatedMutations.incrementAndGet();
            invalidateCache(ChangeReason.SATURATION);
            return null;
        }

        if(sameWorking != null && !sameWorking.cleared() &&
            !subscriber.equals(sameWorking.subscriber()))
        {
            recordChange(incoming, ChangeReason.REASSIGNED, workingId, confirmedAt);
        }
        if(sameSubscriber != null && !sameSubscriber.cleared() && sameSubscriber.workingId() != null &&
            sameSubscriber.workingId() != workingId)
        {
            recordChange(incoming, ChangeReason.REASSIGNED, sameSubscriber.workingId(), confirmedAt);
        }

        //Retire each displaced half before publishing the new pair. Readers may briefly miss, but cannot match the
        //old subscriber after a reassignment begins.
        if(sameSubscriber != null && sameSubscriber.workingId() != null &&
            sameSubscriber.workingId().intValue() != workingId)
        {
            Assignment oldForward = exactWorking(cacheEpoch, sameSubscriber.servingWacn(),
                sameSubscriber.servingSystem(), sameSubscriber.workingId());
            if(oldForward != null && subscriber.equals(oldForward.subscriber()))
            {
                replaceExact(state.byWorkingId(), workingHash(oldForward), oldForward,
                    forwardTombstone(oldForward, confirmedAt, expiresAt, cacheEpoch));
            }
        }

        if(sameWorking != null && sameWorking.subscriber() != null &&
            !sameWorking.subscriber().equals(subscriber))
        {
            Assignment oldReverse = exactSubscriber(cacheEpoch, sameWorking.servingWacn(),
                sameWorking.servingSystem(), sameWorking.subscriber());
            if(oldReverse != null && oldReverse.workingId() != null &&
                oldReverse.workingId().intValue() == workingId)
            {
                replaceExact(state.bySubscriber(), subscriberHash(oldReverse), oldReverse,
                    subscriberTombstone(oldReverse, confirmedAt, expiresAt, cacheEpoch));
            }
        }

        state.bySubscriber().set(subscriberSlot, incoming);
        state.byWorkingId().set(workingSlot, incoming);
        return incoming;
    }

    private Assignment refresh(LookupState state, long cacheEpoch, int servingWacn, int servingSystem,
                               int workingId, P25SubscriberIdentity assertedSubscriber, long confirmedAt,
                               long expiresAt, ObservationContext context)
    {
        Assignment current = findWorking(cacheEpoch, servingWacn, servingSystem, workingId, confirmedAt);
        if(current == null || assertedSubscriber != null && !assertedSubscriber.equals(current.subscriber()))
        {
            return null;
        }

        Assignment refreshed = new Assignment(current.servingWacn(), current.servingSystem(), current.workingId(),
            current.subscriber(), confirmedAt, EVIDENCE_AFFILIATION, expiresAt, false, cacheEpoch,
            current.establishedAt(), context);
        if(compare(refreshed, current) <= 0)
        {
            return null;
        }

        Assignment reverse = exactSubscriber(cacheEpoch, current.servingWacn(), current.servingSystem(),
            current.subscriber());
        if(reverse == null || reverse.workingId() == null || reverse.workingId().intValue() != workingId ||
            !replaceExact(state.bySubscriber(), subscriberHash(reverse), reverse, refreshed) ||
            !replaceExact(state.byWorkingId(), workingHash(current), current, refreshed))
        {
            mSaturatedMutations.incrementAndGet();
            invalidateCache(ChangeReason.SATURATION);
            return null;
        }
        return refreshed;
    }

    private boolean clear(LookupState state, long cacheEpoch, int servingWacn, int servingSystem, Integer workingId,
                          P25SubscriberIdentity subscriber, long clearedAt, long expiresAt)
    {
        Assignment byWorking = workingId != null ?
            exactWorking(cacheEpoch, servingWacn, servingSystem, workingId) : null;
        Assignment bySubscriber = subscriber != null ?
            exactSubscriber(cacheEpoch, servingWacn, servingSystem, subscriber) : null;
        Integer resolvedWorking = workingId;
        P25SubscriberIdentity resolvedSubscriber = subscriber;

        if(byWorking != null && !byWorking.cleared() && byWorking.subscriber() != null &&
            (resolvedSubscriber == null || resolvedSubscriber.equals(byWorking.subscriber())))
        {
            resolvedSubscriber = byWorking.subscriber();
        }
        if(bySubscriber != null && !bySubscriber.cleared() && bySubscriber.workingId() != null &&
            (resolvedWorking == null || resolvedWorking.equals(bySubscriber.workingId())))
        {
            resolvedWorking = bySubscriber.workingId();
        }

        boolean stored = true;
        stored &= installWorkingTombstone(state, cacheEpoch, servingWacn, servingSystem, workingId,
            byWorking != null ? byWorking.subscriber() : resolvedSubscriber, clearedAt, expiresAt);
        if(byWorking != null)
        {
            stored &= installSubscriberTombstone(state, cacheEpoch, servingWacn, servingSystem,
                byWorking.workingId(), byWorking.subscriber(), clearedAt, expiresAt);
        }
        stored &= installSubscriberTombstone(state, cacheEpoch, servingWacn, servingSystem,
            bySubscriber != null ? bySubscriber.workingId() : resolvedWorking, subscriber, clearedAt, expiresAt);
        if(bySubscriber != null)
        {
            stored &= installWorkingTombstone(state, cacheEpoch, servingWacn, servingSystem,
                bySubscriber.workingId(), bySubscriber.subscriber(), clearedAt, expiresAt);
        }
        return stored;
    }

    private boolean installWorkingTombstone(LookupState state, long cacheEpoch, int servingWacn, int servingSystem,
                                            Integer workingId, P25SubscriberIdentity subscriber, long clearedAt,
                                            long expiresAt)
    {
        if(workingId == null)
        {
            return true;
        }

        Assignment tombstone = new Assignment(servingWacn, servingSystem, workingId, subscriber, clearedAt,
            EVIDENCE_CLEAR, expiresAt, true, cacheEpoch);
        Assignment existing = exactWorking(cacheEpoch, servingWacn, servingSystem, workingId);
        if(existing != null && compare(tombstone, existing) <= 0)
        {
            return true;
        }

        int slot = writableSlot(state.byWorkingId(), workingHash(tombstone), tombstone, clearedAt, true, cacheEpoch);
        if(slot < 0)
        {
            return false;
        }
        state.byWorkingId().set(slot, tombstone);
        if(existing != null && !existing.cleared())
        {
            recordChange(existing, ChangeReason.CLEARED, null, clearedAt);
        }
        return true;
    }

    private boolean installSubscriberTombstone(LookupState state, long cacheEpoch, int servingWacn,
                                               int servingSystem, Integer workingId,
                                               P25SubscriberIdentity subscriber, long clearedAt, long expiresAt)
    {
        if(subscriber == null)
        {
            return true;
        }

        Assignment tombstone = new Assignment(servingWacn, servingSystem, workingId, subscriber, clearedAt,
            EVIDENCE_CLEAR, expiresAt, true, cacheEpoch);
        Assignment existing = exactSubscriber(cacheEpoch, servingWacn, servingSystem, subscriber);
        if(existing != null && compare(tombstone, existing) <= 0)
        {
            return true;
        }

        int slot = writableSlot(state.bySubscriber(), subscriberHash(tombstone), tombstone, clearedAt, false,
            cacheEpoch);
        if(slot < 0)
        {
            return false;
        }
        state.bySubscriber().set(slot, tombstone);
        return true;
    }

    private boolean lookupStillCurrent(long cacheEpoch, long mutationRevision, long scopeRevision,
                                       Assignment assignment)
    {
        return mCacheEpoch.get() == cacheEpoch && mMutationRevision.get() == mutationRevision &&
            mScopeRevision.get() == scopeRevision &&
            (mutationRevision & 1L) == 0L &&
            exactWorking(cacheEpoch, assignment.servingWacn(), assignment.servingSystem(),
                assignment.workingId()) == assignment;
    }

    private void beginMutation()
    {
        mMutationRevision.incrementAndGet();
    }

    private void endMutation()
    {
        mMutationRevision.incrementAndGet();
    }

    private void invalidateCache(ChangeReason reason)
    {
        mCacheEpoch.incrementAndGet();
        appendChange(new Change(mChangeSequence.incrementAndGet(), System.currentTimeMillis(), reason,
            null, null, null, null, null));
    }

    private static AssignmentObservation observation(Assignment assignment)
    {
        if(assignment == null || assignment.cleared() || assignment.workingId() == null ||
            assignment.subscriber() == null)
        {
            return null;
        }
        Evidence evidence = assignment.evidencePriority() == EVIDENCE_REGISTRATION ? Evidence.REGISTRATION :
            Evidence.AFFILIATION;
        return new AssignmentObservation(assignment.servingWacn(), assignment.servingSystem(),
            assignment.workingId(), assignment.subscriber(), assignment.observedAt(), assignment.expiresAt(), evidence);
    }

    private static Assignment forwardTombstone(Assignment assignment, long clearedAt, long expiresAt,
                                               long cacheEpoch)
    {
        return new Assignment(assignment.servingWacn(), assignment.servingSystem(), assignment.workingId(), null,
            clearedAt, EVIDENCE_CLEAR, expiresAt, true, cacheEpoch);
    }

    private static Assignment subscriberTombstone(Assignment assignment, long clearedAt, long expiresAt,
                                                  long cacheEpoch)
    {
        return new Assignment(assignment.servingWacn(), assignment.servingSystem(), null, assignment.subscriber(),
            clearedAt, EVIDENCE_CLEAR, expiresAt, true, cacheEpoch);
    }

    private boolean replaceExact(AtomicReferenceArray<Assignment> slots, int hash, Assignment expected,
                                 Assignment replacement)
    {
        for(int probe = 0; probe < mProbeCount; probe++)
        {
            int index = (hash + probe) & mMask;
            if(slots.get(index) == expected)
            {
                slots.set(index, replacement);
                return true;
            }
        }
        return false;
    }

    private int writableSlot(AtomicReferenceArray<Assignment> slots, int hash, Assignment incoming, long timestamp,
                             boolean workingKey, long cacheEpoch)
    {
        int reusable = -1;
        for(int probe = 0; probe < mProbeCount; probe++)
        {
            int index = (hash + probe) & mMask;
            Assignment existing = slots.get(index);
            if(existing != null && existing.cacheEpoch() == cacheEpoch && keyMatches(existing, incoming, workingKey))
            {
                return index;
            }
            if(reusable < 0 && (existing == null || existing.cacheEpoch() != cacheEpoch ||
                existing.expiresAt() < timestamp))
            {
                reusable = index;
            }
        }
        return reusable;
    }

    private Assignment exactWorking(long cacheEpoch, int servingWacn, int servingSystem, int workingId)
    {
        int hash = workingHash(servingWacn, servingSystem, workingId);
        for(int probe = 0; probe < mProbeCount; probe++)
        {
            Assignment assignment = mLookupState.byWorkingId().get((hash + probe) & mMask);
            if(assignment != null && assignment.cacheEpoch() == cacheEpoch &&
                assignment.servingWacn() == servingWacn && assignment.servingSystem() == servingSystem &&
                assignment.workingId() != null && assignment.workingId().intValue() == workingId)
            {
                return assignment;
            }
        }
        return null;
    }

    private Assignment exactSubscriber(long cacheEpoch, int servingWacn, int servingSystem,
                                       P25SubscriberIdentity subscriber)
    {
        int hash = subscriberHash(servingWacn, servingSystem, subscriber);
        for(int probe = 0; probe < mProbeCount; probe++)
        {
            Assignment assignment = mLookupState.bySubscriber().get((hash + probe) & mMask);
            if(assignment != null && assignment.cacheEpoch() == cacheEpoch &&
                assignment.servingWacn() == servingWacn && assignment.servingSystem() == servingSystem &&
                subscriber.equals(assignment.subscriber()))
            {
                return assignment;
            }
        }
        return null;
    }

    private Assignment findWorking(long cacheEpoch, int servingWacn, int servingSystem, int workingId,
                                   long timestamp)
    {
        Assignment assignment = exactWorking(cacheEpoch, servingWacn, servingSystem, workingId);
        if(assignment != null && !assignment.cleared() && assignment.subscriber() != null &&
            assignment.observedAt() <= timestamp && assignment.expiresAt() >= timestamp &&
            assignment.observedAt() > systemGapAt(servingWacn, servingSystem) &&
            observationContinuous(servingWacn, servingSystem, timestamp))
        {
            return assignment;
        }
        return null;
    }

    private static boolean keyMatches(Assignment first, Assignment second, boolean workingKey)
    {
        return first.servingWacn() == second.servingWacn() &&
            first.servingSystem() == second.servingSystem() &&
            (workingKey ? java.util.Objects.equals(first.workingId(), second.workingId()) :
                first.subscriber() != null && first.subscriber().equals(second.subscriber()));
    }

    /** Newer evidence wins; clear wins ties, then registration, then the smallest canonical tuple and WUID. */
    private static int compare(Assignment incoming, Assignment existing)
    {
        int comparison = Long.compare(incoming.observedAt(), existing.observedAt());
        if(comparison == 0)
        {
            comparison = Integer.compare(incoming.evidencePriority(), existing.evidencePriority());
        }
        if(comparison == 0)
        {
            comparison = -compareNullable(incoming.subscriber(), existing.subscriber());
        }
        if(comparison == 0)
        {
            comparison = -compareNullable(incoming.workingId(), existing.workingId());
        }
        return comparison;
    }

    private static int compare(P25SubscriberIdentity first, P25SubscriberIdentity second)
    {
        int comparison = Integer.compare(first.homeWacn(), second.homeWacn());
        if(comparison == 0)
        {
            comparison = Integer.compare(first.homeSystemId(), second.homeSystemId());
        }
        return comparison != 0 ? comparison : Integer.compare(first.subscriberId(), second.subscriberId());
    }

    private static int compareNullable(P25SubscriberIdentity first, P25SubscriberIdentity second)
    {
        if(first == null || second == null)
        {
            return first == second ? 0 : first == null ? -1 : 1;
        }
        return compare(first, second);
    }

    private static int compareNullable(Integer first, Integer second)
    {
        if(first == null || second == null)
        {
            return first == second ? 0 : first == null ? -1 : 1;
        }
        return Integer.compare(first, second);
    }

    private static int workingHash(Assignment assignment)
    {
        return workingHash(assignment.servingWacn(), assignment.servingSystem(), assignment.workingId());
    }

    private static int workingHash(int servingWacn, int servingSystem, int workingId)
    {
        return mix(servingWacn * 31 ^ servingSystem * 131 ^ workingId);
    }

    private static int subscriberHash(Assignment assignment)
    {
        return subscriberHash(assignment.servingWacn(), assignment.servingSystem(), assignment.subscriber());
    }

    private static int subscriberHash(int servingWacn, int servingSystem, P25SubscriberIdentity subscriber)
    {
        return mix(servingWacn * 31 ^ servingSystem * 131 ^ subscriber.homeWacn() * 17 ^
            subscriber.homeSystemId() * 257 ^ subscriber.subscriberId());
    }

    private static int mix(int value)
    {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ value >>> 16;
    }

    private static Integer validWorkingId(int value)
    {
        return value >= 1 && value <= RadioSystemIdentityKey.MAX_P25_WORKING_UNIT_ID ? value : null;
    }

    private static boolean validServingSystem(int servingWacn, int servingSystem)
    {
        return servingWacn >= 0 && servingWacn <= 0xFFFFF && servingSystem >= 0 && servingSystem <= 0xFFF;
    }

    private static long usableTimestamp(long timestamp)
    {
        return timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    private static long expiresAt(long observedAt, Integer leaseMinutes)
    {
        if(leaseMinutes != null && leaseMinutes == NO_EXPIRY_LEASE_MINUTES)
        {
            return Long.MAX_VALUE;
        }

        int minutes = leaseMinutes != null && leaseMinutes >= DEFAULT_LEASE_MINUTES &&
            leaseMinutes <= MAX_LEASE_MINUTES && leaseMinutes % 30 == 0 ? leaseMinutes : DEFAULT_LEASE_MINUTES;
        long leaseMilliseconds = minutes * 60_000L;
        return observedAt > Long.MAX_VALUE - leaseMilliseconds ? Long.MAX_VALUE : observedAt + leaseMilliseconds;
    }

    private static final class LookupState
    {
        private final AtomicReferenceArray<Assignment> mByWorkingId;
        private final AtomicReferenceArray<Assignment> mBySubscriber;

        private LookupState(int slotCount)
        {
            mByWorkingId = new AtomicReferenceArray<>(slotCount);
            mBySubscriber = new AtomicReferenceArray<>(slotCount);
        }

        private AtomicReferenceArray<Assignment> byWorkingId()
        {
            return mByWorkingId;
        }

        private AtomicReferenceArray<Assignment> bySubscriber()
        {
            return mBySubscriber;
        }
    }

    private record Assignment(int servingWacn, int servingSystem, Integer workingId,
                              P25SubscriberIdentity subscriber, long observedAt, int evidencePriority,
                              long expiresAt, boolean cleared, long cacheEpoch, long establishedAt,
                              ObservationContext context)
    {
        private Assignment(int servingWacn, int servingSystem, Integer workingId,
                           P25SubscriberIdentity subscriber, long observedAt, int evidencePriority,
                           long expiresAt, boolean cleared, long cacheEpoch)
        {
            this(servingWacn, servingSystem, workingId, subscriber, observedAt, evidencePriority,
                expiresAt, cleared, cacheEpoch, observedAt, null);
        }

        private Assignment
        {
            if(workingId == null && subscriber == null)
            {
                throw new IllegalArgumentException("Assignment requires a WUID or canonical subscriber");
            }
        }
    }
}
