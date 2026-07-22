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

package io.github.dsheirer.dsp.symbol.stream;

import io.github.dsheirer.module.decode.FeedbackDecoder;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded, latest-only batching observer for one decoder's soft symbol stream.
 *
 * <p>The decoder callback performs one finite check, one array write, and, once per batch, one atomic replacement.
 * It never encodes, waits for a consumer, performs I/O, or touches the database.</p>
 */
public final class SelectedChannelSymbolSource implements FeedbackDecoder.SymbolObserver, AutoCloseable
{
    private static final float MAXIMUM_PHASE = (float)Math.PI;
    public static final int BATCH_SIZE = SymbolFrameCodec.MAXIMUM_SYMBOL_COUNT;
    public static final int MAXIMUM_VISIBLE_SYMBOLS = 4_800;

    private final FeedbackDecoder mDecoder;
    private final long mGeneration;
    private final AtomicReference<SymbolFrame> mLatestFrame = new AtomicReference<>();
    private final Semaphore mAvailable = new Semaphore(0);
    private final AtomicLong mSequence = new AtomicLong();
    private final AtomicLong mPublishedFrames = new AtomicLong();
    private final AtomicLong mDroppedFrames = new AtomicLong();
    private final AtomicLong mDiscardedSymbols = new AtomicLong();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private float[] mProducerBatch = new float[BATCH_SIZE];
    private int mProducerPointer;

    public SelectedChannelSymbolSource(FeedbackDecoder decoder, long generation)
    {
        mDecoder = Objects.requireNonNull(decoder, "Feedback decoder cannot be null");

        if(generation < 0)
        {
            throw new IllegalArgumentException("Symbol source generation cannot be negative");
        }

        mGeneration = generation;
        mDecoder.addSymbolObserver(this);
    }

    @Override
    public void receive(float symbol)
    {
        if(mClosed.get())
        {
            return;
        }

        if(!Float.isFinite(symbol) || symbol < -MAXIMUM_PHASE || symbol > MAXIMUM_PHASE)
        {
            mDiscardedSymbols.incrementAndGet();
            return;
        }

        float[] batch = mProducerBatch;
        batch[mProducerPointer++] = symbol;

        if(mProducerPointer == batch.length)
        {
            mProducerBatch = new float[BATCH_SIZE];
            mProducerPointer = 0;
            SymbolFrame frame = SymbolFrame.owned(0, mGeneration, mSequence.incrementAndGet(), System.nanoTime(),
                batch);
            SymbolFrame replaced = mLatestFrame.getAndSet(frame);

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
    }

    public SymbolFrame poll(Duration timeout) throws InterruptedException
    {
        Objects.requireNonNull(timeout, "Symbol poll timeout cannot be null");

        if(timeout.isNegative())
        {
            throw new IllegalArgumentException("Symbol poll timeout cannot be negative");
        }

        boolean acquired = timeout.isZero() ? mAvailable.tryAcquire() :
            mAvailable.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
        return acquired ? mLatestFrame.getAndSet(null) : null;
    }

    public long getGeneration()
    {
        return mGeneration;
    }

    public long getPublishedFrameCount()
    {
        return mPublishedFrames.get();
    }

    public long getDroppedFrameCount()
    {
        return mDroppedFrames.get();
    }

    public long getDiscardedSymbolCount()
    {
        return mDiscardedSymbols.get();
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

        mDecoder.removeSymbolObserver(this);
        // Do not replace the producer array here.  A callback that passed the closed check immediately before
        // deregistration may still hold or read this field; leaving the bounded array intact keeps that race safe.
        mLatestFrame.set(null);
        mAvailable.drainPermits();
        mAvailable.release();
    }
}
