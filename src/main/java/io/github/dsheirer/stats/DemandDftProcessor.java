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
import io.github.dsheirer.spectrum.DFTSize;
import io.github.dsheirer.spectrum.NativeBufferManager;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.jtransforms.fft.FloatFFT_1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small low-latency FFT processor used only by active web diagnostics.  It calculates and publishes the newest
 * available window in one scheduled task instead of intentionally delaying publication by one display period.
 */
final class DemandDftProcessor implements Listener<INativeBuffer>, AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(DemandDftProcessor.class);

    private final NativeBufferManager<INativeBuffer> mBufferManager;
    private final FloatFFT_1D mFft;
    private final float[] mSamples;
    private final float[] mWindow;
    private final BiConsumer<Long,float[]> mConsumer;
    private final DiagnosticFftScheduler.Task mTask;
    private final AtomicLong mPendingTimestamp = new AtomicLong();
    private final AtomicBoolean mClosed = new AtomicBoolean();

    DemandDftProcessor(DiagnosticFftScheduler scheduler, DFTSize dftSize, int framesPerSecond,
                       BiConsumer<Long,float[]> consumer)
    {
        Objects.requireNonNull(scheduler, "Diagnostic FFT scheduler cannot be null");
        Objects.requireNonNull(dftSize, "Diagnostic FFT size cannot be null");
        mConsumer = Objects.requireNonNull(consumer, "Diagnostic FFT consumer cannot be null");
        int size = dftSize.getSize();
        mBufferManager = new NativeBufferManager<>(size);
        mFft = new FloatFFT_1D(size);
        mSamples = new float[size * 2];
        mWindow = WindowFactory.getWindow(WindowType.BLACKMAN_HARRIS_7, size * 2);
        mTask = scheduler.scheduleWithFixedDelay(this::calculate, framesPerSecond);
    }

    @Override
    public void receive(INativeBuffer buffer)
    {
        receive(buffer, System.currentTimeMillis());
    }

    void receive(INativeBuffer buffer, long observedAtEpochMs)
    {
        if(buffer == null || mClosed.get())
        {
            return;
        }

        long timestamp = observedAtEpochMs > 0 ? observedAtEpochMs : System.currentTimeMillis();
        mPendingTimestamp.compareAndSet(0, timestamp);
        mBufferManager.add(buffer);
    }

    private void calculate()
    {
        if(mClosed.get())
        {
            return;
        }

        long observedAt = mPendingTimestamp.getAndSet(0);

        try
        {
            mBufferManager.get(mSamples.length / 2, mSamples);
            WindowFactory.apply(mWindow, mSamples);
            mFft.complexForward(mSamples);
            mConsumer.accept(observedAt > 0 ? observedAt : System.currentTimeMillis(),
                ComplexDecibelConverter.convert(mSamples));
        }
        catch(IOException exception)
        {
            if(observedAt > 0)
            {
                mPendingTimestamp.compareAndSet(0, observedAt);
            }
        }
        catch(RuntimeException exception)
        {
            if(!mClosed.get())
            {
                mLog.warn("Unable to calculate a web diagnostic FFT frame", exception);
            }
        }
    }

    @Override
    public void close()
    {
        if(mClosed.compareAndSet(false, true))
        {
            mTask.close();
            mPendingTimestamp.set(0);
        }
    }
}
