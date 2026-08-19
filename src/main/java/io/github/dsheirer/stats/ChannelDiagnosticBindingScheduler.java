/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import io.github.dsheirer.util.concurrent.ThreadQoS.QoSClass;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One low-priority, single-threaded scheduler-lifetime executor for demand-owned selected-channel lookup and probe
 * lifecycle. It is intentionally separate from the FFT scheduler because a processing-chain module lock must never
 * pause all tuner and channel diagnostics. Retaining one executor between demand cycles prevents a blocked old lookup
 * from overlapping a replacement lookup.
 */
final class ChannelDiagnosticBindingScheduler implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ChannelDiagnosticBindingScheduler.class);
    private final ScheduledThreadPoolExecutor mExecutor;
    private boolean mTaskActive;
    private boolean mClosed;

    ChannelDiagnosticBindingScheduler()
    {
        mExecutor = new ScheduledThreadPoolExecutor(1,
            new ObserverThreadFactory("sdrtrunk web diagnostic binding", QoSClass.UTILITY));
        mExecutor.setRemoveOnCancelPolicy(true);
        mExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        mExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    synchronized Task scheduleWithFixedDelay(Runnable runnable, int executionsPerSecond)
    {
        Objects.requireNonNull(runnable, "Channel diagnostic binding task cannot be null");

        if(mClosed)
        {
            throw new IllegalStateException("Channel diagnostic binding scheduler is closed");
        }

        if(executionsPerSecond < 1 || executionsPerSecond > 10 || mTaskActive)
        {
            throw new IllegalArgumentException("Only one 1-10 Hz channel binding task is supported");
        }

        mTaskActive = true;
        long delayNanos = TimeUnit.SECONDS.toNanos(1) / executionsPerSecond;
        ScheduledFuture<?> future;

        try
        {
            future = mExecutor.scheduleWithFixedDelay(() ->
            {
                try
                {
                    runnable.run();
                }
                catch(Throwable throwable)
                {
                    if(throwable instanceof Error error)
                    {
                        throw error;
                    }

                    mLog.warn("Channel diagnostic binding refresh failed; a later refresh will retry", throwable);
                }
            }, 0, delayNanos, TimeUnit.NANOSECONDS);
        }
        catch(RuntimeException exception)
        {
            mTaskActive = false;
            throw exception;
        }

        return new Task(this, future);
    }

    private synchronized void release()
    {
        mTaskActive = false;
    }

    synchronized boolean hasWorker()
    {
        return mTaskActive;
    }

    @Override
    public synchronized void close()
    {
        mClosed = true;
        mTaskActive = false;
        mExecutor.shutdownNow();
    }

    static final class Task implements AutoCloseable
    {
        private final ChannelDiagnosticBindingScheduler mOwner;
        private final ScheduledFuture<?> mFuture;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private Task(ChannelDiagnosticBindingScheduler owner, ScheduledFuture<?> future)
        {
            mOwner = owner;
            mFuture = future;
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mFuture.cancel(false);
                mOwner.release();
            }
        }
    }
}
