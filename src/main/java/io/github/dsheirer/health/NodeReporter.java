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

import java.util.Random;

/**
 * When one node's health reports go, and what window each covers, as the node health report format sets it:
 *
 * - every 300 seconds (or what the service asks for), at a random point of the interval drawn once per node, each
 *   send moved by up to 15 seconds either way, the first at least 60 seconds after the node's reports start;
 * - no retry: a report that fails is carried by the next (its window starts where the last delivered one ended), up
 *   to 900 seconds, after which the window starts later and says so (gap);
 * - busy (429 or 503): wait Retry-After, or double the interval up to an hour, as for a server that doesn't take
 *   reports (404, 405, 410, or an answer that isn't one);
 * - credentials refused (401 or 403): no more reports;
 * - a report turned down as wrong (400): counted as sent, so the next doesn't repeat it.
 *
 * Windows start and end at snapshot times exactly, so consecutive windows follow on without loss or overlap, and
 * every window has time in it.  A node whose reports start while recording goes on (its destination restarted, say)
 * can't say what was reported before: its first window starts at its own first snapshot, with gap, so it never
 * covers time another window already did.
 * Not thread safe: one reporting thread per node.
 */
public class NodeReporter
{
    public static final long INTERVAL_MS = 300_000;
    public static final long JITTER_MS = 15_000;
    public static final long FIRST_AFTER_MS = 60_000;
    public static final long MAX_WINDOW_MS = 900_000;
    public static final long MIN_INTERVAL_MS = 60_000;
    public static final long MAX_INTERVAL_MS = 3_600_000;

    /**
     * Why a report wasn't delivered.
     */
    public enum Failure
    {
        /** Not reached, or a server error: the next report covers this window too. */
        UNREACHABLE,
        /** 429 or 503, or a server that doesn't take reports: back off. */
        BUSY,
        /** 401 or 403: the node's credentials were refused, no more reports. */
        REFUSED,
        /** 400: the report was wrong.  Counted as sent, so the next doesn't repeat it. */
        REJECTED
    }

    /**
     * A report's window.
     * @param base snapshot the window starts at, or null to start at the start of the totals
     * @param now snapshot the window ends at
     * @param gap true when earlier time was never reported
     * @param seq this report's number for the node since the start, from 0
     */
    public record Window(HealthSnapshot base, HealthSnapshot now, boolean gap, long seq)
    {
        public long startMs()
        {
            return base != null ? base.timeMs() : now.sinceMs();
        }
    }

    private final Random mRandom;
    private HealthSnapshot mFirst;
    private long mInterval = INTERVAL_MS;
    private Long mSlot;
    private Long mNextAt;
    private Long mBackoff;
    private long[] mDeliveredTo;
    private long[] mBuilt;
    private long mSeq;
    private volatile boolean mStopped;
    private boolean mCollectionGap;

    /**
     * @param key something of the node's own (its ID), to spread nodes that start together
     */
    public NodeReporter(String key)
    {
        this(System.nanoTime() ^ ((long)key.hashCode() << 32));
    }

    /**
     * @param seed for the node's random phase and jitter.  Seeds close together (nodes started together, with
     *             similar IDs) give unrelated phases: the seed is mixed first.
     */
    public NodeReporter(long seed)
    {
        mRandom = new Random(mix(seed));
    }

    /**
     * SplitMix64's finalizer: every bit of the seed changes every bit of the result.  java.util.Random's own seeding
     * leaves the first values of nearby seeds nearly equal.
     */
    static long mix(long seed)
    {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * The node's reports start: the snapshot they start at, the latest when they do.  Without it, they start at the
     * first snapshot they're asked about.
     */
    public void begin(HealthSnapshot first)
    {
        if(mFirst == null)
        {
            mFirst = first;
        }
    }

    /**
     * True when the node's credentials were refused: it sends no more reports.
     */
    public boolean isStopped()
    {
        return mStopped;
    }

    /**
     * When the next report is due, Unix milliseconds, or null before the first totals.
     */
    public Long getNextAt()
    {
        return mNextAt;
    }

    private long jitter()
    {
        return (long)((mRandom.nextDouble() * 2.0 - 1.0) * JITTER_MS);
    }

    /**
     * Report for a slot, jittered, but not before now.
     */
    private void aim(long slot, long now)
    {
        mSlot = slot;
        mNextAt = Math.max(slot + jitter(), now);
    }

    /**
     * On to the slot after this one, or if that's long past, the first still to come.
     */
    private void advance(long now)
    {
        long interval = mBackoff != null ? mBackoff : mInterval;
        long slot = (mSlot != null ? mSlot : now) + interval;

        while(slot + JITTER_MS < now)
        {
            slot += interval;
        }

        aim(slot, now);
    }

    /**
     * Whether a report is due, given the latest snapshot.
     */
    public boolean isDue(HealthSnapshot now)
    {
        if(mStopped || now == null)
        {
            return false;
        }

        //The totals started over: so do the reports.
        if(mDeliveredTo != null && mDeliveredTo[1] != now.sinceMs())
        {
            mDeliveredTo = null;
            mSeq = 0;
            mFirst = now;
        }

        if(mFirst == null)
        {
            mFirst = now;
        }

        if(mSlot == null)
        {
            long start = mFirst.timeMs();
            long phase = (long)(mRandom.nextDouble() * mInterval);
            long slot = start + phase;

            while(slot < start + FIRST_AFTER_MS)
            {
                slot += mInterval;
            }

            aim(slot, start + FIRST_AFTER_MS);
        }

        return now.timeMs() >= mNextAt;
    }

    /**
     * The window of the report due: from the end of the last one delivered (or, after more than 15 minutes
     * undelivered, the latest start a window may have, with gap) to the latest snapshot.  The first window starts
     * where the totals do, or, for reports that started after other snapshots were taken, at their own first
     * snapshot, with gap.  Null without snapshots, or when the window would have no time in it.
     */
    public Window window(HealthHistory history)
    {
        HealthSnapshot now = history.latest();

        if(now == null)
        {
            return null;
        }

        Long from = mDeliveredTo != null && mDeliveredTo[1] == now.sinceMs() ? mDeliveredTo[0] : null;
        boolean startedLate = from == null && startedLate(history, now);
        long oldest = now.timeMs() - MAX_WINDOW_MS;
        HealthSnapshot base;
        boolean gap;

        if(startedLate)
        {
            gap = true;
            base = history.atOrAfter(Math.max(mFirst.timeMs(), oldest));
        }
        else if(from != null)
        {
            gap = from < oldest;
            base = history.atOrAfter(gap ? oldest : from);
        }
        else if(now.timeMs() - now.sinceMs() > MAX_WINDOW_MS)
        {
            gap = true;
            base = history.atOrAfter(oldest);
        }
        else
        {
            gap = false;
            base = null;
        }

        //A window with no time in it (yet), or a start no snapshot is kept for, isn't sent.
        long start = base != null ? base.timeMs() : now.sinceMs();

        if(start >= now.timeMs() || (base == null && (from != null || startedLate)))
        {
            return null;
        }

        mBuilt = new long[]{now.timeMs(), now.sinceMs()};
        return new Window(base, now, gap || mCollectionGap, mSeq);
    }

    /**
     * True when these reports started after the totals' first snapshot: something may have reported before them.
     */
    private boolean startedLate(HealthHistory history, HealthSnapshot now)
    {
        HealthSnapshot first = history.first();
        return mFirst != null && first != null && mFirst.sinceMs() == now.sinceMs() &&
            first.sinceMs() == now.sinceMs() && mFirst.timeMs() > first.timeMs();
    }

    /**
     * Discards an interval whose measurements were incomplete. The next report starts here and explicitly
     * reports a gap before its start; partial mandatory counts are never presented as a complete window.
     */
    public void discardTo(HealthSnapshot now)
    {
        mDeliveredTo = new long[]{now.timeMs(), now.sinceMs()};
        mBuilt = null;
        mCollectionGap = true;
        advance(now.timeMs() + 1000);
    }

    /**
     * The last window was delivered.
     * @param nextS the service's interval for the next report, seconds, or null
     */
    public void delivered(Double nextS)
    {
        delivered(nextS, mBuilt != null ? mBuilt[0] : 0);
    }

    /** Response-directed delays start at receipt, independently of the older measurement-window boundary. */
    public void delivered(Double nextS, long completedAt)
    {
        if(mBuilt == null)
        {
            return;
        }

        mSeq++;
        mDeliveredTo = mBuilt;
        mBuilt = null;
        mCollectionGap = false;
        mBackoff = null;
        long end = Math.max(mDeliveredTo[0], completedAt);

        if(nextS != null && Double.isFinite(nextS))
        {
            mInterval = Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, (long)(nextS * 1000)));
            aim(end + mInterval, end + MIN_INTERVAL_MS);
        }
        else
        {
            advance(end + MIN_INTERVAL_MS);
        }
    }

    /**
     * The last window wasn't delivered.
     * @param now latest snapshot
     * @param failure why
     * @param retryAfterS a busy service's Retry-After, seconds, or null
     */
    public void failed(HealthSnapshot now, Failure failure, Double retryAfterS)
    {
        failed(now, failure, retryAfterS, now.timeMs());
    }

    /** Transport completion controls scheduling; snapshot time controls only report contents. */
    public void failed(HealthSnapshot now, Failure failure, Double retryAfterS, long completedAt)
    {
        long responseAt = Math.max(now.timeMs(), completedAt);
        switch(failure)
        {
            case REFUSED -> mStopped = true;
            case BUSY -> {
                if(retryAfterS != null && Double.isFinite(retryAfterS))
                {
                    long after = Math.max(1000, (long)(retryAfterS * 1000));
                    mNextAt = after > Long.MAX_VALUE - responseAt ? Long.MAX_VALUE : responseAt + after;
                }
                else
                {
                    mBackoff = Math.min((mBackoff != null ? mBackoff : mInterval) * 2, MAX_INTERVAL_MS);
                    advance(responseAt + 1000);
                }
            }
            case REJECTED -> {
                mSeq++;
                mCollectionGap = false;
                mDeliveredTo = mBuilt != null ? mBuilt : new long[]{now.timeMs(), now.sinceMs()};
                advance(responseAt + 1000);
            }
            case UNREACHABLE -> advance(responseAt + 1000);
        }

        mBuilt = null;
    }
}
