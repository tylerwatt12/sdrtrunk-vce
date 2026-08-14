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

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Bounded fan-out for live browser events. Slow subscribers lose their oldest pending update and never block
 * producers; each subscription exposes a monotonic drop count so stateful consumers can request one resnapshot.
 */
final class StatsLiveEventHub implements AutoCloseable
{
    private volatile int mMaximumSubscribers;
    private final int mQueueCapacity;
    private final Set<Subscription> mSubscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicInteger mSubscriberCount = new AtomicInteger();
    private final AtomicLong mRejectedSubscriptions = new AtomicLong();
    private final AtomicLong mDroppedEvents = new AtomicLong();

    StatsLiveEventHub(int maximumSubscribers, int queueCapacity)
    {
        mMaximumSubscribers = Math.max(1, maximumSubscribers);
        mQueueCapacity = Math.max(1, queueCapacity);
    }

    Subscription subscribe()
    {
        return subscribe(event -> true, null);
    }

    Subscription subscribe(Predicate<LiveEvent> filter)
    {
        return subscribe(filter, null);
    }

    Subscription subscribe(Predicate<LiveEvent> filter, Runnable closeAction)
    {
        while(true)
        {
            int current = mSubscriberCount.get();

            if(current >= mMaximumSubscribers)
            {
                mRejectedSubscriptions.incrementAndGet();
                return null;
            }

            if(mSubscriberCount.compareAndSet(current, current + 1))
            {
                break;
            }
        }

        Subscription subscription = new Subscription(filter != null ? filter : event -> true, closeAction);
        mSubscriptions.add(subscription);
        return subscription;
    }

    boolean hasSubscribers()
    {
        return !mSubscriptions.isEmpty();
    }

    /** Returns true when at least one current subscriber would accept this event. */
    boolean hasMatchingSubscriber(String name, Object data)
    {
        LiveEvent event = new LiveEvent(name, data);

        for(Subscription subscription : mSubscriptions)
        {
            if(subscription.accepts(event))
            {
                return true;
            }
        }

        return false;
    }

    int subscriberCount()
    {
        return mSubscriberCount.get();
    }

    int maximumSubscribers()
    {
        return mMaximumSubscribers;
    }

    void setMaximumSubscribers(int maximumSubscribers)
    {
        mMaximumSubscribers = Math.max(1, maximumSubscribers);
    }

    int queueCapacity()
    {
        return mQueueCapacity;
    }

    long rejectedSubscriptions()
    {
        return mRejectedSubscriptions.get();
    }

    long droppedEvents()
    {
        return mDroppedEvents.get();
    }

    void publish(String name, Object data)
    {
        LiveEvent event = new LiveEvent(name, data);

        for(Subscription subscription: mSubscriptions)
        {
            subscription.offer(event);
        }
    }

    @Override
    public void close()
    {
        for(Subscription subscription: mSubscriptions)
        {
            subscription.close();
        }

        mSubscriptions.clear();
    }

    record LiveEvent(String name, Object data)
    {
    }

    final class Subscription implements AutoCloseable
    {
        private final ArrayBlockingQueue<LiveEvent> mQueue = new ArrayBlockingQueue<>(mQueueCapacity);
        private final Predicate<LiveEvent> mFilter;
        private final Runnable mCloseAction;
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final AtomicLong mDroppedCount = new AtomicLong();
        private final AtomicLong mPendingDroppedEvents = new AtomicLong();

        private Subscription(Predicate<LiveEvent> filter, Runnable closeAction)
        {
            mFilter = filter;
            mCloseAction = closeAction;
        }

        private void offer(LiveEvent event)
        {
            if(!accepts(event) || mQueue.offer(event))
            {
                return;
            }

            mQueue.poll();
            mDroppedCount.incrementAndGet();
            mPendingDroppedEvents.incrementAndGet();
            mQueue.offer(event);
            StatsLiveEventHub.this.mDroppedEvents.incrementAndGet();
        }

        private boolean accepts(LiveEvent event)
        {
            return !mClosed.get() && mFilter.test(event);
        }

        long drainDroppedEvents()
        {
            return mPendingDroppedEvents.getAndSet(0L);
        }

        LiveEvent poll(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mClosed.get() ? null : mQueue.poll(timeout, unit);
        }

        boolean isClosed()
        {
            return mClosed.get();
        }

        long droppedCount()
        {
            return mDroppedCount.get();
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mSubscriptions.remove(this);
                mSubscriberCount.decrementAndGet();

                try
                {
                    if(mCloseAction != null)
                    {
                        mCloseAction.run();
                    }
                }
                finally
                {
                    mQueue.clear();
                }
            }
        }
    }
}
