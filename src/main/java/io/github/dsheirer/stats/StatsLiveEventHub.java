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

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, non-blocking fan-out for server-sent events.
 *
 * <p>Published events receive a monotonically increasing identifier.  Callers can opt into a bounded replay window;
 * the default fan-out mode retains nothing when nobody is listening.  A reconnecting client can atomically register
 * at a prior event identifier and receive everything still available after that point.  If either the configured
 * replay window or an individual subscriber queue cannot cover the requested range, the subscriber receives a
 * {@value #RESNAPSHOT_EVENT_NAME} control event instead of silently receiving a partial transition sequence.</p>
 *
 * <p>All bookkeeping uses one short-held lock.  Producers never wait for a client to consume an event and never do
 * network or serialization work while holding the lock.</p>
 */
public final class StatsLiveEventHub implements AutoCloseable
{
    public static final String RESNAPSHOT_EVENT_NAME = "resnapshot";

    private final int mMaximumSubscribers;
    private final int mQueueCapacity;
    private final int mReplayCapacity;
    private final ReentrantLock mLock = new ReentrantLock();
    private final Set<Subscription> mSubscriptions = new HashSet<>();
    private final ArrayDeque<LiveEvent> mReplay;
    private long mLastEventId;
    private boolean mClosed;

    /**
     * Creates a bounded fan-out hub without retaining events when nobody is listening.
     */
    public StatsLiveEventHub(int maximumSubscribers, int queueCapacity)
    {
        this(maximumSubscribers, queueCapacity, 0);
    }

    /**
     * Creates a hub with independently bounded subscriber and shared replay queues.  A replay capacity of zero
     * disables retention while preserving explicit resnapshot behavior for a reconnect cursor.
     */
    public StatsLiveEventHub(int maximumSubscribers, int queueCapacity, int replayCapacity)
    {
        mMaximumSubscribers = Math.max(1, maximumSubscribers);
        mQueueCapacity = Math.max(1, queueCapacity);
        mReplayCapacity = Math.max(0, replayCapacity);
        mReplay = new ArrayDeque<>(Math.max(1, mReplayCapacity));
    }

    /**
     * Registers a new subscriber at the current high-water mark without replaying older events.
     *
     * <p>This preserves the original Systems stream behavior: the handler registers first, emits its current snapshot,
     * and then drains changes that occurred while the snapshot was being encoded.</p>
     */
    public Subscription subscribe()
    {
        return subscribeInternal(null);
    }

    /**
     * Atomically registers a reconnecting subscriber and queues events newer than {@code lastEventId}.
     *
     * @param lastEventId last completely applied event identifier, where zero means before the first event
     * @return subscription, or null when the hub is closed or at its subscriber limit
     */
    public Subscription subscribe(long lastEventId)
    {
        if(lastEventId < 0)
        {
            throw new IllegalArgumentException("Last event identifier cannot be negative");
        }

        return subscribeInternal(lastEventId);
    }

    private Subscription subscribeInternal(Long lastEventId)
    {
        mLock.lock();

        try
        {
            if(mClosed || mSubscriptions.size() >= mMaximumSubscribers)
            {
                return null;
            }

            long registrationHighWater = mLastEventId;
            long startingEventId = lastEventId != null ? lastEventId : registrationHighWater;
            Subscription subscription = new Subscription(registrationHighWater, startingEventId);
            mSubscriptions.add(subscription);

            if(lastEventId != null)
            {
                queueReplayLocked(subscription, lastEventId, registrationHighWater);
            }

            return subscription;
        }
        finally
        {
            mLock.unlock();
        }
    }

    private void queueReplayLocked(Subscription subscription, long lastEventId, long highWaterEventId)
    {
        if(lastEventId == highWaterEventId)
        {
            return;
        }

        long earliestAvailableEventId = earliestAvailableEventIdLocked();

        if(lastEventId > highWaterEventId)
        {
            subscription.requireResnapshotLocked("cursor_ahead_of_stream", lastEventId, earliestAvailableEventId,
                highWaterEventId);
            return;
        }

        if(mReplay.isEmpty() || lastEventId < earliestAvailableEventId - 1)
        {
            subscription.requireResnapshotLocked("replay_window_expired", lastEventId, earliestAvailableEventId,
                highWaterEventId);
            return;
        }

        int replayCount = 0;

        for(LiveEvent event: mReplay)
        {
            if(event.id() > lastEventId)
            {
                replayCount++;
            }
        }

        if(replayCount > mQueueCapacity)
        {
            subscription.requireResnapshotLocked("replay_exceeds_subscriber_queue", lastEventId,
                earliestAvailableEventId, highWaterEventId);
            return;
        }

        for(LiveEvent event: mReplay)
        {
            if(event.id() > lastEventId)
            {
                subscription.mQueue.addLast(event);
            }
        }

        if(replayCount > 0)
        {
            subscription.mAvailable.signal();
        }
    }

    public boolean hasSubscribers()
    {
        mLock.lock();

        try
        {
            return !mSubscriptions.isEmpty();
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Publishes an event without waiting for any subscriber and returns its stream identifier, or {@code -1} when the
     * hub is already closed.
     */
    public long publish(String name, Object data)
    {
        Objects.requireNonNull(name, "Event name cannot be null");
        mLock.lock();

        try
        {
            if(mClosed)
            {
                return -1;
            }

            if(mLastEventId == Long.MAX_VALUE)
            {
                throw new IllegalStateException("Live event identifier space exhausted");
            }

            LiveEvent event = new LiveEvent(++mLastEventId, name, data);

            if(mReplayCapacity > 0 && mReplay.size() == mReplayCapacity)
            {
                mReplay.removeFirst();
            }

            if(mReplayCapacity > 0)
            {
                mReplay.addLast(event);
            }

            for(Subscription subscription: mSubscriptions)
            {
                subscription.offerLocked(event);
            }

            return event.id();
        }
        finally
        {
            mLock.unlock();
        }
    }

    public long highWaterEventId()
    {
        mLock.lock();

        try
        {
            return mLastEventId;
        }
        finally
        {
            mLock.unlock();
        }
    }

    private long earliestAvailableEventIdLocked()
    {
        if(!mReplay.isEmpty())
        {
            return mReplay.getFirst().id();
        }

        return mLastEventId == Long.MAX_VALUE ? Long.MAX_VALUE : mLastEventId + 1;
    }

    @Override
    public void close()
    {
        mLock.lock();

        try
        {
            if(mClosed)
            {
                return;
            }

            mClosed = true;

            for(Subscription subscription: mSubscriptions)
            {
                subscription.closeLocked();
            }

            mSubscriptions.clear();
            mReplay.clear();
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Immutable event envelope.  Control events use {@value #RESNAPSHOT_EVENT_NAME} and carry a {@link ReplayGap}.
     */
    public record LiveEvent(long id, String name, Object data)
    {
        public boolean requiresResnapshot()
        {
            return RESNAPSHOT_EVENT_NAME.equals(name);
        }
    }

    /**
     * Explains why a client must fetch a fresh authoritative snapshot before applying more deltas.
     */
    public record ReplayGap(String reason, long requestedAfterEventId, long earliestAvailableEventId,
                            long highWaterEventId)
    {
    }

    public final class Subscription implements AutoCloseable
    {
        private final ArrayDeque<LiveEvent> mQueue = new ArrayDeque<>(mQueueCapacity);
        private final Condition mAvailable = mLock.newCondition();
        private final long mRegistrationHighWaterEventId;
        private volatile boolean mClosed;
        private long mLastDeliveredEventId;
        private LiveEvent mPendingResnapshot;

        private Subscription(long registrationHighWaterEventId, long startingEventId)
        {
            mRegistrationHighWaterEventId = registrationHighWaterEventId;
            mLastDeliveredEventId = startingEventId;
        }

        private void offerLocked(LiveEvent event)
        {
            if(mClosed)
            {
                return;
            }

            if(mQueue.size() == mQueueCapacity)
            {
                mQueue.clear();
                requireResnapshotLocked("subscriber_queue_overflow", mLastDeliveredEventId,
                    earliestAvailableEventIdLocked(), event.id());
                return;
            }

            mQueue.addLast(event);
            mAvailable.signal();
        }

        private void requireResnapshotLocked(String reason, long requestedAfterEventId,
                                             long earliestAvailableEventId, long highWaterEventId)
        {
            mQueue.clear();
            mPendingResnapshot = new LiveEvent(highWaterEventId, RESNAPSHOT_EVENT_NAME,
                new ReplayGap(reason, requestedAfterEventId, earliestAvailableEventId, highWaterEventId));
            mAvailable.signal();
        }

        public LiveEvent poll(long timeout, TimeUnit unit) throws InterruptedException
        {
            Objects.requireNonNull(unit, "Time unit cannot be null");
            long remainingNanos = unit.toNanos(timeout);
            mLock.lockInterruptibly();

            try
            {
                while(!mClosed && mPendingResnapshot == null && mQueue.isEmpty())
                {
                    if(remainingNanos <= 0)
                    {
                        return null;
                    }

                    remainingNanos = mAvailable.awaitNanos(remainingNanos);
                }

                if(mClosed)
                {
                    return null;
                }

                LiveEvent event;

                if(mPendingResnapshot != null)
                {
                    event = mPendingResnapshot;
                    mPendingResnapshot = null;
                }
                else
                {
                    event = mQueue.removeFirst();
                }

                mLastDeliveredEventId = Math.max(mLastDeliveredEventId, event.id());
                return event;
            }
            finally
            {
                mLock.unlock();
            }
        }

        /**
         * Event high-water mark captured atomically with registration and replay selection.
         */
        public long registrationHighWaterEventId()
        {
            return mRegistrationHighWaterEventId;
        }

        public boolean isClosed()
        {
            return mClosed;
        }

        /**
         * Advances this subscriber through the high-water mark represented by an authoritative snapshot.  Any queued
         * deltas already covered by that snapshot are discarded so they cannot be applied afterward and temporarily
         * restore stale state.
         */
        public void acknowledgeSnapshot(long highWaterEventId)
        {
            if(highWaterEventId < 0)
            {
                throw new IllegalArgumentException("Snapshot high-water identifier cannot be negative");
            }

            mLock.lock();

            try
            {
                if(mClosed)
                {
                    return;
                }

                if(mPendingResnapshot != null && mPendingResnapshot.id() <= highWaterEventId)
                {
                    mPendingResnapshot = null;
                }

                while(!mQueue.isEmpty() && mQueue.getFirst().id() <= highWaterEventId)
                {
                    mQueue.removeFirst();
                }

                mLastDeliveredEventId = Math.max(mLastDeliveredEventId, highWaterEventId);
            }
            finally
            {
                mLock.unlock();
            }
        }

        @Override
        public void close()
        {
            mLock.lock();

            try
            {
                if(!mClosed)
                {
                    closeLocked();
                    mSubscriptions.remove(this);
                }
            }
            finally
            {
                mLock.unlock();
            }
        }

        private void closeLocked()
        {
            mClosed = true;
            mQueue.clear();
            mPendingResnapshot = null;
            mAvailable.signalAll();
        }
    }
}
