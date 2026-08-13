/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.filter.channelizer;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.sample.Listener;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Arrival-driven processor for a tuner's native IQ buffers.  The producer handoff is lock-free.  A bounded queue is
 * limited by sample time instead of buffer count because receiver buffer sizes vary widely.  During overload, the
 * oldest queued IQ is discarded so processing resumes with the newest available signal.  A zero duration selects an
 * unbounded diagnostic queue.
 */
class NativeBufferProcessor implements Listener<INativeBuffer>
{
    @FunctionalInterface
    interface GenerationAwareListener
    {
        void receive(INativeBuffer nativeBuffer, long runGeneration);
    }

    static final long DEFAULT_MAXIMUM_QUEUE_DURATION_MILLISECONDS = 100;
    private static final Logger mLog = LoggerFactory.getLogger(NativeBufferProcessor.class);

    private final String mName;
    private final long mMaximumQueueDurationMilliseconds;
    private final GenerationAwareListener mListener;
    private final ExecutorService mExecutorService;
    private final ConcurrentLinkedQueue<QueuedNativeBuffer> mQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger mWorkInProgress = new AtomicInteger();
    private final AtomicInteger mActiveProducers = new AtomicInteger();
    private final AtomicLong mRunGeneration = new AtomicLong();
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicBoolean mDisposed = new AtomicBoolean();
    private volatile double mSampleRate;
    private volatile long mMaximumQueuedSampleCount;
    private final AtomicInteger mWaitingBufferCount = new AtomicInteger();
    private final AtomicLong mWaitingSampleCount = new AtomicLong();
    private final AtomicInteger mHighWaterWaitingBufferCount = new AtomicInteger();
    private final AtomicLong mHighWaterWaitingSampleCount = new AtomicLong();
    private final AtomicLong mReceivedBufferCount = new AtomicLong();
    private final AtomicLong mReceivedSampleCount = new AtomicLong();
    private final AtomicLong mReceivedDurationNanoseconds = new AtomicLong();
    private final AtomicLong mProcessedBufferCount = new AtomicLong();
    private final AtomicLong mProcessedSampleCount = new AtomicLong();
    private final AtomicLong mProcessedDurationNanoseconds = new AtomicLong();
    private final AtomicLong mDroppedBufferCount = new AtomicLong();
    private final AtomicLong mDroppedSampleCount = new AtomicLong();
    private final AtomicLong mDroppedDurationNanoseconds = new AtomicLong();
    private final AtomicLong mCleanupBufferCount = new AtomicLong();
    private final AtomicLong mCleanupSampleCount = new AtomicLong();
    private final AtomicLong mCleanupDurationNanoseconds = new AtomicLong();
    private volatile long mInFlightSampleCount;
    private volatile long mLastIngressNanos;
    private volatile long mLastCompletionNanos;
    private volatile long mActiveSinceNanos;

    NativeBufferProcessor(String name, double sampleRate, Listener<INativeBuffer> listener)
    {
        this(name, sampleRate, ReceiverQueueProfile.getActive().getNativeBufferMaximumQueueDurationMillis(),
            adapt(listener));
    }

    NativeBufferProcessor(String name, double sampleRate, GenerationAwareListener listener)
    {
        this(name, sampleRate, ReceiverQueueProfile.getActive().getNativeBufferMaximumQueueDurationMillis(), listener);
    }

    NativeBufferProcessor(String name, double sampleRate, long maximumQueueDurationMilliseconds,
                          Listener<INativeBuffer> listener)
    {
        this(name, sampleRate, maximumQueueDurationMilliseconds, adapt(listener));
    }

    NativeBufferProcessor(String name, double sampleRate, long maximumQueueDurationMilliseconds,
                          GenerationAwareListener listener)
    {
        if(name == null || name.isBlank())
        {
            throw new IllegalArgumentException("Native buffer processor name cannot be empty");
        }

        if(maximumQueueDurationMilliseconds < 0)
        {
            throw new IllegalArgumentException("Maximum queue duration cannot be negative");
        }

        if(listener == null)
        {
            throw new IllegalArgumentException("Native buffer listener cannot be null");
        }

        validateInitialSampleRate(sampleRate);
        mName = name;
        mSampleRate = sampleRate;
        mMaximumQueueDurationMilliseconds = maximumQueueDurationMilliseconds;
        mMaximumQueuedSampleCount = sampleRate > 0 && maximumQueueDurationMilliseconds > 0 ?
            calculateMaximumQueuedSampleCount(sampleRate) : 0;
        mListener = listener;
        mExecutorService = Executors.newSingleThreadExecutor(new NamingThreadFactory(name));
    }

    /** Starts a new run generation.  Old queued buffers are discarded by the worker, never by the caller. */
    synchronized void start()
    {
        if(mDisposed.get())
        {
            throw new IllegalStateException("Cannot restart a disposed native buffer processor");
        }

        if(mRunning.compareAndSet(false, true))
        {
            mRunGeneration.incrementAndGet();
            signalWorker();
        }
    }

    /** Stops accepting buffers.  A buffer already in the callback is allowed to finish. */
    synchronized void stop()
    {
        if(mRunning.compareAndSet(true, false))
        {
            mRunGeneration.incrementAndGet();
            signalWorker();
        }
    }

    /** Permanently shuts down this processor after the worker releases queued buffers. */
    synchronized void dispose()
    {
        if(mDisposed.compareAndSet(false, true))
        {
            mRunning.set(false);
            mRunGeneration.incrementAndGet();
            signalWorker();
        }
    }

    /** Updates the sample rate used to translate the time limit into a sample limit. */
    void setSampleRate(double sampleRate)
    {
        validateSampleRate(sampleRate);
        mSampleRate = sampleRate;
        mMaximumQueuedSampleCount = mMaximumQueueDurationMilliseconds > 0 ?
            calculateMaximumQueuedSampleCount(sampleRate) : 0;
        trimQueue();
    }

    /**
     * Adds a native buffer without acquiring a lock.  Lifecycle races are tagged with a generation and cleaned by the
     * single worker instead of making the hardware callback wait.
     */
    @Override
    public void receive(INativeBuffer nativeBuffer)
    {
        if(nativeBuffer == null || nativeBuffer.sampleCount() <= 0 || mDisposed.get())
        {
            return;
        }

        mActiveProducers.incrementAndGet();

        try
        {
            double sampleRate = mSampleRate;
            long generation = mRunGeneration.get();

            if(!mRunning.get() || mDisposed.get() || sampleRate <= 0)
            {
                return;
            }

            long sampleCount = nativeBuffer.sampleCount();
            QueuedNativeBuffer queued = new QueuedNativeBuffer(nativeBuffer, sampleCount, sampleRate, generation);

            //Reserve the constant-time counters before publishing the element.  This prevents the worker from
            //subtracting an element whose count has not yet been made visible.
            int waitingBuffers = mWaitingBufferCount.incrementAndGet();
            long waitingSamples = mWaitingSampleCount.addAndGet(sampleCount);
            mQueue.offer(queued);
            mReceivedBufferCount.incrementAndGet();
            mReceivedSampleCount.addAndGet(sampleCount);
            mReceivedDurationNanoseconds.addAndGet(toNanoseconds(sampleCount, sampleRate));
            mLastIngressNanos = System.nanoTime();
            updateHighWater(mHighWaterWaitingBufferCount, waitingBuffers);
            updateHighWater(mHighWaterWaitingSampleCount, waitingSamples);
            trimQueue();
            signalWorker();
        }
        finally
        {
            if(mActiveProducers.decrementAndGet() == 0 && mDisposed.get())
            {
                signalWorker();
            }
        }
    }

    /** Schedules the single worker using a work-in-progress counter so no ingress notification can be lost. */
    private void signalWorker()
    {
        if(mWorkInProgress.getAndIncrement() == 0)
        {
            try
            {
                mExecutorService.execute(this::process);
            }
            catch(RejectedExecutionException exception)
            {
                //The executor is only shut down after disposal and after all active producers have exited.
                mWorkInProgress.set(0);

                if(!mDisposed.get())
                {
                    mLog.error("Native buffer processor [{}] rejected its processing task", mName, exception);
                }
            }
        }
    }

    /** Processes or cleans every published buffer serially. */
    private void process()
    {
        int missedSignals = 1;

        do
        {
            QueuedNativeBuffer queued = mQueue.poll();

            while(queued != null)
            {
                removeWaiting(queued.sampleCount());
                long generation = mRunGeneration.get();

                if(!mRunning.get() || queued.generation() != generation)
                {
                    recordCleanup(queued);
                }
                else
                {
                    process(queued);
                }

                queued = mQueue.poll();
            }

            missedSignals = mWorkInProgress.addAndGet(-missedSignals);
        }
        while(missedSignals != 0);

        if(mDisposed.get() && mActiveProducers.get() == 0 && mQueue.isEmpty())
        {
            mExecutorService.shutdown();
        }
    }

    private void process(QueuedNativeBuffer queued)
    {
        mInFlightSampleCount = queued.sampleCount();
        mActiveSinceNanos = System.nanoTime();

        try
        {
            mListener.receive(queued.buffer(), queued.generation());
        }
        catch(Throwable throwable)
        {
            mLog.error("Error while processing a native buffer in [{}]", mName, throwable);
        }
        finally
        {
            mProcessedBufferCount.incrementAndGet();
            mProcessedSampleCount.addAndGet(queued.sampleCount());
            mProcessedDurationNanoseconds.addAndGet(toNanoseconds(queued.sampleCount(), queued.sampleRate()));
            mLastCompletionNanos = System.nanoTime();
            mInFlightSampleCount = 0;
            mActiveSinceNanos = 0;
        }
    }

    /**
     * Removes oldest waiting buffers until the queue is within its time limit.  One complete hardware buffer is
     * always retained because some receivers produce a single buffer longer than the configured target.
     */
    private void trimQueue()
    {
        if(mMaximumQueueDurationMilliseconds == 0)
        {
            return;
        }

        long maximumSamples = mMaximumQueuedSampleCount;

        while(mWaitingSampleCount.get() > maximumSamples && mWaitingBufferCount.get() > 1)
        {
            QueuedNativeBuffer removed = mQueue.poll();

            if(removed == null)
            {
                return;
            }

            removeWaiting(removed.sampleCount());

            if(mRunning.get() && removed.generation() == mRunGeneration.get())
            {
                recordDrop(removed);
            }
            else
            {
                recordCleanup(removed);
            }
        }
    }

    private void removeWaiting(long samples)
    {
        mWaitingBufferCount.decrementAndGet();
        mWaitingSampleCount.addAndGet(-samples);
    }

    private void recordDrop(QueuedNativeBuffer queued)
    {
        mDroppedBufferCount.incrementAndGet();
        mDroppedSampleCount.addAndGet(queued.sampleCount());
        mDroppedDurationNanoseconds.addAndGet(toNanoseconds(queued.sampleCount(), queued.sampleRate()));
    }

    private void recordCleanup(QueuedNativeBuffer queued)
    {
        mCleanupBufferCount.incrementAndGet();
        mCleanupSampleCount.addAndGet(queued.sampleCount());
        mCleanupDurationNanoseconds.addAndGet(toNanoseconds(queued.sampleCount(), queued.sampleRate()));
    }

    private long calculateMaximumQueuedSampleCount(double sampleRate)
    {
        return Math.max(1, Math.round(sampleRate * mMaximumQueueDurationMilliseconds / 1000.0));
    }

    private static long toMilliseconds(long nanoseconds)
    {
        return Math.round(nanoseconds / 1_000_000.0);
    }

    private static long toMilliseconds(long sampleCount, double sampleRate)
    {
        return sampleRate > 0 ? Math.round(sampleCount * 1000.0 / sampleRate) : 0;
    }

    private static long toNanoseconds(long sampleCount, double sampleRate)
    {
        return Math.round(sampleCount * 1_000_000_000.0 / sampleRate);
    }

    private static void validateSampleRate(double sampleRate)
    {
        if(!Double.isFinite(sampleRate) || sampleRate <= 0)
        {
            throw new IllegalArgumentException("Sample rate must be a positive finite value");
        }
    }

    private static void validateInitialSampleRate(double sampleRate)
    {
        if(!Double.isFinite(sampleRate) || sampleRate < 0)
        {
            throw new IllegalArgumentException("Initial sample rate must be zero or a positive finite value");
        }
    }

    private static GenerationAwareListener adapt(Listener<INativeBuffer> listener)
    {
        if(listener == null)
        {
            throw new IllegalArgumentException("Native buffer listener cannot be null");
        }

        return (nativeBuffer, runGeneration) -> listener.receive(nativeBuffer);
    }

    /**
     * Validates the immutable generation carried by one queued native buffer against the currently active run.
     */
    boolean isCurrentRunGeneration(long runGeneration)
    {
        return mRunning.get() && !mDisposed.get() && mRunGeneration.get() == runGeneration;
    }

    private static void updateHighWater(AtomicInteger highWater, int candidate)
    {
        int current = highWater.get();

        while(candidate > current && !highWater.compareAndSet(current, candidate))
        {
            current = highWater.get();
        }
    }

    private static void updateHighWater(AtomicLong highWater, long candidate)
    {
        long current = highWater.get();

        while(candidate > current && !highWater.compareAndSet(current, candidate))
        {
            current = highWater.get();
        }
    }

    /** Returns a constant-time diagnostic snapshot without traversing the queue or taking a lock. */
    ReceiverQueueMetricsSnapshot.NativeBufferMetrics getQueueMetrics()
    {
        double sampleRate = mSampleRate;
        int waitingBuffers = Math.max(0, mWaitingBufferCount.get());
        long waitingSamples = Math.max(0, mWaitingSampleCount.get());
        long inFlightSamples = mInFlightSampleCount;
        long highWaterSamples = mHighWaterWaitingSampleCount.get();

        return new ReceiverQueueMetricsSnapshot.NativeBufferMetrics(mName, sampleRate,
            mMaximumQueueDurationMilliseconds, mMaximumQueuedSampleCount, waitingBuffers, waitingSamples,
            toMilliseconds(waitingSamples, sampleRate), inFlightSamples > 0 ? 1 : 0, inFlightSamples,
            toMilliseconds(inFlightSamples, sampleRate), mHighWaterWaitingBufferCount.get(), highWaterSamples,
            toMilliseconds(highWaterSamples, sampleRate), mReceivedBufferCount.get(), mReceivedSampleCount.get(),
            toMilliseconds(mReceivedDurationNanoseconds.get()), mProcessedBufferCount.get(),
            mProcessedSampleCount.get(), toMilliseconds(mProcessedDurationNanoseconds.get()),
            mDroppedBufferCount.get(), mDroppedSampleCount.get(), toMilliseconds(mDroppedDurationNanoseconds.get()),
            mCleanupBufferCount.get(), mCleanupSampleCount.get(), toMilliseconds(mCleanupDurationNanoseconds.get()),
            mLastIngressNanos, mLastCompletionNanos, mActiveSinceNanos, mRunning.get(), mDisposed.get());
    }

    int getQueueSize()
    {
        return Math.max(0, mWaitingBufferCount.get());
    }

    long getQueuedSampleCount()
    {
        return Math.max(0, mWaitingSampleCount.get());
    }

    long getMaximumQueuedSampleCount()
    {
        return mMaximumQueuedSampleCount;
    }

    long getDroppedBufferCount()
    {
        return mDroppedBufferCount.get();
    }

    long getDroppedSampleCount()
    {
        return mDroppedSampleCount.get();
    }

    boolean awaitTermination(long timeout, TimeUnit timeUnit) throws InterruptedException
    {
        return mExecutorService.awaitTermination(timeout, timeUnit);
    }

    private record QueuedNativeBuffer(INativeBuffer buffer, long sampleCount, double sampleRate, long generation)
    {
    }
}
