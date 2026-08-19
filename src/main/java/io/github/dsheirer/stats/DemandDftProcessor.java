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

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.dsp.window.WindowFactory;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.ComplexSamplesNativeBufferAdapter;
import io.github.dsheirer.spectrum.DFTSize;
import io.github.dsheirer.spectrum.NativeBufferManager;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import io.github.dsheirer.util.concurrent.BoundedSpscReferenceQueue;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small low-latency FFT processor used only by active web diagnostics.  It calculates and publishes the newest
 * available window in one scheduled task instead of intentionally delaying publication by one display period.
 */
final class DemandDftProcessor implements Listener<INativeBuffer>, AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(DemandDftProcessor.class);
    private static final int INGRESS_CAPACITY = 8;

    private final BoundedSpscReferenceQueue<INativeBuffer> mNativeIngress =
        new BoundedSpscReferenceQueue<>(INGRESS_CAPACITY);
    private final BoundedSpscReferenceQueue<ComplexSamples> mComplexIngress =
        new BoundedSpscReferenceQueue<>(INGRESS_CAPACITY);
    private final ResultConsumer mConsumer;
    private final DFTSize mDftSize;
    private final DiagnosticComplexFft.Factory mFftFactory;
    private final DiagnosticFftScheduler.Task mTask;
    private final AtomicLong mConfiguration = new AtomicLong(1);
    private final AtomicLong mDroppedBuffers = new AtomicLong();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    /* The following fields are created and used only by the diagnostic scheduler worker. */
    private NativeBufferManager<INativeBuffer> mBufferManager;
    private DiagnosticComplexFft mFft;
    private float[] mSamples;
    private float[] mWindow;
    private DFTSize mAppliedDftSize;
    private long mAppliedConfiguration;
    private long mPendingTimestamp;
    private volatile Thread mInitializationThread;

    DemandDftProcessor(DiagnosticFftScheduler scheduler, DFTSize dftSize, int framesPerSecond,
                       BiConsumer<Long,float[]> consumer)
    {
        this(scheduler, dftSize, framesPerSecond, (observedAt, values, configuration) ->
            consumer.accept(observedAt, values));
    }

    DemandDftProcessor(DiagnosticFftScheduler scheduler, DFTSize dftSize, int framesPerSecond,
                       ResultConsumer consumer)
    {
        this(scheduler, dftSize, framesPerSecond, consumer, SerialDiagnosticFft.FACTORY);
    }

    DemandDftProcessor(DiagnosticFftScheduler scheduler, DFTSize dftSize, int framesPerSecond,
                       ResultConsumer consumer, DiagnosticComplexFft.Factory fftFactory)
    {
        Objects.requireNonNull(scheduler, "Diagnostic FFT scheduler cannot be null");
        mDftSize = Objects.requireNonNull(dftSize, "Diagnostic FFT size cannot be null");
        mConsumer = Objects.requireNonNull(consumer, "Diagnostic FFT consumer cannot be null");
        mFftFactory = Objects.requireNonNull(fftFactory, "Diagnostic FFT factory cannot be null");
        mTask = scheduler.scheduleWithFixedDelay(this::calculate, framesPerSecond);
    }

    @Override
    public void receive(INativeBuffer buffer)
    {
        receive(buffer, System.currentTimeMillis());
    }

    void receive(INativeBuffer buffer, long observedAtEpochMs)
    {
        receive(buffer, observedAtEpochMs, mConfiguration.get());
    }

    /**
     * Tags the buffer with the tuning/configuration epoch observed at callback entry.  A late producer offer from an
     * older epoch can then be rejected by the worker instead of being displayed under newer tuner metadata.
     */
    void receive(INativeBuffer buffer, long observedAtEpochMs, long ingressConfiguration)
    {
        if(buffer == null || mClosed.get())
        {
            return;
        }

        long timestamp = observedAtEpochMs > 0 ? observedAtEpochMs : buffer.getTimestamp();

        if(!mNativeIngress.offer(buffer, Math.max(0, timestamp), ingressConfiguration))
        {
            mDroppedBuffers.incrementAndGet();
        }
    }

    /**
     * Receives a processing-chain complex buffer without constructing a native-buffer adapter on that chain's
     * producer thread.  Adaptation and sample copying happen on the diagnostic worker.
     */
    void receive(ComplexSamples samples)
    {
        long ingressConfiguration = mConfiguration.get();

        if(samples != null && !mClosed.get() &&
            !mComplexIngress.offer(samples, Math.max(0, samples.timestamp()), ingressConfiguration))
        {
            mDroppedBuffers.incrementAndGet();
        }
    }

    /**
     * Drops any partially accumulated window at the next worker pass, for example across a tuner retune.
     */
    long requestReset()
    {
        if(!mClosed.get())
        {
            return mConfiguration.incrementAndGet();
        }

        return mConfiguration.get();
    }

    long configuration()
    {
        return mConfiguration.get();
    }

    long droppedBufferCount()
    {
        return mDroppedBuffers.get();
    }

    Thread initializationThread()
    {
        return mInitializationThread;
    }

    private void calculate()
    {
        if(mClosed.get())
        {
            return;
        }

        long configuration = mConfiguration.get();

        try
        {
            boolean firstInitialization = mAppliedDftSize == null;

            if(firstInitialization)
            {
                initialize(mDftSize);
            }

            if(configuration != mAppliedConfiguration)
            {
                mAppliedConfiguration = configuration;

                if(!firstInitialization)
                {
                    //A tuning or size transition must never mix samples from the two configurations.  The queues and
                    //accumulator are worker-owned, so this reset cannot delay their producer callbacks.
                    mBufferManager.clear();
                    mPendingTimestamp = 0;
                    return;
                }
            }

            drainIngress(configuration);
            mBufferManager.get(mSamples.length / 2, mSamples);
            WindowFactory.apply(mWindow, mSamples);
            mFft.forward(mSamples);
            float[] converted = ComplexDecibelConverter.convert(mSamples);

            if(configuration == mConfiguration.get() && !mClosed.get())
            {
                long observedAt = mPendingTimestamp;
                mPendingTimestamp = 0;
                mConsumer.accept(observedAt > 0 ? observedAt : System.currentTimeMillis(),
                    converted, configuration);
            }
        }
        catch(IOException exception)
        {
            //Keep the worker-owned accumulated samples and timestamp for the next bounded pass.
        }
        catch(RuntimeException exception)
        {
            if(!mClosed.get())
            {
                mLog.warn("Unable to calculate a web diagnostic FFT frame", exception);
            }
        }
    }

    private void initialize(DFTSize dftSize)
    {
        int size = dftSize.getSize();
        mBufferManager = new NativeBufferManager<>(size);
        mFft = mFftFactory.create(size);
        mSamples = new float[size * 2];
        mWindow = WindowFactory.getWindow(WindowType.BLACKMAN_HARRIS_7, size * 2);
        mAppliedDftSize = dftSize;
        mInitializationThread = Thread.currentThread();
    }

    private void drainIngress(long configuration)
    {
        INativeBuffer nativeBuffer;

        while((nativeBuffer = mNativeIngress.poll()) != null)
        {
            if(mNativeIngress.lastPolledSecondaryMetadata() != configuration)
            {
                continue;
            }

            captureTimestamp(mNativeIngress.lastPolledMetadata());
            mBufferManager.add(nativeBuffer);
        }

        ComplexSamples complexSamples;

        while((complexSamples = mComplexIngress.poll()) != null)
        {
            if(mComplexIngress.lastPolledSecondaryMetadata() != configuration)
            {
                continue;
            }

            captureTimestamp(mComplexIngress.lastPolledMetadata());
            //Adapter construction is intentionally worker-side; the processing-chain callback only offered the
            //existing ComplexSamples reference to the fixed SPSC ingress.
            mBufferManager.add(new ComplexSamplesNativeBufferAdapter(complexSamples));
        }
    }

    private void captureTimestamp(long timestamp)
    {
        if(mPendingTimestamp <= 0 && timestamp > 0)
        {
            mPendingTimestamp = timestamp;
        }
    }

    @Override
    public void close()
    {
        if(mClosed.compareAndSet(false, true))
        {
            mTask.close();
        }
    }

    @FunctionalInterface
    interface ResultConsumer
    {
        void accept(long observedAtEpochMs, float[] values, long configuration);
    }
}
