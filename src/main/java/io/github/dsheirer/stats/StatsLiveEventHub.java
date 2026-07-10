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

/**
 * Bounded fan-out for server-sent events. Slow clients lose their oldest pending update and never block producers.
 */
final class StatsLiveEventHub implements AutoCloseable
{
    private final int mMaximumSubscribers;
    private final int mQueueCapacity;
    private final Set<Subscription> mSubscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicInteger mSubscriberCount = new AtomicInteger();

    StatsLiveEventHub(int maximumSubscribers, int queueCapacity)
    {
        mMaximumSubscribers = Math.max(1, maximumSubscribers);
        mQueueCapacity = Math.max(1, queueCapacity);
    }

    Subscription subscribe()
    {
        while(true)
        {
            int current = mSubscriberCount.get();

            if(current >= mMaximumSubscribers)
            {
                return null;
            }

            if(mSubscriberCount.compareAndSet(current, current + 1))
            {
                break;
            }
        }

        Subscription subscription = new Subscription();
        mSubscriptions.add(subscription);
        return subscription;
    }

    boolean hasSubscribers()
    {
        return !mSubscriptions.isEmpty();
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
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private void offer(LiveEvent event)
        {
            if(mClosed.get() || mQueue.offer(event))
            {
                return;
            }

            mQueue.poll();
            mQueue.offer(event);
        }

        LiveEvent poll(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mClosed.get() ? null : mQueue.poll(timeout, unit);
        }

        boolean isClosed()
        {
            return mClosed.get();
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mSubscriptions.remove(this);
                mSubscriberCount.decrementAndGet();
                mQueue.clear();
            }
        }
    }
}
