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
 * Measurement updates run only while an enabled reporting destination selects this channel. Cheap lifecycle,
 * rate and identity state remains current while disabled. Collection epochs qualify activation boundaries without
 * resetting cumulative totals. CallTally owns the separate settled call-outcome accounting.
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
    private final Object mCollectionLock = new Object();
    private volatile boolean mCollecting;
    private final AtomicLong mCollectionEpoch = new AtomicLong();
    private volatile long mCollectionSinceMs;
    private volatile boolean mProcessing;
    private volatile boolean mSquelchOpen;
    private volatile boolean mKnownSampleRate;
    private volatile String mProtocol;
    private final AtomicLong mFrequency = new AtomicLong();
    private final AtomicIntegerArray mIdentity = new AtomicIntegerArray(IDENTITY_NAMES.length);
    private final AtomicLong mGood = new AtomicLong();
    private final AtomicLong mBad = new AtomicLong();
    private final AtomicLong mSyncLossSym = new AtomicLong();
    private volatile boolean mSyncLossMeasured;
    private final AtomicLong mLockedMs = new AtomicLong();
    private final AtomicLong mLastGood = new AtomicLong();
    //Lock display uses raw decoder progress and wall freshness; duration accounting above stays unchanged.
    private final AtomicLong mDecoderTime = new AtomicLong(-1);
    private final AtomicLong mDecoderGoodTime = new AtomicLong(-1);
    private final AtomicLong mDecoderArrivedAt = new AtomicLong(-1);
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
        this(info, new CallTally(), true);
    }

    /** Production registrations stay dormant until an enabled destination selects this channel. */
    public ChannelHealth(ChannelInfo info, boolean collecting)
    {
        this(info, new CallTally(), collecting);
    }

    /** Injection point for collection-isolation tests; no observer is called by the measurement methods. */
    ChannelHealth(ChannelInfo info, CallTally calls)
    {
        this(info, calls, true);
    }

    private ChannelHealth(ChannelInfo info, CallTally calls, boolean collecting)
    {
        mInfo = info;
        mCalls = calls;
        mCollecting = collecting;
        mCalls.setEnabled(collecting && mCallsCounted.get());
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
        synchronized(mCollectionLock)
        {
            mCallsCounted.set(true);
            mCalls.setEnabled(mCollecting);
        }
    }

    public boolean isCollecting()
    {
        return mCollecting;
    }

    public long collectionEpoch()
    {
        return mCollectionEpoch.get();
    }

    public long collectionSinceMs()
    {
        return mCollectionSinceMs;
    }

    public boolean countsCalls()
    {
        return mCallsCounted.get();
    }

    public void setCollecting(boolean collecting)
    {
        setCollecting(collecting, System.currentTimeMillis());
    }

    /** Worker/lifecycle only. Receiver callbacks never acquire this transition lock or wait for its work. */
    public void setCollecting(boolean collecting, long now)
    {
        synchronized(mCollectionLock)
        {
            if(mCollecting == collecting)
            {
                return;
            }
            beginTimingUpdate();
            try
            {
                //Close admission before changing interval state. A producer admitted just before this boundary
                //keeps timing readers incomplete until its bounded update finishes.
                mCollecting = false;
                long epoch = mCollectionEpoch.incrementAndGet();
                long since = mRunningSince.getAndSet(-epoch - 2);
                if(since >= 0)
                {
                    mRunningMs.addAndGet(Math.max(0, now - since));
                }
                finishLock(now, -epoch - 1);
                clearDecoderClock();
                finishOpen(now, false, -epoch - 2);
                mCalls.setEnabled(collecting && mCallsCounted.get());
                if(collecting)
                {
                    mCollectionSinceMs = now;
                    mDeliveryMeasured = mKnownSampleRate;
                    mDeliveryUnknown = false;
                    if(mProcessing) mRunningSince.set(now);
                    //An already-open transmission contributes duration after activation, but no invented opening.
                    if(mSquelchOpen) mOpenedAt.set(now);
                }
                mCollecting = collecting;
            }
            finally
            {
                endTimingUpdate();
            }
        }
    }

    /** Lifecycle calls retain live state while off, without accumulating disabled running time. */
    public void started(long now)
    {
        mProcessing = true;
        long epoch = collectionEpoch();
        if(!beginMeasurement(epoch)) return;
        try
        {
            long previous = mRunningSince.get();
            if(previous < 0) mRunningSince.compareAndSet(previous, now);
        }
        finally
        {
            endTimingUpdate();
        }
    }

    public void stopped(long now)
    {
        mProcessing = false;
        mSquelchOpen = false;
        if(!mCollecting) return;
        beginTimingUpdate();
        try
        {
            long since = mRunningSince.getAndSet(-mCollectionEpoch.get() - 2);
            if(since >= 0)
            {
                mRunningMs.addAndGet(Math.max(0, now - since));
            }
            finishLock(now, -mCollectionEpoch.get() - 1);
            clearDecoderClock();
            //Stopping cuts off an open transmission; its duration counts, but it did not complete.
            finishOpen(now, false, -mCollectionEpoch.get() - 2);
        }
        finally
        {
            endTimingUpdate();
        }
    }

    private void finishLock(long now, long empty)
    {
        long lastGood = mLastGood.getAndSet(empty);
        if(lastGood > 0)
        {
            mLockedMs.addAndGet(Math.min(Math.max(0, now - lastGood), LOCK_HOLD_MS));
        }
    }

    private void finishOpen(long now, boolean countClose, long empty)
    {
        long opened = mOpenedAt.getAndSet(empty);
        if(opened >= 0)
        {
            mOpenMs.addAndGet(Math.max(0, now - opened));
            if(countClose) mCloses.incrementAndGet();
        }
    }

    /** Configured supported protocol, known even before the first readable control unit arrives. */
    public void setProtocol(String protocol)
    {
        mProtocol = protocol;
    }

    public void setFrequency(long frequency)
    {
        if(mFrequency.get() == frequency) return;
        beginTimingUpdate();
        try
        {
            if(mFrequency.getAndSet(frequency) != frequency) clearDecoderClock();
        }
        finally
        {
            endTimingUpdate();
        }
    }

    /** Counts a control unit without collection allocation, locks, queues or observer calls. */
    public void control(String protocol, boolean valid, long timestamp)
    {
        control(protocol, valid, timestamp, timestamp);
    }

    /** Decoder timestamps describe sample time; arrival time measures whether decoding is still progressing. */
    public void control(String protocol, boolean valid, long timestamp, long arrivedAt)
    {
        if(!mCollecting) return;
        control(collectionEpoch(), protocol, valid, timestamp, arrivedAt);
    }

    void control(long epoch, String protocol, boolean valid, long timestamp)
    {
        control(epoch, protocol, valid, timestamp, timestamp);
    }

    void control(long epoch, String protocol, boolean valid, long timestamp, long arrivedAt)
    {
        //Capture expected values before admission: a concurrent lifecycle reset makes these CAS updates fail.
        long decoderTime = mDecoderTime.get();
        long decoderGoodTime = mDecoderGoodTime.get();
        long decoderArrivedAt = mDecoderArrivedAt.get();
        if(!beginMeasurement(epoch)) return;
        try
        {
            mProtocol = protocol;
            observeDecoder(timestamp, arrivedAt, valid, decoderTime, decoderGoodTime, decoderArrivedAt);
            if(valid)
            {
                mGood.incrementAndGet();
                long previous = mLastGood.get();
                long observedAt = Math.max(timestamp, mCollectionSinceMs);
                //One CAS prevents an obsolete timestamp/epoch from moving lock observation backwards. Producers
                //never retry; transition sentinels are distinct so stale updates cannot revive an old lock tail.
                if(observedAt > previous && mLastGood.compareAndSet(previous, observedAt) && previous > 0)
                {
                    mLockedMs.addAndGet(Math.min(observedAt - previous, LOCK_HOLD_MS));
                }
            }
            else
            {
                mBad.incrementAndGet();
            }
        }
        finally
        {
            endTimingUpdate();
        }
    }

    /** Epoch check after fixed admission prevents a queued old callback from entering a newly enabled window. */
    private boolean beginMeasurement(long epoch)
    {
        if(!mCollecting || epoch != mCollectionEpoch.get()) return false;
        beginTimingUpdate();
        if(!mCollecting || epoch != mCollectionEpoch.get())
        {
            endTimingUpdate();
            return false;
        }
        return true;
    }

    public void setSyncLossMeasured(boolean measured)
    {
        mSyncLossMeasured = measured;
    }

    public void syncLoss(long symbols)
    {
        if(!mCollecting) return;
        syncLoss(collectionEpoch(), symbols);
    }

    void syncLoss(long epoch, long symbols)
    {
        syncLoss(epoch, symbols, -1, -1);
    }

    public void syncLoss(long symbols, long timestamp, long arrivedAt)
    {
        if(!mCollecting) return;
        syncLoss(collectionEpoch(), symbols, timestamp, arrivedAt);
    }

    void syncLoss(long epoch, long symbols, long timestamp, long arrivedAt)
    {
        long decoderTime = mDecoderTime.get();
        long decoderGoodTime = mDecoderGoodTime.get();
        long decoderArrivedAt = mDecoderArrivedAt.get();
        if(symbols <= 0 || !beginMeasurement(epoch)) return;
        try
        {
            mSyncLossSym.addAndGet(symbols);
            observeDecoder(timestamp, arrivedAt, false, decoderTime, decoderGoodTime, decoderArrivedAt);
        }
        finally
        {
            endTimingUpdate();
        }
    }

    private void observeDecoder(long timestamp, long arrivedAt, boolean valid, long previousTime,
                                long previousGood, long previousArrival)
    {
        if(!mProcessing || timestamp < 0 || arrivedAt < 0) return;
        if(timestamp > previousTime && mDecoderTime.compareAndSet(previousTime, timestamp) &&
            arrivedAt > previousArrival)
        {
            mDecoderArrivedAt.compareAndSet(previousArrival, arrivedAt);
        }
        if(valid && timestamp > previousGood) mDecoderGoodTime.compareAndSet(previousGood, timestamp);
    }

    /** Unique negative sentinels prevent an admitted old callback from restoring cleared lock evidence. */
    private void clearDecoderClock()
    {
        long empty = -mTimingRevision.incrementAndGet() - 1;
        mDecoderTime.set(empty);
        mDecoderGoodTime.set(empty);
        mDecoderArrivedAt.set(empty);
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

    /** Cheap live squelch state is retained while disabled so activation can rebase a current transmission. */
    public void squelchState(boolean open)
    {
        mSquelchOpen = open;
    }

    public void opened(long now)
    {
        mSquelchOpen = true;
        if(!mCollecting) return;
        opened(collectionEpoch(), now);
    }

    void opened(long epoch, long now)
    {
        if(!beginMeasurement(epoch)) return;
        try
        {
            long previous = mOpenedAt.get();
            if(previous < 0 && mOpenedAt.compareAndSet(previous, now)) mOpens.incrementAndGet();
        }
        finally
        {
            endTimingUpdate();
        }
    }

    public void closed(long now)
    {
        mSquelchOpen = false;
        if(!mCollecting) return;
        closed(collectionEpoch(), now);
    }

    void closed(long epoch, long now)
    {
        if(!beginMeasurement(epoch)) return;
        try
        {
            long opened = mOpenedAt.get();
            if(opened >= 0 && mOpenedAt.compareAndSet(opened, -epoch - 2))
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
            long decoderTime = mDecoderTime.get();
            long decoderGoodTime = mDecoderGoodTime.get();
            long decoderArrivedAt = mDecoderArrivedAt.get();
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
            boolean lockedNow = runningSince >= 0 && decoderGoodTime >= 0 && decoderTime >= decoderGoodTime &&
                decoderTime - decoderGoodTime <= LOCK_HOLD_MS && decoderArrivedAt >= 0 &&
                now >= decoderArrivedAt && now - decoderArrivedAt <= LOCK_HOLD_MS;
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
        mKnownSampleRate = Double.isFinite(sampleRate) && sampleRate > 0;
        mDeliveryMeasured = mKnownSampleRate;
    }

    /** Actual conventional complex samples delivered while requested; trunked channels do not use heardS. */
    public void deliveredSamples(int count, double sampleRate)
    {
        if(!mCollecting || mCallsCounted.get()) return;
        deliveredSamples(collectionEpoch(), count, sampleRate);
    }

    void deliveredSamples(long epoch, int count, double sampleRate)
    {
        if(count <= 0 || mCallsCounted.get() || !beginMeasurement(epoch)) return;
        try
        {
            if(!Double.isFinite(sampleRate) || sampleRate <= 0)
            {
                mDeliveryUnknown = true;
                return;
            }
            mDeliveredNanos.addAndGet(Math.round(count * 1_000_000_000.0 / sampleRate));
            mDeliveryMeasured = true;
        }
        finally
        {
            endTimingUpdate();
        }
    }

    /** Reporting-worker projection; producer callbacks do not use its collections or CallTally snapshot. */
    public Totals totals(long now)
    {
        long epoch = mCollectionEpoch.get();
        boolean collecting = mCollecting;
        long collectionSince = mCollectionSinceMs;
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
            mDeliveryMeasured && !mDeliveryUnknown ? mDeliveredNanos.get() / 1_000_000 : null,
            timing.complete() && epoch == mCollectionEpoch.get() && collecting == mCollecting,
            epoch, collecting, collectionSince);
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
     * @param locked true when the latest decoder time is within 2 s of a good control message and decoding advanced
     *               within the last 2 s of wall time; false while stopped, stale or hunting
     * @param calls announced, and their outcomes, or null when nothing counts them
     * @param runningMs milliseconds the channel was processing while collection was requested
     * @param opens squelch openings
     * @param closes squelch closings (transmissions completed)
     * @param openMs milliseconds open
     * @param heardMs duration of complex samples actually delivered to this processing chain, or null when unmeasured;
     *                source buffering and hardware loss not visible to this callback cannot be independently inferred
     * @param timingComplete false when a bounded reader could only retain an older complete timing tuple; the report
     *                       window must be omitted rather than attribute old timing totals to its new boundary
     * @param collectionEpoch changes whenever reporting demand enables or disables this channel
     * @param collecting true while at least one enabled reporting destination selects this channel
     * @param collectionSinceMs start of the current enabled measurement epoch, in Unix milliseconds
     */
    public record Totals(ChannelInfo info, String protocol, long frequency, Map<String,Integer> identity, long good,
                         long bad, Long syncLossSym, long lockedMs, boolean locked, CallTally.Totals calls,
                         long runningMs,
                         long opens, long closes, long openMs, Long heardMs, boolean timingComplete,
                         long collectionEpoch, boolean collecting, long collectionSinceMs)
    {
        /** Compatibility snapshots are collecting fixtures from the start of their supplied cumulative totals. */
        public Totals(ChannelInfo info, String protocol, long frequency, Map<String,Integer> identity, long good,
                      long bad, Long syncLossSym, long lockedMs, boolean locked, CallTally.Totals calls,
                      long runningMs, long opens, long closes, long openMs, Long heardMs, boolean timingComplete)
        {
            this(info, protocol, frequency, identity, good, bad, syncLossSym, lockedMs, locked, calls,
                runningMs, opens, closes, openMs, heardMs, timingComplete, 0, true, 0);
        }

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
