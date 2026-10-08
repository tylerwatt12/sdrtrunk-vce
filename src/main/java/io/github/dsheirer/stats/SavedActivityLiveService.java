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

package io.github.dsheirer.stats;

import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * One demand-driven reader of committed Activity rows. The writer only coalesces a wakeup; database reads,
 * enrichment and bounded browser fan-out run here, away from receiver observations and the database writer.
 * New viewers bridge their history seed to this live edge with the ordinary forward Activity cursor.
 */
final class SavedActivityLiveService implements AutoCloseable
{
    interface Source
    {
        long watermark();
        Map<String,Object> after(long afterId, Long watermarkId);
    }

    record State(long revision, boolean initialized, long watermarkId, long serverTimeMs,
                 int grantTimeoutMs, boolean collectionEnabled)
    {
        Map<String,Object> payload(String subscriptionId)
        {
            return Map.of("subscription_id", subscriptionId, "watermark_id", watermarkId,
                "server_time_ms", System.currentTimeMillis(), "traffic_grant_age_out_milliseconds", grantTimeoutMs,
                "collection_enabled", collectionEnabled);
        }
    }

    private final Source mSource;
    private final Consumer<Boolean> mDemand;
    private final IntSupplier mGrantTimeout;
    private final BooleanSupplier mCollectionEnabled;
    private final StatsLiveEventHub mHub;
    private final AtomicLong mCommitRevision = new AtomicLong();
    private final AtomicBoolean mWakeupPending = new AtomicBoolean();
    private final Semaphore mWakeup = new Semaphore(0);
    private volatile boolean mClosed;
    private volatile boolean mActive;
    private volatile long mEpoch;
    private volatile State mState = new State(0, false, 0, 0, 0, false);
    private Thread mReader;

    SavedActivityLiveService(Source source, Consumer<Boolean> demand, IntSupplier grantTimeout,
                            BooleanSupplier collectionEnabled)
    {
        this(source, demand, grantTimeout, collectionEnabled, 32, 64);
    }

    SavedActivityLiveService(Source source, Consumer<Boolean> demand, IntSupplier grantTimeout,
                            BooleanSupplier collectionEnabled, int maximumSubscribers, int queueCapacity)
    {
        mSource = source;
        mDemand = demand;
        mGrantTimeout = grantTimeout;
        mCollectionEnabled = collectionEnabled;
        mHub = new StatsLiveEventHub(maximumSubscribers, queueCapacity);
    }

    synchronized StatsLiveEventHub.Subscription subscribe()
    {
        if(mClosed) return null;
        StatsLiveEventHub.Subscription subscription = mHub.subscribe(event -> true, this::subscriptionClosed);
        if(subscription == null) return null;
        if(!mActive)
        {
            mActive = true;
            mEpoch++;
            mState = new State(mState.revision() + 1, false, 0, 0, 0, false);
            mDemand.accept(true);
        }
        if(mReader == null)
        {
            mReader = new ObserverThreadFactory("saved-activity-live-reader").newThread(this::runReader);
            mReader.start();
        }
        wakeup();
        return subscription;
    }

    private synchronized void subscriptionClosed()
    {
        if(mActive && !mHub.hasSubscribers())
        {
            mActive = false;
            mEpoch++;
            mDemand.accept(false);
            wakeup();
        }
    }

    /** Constant, nonblocking post-commit notification; no row projection or subscriber work. */
    void signalCommit()
    {
        if(mActive && !mClosed)
        {
            mCommitRevision.incrementAndGet();
            wakeup();
        }
    }

    private void wakeup()
    {
        if(mWakeupPending.compareAndSet(false, true)) mWakeup.release();
    }

    State state()
    {
        return mState;
    }

    private void runReader()
    {
        long epoch = -1;
        long cursor = 0;
        long observedCommit = -1;
        long drainingCommit = -1;
        Long watermark = null;
        boolean readFailed = false;
        while(!mClosed)
        {
            try
            {
                mWakeup.tryAcquire(1, TimeUnit.SECONDS);
                mWakeupPending.set(false);
                if(!mActive) continue;
                long currentEpoch = mEpoch;
                if(epoch != currentEpoch)
                {
                    long beforeRead = mCommitRevision.get();
                    long liveEdge = mSource.watermark();
                    synchronized(this)
                    {
                        if(!current(currentEpoch)) continue;
                        epoch = currentEpoch;
                        cursor = liveEdge;
                        watermark = null;
                        observedCommit = beforeRead;
                        updateState(liveEdge, true);
                    }
                }
                synchronized(this)
                {
                    if(!current(epoch)) continue;
                    updateState(mState.watermarkId(), false);
                }
                long requestedCommit = mCommitRevision.get();
                if(watermark == null && observedCommit == requestedCommit) continue;
                if(watermark == null) drainingCommit = requestedCommit;
                for(int pageCount = 0; pageCount < 4 && current(epoch); pageCount++)
                {
                    Map<String,Object> page = mSource.after(cursor, watermark);
                    long next = ((Number)page.get("next_after_id")).longValue();
                    long pageWatermark = ((Number)page.get("watermark_id")).longValue();
                    boolean more = Boolean.TRUE.equals(page.get("has_more"));
                    synchronized(this)
                    {
                        if(!current(epoch)) break;
                        if(Boolean.TRUE.equals(page.get("reset_required")))
                        {
                            mHub.publish("live_gap", Map.of("reason", "history_reset"));
                            cursor = pageWatermark;
                            watermark = null;
                            updateState(cursor, true);
                        }
                        else
                        {
                            if(page.get("rows") instanceof List<?> rows && !rows.isEmpty())
                            {
                                mHub.publish("activity_append", Map.of("rows", rows, "next_after_id", next,
                                    "watermark_id", pageWatermark));
                            }
                            cursor = next;
                            watermark = more ? pageWatermark : null;
                            State previous = mState;
                            mState = new State(previous.revision(), true, cursor, previous.serverTimeMs(),
                                previous.grantTimeoutMs(), previous.collectionEnabled());
                        }
                    }
                    readFailed = false;
                    if(!more || watermark == null)
                    {
                        observedCommit = drainingCommit;
                        break;
                    }
                }
                if(watermark != null) wakeup();
            }
            catch(InterruptedException exception)
            {
                if(mClosed) return;
            }
            catch(RuntimeException exception)
            {
                synchronized(this)
                {
                    if(mActive && !readFailed)
                        mHub.publish("live_gap", Map.of("reason", "saved_activity_unavailable"));
                }
                readFailed = true;
                // Retry at the bounded idle cadence; commits remain coalesced and are not lost.
            }
        }
    }

    private boolean current(long epoch)
    {
        return !mClosed && mActive && mEpoch == epoch;
    }

    /** Reader-only metadata refresh; unchanged settings do not generate another recovery boundary. */
    private void updateState(long watermark, boolean force)
    {
        int timeout = Math.max(1, mGrantTimeout.getAsInt());
        boolean enabled = mCollectionEnabled.getAsBoolean();
        State previous = mState;
        if(force || timeout != previous.grantTimeoutMs() || enabled != previous.collectionEnabled())
            mState = new State(previous.revision() + 1, true, watermark, System.currentTimeMillis(), timeout, enabled);
    }

    @Override
    public synchronized void close()
    {
        if(mClosed) return;
        mClosed = true;
        mHub.close();
        mActive = false;
        mEpoch++;
        mDemand.accept(false);
        if(mReader != null) mReader.interrupt();
    }
}
