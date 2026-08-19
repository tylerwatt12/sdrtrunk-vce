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
import io.github.dsheirer.util.concurrent.ThreadQoS.QoSClass;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-threaded scheduler-lifetime executor for demand-owned web diagnostic FFT calculations. Fixed-delay
 * scheduling prevents delayed diagnostic work from running catch-up bursts that could compete with receiver
 * processing and places a hard one-thread ceiling on diagnostic CPU use. Keeping one executor for the scheduler
 * lifetime also prevents a blocked task from an earlier demand cycle from overlapping a replacement task.
 */
final class DiagnosticFftScheduler implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(DiagnosticFftScheduler.class);
    private final ScheduledThreadPoolExecutor mExecutor;
    private int mTaskCount;
    private boolean mClosed;

    DiagnosticFftScheduler()
    {
        mExecutor = new ScheduledThreadPoolExecutor(1,
            new ObserverThreadFactory("sdrtrunk web diagnostic FFT", QoSClass.UTILITY));
        mExecutor.setRemoveOnCancelPolicy(true);
        mExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        mExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    synchronized Task scheduleWithFixedDelay(Runnable runnable, int framesPerSecond)
    {
        Objects.requireNonNull(runnable, "Diagnostic FFT task cannot be null");

        if(mClosed)
        {
            throw new IllegalStateException("Diagnostic FFT scheduler is closed");
        }

        if(framesPerSecond < 1 || framesPerSecond > 60)
        {
            throw new IllegalArgumentException("Diagnostic frame rate must be between 1 and 60");
        }

        mTaskCount++;

        try
        {
            long delayNanos = TimeUnit.SECONDS.toNanos(1) / framesPerSecond;
            Runnable guarded = () -> {
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

                    //Scheduled executors silently cancel periodic tasks after an uncaught exception.  Diagnostics
                    //are loss-tolerant, so report the failure off the receiver path and keep later frames alive.
                    mLog.warn("Diagnostic worker task failed; later observations will continue", throwable);
                }
            };
            ScheduledFuture<?> future = mExecutor.scheduleWithFixedDelay(guarded, 0, delayNanos,
                TimeUnit.NANOSECONDS);
            return new Task(this, future);
        }
        catch(RuntimeException exception)
        {
            release();
            throw exception;
        }
    }

    private synchronized void release()
    {
        mTaskCount = Math.max(0, mTaskCount - 1);
    }

    synchronized int activeTaskCount()
    {
        return mTaskCount;
    }

    synchronized boolean hasWorker()
    {
        return mTaskCount > 0;
    }

    @Override
    public synchronized void close()
    {
        if(mClosed)
        {
            return;
        }

        mClosed = true;
        mTaskCount = 0;
        mExecutor.shutdownNow();
    }

    static final class Task implements AutoCloseable
    {
        private final DiagnosticFftScheduler mOwner;
        private final ScheduledFuture<?> mFuture;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private Task(DiagnosticFftScheduler owner, ScheduledFuture<?> future)
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
