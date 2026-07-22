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

package io.github.dsheirer.spectrum.stream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded, transport-neutral, latest-only fan-out for spectrum frames.
 *
 * <p>One shared source is started when the first subscriber arrives.  It remains active for a configurable grace
 * period after the last subscriber leaves so that a quick browser reconnect does not churn the upstream producer.
 * Each subscriber owns exactly one replaceable frame slot.  Publishing uses only atomic operations and never waits
 * for a subscriber, so a slow or disconnected transport cannot apply backpressure to spectrum production.</p>
 */
public final class SpectrumStreamService implements AutoCloseable
{
    private static final Duration EXECUTOR_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final Configuration mConfiguration;
    private final SpectrumFrameSource mFrameSource;
    private final Object mLifecycleLock = new Object();
    private final CopyOnWriteArrayList<Subscription> mSubscriptions = new CopyOnWriteArrayList<>();
    private final ScheduledThreadPoolExecutor mGraceExecutor;
    private final AtomicLong mPublishedFrameCount = new AtomicLong();
    private final AtomicLong mSourceStartCount = new AtomicLong();
    private final AtomicLong mSourceStopCount = new AtomicLong();
    private volatile boolean mClosed;
    private volatile boolean mSourceStarted;
    private ScheduledFuture<?> mPendingStop;

    public SpectrumStreamService(Configuration configuration, SpectrumFrameSource frameSource)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Spectrum stream configuration cannot be null");
        mFrameSource = Objects.requireNonNull(frameSource, "Spectrum frame source cannot be null");
        mGraceExecutor = new ScheduledThreadPoolExecutor(1, runnable ->
        {
            Thread thread = new Thread(runnable, mConfiguration.lifecycleThreadName());
            thread.setDaemon(true);
            return thread;
        });
        mGraceExecutor.setRemoveOnCancelPolicy(true);
        mGraceExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        mGraceExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    /**
     * Attempts to create a subscriber without exceeding the configured bound.
     */
    public Optional<Subscription> trySubscribe()
    {
        synchronized(mLifecycleLock)
        {
            if(mClosed)
            {
                throw new IllegalStateException("Spectrum stream service is closed");
            }

            if(mSubscriptions.size() >= mConfiguration.maximumSubscribers())
            {
                return Optional.empty();
            }

            cancelPendingStopLocked();

            //An interactive source can stop itself when its selected tuner is disabled, removed, or errors.  Do not
            //preserve that stopped instance through the reconnect grace period; the next owner gets a fresh start.
            if(mSourceStarted && !mFrameSource.isRunning())
            {
                stopSourceLocked();
            }

            Subscription subscription = new Subscription(this);
            mSubscriptions.add(subscription);

            if(!mSourceStarted)
            {
                try
                {
                    mFrameSource.start(this::publish);
                    mSourceStarted = true;
                    mSourceStartCount.incrementAndGet();
                }
                catch(RuntimeException exception)
                {
                    mSubscriptions.remove(subscription);
                    subscription.closeFromService();
                    throw exception;
                }
            }

            return Optional.of(subscription);
        }
    }

    private void publish(SpectrumFrame frame)
    {
        if(frame == null || mClosed)
        {
            return;
        }

        mPublishedFrameCount.incrementAndGet();

        for(Subscription subscription: mSubscriptions)
        {
            subscription.offer(frame);
        }
    }

    private void unsubscribe(Subscription subscription)
    {
        synchronized(mLifecycleLock)
        {
            if(!mSubscriptions.remove(subscription) || mClosed || !mSubscriptions.isEmpty())
            {
                return;
            }

            if(mConfiguration.lastSubscriberGrace().isZero())
            {
                stopSourceLocked();
            }
            else
            {
                cancelPendingStopLocked();
                mPendingStop = mGraceExecutor.schedule(this::stopSourceIfIdle,
                    mConfiguration.lastSubscriberGrace().toNanos(), TimeUnit.NANOSECONDS);
            }
        }
    }

    private void stopSourceIfIdle()
    {
        synchronized(mLifecycleLock)
        {
            mPendingStop = null;

            if(!mClosed && mSubscriptions.isEmpty())
            {
                stopSourceLocked();
            }
        }
    }

    private void stopSourceLocked()
    {
        if(!mSourceStarted)
        {
            return;
        }

        mSourceStarted = false;
        mFrameSource.stop();
        mSourceStopCount.incrementAndGet();
    }

    private void cancelPendingStopLocked()
    {
        if(mPendingStop != null)
        {
            mPendingStop.cancel(false);
            mPendingStop = null;
        }
    }

    public int getSubscriberCount()
    {
        return mSubscriptions.size();
    }

    public boolean isSourceRunning()
    {
        return mSourceStarted && mFrameSource.isRunning();
    }

    public long getPublishedFrameCount()
    {
        return mPublishedFrameCount.get();
    }

    public long getSourceStartCount()
    {
        return mSourceStartCount.get();
    }

    public long getSourceStopCount()
    {
        return mSourceStopCount.get();
    }

    public boolean isInteractive()
    {
        return mFrameSource instanceof InteractiveSpectrumFrameSource;
    }

    public List<InteractiveSpectrumFrameSource.Target> getTargets()
    {
        if(mFrameSource instanceof InteractiveSpectrumFrameSource interactive)
        {
            return interactive.getTargets();
        }

        return List.of(new InteractiveSpectrumFrameSource.Target("DEFAULT", "Spectrum"));
    }

    public void requestView(InteractiveSpectrumFrameSource.ViewRequest request)
    {
        Objects.requireNonNull(request, "Spectrum view request cannot be null");

        if(mFrameSource instanceof InteractiveSpectrumFrameSource interactive)
        {
            interactive.requestView(request);
            return;
        }

        if(request.viewport() != null || request.targetId() != null && !"DEFAULT".equals(request.targetId()))
        {
            throw new IllegalArgumentException("Spectrum source does not support interactive views");
        }
    }

    public InteractiveSpectrumFrameSource.AppliedView getAppliedView()
    {
        return mFrameSource instanceof InteractiveSpectrumFrameSource interactive ? interactive.getAppliedView() :
            null;
    }

    public boolean isLifecycleExecutorTerminated()
    {
        return mGraceExecutor.isTerminated();
    }

    @Override
    public void close()
    {
        List<Subscription> subscriptions;
        RuntimeException closeFailure = null;

        synchronized(mLifecycleLock)
        {
            if(mClosed)
            {
                return;
            }

            mClosed = true;
            cancelPendingStopLocked();
            subscriptions = new ArrayList<>(mSubscriptions);
            mSubscriptions.clear();

            try
            {
                stopSourceLocked();
            }
            catch(RuntimeException exception)
            {
                closeFailure = exception;
            }
        }

        for(Subscription subscription: subscriptions)
        {
            subscription.closeFromService();
        }

        try
        {
            mFrameSource.close();
        }
        catch(RuntimeException exception)
        {
            closeFailure = appendFailure(closeFailure, exception);
        }
        finally
        {
            mGraceExecutor.shutdownNow();

            try
            {
                SyntheticSpectrumFrameSource.awaitExecutorTermination(mGraceExecutor, EXECUTOR_SHUTDOWN_TIMEOUT,
                    "spectrum stream lifecycle executor");
            }
            catch(RuntimeException exception)
            {
                closeFailure = appendFailure(closeFailure, exception);
            }
        }

        if(closeFailure != null)
        {
            throw closeFailure;
        }
    }

    private static RuntimeException appendFailure(RuntimeException first, RuntimeException additional)
    {
        if(first == null)
        {
            return additional;
        }

        first.addSuppressed(additional);
        return first;
    }

    public record Configuration(int maximumSubscribers, Duration lastSubscriberGrace, String lifecycleThreadName)
    {
        public Configuration
        {
            if(maximumSubscribers <= 0)
            {
                throw new IllegalArgumentException("Maximum subscribers must be positive");
            }

            Objects.requireNonNull(lastSubscriberGrace, "Last-subscriber grace cannot be null");

            if(lastSubscriberGrace.isNegative())
            {
                throw new IllegalArgumentException("Last-subscriber grace cannot be negative");
            }

            if(!lastSubscriberGrace.isZero() && lastSubscriberGrace.toNanos() <= 0)
            {
                throw new IllegalArgumentException("Last-subscriber grace must be zero or at least one nanosecond");
            }

            if(lifecycleThreadName == null || lifecycleThreadName.isBlank())
            {
                throw new IllegalArgumentException("Lifecycle thread name cannot be blank");
            }
        }
    }

    /**
     * One latest-only nonblocking delivery slot.
     */
    public static final class Subscription implements AutoCloseable
    {
        private final SpectrumStreamService mOwner;
        private final AtomicReference<SpectrumFrame> mLatestFrame = new AtomicReference<>();
        private final Semaphore mAvailable = new Semaphore(0);
        private final AtomicLong mDroppedFrameCount = new AtomicLong();
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private Subscription(SpectrumStreamService owner)
        {
            mOwner = owner;
        }

        private void offer(SpectrumFrame frame)
        {
            if(mClosed.get())
            {
                return;
            }

            SpectrumFrame replaced = mLatestFrame.getAndSet(frame);

            if(mClosed.get())
            {
                mLatestFrame.compareAndSet(frame, null);
                return;
            }

            if(replaced == null)
            {
                mAvailable.release();
            }
            else
            {
                mDroppedFrameCount.incrementAndGet();
            }
        }

        /**
         * Returns the latest pending frame or {@code null} if the timeout expires or this subscription closes.
         */
        public SpectrumFrame poll(Duration timeout) throws InterruptedException
        {
            Objects.requireNonNull(timeout, "Poll timeout cannot be null");

            if(timeout.isNegative())
            {
                throw new IllegalArgumentException("Poll timeout cannot be negative");
            }

            long remainingNanos = timeout.toNanos();
            long startNanos = System.nanoTime();

            while(true)
            {
                boolean acquired = remainingNanos == 0 ? mAvailable.tryAcquire() :
                    mAvailable.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS);

                if(!acquired)
                {
                    return null;
                }

                SpectrumFrame frame = mLatestFrame.getAndSet(null);

                if(frame != null || mClosed.get())
                {
                    return frame;
                }

                if(timeout.isZero())
                {
                    return null;
                }

                remainingNanos = timeout.toNanos() - (System.nanoTime() - startNanos);

                if(remainingNanos <= 0)
                {
                    return null;
                }
            }
        }

        public long getDroppedFrameCount()
        {
            return mDroppedFrameCount.get();
        }

        public boolean hasPendingFrame()
        {
            return mLatestFrame.get() != null;
        }

        public boolean isClosed()
        {
            return mClosed.get();
        }

        @Override
        public void close()
        {
            if(closeSlot())
            {
                mOwner.unsubscribe(this);
            }
        }

        private void closeFromService()
        {
            closeSlot();
        }

        private boolean closeSlot()
        {
            if(!mClosed.compareAndSet(false, true))
            {
                return false;
            }

            mLatestFrame.set(null);
            mAvailable.drainPermits();
            mAvailable.release();
            return true;
        }
    }
}
