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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Monotonic totals of settled calls. An active call keeps its tentative outcome separately until it ends or goes
 * quiet; a later successful allocation can therefore never subtract from a previously reported failure.
 *
 * Producers perform fixed atomic operations only. Snapshot/expiry work belongs to the reporting worker, never a
 * decoder callback. The pending-call ring is bounded; saturation loses observer data and marks the affected report
 * window incomplete rather than delaying reception or presenting missing observations as zero calls.
 */
public class CallTally
{
    static final int MAX_PENDING = 4096;
    // P25 continuation is 10s, NXDN/DMR are 3s, unresolved P25 grants are 5s.
    static final long MAX_QUIET_MS = 10_000;
    private final Object mSnapshotLock = new Object();
    private long mObservedSourceLosses;
    private long mLossHorizon = Long.MIN_VALUE;
    private long mLastSnapshotTime = Long.MIN_VALUE;
    private long mLossWindows;
    private final AtomicLongArray mOutcomes = new AtomicLongArray(CallOutcome.values().length);
    private final AtomicReferenceArray<Pending> mPending = new AtomicReferenceArray<>(MAX_PENDING);
    private final AtomicInteger mNext = new AtomicInteger();
    private final AtomicLong mLosses = new AtomicLong();

    /** One final outcome, counted once by its call holder. */
    public void count(CallOutcome outcome)
    {
        if(outcome != null)
        {
            mOutcomes.incrementAndGet(outcome.ordinal());
        }
    }

    /** Compatibility for callers recording an already settled call, never for reclassification. */
    public void count(CallOutcome previous, CallOutcome outcome)
    {
        if(previous != null)
        {
            throw new IllegalArgumentException("An already settled call cannot change outcome");
        }
        count(outcome);
    }

    /** Begins one call's tentative accounting, without retaining talkgroup/radio identities in the reporting worker. */
    public Pending pending(CallOutcome outcome, long timestamp, long quietMs)
    {
        if(quietMs <= 0 || quietMs > MAX_QUIET_MS)
        {
            throw new IllegalArgumentException("Call quiet period exceeds bounded health recovery horizon");
        }
        Pending pending = new Pending(outcome, timestamp, quietMs);
        int slot = mNext.getAndIncrement() & (MAX_PENDING - 1);
        Pending previous = mPending.get(slot);
        if(previous != null && !previous.isFinished() || !mPending.compareAndSet(slot, previous, pending))
        {
            pending.mTracked = false;
            lost();
        }
        return pending;
    }

    /** A bounded producer-side observation was lost. */
    public void lost()
    {
        mLosses.incrementAndGet();
    }

    /** Settled calls only; ongoing calls are included after their final outcome is known. */
    public long getStarted()
    {
        long count = 0;
        for(CallOutcome outcome: CallOutcome.values())
        {
            count += get(outcome);
        }
        return count;
    }

    public long get(CallOutcome outcome)
    {
        return mOutcomes.get(outcome.ordinal());
    }

    /** A reporting-worker snapshot without advancing quiet time. Producers never acquire this reader-only lock. */
    public Totals totals()
    {
        synchronized(mSnapshotLock)
        {
            return snapshotTotals();
        }
    }

    private Totals snapshotTotals()
    {
        Map<CallOutcome,Long> outcomes = new EnumMap<>(CallOutcome.class);
        long started = 0;
        for(CallOutcome outcome: CallOutcome.values())
        {
            long count = get(outcome);
            outcomes.put(outcome, count);
            started += count;
        }
        return new Totals(started, Map.copyOf(outcomes), mLosses.get() + mLossWindows);
    }

    /**
     * Reporting-worker operation. A lost observer call may settle after its initial loss, so reporting remains
     * incomplete through the maximum quiet period and the first snapshot past it. Continued lost activity extends
     * that horizon. Source loss and generated window markers are separate, so markers cannot extend themselves.
     */
    public Totals totals(long now)
    {
        synchronized(mSnapshotLock)
        {
            for(int index = 0; index < MAX_PENDING; index++)
            {
                Pending pending = mPending.get(index);
                if(pending != null) pending.expire(now);
            }
            long sourceLosses = mLosses.get();
            if(sourceLosses != mObservedSourceLosses)
            {
                mObservedSourceLosses = sourceLosses;
                mLossHorizon = now + MAX_QUIET_MS;
            }
            if(mLossHorizon != Long.MIN_VALUE && mLastSnapshotTime <= mLossHorizon)
            {
                mLossWindows++;
            }
            mLastSnapshotTime = now;
            return snapshotTotals();
        }
    }

    private record State(CallOutcome outcome, long timestamp, boolean expired) {}
    private static final State FINISHED = new State(null, Long.MIN_VALUE, false);

    /** One call, owned by a decoder's existing lifecycle and observed lock-free by the reporting worker. */
    public final class Pending
    {
        private final AtomicReference<State> mState;
        private final long mQuietMs;
        private boolean mTracked = true;

        private Pending(CallOutcome outcome, long timestamp, long quietMs)
        {
            mState = new AtomicReference<>(new State(outcome, timestamp, false));
            mQuietMs = quietMs;
        }

        public boolean isFinished()
        {
            State state = mState.get();
            return state == FINISHED || state.expired();
        }

        /** A single attempt: a concurrent expiry cannot hold up its producer. */
        public void observe(CallOutcome outcome, long timestamp)
        {
            State state = mState.get();
            if(state.expired())
            {
                contradicted(state, outcome);
            }
            else if(state != FINISHED)
            {
                if(!mState.compareAndSet(state, new State(outcome != null ? outcome : state.outcome(),
                    Math.max(timestamp, state.timestamp()), false)))
                {
                    State current = mState.get();
                    if(current.expired()) contradicted(current, outcome);
                    else if(current != FINISHED) lost();
                }
                if(!mTracked) lost();
            }
        }

        private void contradicted(State state, CallOutcome outcome)
        {
            //Late material evidence invalidates completeness; it must never silently retain a provisional failure.
            if(outcome != null && outcome != state.outcome()) lost();
        }

        /** Retires the call exactly once on end, replacement, reset or stop. */
        public void finish()
        {
            settle(mState.getAndSet(FINISHED));
        }

        /** An unresolved grant subsequently resolved; the normal call tracker will account for it instead. */
        public void cancel()
        {
            mState.getAndSet(FINISHED);
        }

        /** One bounded quiet check, also usable by the existing call owner before replacement/resolution. */
        public void expire(long now)
        {
            State state = mState.get();
            if(state != FINISHED && !state.expired() && now - state.timestamp() > mQuietMs &&
                mState.compareAndSet(state, new State(state.outcome(), state.timestamp(), true)))
            {
                settle(state);
            }
        }

        private void settle(State state)
        {
            if(state != FINISHED && !state.expired())
            {
                if(mTracked) count(state.outcome());
                else lost();
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
