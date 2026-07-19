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
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Lightweight deterministic spectrum source for lifecycle, transport, and browser development.
 */
public final class SyntheticSpectrumFrameSource implements SpectrumFrameSource
{
    private static final Duration EXECUTOR_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final Configuration mConfiguration;
    private final Object mLifecycleLock = new Object();
    private final ScheduledThreadPoolExecutor mExecutor;
    private final AtomicLong mSequence = new AtomicLong();
    private final AtomicLong mProducedFrameCount = new AtomicLong();
    private final AtomicLong mProductionErrorCount = new AtomicLong();
    private final AtomicLong mStartCount = new AtomicLong();
    private final AtomicLong mStopCount = new AtomicLong();
    private volatile Consumer<SpectrumFrame> mFrameConsumer;
    private volatile boolean mRunning;
    private volatile boolean mClosed;
    private long mRunGeneration;
    private ScheduledFuture<?> mProducerTask;

    public SyntheticSpectrumFrameSource(Configuration configuration)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Synthetic spectrum configuration cannot be null");
        mExecutor = new ScheduledThreadPoolExecutor(1, runnable ->
        {
            Thread thread = new Thread(runnable, mConfiguration.threadName());
            thread.setDaemon(true);
            return thread;
        });
        mExecutor.setRemoveOnCancelPolicy(true);
        mExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        mExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    @Override
    public void start(Consumer<SpectrumFrame> frameConsumer)
    {
        Objects.requireNonNull(frameConsumer, "Spectrum frame consumer cannot be null");

        synchronized(mLifecycleLock)
        {
            if(mClosed)
            {
                throw new IllegalStateException("Synthetic spectrum source is closed");
            }

            if(mRunning)
            {
                return;
            }

            mFrameConsumer = frameConsumer;
            mRunning = true;
            long runGeneration = ++mRunGeneration;
            mStartCount.incrementAndGet();
            mProducerTask = mExecutor.scheduleWithFixedDelay(() -> produce(runGeneration), 0,
                mConfiguration.frameInterval().toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private void produce(long runGeneration)
    {
        Consumer<SpectrumFrame> consumer = mFrameConsumer;

        if(!mRunning || mClosed || consumer == null || runGeneration != mRunGeneration)
        {
            return;
        }

        try
        {
            long sequence = mSequence.getAndIncrement();
            long monotonicTimestampNanos = System.nanoTime();
            long captureTimestampEpochNanos = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
            float[] bins = createBins(sequence, mConfiguration.binCount());
            SpectrumFrame frame = SpectrumFrame.float32Owned(
                SpectrumFrame.FLAG_CAPTURE_TIMESTAMP_VALID | SpectrumFrame.FLAG_SYNTHETIC,
                mConfiguration.targetGeneration(), sequence, monotonicTimestampNanos, captureTimestampEpochNanos,
                mConfiguration.centerFrequencyHz(), mConfiguration.sampleRateHz(), bins);

            if(mRunning && !mClosed && consumer == mFrameConsumer && runGeneration == mRunGeneration)
            {
                consumer.accept(frame);
                mProducedFrameCount.incrementAndGet();
            }
        }
        catch(RuntimeException exception)
        {
            // A malformed synthetic frame or consumer failure must not permanently cancel the scheduled producer.
            mProductionErrorCount.incrementAndGet();
        }
    }

    private static float[] createBins(long sequence, int binCount)
    {
        float[] bins = new float[binCount];
        long randomState = sequence ^ 0x9E3779B97F4A7C15L;
        int movingPeak = (int)Math.floorMod(sequence * 3, binCount);
        int fixedPeak = binCount / 3;

        for(int x = 0; x < binCount; x++)
        {
            randomState ^= randomState << 13;
            randomState ^= randomState >>> 7;
            randomState ^= randomState << 17;
            float noise = (randomState & 0xFF) / 255.0f * 5.0f;
            float value = -115.0f + noise;
            int movingDistance = Math.abs(x - movingPeak);
            int fixedDistance = Math.abs(x - fixedPeak);

            if(movingDistance < 5)
            {
                value += 35.0f - movingDistance * 6.0f;
            }

            if(fixedDistance < 3)
            {
                value += 24.0f - fixedDistance * 7.0f;
            }

            bins[x] = value;
        }

        return bins;
    }

    @Override
    public void stop()
    {
        synchronized(mLifecycleLock)
        {
            stopLocked();
        }
    }

    private void stopLocked()
    {
        if(!mRunning)
        {
            return;
        }

        mRunning = false;
        mRunGeneration++;
        mFrameConsumer = null;

        if(mProducerTask != null)
        {
            mProducerTask.cancel(false);
            mProducerTask = null;
        }

        mStopCount.incrementAndGet();
    }

    @Override
    public boolean isRunning()
    {
        return mRunning;
    }

    public long getProducedFrameCount()
    {
        return mProducedFrameCount.get();
    }

    public long getProductionErrorCount()
    {
        return mProductionErrorCount.get();
    }

    public long getStartCount()
    {
        return mStartCount.get();
    }

    public long getStopCount()
    {
        return mStopCount.get();
    }

    public boolean isExecutorTerminated()
    {
        return mExecutor.isTerminated();
    }

    @Override
    public void close()
    {
        synchronized(mLifecycleLock)
        {
            if(mClosed)
            {
                return;
            }

            mClosed = true;
            stopLocked();
        }

        mExecutor.shutdownNow();
        awaitExecutorTermination(mExecutor, EXECUTOR_SHUTDOWN_TIMEOUT, "synthetic spectrum producer");
    }

    static void awaitExecutorTermination(ScheduledThreadPoolExecutor executor, Duration timeout, String description)
    {
        try
        {
            if(!executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS))
            {
                throw new IllegalStateException("Failed to terminate " + description);
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while terminating " + description, exception);
        }
    }

    public record Configuration(long targetGeneration, long centerFrequencyHz, long sampleRateHz, int binCount,
                                Duration frameInterval, String threadName)
    {
        public Configuration
        {
            if(targetGeneration < 0)
            {
                throw new IllegalArgumentException("Target generation cannot be negative");
            }

            if(centerFrequencyHz < 0)
            {
                throw new IllegalArgumentException("Center frequency cannot be negative");
            }

            if(sampleRateHz <= 0)
            {
                throw new IllegalArgumentException("Sample rate must be positive");
            }

            if(binCount <= 0 || binCount > SpectrumFrameCodec.MAXIMUM_BIN_COUNT)
            {
                throw new IllegalArgumentException("Invalid spectrum bin count: " + binCount);
            }

            Objects.requireNonNull(frameInterval, "Frame interval cannot be null");

            if(frameInterval.isZero() || frameInterval.isNegative())
            {
                throw new IllegalArgumentException("Frame interval must be positive");
            }

            if(frameInterval.toNanos() <= 0)
            {
                throw new IllegalArgumentException("Frame interval must be at least one nanosecond");
            }

            if(threadName == null || threadName.isBlank())
            {
                throw new IllegalArgumentException("Producer thread name cannot be blank");
            }
        }
    }
}
