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

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cumulative measurements for one configured channel. Receiver callbacks update a fixed set of atomic scalars;
 * readers never acquire a receiver lock, and only the reporting worker assembles collections. A busy reporter can
 * therefore neither hold up a decoder nor lose accounting observations by filling a handoff queue.
 *
 * Scalar measurements are sampled independently, rather than promising a simultaneous cut across decoder, source,
 * squelch and traffic-manager callbacks. CallTally owns the separate settled call-outcome accounting.
 */
public class ChannelHealth
{
    /** A control channel counts as locked until this long after its last good message. */
    public static final long LOCK_HOLD_MS = 2000;
    private static final int UNKNOWN_IDENTITY = Integer.MIN_VALUE;
    private static final String[] IDENTITY_NAMES =
        {"wacn", "sysId", "rfss", "site", "nac", "network", "colorCode", "system", "ran"};

    private final ChannelInfo mInfo;
    private final CallTally mCalls;
    private final AtomicBoolean mCallsCounted = new AtomicBoolean();
    private volatile String mProtocol;
    private final AtomicLong mFrequency = new AtomicLong();
    private final AtomicIntegerArray mIdentity = new AtomicIntegerArray(IDENTITY_NAMES.length);
    private final AtomicLong mGood = new AtomicLong();
    private final AtomicLong mBad = new AtomicLong();
    private final AtomicLong mSyncLossSym = new AtomicLong();
    private volatile boolean mSyncLossMeasured;
    private final AtomicLong mLockedMs = new AtomicLong();
    private final AtomicLong mLastGood = new AtomicLong();
    private final AtomicLong mRunningMs = new AtomicLong();
    private final AtomicLong mRunningSince = new AtomicLong(-1);
    private final AtomicLong mOpens = new AtomicLong();
    private final AtomicLong mCloses = new AtomicLong();
    private final AtomicLong mOpenMs = new AtomicLong();
    private final AtomicLong mOpenedAt = new AtomicLong(-1);
    private final AtomicLong mDeliveredNanos = new AtomicLong();
    private volatile boolean mDeliveryMeasured;
    private volatile boolean mDeliveryUnknown;
    private final AtomicInteger mTimingWriters = new AtomicInteger();
    private final AtomicLong mTimingRevision = new AtomicLong();
    private final AtomicReference<TimingTotals> mPublishedTiming = new AtomicReference<>(
        new TimingTotals(Long.MIN_VALUE, 0, false, 0, 0, 0, 0, false));

    public ChannelHealth(ChannelInfo info)
    {
        this(info, new CallTally());
    }

    /** Injection point for collection-isolation tests; no observer is called by the measurement methods. */
    ChannelHealth(ChannelInfo info, CallTally calls)
    {
        mInfo = info;
        mCalls = calls;
        for(int index = 0; index < IDENTITY_NAMES.length; index++)
        {
            mIdentity.set(index, UNKNOWN_IDENTITY);
        }
    }

    public ChannelInfo getInfo()
    {
        return mInfo;
    }

    public CallTally getCalls()
    {
        return mCalls;
    }

    public void setCallsCounted()
    {
        mCallsCounted.set(true);
    }

    /** Lifecycle calls are serialized by the processing chain; snapshots never participate in their updates. */
    public void started(long now)
    {
        beginTimingUpdate();
        try
        {
            mRunningSince.compareAndSet(-1, now);
        }
        finally
        {
            endTimingUpdate();
        }
    }

    public void stopped(long now)
    {
        beginTimingUpdate();
        try
        {
            long since = mRunningSince.getAndSet(-1);
            if(since >= 0)
            {
                mRunningMs.addAndGet(Math.max(0, now - since));
            }
            //Finish the last lock interval here so a later restart cannot credit time while stopped.
            long lastGood = mLastGood.getAndSet(0);
            if(lastGood > 0)
            {
                mLockedMs.addAndGet(Math.min(Math.max(0, now - lastGood), LOCK_HOLD_MS));
            }
            closed(now);
        }
        finally
        {
            endTimingUpdate();
        }
    }

    /** Configured supported protocol, known even before the first readable control unit arrives. */
    public void setProtocol(String protocol)
    {
        mProtocol = protocol;
    }

    public void setFrequency(long frequency)
    {
        mFrequency.set(frequency);
    }

    /** Counts a control unit without collection allocation, locks, queues or observer calls. */
    public void control(String protocol, boolean valid, long timestamp)
    {
        mProtocol = protocol;
        if(valid)
        {
            mGood.incrementAndGet();
            beginTimingUpdate();
            try
            {
                long previous = mLastGood.get();
                //The decoder normally has one ordered message writer. One CAS also prevents an obsolete timestamp
                //from moving lock observation backwards; a competing writer is never retried or waited for.
                if(timestamp > previous && mLastGood.compareAndSet(previous, timestamp) && previous > 0)
                {
                    mLockedMs.addAndGet(Math.min(timestamp - previous, LOCK_HOLD_MS));
                }
            }
            finally
            {
                endTimingUpdate();
            }
        }
        else
        {
            mBad.incrementAndGet();
        }
    }

    public void setSyncLossMeasured(boolean measured)
    {
        mSyncLossMeasured = measured;
    }

    public void syncLoss(long symbols)
    {
        if(symbols > 0)
        {
            mSyncLossSym.addAndGet(symbols);
        }
    }

    /** Fixed identity fields only: never grow a map or copy a collection on a decoder callback. */
    public void identity(String name, int value)
    {
        if(name == null)
        {
            return;
        }
        int index = switch(name)
        {
            case "wacn" -> 0;
            case "sysId" -> 1;
            case "rfss" -> 2;
            case "site" -> 3;
            case "nac" -> 4;
            case "network" -> 5;
            case "colorCode" -> 6;
            case "system" -> 7;
            case "ran" -> 8;
            default -> -1;
        };
        if(index >= 0)
        {
            mIdentity.set(index, value);
        }
    }

    public void opened(long now)
    {
        beginTimingUpdate();
        try
        {
            if(mOpenedAt.compareAndSet(-1, now))
            {
                mOpens.incrementAndGet();
            }
        }
        finally
        {
            endTimingUpdate();
        }
    }

    public void closed(long now)
    {
        beginTimingUpdate();
        try
        {
            long opened = mOpenedAt.getAndSet(-1);
            if(opened >= 0)
            {
                mOpenMs.addAndGet(Math.max(0, now - opened));
                mCloses.incrementAndGet();
            }
        }
        finally
        {
            endTimingUpdate();
        }
    }

    private void beginTimingUpdate()
    {
        mTimingWriters.incrementAndGet();
    }

    private void endTimingUpdate()
    {
        mTimingRevision.incrementAndGet();
        mTimingWriters.decrementAndGet();
    }

    /**
     * Only the reporting worker retries. A contended read retains the previous complete, already evaluated tuple;
     * it must not extend an old last-good/open timestamp to the new request time and double-credit an interval.
     */
    private TimingTotals timing(long now)
    {
        for(int attempt = 0; attempt < 3; attempt++)
        {
            long revision = mTimingRevision.get();
            if(mTimingWriters.get() != 0)
            {
                continue;
            }
            long lastGood = mLastGood.get();
            long locked = mLockedMs.get();
            long runningSince = mRunningSince.get();
            long running = mRunningMs.get();
            long openedAt = mOpenedAt.get();
            long open = mOpenMs.get();
            long opens = mOpens.get();
            long closes = mCloses.get();
            if(mTimingWriters.get() != 0 || revision != mTimingRevision.get())
            {
                continue;
            }
            if(lastGood > 0 && now > lastGood)
            {
                locked += Math.min(now - lastGood, LOCK_HOLD_MS);
            }
            running += runningSince >= 0 ? Math.max(0, now - runningSince) : 0;
            open += openedAt >= 0 ? Math.max(0, now - openedAt) : 0;
            boolean lockedNow = runningSince >= 0 && lastGood > 0 && now >= lastGood &&
                now - lastGood <= LOCK_HOLD_MS;
            TimingTotals observed = new TimingTotals(now, locked, lockedNow, running, opens, closes, open, true);
            TimingTotals previous = mPublishedTiming.get();
            if(now >= previous.observedAt() && mPublishedTiming.compareAndSet(previous, observed))
            {
                return observed;
            }
            return incompleteTiming();
        }
        return incompleteTiming();
    }

    private TimingTotals incompleteTiming()
    {
        TimingTotals previous = mPublishedTiming.get();
        return new TimingTotals(previous.observedAt(), previous.lockedMs(), previous.locked(), previous.runningMs(),
            previous.opens(), previous.closes(), previous.openMs(), false);
    }

    private record TimingTotals(long observedAt, long lockedMs, boolean locked, long runningMs,
                                long opens, long closes, long openMs, boolean complete)
    {
    }

    /** A known complex-source rate establishes that zero delivered samples is a measured zero, not unknown. */
    public void sampleRate(double sampleRate)
    {
        if(Double.isFinite(sampleRate) && sampleRate > 0)
        {
            mDeliveryMeasured = true;
        }
    }

    /** Actual complex samples delivered to this processing chain, excluding buffers that never arrived. */
    public void deliveredSamples(int count, double sampleRate)
    {
        if(count <= 0)
        {
            return;
        }
        if(!Double.isFinite(sampleRate) || sampleRate <= 0)
        {
            //Never reinterpret a delivery whose duration was unknown as measured uptime in a later report.
            mDeliveryUnknown = true;
            return;
        }
        mDeliveredNanos.addAndGet(Math.round(count * 1_000_000_000.0 / sampleRate));
        mDeliveryMeasured = true;
    }

    /** Reporting-worker projection; producer callbacks do not use its collections or CallTally snapshot. */
    public Totals totals(long now)
    {
        TimingTotals timing = timing(now);
        Map<String,Integer> identity = new TreeMap<>();
        for(int index = 0; index < IDENTITY_NAMES.length; index++)
        {
            int value = mIdentity.get(index);
            if(value != UNKNOWN_IDENTITY)
            {
                identity.put(IDENTITY_NAMES[index], value);
            }
        }
        return new Totals(mInfo, mProtocol, mFrequency.get(), Map.copyOf(identity), mGood.get(), mBad.get(),
            mSyncLossMeasured ? mSyncLossSym.get() : null, timing.lockedMs(), timing.locked(),
            mCallsCounted.get() ? mCalls.totals(now) : null, timing.runningMs(), timing.opens(), timing.closes(),
            timing.openMs(),
            mDeliveryMeasured && !mDeliveryUnknown ? mDeliveredNanos.get() / 1_000_000 : null, timing.complete());
    }

    /**
     * What the channel is, from its configuration.
     * @param channelId of the channel configuration, for this run
     * @param configurationId the channel configuration's durable ID (a UUID), or null
     * @param aliasListId durable ID of the channel's alias list (which aliases, and so which streams, its calls go to)
     * @param name of the channel
     * @param system name the user gave
     * @param site name the user gave
     * @param aliasListName of the channel
     * @param mode a conventional channel's mode as a node health report names it (fm, am, p25, dmr, nxdn), or null
     *             when the decoder can't be a conventional channel
     * @param symbolRate of its control channel, baud, or null when not known
     */
    public record ChannelInfo(int channelId, String configurationId, long aliasListId, String name, String system,
                              String site, String aliasListName, String mode, Integer symbolRate)
    {
    }

    /**
     * One channel's totals at one moment, since it started.
     * @param protocol supported configured protocol, or null for analog/unsupported decoders; calls establishes
     *                 whether this is a trunked site rather than a conventional digital channel
     * @param frequency tuned
     * @param identity of the site, as broadcast
     * @param good control messages decoded
     * @param bad control messages framed but not decoded
     * @param syncLossSym symbols the control channel's decoder went through without frame sync, or null when its
     *                    decoder doesn't say
     * @param lockedMs milliseconds with a good control message within the last 2 s
     * @param locked true when there was a good control message within the last 2 s at this moment: false while the
     *               channel hunts for its control channel
     * @param calls announced, and their outcomes, or null when nothing counts them
     * @param runningMs milliseconds the channel was processing
     * @param opens squelch openings
     * @param closes squelch closings (transmissions completed)
     * @param openMs milliseconds open
     * @param heardMs duration of complex samples actually delivered to this processing chain, or null when unmeasured;
     *                source buffering and hardware loss not visible to this callback cannot be independently inferred
     * @param timingComplete false when a bounded reader could only retain an older complete timing tuple; the report
     *                       window must be omitted rather than attribute old timing totals to its new boundary
     */
    public record Totals(ChannelInfo info, String protocol, long frequency, Map<String,Integer> identity, long good,
                         long bad, Long syncLossSym, long lockedMs, boolean locked, CallTally.Totals calls,
                         long runningMs,
                         long opens, long closes, long openMs, Long heardMs, boolean timingComplete)
    {
        /** Compatibility snapshots explicitly supplying heard duration predate contention qualification. */
        public Totals(ChannelInfo info, String protocol, long frequency, Map<String,Integer> identity, long good,
                      long bad, Long syncLossSym, long lockedMs, boolean locked, CallTally.Totals calls,
                      long runningMs, long opens, long closes, long openMs, Long heardMs)
        {
            this(info, protocol, frequency, identity, good, bad, syncLossSym, lockedMs, locked, calls,
                runningMs, opens, closes, openMs, heardMs, true);
        }

        /** Compatibility snapshots do not claim sample-delivery measurements. */
        public Totals(ChannelInfo info, String protocol, long frequency, Map<String,Integer> identity, long good,
                      long bad, Long syncLossSym, long lockedMs, boolean locked, CallTally.Totals calls,
                      long runningMs, long opens, long closes, long openMs)
        {
            this(info, protocol, frequency, identity, good, bad, syncLossSym, lockedMs, locked, calls,
                runningMs, opens, closes, openMs, null, true);
        }

        /**
         * True when a supported digital protocol is known. A non-null calls tally separately establishes trunking.
         */
        public boolean isSite()
        {
            return protocol != null;
        }
    }
}
