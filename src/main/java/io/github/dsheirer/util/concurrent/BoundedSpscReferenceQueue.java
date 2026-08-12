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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-capacity single-producer/single-consumer reference queue.
 *
 * <p>The backing arrays are allocated once.  {@link #offer(Object)} and {@link #poll()} never acquire a lock,
 * wait, resize, or allocate.  A producer must drop or coalesce an item when {@code offer} returns false.  This is
 * intended for receiver observation points where delaying the producer is less acceptable than losing diagnostic
 * data.</p>
 *
 * <p>Two optional long values can travel with each reference without allocating a wrapper.  The consumer reads them
 * with {@link #lastPolledMetadata()} and {@link #lastPolledSecondaryMetadata()} immediately after a successful
 * poll.</p>
 */
public final class BoundedSpscReferenceQueue<T>
{
    private final Object[] mValues;
    private final long[] mMetadata;
    private final long[] mSecondaryMetadata;
    private final int mMask;
    private final AtomicLong mReadSequence = new AtomicLong();
    private final AtomicLong mWriteSequence = new AtomicLong();
    private long mLastPolledMetadata;
    private long mLastPolledSecondaryMetadata;

    /**
     * Creates a queue whose capacity must be a power of two.
     */
    public BoundedSpscReferenceQueue(int capacity)
    {
        if(capacity < 2 || Integer.bitCount(capacity) != 1)
        {
            throw new IllegalArgumentException("SPSC queue capacity must be a power of two and at least two");
        }

        mValues = new Object[capacity];
        mMetadata = new long[capacity];
        mSecondaryMetadata = new long[capacity];
        mMask = capacity - 1;
    }

    public boolean offer(T value)
    {
        return offer(value, 0);
    }

    /**
     * Offers a reference and primitive metadata without allocating a tuple.
     */
    public boolean offer(T value, long metadata)
    {
        return offer(value, metadata, 0);
    }

    /**
     * Offers a reference and two primitive metadata values without allocating a tuple.
     */
    public boolean offer(T value, long metadata, long secondaryMetadata)
    {
        Objects.requireNonNull(value, "SPSC queue value cannot be null");
        long write = mWriteSequence.getPlain();

        if(write - mReadSequence.getAcquire() >= mValues.length)
        {
            return false;
        }

        int index = (int)write & mMask;
        mValues[index] = value;
        mMetadata[index] = metadata;
        mSecondaryMetadata[index] = secondaryMetadata;
        mWriteSequence.setRelease(write + 1);
        return true;
    }

    @SuppressWarnings("unchecked")
    public T poll()
    {
        long read = mReadSequence.getPlain();

        if(read >= mWriteSequence.getAcquire())
        {
            return null;
        }

        int index = (int)read & mMask;
        T value = (T)mValues[index];
        mLastPolledMetadata = mMetadata[index];
        mLastPolledSecondaryMetadata = mSecondaryMetadata[index];
        mValues[index] = null;
        mMetadata[index] = 0;
        mSecondaryMetadata[index] = 0;
        mReadSequence.setRelease(read + 1);
        return value;
    }

    /**
     * Metadata associated with the most recent successful {@link #poll()} on the consumer thread.
     */
    public long lastPolledMetadata()
    {
        return mLastPolledMetadata;
    }

    /**
     * Secondary metadata associated with the most recent successful {@link #poll()} on the consumer thread.
     */
    public long lastPolledSecondaryMetadata()
    {
        return mLastPolledSecondaryMetadata;
    }

    public int size()
    {
        long size = mWriteSequence.getAcquire() - mReadSequence.getAcquire();
        return (int)Math.max(0, Math.min(mValues.length, size));
    }

    /**
     * Consumer-side discard of every queued reference.
     */
    public void clear()
    {
        while(poll() != null)
        {
            //Discard bounded observer data.
        }

        mLastPolledMetadata = 0;
        mLastPolledSecondaryMetadata = 0;
    }
}
