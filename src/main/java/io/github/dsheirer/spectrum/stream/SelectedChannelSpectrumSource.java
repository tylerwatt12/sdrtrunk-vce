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

import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.sample.complex.ComplexSamplesToNativeBufferModule;
import io.github.dsheirer.spectrum.ComplexDftProcessor;
import io.github.dsheirer.spectrum.DFTSize;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demand-owned FFT tap for one already-running channel processing chain.
 *
 * <p>The processing-chain callback performs only the existing bounded native-buffer enqueue. FFT conversion and
 * frame publication run on the dedicated DFT executor. Each consumer sees one replaceable latest-frame slot, so a
 * slow browser can never apply backpressure to the decoder or sample callback.</p>
 */
public final class SelectedChannelSpectrumSource implements AutoCloseable
{
    public static final int FFT_SIZE = 4_096;
    public static final int FRAMES_PER_SECOND = 12;

    private final ProcessingChain mProcessingChain;
    private final long mGeneration;
    private final long mCenterFrequencyHz;
    private final long mSampleRateHz;
    private final ComplexDftProcessor mProcessor;
    private final ComplexSamplesToNativeBufferModule mTap = new ComplexSamplesToNativeBufferModule();
    private final AtomicReference<SpectrumFrame> mLatestFrame = new AtomicReference<>();
    private final Semaphore mAvailable = new Semaphore(0);
    private final AtomicLong mSequence = new AtomicLong();
    private final AtomicLong mPublishedFrames = new AtomicLong();
    private final AtomicLong mDroppedFrames = new AtomicLong();
    private final AtomicBoolean mClosed = new AtomicBoolean();

    public SelectedChannelSpectrumSource(ProcessingChain processingChain, long generation, long centerFrequencyHz,
                                         long sampleRateHz)
    {
        mProcessingChain = Objects.requireNonNull(processingChain, "Processing chain cannot be null");

        if(generation < 0 || centerFrequencyHz < 0 || sampleRateHz <= 0)
        {
            throw new IllegalArgumentException("Selected-channel spectrum metadata is invalid");
        }

        mGeneration = generation;
        mCenterFrequencyHz = centerFrequencyHz;
        mSampleRateHz = sampleRateHz;
        ComplexDftProcessor processor = new ComplexDftProcessor();
        mProcessor = processor;
        boolean moduleAdded = false;

        try
        {
            processor.setRepeatLastFrameWhenIdle(false);
            processor.setDFTSize(DFTSize.FFT04096);
            processor.setFrameRate(FRAMES_PER_SECOND);
            ComplexDecibelConverter converter = new ComplexDecibelConverter();
            converter.addListener(this::publish);
            processor.addConverter(converter);
            mTap.setListener(processor);
            mProcessingChain.addModule(mTap);
            moduleAdded = true;
        }
        catch(RuntimeException exception)
        {
            mClosed.set(true);
            mTap.removeListener();

            if(moduleAdded || mProcessingChain.getModules().contains(mTap))
            {
                try
                {
                    mProcessingChain.removeModule(mTap);
                }
                catch(RuntimeException cleanupException)
                {
                    exception.addSuppressed(cleanupException);
                }
            }

            processor.dispose();
            throw exception;
        }
    }

    private void publish(float[] bins)
    {
        if(mClosed.get() || bins == null || bins.length != FFT_SIZE)
        {
            return;
        }

        SpectrumFrame frame = SpectrumFrame.float32Owned(0, mGeneration, mSequence.incrementAndGet(),
            System.nanoTime(), 0, mCenterFrequencyHz, mSampleRateHz, bins);
        SpectrumFrame replaced = mLatestFrame.getAndSet(frame);

        if(mClosed.get())
        {
            mLatestFrame.compareAndSet(frame, null);
            return;
        }

        mPublishedFrames.incrementAndGet();

        if(replaced == null)
        {
            mAvailable.release();
        }
        else
        {
            mDroppedFrames.incrementAndGet();
        }
    }

    public SpectrumFrame poll(Duration timeout) throws InterruptedException
    {
        Objects.requireNonNull(timeout, "Spectrum poll timeout cannot be null");

        if(timeout.isNegative())
        {
            throw new IllegalArgumentException("Spectrum poll timeout cannot be negative");
        }

        boolean acquired = timeout.isZero() ? mAvailable.tryAcquire() :
            mAvailable.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
        return acquired ? mLatestFrame.getAndSet(null) : null;
    }

    public long getGeneration()
    {
        return mGeneration;
    }

    public long getCenterFrequencyHz()
    {
        return mCenterFrequencyHz;
    }

    public long getSampleRateHz()
    {
        return mSampleRateHz;
    }

    public long getPublishedFrameCount()
    {
        return mPublishedFrames.get();
    }

    public long getDroppedFrameCount()
    {
        return mDroppedFrames.get();
    }

    public boolean isClosed()
    {
        return mClosed.get();
    }

    @Override
    public void close()
    {
        if(!mClosed.compareAndSet(false, true))
        {
            return;
        }

        RuntimeException failure = null;
        mTap.removeListener();

        try
        {
            mProcessingChain.removeModule(mTap);
        }
        catch(RuntimeException exception)
        {
            failure = exception;
        }
        finally
        {
            mProcessor.dispose();
            mLatestFrame.set(null);
            mAvailable.drainPermits();
            mAvailable.release();
        }

        if(failure != null)
        {
            throw failure;
        }
    }
}
