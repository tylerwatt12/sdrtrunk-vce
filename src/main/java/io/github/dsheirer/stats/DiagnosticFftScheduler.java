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

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demand-owned single worker for web diagnostic FFT calculations.  The worker exists only while at least one
 * diagnostic producer is active.  Fixed-delay scheduling prevents delayed diagnostic work from running catch-up
 * bursts that could compete with receiver processing.
 */
final class DiagnosticFftScheduler implements AutoCloseable
{
    private ScheduledThreadPoolExecutor mExecutor;
    private int mTaskCount;
    private boolean mClosed;

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

        if(mExecutor == null)
        {
            mExecutor = new ScheduledThreadPoolExecutor(1, runnableTask ->
            {
                Thread thread = new Thread(runnableTask, "sdrtrunk web diagnostic FFT");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            });
            mExecutor.setRemoveOnCancelPolicy(true);
            mExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            mExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        }

        ScheduledThreadPoolExecutor executor = mExecutor;
        mTaskCount++;

        try
        {
            long delayNanos = TimeUnit.SECONDS.toNanos(1) / framesPerSecond;
            ScheduledFuture<?> future = executor.scheduleWithFixedDelay(runnable, 0, delayNanos,
                TimeUnit.NANOSECONDS);
            return new Task(this, executor, future);
        }
        catch(RuntimeException exception)
        {
            release(executor);
            throw exception;
        }
    }

    private synchronized void release(ScheduledThreadPoolExecutor executor)
    {
        if(executor != mExecutor)
        {
            return;
        }

        mTaskCount = Math.max(0, mTaskCount - 1);

        if(mTaskCount == 0)
        {
            mExecutor = null;
            executor.shutdownNow();
        }
    }

    synchronized int activeTaskCount()
    {
        return mTaskCount;
    }

    synchronized boolean hasWorker()
    {
        return mExecutor != null;
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
        ScheduledThreadPoolExecutor executor = mExecutor;
        mExecutor = null;

        if(executor != null)
        {
            executor.shutdownNow();
        }
    }

    static final class Task implements AutoCloseable
    {
        private final DiagnosticFftScheduler mOwner;
        private final ScheduledThreadPoolExecutor mExecutor;
        private final ScheduledFuture<?> mFuture;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private Task(DiagnosticFftScheduler owner, ScheduledThreadPoolExecutor executor, ScheduledFuture<?> future)
        {
            mOwner = owner;
            mExecutor = executor;
            mFuture = future;
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mFuture.cancel(false);
                mOwner.release(mExecutor);
            }
        }
    }
}
