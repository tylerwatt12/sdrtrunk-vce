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

package io.github.dsheirer.health;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Monotonic settled-call totals with bounded, non-blocking producer operations. Pending outcomes and timestamps use
 * one atomic word, so traffic refreshes allocate nothing and cannot race expiry into a silently stale outcome.
 * Snapshots alone perform collection scans and take the private reader lock. Disabled collection does neither.
 */
public class CallTally
{
    static final int MAX_PENDING = 4096;
    static final long MAX_QUIET_MS = 10_000;
    private static final CallOutcome[] OUTCOMES = CallOutcome.values();
    // Seven outcomes use codes 1..7; zero is reserved for explicit completion. Bit 3 records quiet expiry.
    private static final int STATE_BITS = 4;
    private static final long OUTCOME_MASK = 7L;
    private static final long EXPIRED_BIT = 8L;
    private static final long FINISHED = 0L;
    static final long MIN_TIMESTAMP = Long.MIN_VALUE >> STATE_BITS;
    static final long MAX_TIMESTAMP = Long.MAX_VALUE >> STATE_BITS;
    private final Object mSnapshotLock = new Object();
    private volatile CollectionState mCollection = new CollectionState(1, true, null);
    private final AtomicReference<PendingRing> mPending = new AtomicReference<>();
    private long mObservedSourceLosses;
    private long mLossHorizon = Long.MIN_VALUE;
    private long mLastSnapshotTime = Long.MIN_VALUE;
    private long mLossWindows;

    private static final class CollectionState
    {
        private final long generation;
        private final boolean enabled;
        private final AtomicLongArray outcomes = new AtomicLongArray(OUTCOMES.length);
        private final AtomicLong losses;

        private CollectionState(long generation, boolean enabled, CollectionState previous)
        {
            this.generation = generation;
            this.enabled = enabled;
            if(previous != null)
            {
                for(int index = 0; index < OUTCOMES.length; index++) outcomes.set(index, previous.outcomes.get(index));
            }
            losses = new AtomicLong(previous != null ? previous.losses.get() : 0);
        }
    }

    private static final class PendingRing
    {
        private final long generation;
        private final AtomicReferenceArray<Pending> slots = new AtomicReferenceArray<>(MAX_PENDING);
        private final AtomicInteger next = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicBoolean dirty = new AtomicBoolean();

        private PendingRing(long generation) { this.generation = generation; }
    }

    /** Lifecycle/reporting-worker operation. Old producers can finish only into their retired counter generation. */
    public void setEnabled(boolean enabled)
    {
        synchronized(mSnapshotLock)
        {
            CollectionState previous = mCollection;
            if(previous.enabled == enabled) return;
            CollectionState next = new CollectionState(previous.generation + 1, enabled, previous);
            if(enabled)
            {
                // Demand is applied on the lifecycle/reporting worker, before the first decoder grant.
                mPending.set(new PendingRing(next.generation));
                mCollection = next;
            }
            else
            {
                mCollection = next;
                mPending.set(null);
            }
            mObservedSourceLosses = next.losses.get();
            mLossHorizon = Long.MIN_VALUE;
            mLastSnapshotTime = Long.MIN_VALUE;
        }
    }

    public boolean isEnabled() { return mCollection.enabled; }
    public long generation() { return mCollection.generation; }
    boolean isGenerationEnabled(long generation)
    {
        CollectionState state = mCollection;
        return state.enabled && state.generation == generation;
    }

    public void count(CallOutcome outcome)
    {
        CollectionState state = mCollection;
        if(state.enabled && outcome != null) state.outcomes.incrementAndGet(outcome.ordinal());
    }

    public void count(CallOutcome previous, CallOutcome outcome)
    {
        if(previous != null) throw new IllegalArgumentException("An already settled call cannot change outcome");
        count(outcome);
    }

    /** No holder or ring allocation when disabled. The bounded ring is created only for the first enabled call. */
    public Pending pending(CallOutcome outcome, long timestamp, long quietMs)
    {
        CollectionState state = mCollection;
        if(!state.enabled) return null;
        if(outcome == null || quietMs <= 0 || quietMs > MAX_QUIET_MS || !validTimestamp(timestamp))
        {
            lost(state);
            return null;
        }
        PendingRing ring = mPending.get();
        if(ring == null || ring.generation != state.generation)
        {
            PendingRing candidate = new PendingRing(state.generation);
            if(mCollection != state) return null;
            if(mPending.compareAndSet(ring, candidate)) ring = candidate;
            else ring = mPending.get();
            if(ring == null || ring.generation != state.generation) return null;
        }
        if(mCollection != state)
        {
            mPending.compareAndSet(ring, null);
            return null;
        }
        Pending pending = new Pending(state, ring.active, ring.dirty, outcome, timestamp, quietMs);
        int slot = ring.next.getAndIncrement() & (MAX_PENDING - 1);
        Pending previous = ring.slots.get(slot);
        ring.active.incrementAndGet();
        if(previous != null && !previous.isFinished() || !ring.slots.compareAndSet(slot, previous, pending))
        {
            pending.mTracked = false;
            ring.active.decrementAndGet();
            lost(state);
        }
        else ring.dirty.set(true);
        if(mCollection != state) mPending.compareAndSet(ring, null);
        return pending;
    }

    public void lost() { lost(mCollection); }

    private void lost(CollectionState state)
    {
        if(state.enabled && mCollection == state) state.losses.incrementAndGet();
    }

    public long getStarted()
    {
        CollectionState state = mCollection;
        long count = 0;
        for(int index = 0; index < OUTCOMES.length; index++) count += state.outcomes.get(index);
        return count;
    }

    public long get(CallOutcome outcome) { return mCollection.outcomes.get(outcome.ordinal()); }

    public Totals totals()
    {
        synchronized(mSnapshotLock) { return snapshotTotals(mCollection); }
    }

    private Totals snapshotTotals(CollectionState state)
    {
        Map<CallOutcome,Long> outcomes = new EnumMap<>(CallOutcome.class);
        long started = 0;
        for(CallOutcome outcome: OUTCOMES)
        {
            long count = state.outcomes.get(outcome.ordinal());
            outcomes.put(outcome, count);
            started += count;
        }
        return new Totals(started, Map.copyOf(outcomes), state.losses.get() + mLossWindows);
    }

    /** Expiry scans run only for a non-empty/dirty enabled ring. Loss markers never extend their own horizon. */
    public Totals totals(long now)
    {
        synchronized(mSnapshotLock)
        {
            CollectionState state = mCollection;
            PendingRing ring = mPending.get();
            if(state.enabled && ring != null && ring.generation == state.generation &&
                (ring.dirty.getAndSet(false) || ring.active.get() > 0))
            {
                for(int index = 0; index < MAX_PENDING; index++)
                {
                    Pending pending = ring.slots.get(index);
                    if(pending != null)
                    {
                        pending.expire(now, false);
                        if(pending.isFinished()) ring.slots.compareAndSet(index, pending, null);
                    }
                }
            }
            long sourceLosses = state.losses.get();
            if(state.enabled && sourceLosses != mObservedSourceLosses)
            {
                mObservedSourceLosses = sourceLosses;
                mLossHorizon = now + MAX_QUIET_MS;
            }
            if(state.enabled && mLossHorizon != Long.MIN_VALUE && mLastSnapshotTime <= mLossHorizon) mLossWindows++;
            mLastSnapshotTime = now;
            return snapshotTotals(state);
        }
    }

    private static boolean validTimestamp(long timestamp) { return timestamp >= MIN_TIMESTAMP && timestamp <= MAX_TIMESTAMP; }
    private static long encode(CallOutcome outcome, long timestamp) { return timestamp << STATE_BITS | outcome.ordinal() + 1L; }
    private static long timestamp(long state) { return state >> STATE_BITS; }
    private static boolean expired(long state) { return (state & EXPIRED_BIT) != 0; }
    private static CallOutcome outcome(long state) { return OUTCOMES[(int)(state & OUTCOME_MASK) - 1]; }

    /** Holds no ring/array reference: disabling collection releases the large ring even while trackers remain alive. */
    public final class Pending
    {
        private final CollectionState mCollectionState;
        private final AtomicInteger mActive;
        private final AtomicBoolean mDirty;
        private final AtomicLong mState;
        private final long mQuietMs;
        private boolean mTracked = true;

        private Pending(CollectionState collection, AtomicInteger active, AtomicBoolean dirty, CallOutcome outcome,
                        long timestamp, long quietMs)
        {
            mCollectionState = collection;
            mActive = active;
            mDirty = dirty;
            mState = new AtomicLong(encode(outcome, timestamp));
            mQuietMs = quietMs;
        }

        public long generation() { return mCollectionState.generation; }
        private boolean current() { return mCollectionState.enabled && mCollection == mCollectionState; }
        public boolean isFinished() { long state = mState.get(); return !current() || state == FINISHED || expired(state); }

        /** One CAS and no allocation, including timestamp-only traffic observations. */
        public void observe(CallOutcome nextOutcome, long observedAt)
        {
            if(!current()) return;
            if(!validTimestamp(observedAt)) { lost(mCollectionState); return; }
            long state = mState.get();
            if(expired(state)) contradicted(state, nextOutcome);
            else if(state != FINISHED)
            {
                long next = encode(nextOutcome != null ? nextOutcome : outcome(state), Math.max(observedAt, timestamp(state)));
                if(next != state && !mState.compareAndSet(state, next))
                {
                    long actual = mState.get();
                    if(expired(actual)) contradicted(actual, nextOutcome);
                    else if(actual != FINISHED) lost(mCollectionState);
                }
                if(!mTracked) lost(mCollectionState);
            }
        }

        private void contradicted(long state, CallOutcome next)
        {
            if(next != null && next != outcome(state)) lost(mCollectionState);
        }

        public void finish()
        {
            if(current()) settle(mState.getAndSet(FINISHED), true);
        }

        public void cancel()
        {
            if(current()) retire(mState.getAndSet(FINISHED), true);
        }

        public void expire(long now) { expire(now, true); }

        private void expire(long now, boolean dirty)
        {
            if(!current()) return;
            if(!validTimestamp(now)) { lost(mCollectionState); return; }
            long state = mState.get();
            if(state != FINISHED && !expired(state) && now - timestamp(state) > mQuietMs &&
                mState.compareAndSet(state, state | EXPIRED_BIT)) settle(state, dirty);
        }

        private void retire(long state, boolean dirty)
        {
            if(state != FINISHED && !expired(state) && mTracked)
            {
                mActive.decrementAndGet();
                if(dirty) mDirty.set(true);
            }
        }

        private void settle(long state, boolean dirty)
        {
            if(state != FINISHED && !expired(state))
            {
                retire(state, dirty);
                if(mTracked) mCollectionState.outcomes.incrementAndGet(outcome(state).ordinal());
                else lost(mCollectionState);
            }
        }
    }

    /** Equal started/outcome totals sampled together; losses qualify completeness of their report window. */
    public record Totals(long started, Map<CallOutcome,Long> outcomes, long losses)
    {
        public static final Totals EMPTY = new Totals(0, Map.of(), 0);

        public Totals(long started, Map<CallOutcome,Long> outcomes)
        {
            this(started, outcomes, 0);
        }

        public long get(CallOutcome outcome)
        {
            return outcomes.getOrDefault(outcome, 0L);
        }
    }
}
