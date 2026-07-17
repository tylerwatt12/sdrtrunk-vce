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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Arrival-driven processor for a tuner's native IQ buffers.  The queue is limited by sample time instead of buffer
 * count because receiver buffer sizes vary widely.  During overload, the oldest queued IQ is discarded so processing
 * resumes with the newest available signal.
 */
class NativeBufferProcessor implements Listener<INativeBuffer>
{
    static final long DEFAULT_MAXIMUM_QUEUE_DURATION_MILLISECONDS = 100;
    private static final long OVERFLOW_WARNING_INTERVAL_NANOSECONDS = TimeUnit.SECONDS.toNanos(5);
    private static final Logger mLog = LoggerFactory.getLogger(NativeBufferProcessor.class);

    private final String mName;
    private final long mMaximumQueueDurationMilliseconds;
    private final Listener<INativeBuffer> mListener;
    private final ExecutorService mExecutorService;
    private final Deque<INativeBuffer> mQueue = new ArrayDeque<>();
    private final ReentrantLock mLock = new ReentrantLock();
    private final AtomicBoolean mInvalidBufferWarningLogged = new AtomicBoolean();
    private final AtomicBoolean mUnconfiguredSampleRateWarningLogged = new AtomicBoolean();
    private double mSampleRate;
    private long mMaximumQueuedSampleCount;
    private long mQueuedSampleCount;
    private long mDroppedBufferCount;
    private long mDroppedSampleCount;
    private long mDroppedDurationNanoseconds;
    private long mLastOverflowWarningTimestamp;
    private boolean mProcessingScheduled;
    private boolean mRunning;
    private boolean mDisposed;

    NativeBufferProcessor(String name, double sampleRate, Listener<INativeBuffer> listener)
    {
        this(name, sampleRate, DEFAULT_MAXIMUM_QUEUE_DURATION_MILLISECONDS, listener);
    }

    NativeBufferProcessor(String name, double sampleRate, long maximumQueueDurationMilliseconds,
                          Listener<INativeBuffer> listener)
    {
        if(name == null || name.isBlank())
        {
            throw new IllegalArgumentException("Native buffer processor name cannot be empty");
        }

        if(maximumQueueDurationMilliseconds <= 0)
        {
            throw new IllegalArgumentException("Maximum queue duration must be greater than zero");
        }

        if(listener == null)
        {
            throw new IllegalArgumentException("Native buffer listener cannot be null");
        }

        validateInitialSampleRate(sampleRate);
        mName = name;
        mSampleRate = sampleRate;
        mMaximumQueueDurationMilliseconds = maximumQueueDurationMilliseconds;
        mMaximumQueuedSampleCount = sampleRate > 0 ? calculateMaximumQueuedSampleCount(sampleRate) : 0;
        mListener = listener;
        mExecutorService = Executors.newSingleThreadExecutor(new NamingThreadFactory(name));
    }

    /**
     * Starts accepting and processing native buffers.
     */
    void start()
    {
        mLock.lock();

        try
        {
            if(mDisposed)
            {
                throw new IllegalStateException("Cannot restart a disposed native buffer processor");
            }

            if(!mRunning)
            {
                clearQueue();
                mRunning = true;
            }
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Stops accepting buffers and discards any queued IQ.  A buffer already being processed is allowed to finish.
     */
    void stop()
    {
        mLock.lock();

        try
        {
            mRunning = false;
            clearQueue();
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Permanently shuts down this processor.
     */
    void dispose()
    {
        mLock.lock();

        try
        {
            if(mDisposed)
            {
                return;
            }

            mRunning = false;
            mDisposed = true;
            clearQueue();
        }
        finally
        {
            mLock.unlock();
        }

        //Do not wait here.  The processing callback can be releasing locks held by the shutdown caller.
        mExecutorService.shutdown();
    }

    /**
     * Updates the sample rate used to translate the time limit into a sample limit.
     */
    void setSampleRate(double sampleRate)
    {
        validateSampleRate(sampleRate);
        Overflow overflow;

        mLock.lock();

        try
        {
            mSampleRate = sampleRate;
            mMaximumQueuedSampleCount = calculateMaximumQueuedSampleCount(sampleRate);
            mUnconfiguredSampleRateWarningLogged.set(false);
            overflow = trimQueue();
        }
        finally
        {
            mLock.unlock();
        }

        logOverflow(overflow);
    }

    /**
     * Enqueues a native IQ buffer and immediately schedules processing when the consumer is idle.
     */
    @Override
    public void receive(INativeBuffer nativeBuffer)
    {
        if(nativeBuffer == null)
        {
            return;
        }

        if(nativeBuffer.sampleCount() <= 0)
        {
            if(mInvalidBufferWarningLogged.compareAndSet(false, true))
            {
                mLog.warn("Native buffer processor [{}] ignored a buffer with no samples", mName);
            }

            return;
        }

        boolean scheduleProcessing = false;
        Overflow overflow;

        mLock.lock();

        try
        {
            if(!mRunning || mDisposed)
            {
                return;
            }

            if(mSampleRate <= 0)
            {
                if(mUnconfiguredSampleRateWarningLogged.compareAndSet(false, true))
                {
                    mLog.warn("Native buffer processor [{}] ignored a buffer before sample-rate configuration", mName);
                }

                return;
            }

            mQueue.addLast(nativeBuffer);
            mQueuedSampleCount += getSampleCount(nativeBuffer);
            overflow = trimQueue();

            if(!mProcessingScheduled)
            {
                mProcessingScheduled = true;
                scheduleProcessing = true;
            }
        }
        finally
        {
            mLock.unlock();
        }

        logOverflow(overflow);

        if(scheduleProcessing)
        {
            try
            {
                mExecutorService.execute(this::process);
            }
            catch(RejectedExecutionException ree)
            {
                boolean logRejection;
                mLock.lock();

                try
                {
                    mProcessingScheduled = false;
                    clearQueue();
                    logRejection = !mDisposed;
                }
                finally
                {
                    mLock.unlock();
                }

                if(logRejection)
                {
                    mLog.error("Native buffer processor [{}] rejected its processing task", mName, ree);
                }
            }
        }
    }

    /**
     * Processes queued buffers serially until the queue is empty or processing is stopped.
     */
    private void process()
    {
        while(true)
        {
            INativeBuffer nativeBuffer;

            mLock.lock();

            try
            {
                if(!mRunning || mQueue.isEmpty())
                {
                    mProcessingScheduled = false;
                    return;
                }

                nativeBuffer = mQueue.removeFirst();
                mQueuedSampleCount -= getSampleCount(nativeBuffer);
            }
            finally
            {
                mLock.unlock();
            }

            try
            {
                mListener.receive(nativeBuffer);
            }
            catch(Throwable throwable)
            {
                mLog.error("Error while processing a native buffer in [{}]", mName, throwable);
            }
        }
    }

    /**
     * Removes the oldest buffers until the queue is within its time limit.  One complete hardware buffer is always
     * retained because some receivers can produce a single buffer longer than the configured target.
     */
    private Overflow trimQueue()
    {
        long droppedBuffers = 0;
        long droppedSamples = 0;

        while(mQueuedSampleCount > mMaximumQueuedSampleCount && mQueue.size() > 1)
        {
            INativeBuffer dropped = mQueue.removeFirst();
            long sampleCount = getSampleCount(dropped);
            mQueuedSampleCount -= sampleCount;
            droppedBuffers++;
            droppedSamples += sampleCount;
        }

        if(droppedBuffers > 0)
        {
            long droppedDurationNanoseconds = toNanoseconds(droppedSamples, mSampleRate);
            mDroppedBufferCount += droppedBuffers;
            mDroppedSampleCount += droppedSamples;
            mDroppedDurationNanoseconds += droppedDurationNanoseconds;
            long now = System.nanoTime();
            boolean shouldLog = mLastOverflowWarningTimestamp == 0 ||
                now - mLastOverflowWarningTimestamp >= OVERFLOW_WARNING_INTERVAL_NANOSECONDS;

            if(shouldLog)
            {
                mLastOverflowWarningTimestamp = now;
                return new Overflow(droppedBuffers, toMilliseconds(droppedDurationNanoseconds),
                    toMilliseconds(mQueuedSampleCount, mSampleRate), mDroppedBufferCount,
                    toMilliseconds(mDroppedDurationNanoseconds));
            }
        }

        return null;
    }

    private void logOverflow(Overflow overflow)
    {
        if(overflow != null)
        {
            mLog.warn("Native buffer processor [{}] discarded [{}] stale buffer(s), [{}] ms of IQ; retained [{}] " +
                    "ms, total discarded [{}] buffer(s)/[{}] ms", mName, overflow.droppedBuffers(),
                overflow.droppedMilliseconds(), overflow.queuedMilliseconds(), overflow.totalDroppedBuffers(),
                overflow.totalDroppedMilliseconds());
        }
    }

    private long calculateMaximumQueuedSampleCount(double sampleRate)
    {
        return Math.max(1, Math.round(sampleRate * mMaximumQueueDurationMilliseconds / 1000.0));
    }

    private static long getSampleCount(INativeBuffer nativeBuffer)
    {
        return nativeBuffer.sampleCount();
    }

    private static long toMilliseconds(long sampleCount, double sampleRate)
    {
        return Math.round(sampleCount * 1000.0 / sampleRate);
    }

    private static long toNanoseconds(long sampleCount, double sampleRate)
    {
        return Math.round(sampleCount * 1_000_000_000.0 / sampleRate);
    }

    private static long toMilliseconds(long nanoseconds)
    {
        return Math.round(nanoseconds / 1_000_000.0);
    }

    private static void validateSampleRate(double sampleRate)
    {
        if(!Double.isFinite(sampleRate) || sampleRate <= 0)
        {
            throw new IllegalArgumentException("Sample rate must be a positive finite value");
        }
    }

    /**
     * Tuner controllers are constructed before hardware configuration and can initially report a zero sample rate.
     * Negative and non-finite values are never valid.
     */
    private static void validateInitialSampleRate(double sampleRate)
    {
        if(!Double.isFinite(sampleRate) || sampleRate < 0)
        {
            throw new IllegalArgumentException("Initial sample rate must be zero or a positive finite value");
        }
    }

    private void clearQueue()
    {
        mQueue.clear();
        mQueuedSampleCount = 0;
    }

    int getQueueSize()
    {
        mLock.lock();

        try
        {
            return mQueue.size();
        }
        finally
        {
            mLock.unlock();
        }
    }

    long getQueuedSampleCount()
    {
        mLock.lock();

        try
        {
            return mQueuedSampleCount;
        }
        finally
        {
            mLock.unlock();
        }
    }

    long getMaximumQueuedSampleCount()
    {
        mLock.lock();

        try
        {
            return mMaximumQueuedSampleCount;
        }
        finally
        {
            mLock.unlock();
        }
    }

    long getDroppedBufferCount()
    {
        mLock.lock();

        try
        {
            return mDroppedBufferCount;
        }
        finally
        {
            mLock.unlock();
        }
    }

    long getDroppedSampleCount()
    {
        mLock.lock();

        try
        {
            return mDroppedSampleCount;
        }
        finally
        {
            mLock.unlock();
        }
    }

    boolean awaitTermination(long timeout, TimeUnit timeUnit) throws InterruptedException
    {
        return mExecutorService.awaitTermination(timeout, timeUnit);
    }

    private record Overflow(long droppedBuffers, long droppedMilliseconds, long queuedMilliseconds,
                            long totalDroppedBuffers, long totalDroppedMilliseconds)
    {
    }
}
