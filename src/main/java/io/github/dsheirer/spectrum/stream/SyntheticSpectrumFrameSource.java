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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Lightweight deterministic spectrum source for lifecycle, transport, and browser development.
 */
public final class SyntheticSpectrumFrameSource implements InteractiveSpectrumFrameSource
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
    private final AtomicReference<ViewRequest> mRequestedView = new AtomicReference<>();
    private volatile Consumer<SpectrumFrame> mFrameConsumer;
    private volatile AppliedView mAppliedView;
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
            ViewRequest view = mRequestedView.get();

            if(view == null)
            {
                view = new ViewRequest(0, "SYNTHETIC", null);
            }

            int fftSize = syntheticFftSize(view.viewport());
            float[] fullBins = createBins(sequence, fftSize);
            int firstBin = 0;
            int binCount = Math.min(fftSize, MAXIMUM_TRANSMITTED_BINS);

            if(view.viewport() != null)
            {
                Crop crop = crop(view.viewport(), fftSize);
                firstBin = crop.firstBin();
                binCount = crop.binCount();
            }

            float[] bins = firstBin == 0 && binCount == fullBins.length ? fullBins :
                Arrays.copyOfRange(fullBins, firstBin, firstBin + binCount);
            SpectrumFrame frame = SpectrumFrame.float32Owned(
                SpectrumFrame.FLAG_CAPTURE_TIMESTAMP_VALID | SpectrumFrame.FLAG_SYNTHETIC,
                mConfiguration.targetGeneration(), sequence, monotonicTimestampNanos, captureTimestampEpochNanos,
                mConfiguration.centerFrequencyHz(), mConfiguration.sampleRateHz(), view.revision(), fftSize,
                firstBin, bins);
            mAppliedView = new AppliedView(view.revision(), "SYNTHETIC", "Synthetic signal source",
                mConfiguration.targetGeneration(), mConfiguration.centerFrequencyHz(), mConfiguration.sampleRateHz(),
                fftSize, firstBin, binCount);

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

    private int syntheticFftSize(Viewport viewport)
    {
        int baseSize = mConfiguration.binCount();

        if(baseSize != BASE_FFT_SIZE || viewport == null)
        {
            return baseSize;
        }

        double span = Math.min(mConfiguration.sampleRateHz(),
            (double)viewport.endFrequencyHz() - viewport.startFrequencyHz());
        double zoom = mConfiguration.sampleRateHz() / span;
        int fftSize = baseSize;

        while(fftSize < MAXIMUM_FFT_SIZE && zoom >= 2.0)
        {
            fftSize *= 2;
            zoom /= 2.0;
        }

        return fftSize;
    }

    private Crop crop(Viewport viewport, int fftSize)
    {
        double fullStart = mConfiguration.centerFrequencyHz() - mConfiguration.sampleRateHz() / 2.0;
        double fullEnd = fullStart + mConfiguration.sampleRateHz();
        double requestedSpan = Math.min(mConfiguration.sampleRateHz(),
            (double)viewport.endFrequencyHz() - viewport.startFrequencyHz());
        double requestedCenter = ((double)viewport.startFrequencyHz() + viewport.endFrequencyHz()) / 2.0;
        double halfSpan = requestedSpan / 2.0;
        double boundedCenter = Math.max(fullStart + halfSpan, Math.min(fullEnd - halfSpan, requestedCenter));
        double binWidth = (double)mConfiguration.sampleRateHz() / fftSize;
        int binCount = Math.max(1, Math.min(MAXIMUM_TRANSMITTED_BINS,
            (int)Math.round(requestedSpan / binWidth)));
        int firstBin = (int)Math.round((boundedCenter - fullStart) / binWidth - binCount / 2.0);
        return new Crop(Math.max(0, Math.min(fftSize - binCount, firstBin)), binCount);
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

    @Override
    public List<Target> getTargets()
    {
        return List.of(new Target("SYNTHETIC", "Synthetic signal source"));
    }

    @Override
    public void requestView(ViewRequest request)
    {
        Objects.requireNonNull(request, "Synthetic spectrum view request cannot be null");

        if(request.targetId() != null && !"SYNTHETIC".equals(request.targetId()))
        {
            throw new IllegalArgumentException("Synthetic spectrum target is unavailable");
        }

        mRequestedView.set(request);
    }

    @Override
    public AppliedView getAppliedView()
    {
        return mAppliedView;
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

    private record Crop(int firstBin, int binCount)
    {
    }
}
