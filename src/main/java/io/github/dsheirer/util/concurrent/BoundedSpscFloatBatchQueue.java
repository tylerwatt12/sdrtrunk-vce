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

package io.github.dsheirer.util.concurrent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Preallocated SPSC queue that batches primitive float observations without allocating on the producer thread.
 * The consumer must call {@link #release()} after it finishes reading each non-null batch returned by
 * {@link #poll()}.
 */
public final class BoundedSpscFloatBatchQueue
{
    private final float[][] mBatches;
    private final int mMask;
    private final int mBatchSize;
    private final AtomicLong mReadSequence = new AtomicLong();
    private final AtomicLong mWriteSequence = new AtomicLong();
    private int mWritePointer;
    private boolean mConsumerHoldingBatch;

    public BoundedSpscFloatBatchQueue(int batchSize, int batchCapacity)
    {
        if(batchSize < 1)
        {
            throw new IllegalArgumentException("Float batch size must be positive");
        }

        if(batchCapacity < 2 || Integer.bitCount(batchCapacity) != 1)
        {
            throw new IllegalArgumentException("Float batch capacity must be a power of two and at least two");
        }

        mBatchSize = batchSize;
        mBatches = new float[batchCapacity][batchSize];
        mMask = batchCapacity - 1;
    }

    /**
     * Adds one value, returning false immediately when every preallocated batch is awaiting the consumer.
     */
    public boolean offer(float value)
    {
        long write = mWriteSequence.getPlain();

        if(write - mReadSequence.getAcquire() >= mBatches.length)
        {
            return false;
        }

        float[] batch = mBatches[(int)write & mMask];
        batch[mWritePointer++] = value;

        if(mWritePointer == mBatchSize)
        {
            mWritePointer = 0;
            mWriteSequence.setRelease(write + 1);
        }

        return true;
    }

    /**
     * Gets the next complete batch.  The returned preallocated array remains consumer-owned until {@link #release()}.
     */
    public float[] poll()
    {
        if(mConsumerHoldingBatch)
        {
            throw new IllegalStateException("Release the current float batch before polling another");
        }

        long read = mReadSequence.getPlain();

        if(read >= mWriteSequence.getAcquire())
        {
            return null;
        }

        mConsumerHoldingBatch = true;
        return mBatches[(int)read & mMask];
    }

    public void release()
    {
        if(!mConsumerHoldingBatch)
        {
            throw new IllegalStateException("No float batch is currently held by the consumer");
        }

        mConsumerHoldingBatch = false;
        mReadSequence.setRelease(mReadSequence.getPlain() + 1);
    }

    public int completeBatchCount()
    {
        long size = mWriteSequence.getAcquire() - mReadSequence.getAcquire();
        return (int)Math.max(0, Math.min(mBatches.length, size));
    }
}
