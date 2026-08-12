/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.util.concurrent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Preallocated bounded multi-producer/single-consumer reference queue.
 *
 * <p>The producer path is allocation-free, lock-free, and uses a fixed maximum number of attempts.  A full or highly
 * contended queue rejects the new value so that
 * receiver and decoder callbacks can discard observer data instead of waiting for a diagnostic, statistics, or UI
 * consumer.  The implementation uses per-slot sequence numbers so a consumer never observes a producer reservation
 * before the corresponding value has been published.</p>
 *
 * <p>Capacity must be a power of two.  There is one logical consumer; {@link #poll()} must not be called concurrently
 * by multiple threads.</p>
 */
public final class BoundedMpscReferenceQueue<T>
{
    private static final int MAXIMUM_OFFER_ATTEMPTS = 4;
    private final Cell<T>[] mCells;
    private final int mMask;
    private final AtomicLong mProducerSequence = new AtomicLong();
    private final AtomicLong mConsumerSequence = new AtomicLong();

    @SuppressWarnings("unchecked")
    public BoundedMpscReferenceQueue(int capacity)
    {
        if(capacity < 2 || Integer.bitCount(capacity) != 1)
        {
            throw new IllegalArgumentException("capacity must be a power of two greater than one");
        }

        mCells = (Cell<T>[])new Cell<?>[capacity];
        mMask = capacity - 1;

        for(int x = 0; x < capacity; x++)
        {
            mCells[x] = new Cell<>(x);
        }
    }

    /**
     * Offers a value without allocating or waiting.
     *
     * @return true when published, or false when the bounded queue is full
     */
    public boolean offer(T value)
    {
        Objects.requireNonNull(value, "value cannot be null");
        long sequence = mProducerSequence.get();

        for(int attempt = 0; attempt < MAXIMUM_OFFER_ATTEMPTS; attempt++)
        {
            Cell<T> cell = mCells[(int)sequence & mMask];
            long available = cell.mSequence.get();
            long difference = available - sequence;

            if(difference == 0)
            {
                if(mProducerSequence.compareAndSet(sequence, sequence + 1))
                {
                    cell.mValue = value;
                    cell.mSequence.lazySet(sequence + 1);
                    return true;
                }
            }
            else if(difference < 0)
            {
                return false;
            }

            sequence = mProducerSequence.get();
        }

        return false;
    }

    /**
     * Polls the next value from the single consumer thread.
     */
    public T poll()
    {
        long sequence = mConsumerSequence.get();
        Cell<T> cell = mCells[(int)sequence & mMask];
        long available = cell.mSequence.get();

        if(available - (sequence + 1) != 0)
        {
            return null;
        }

        T value = cell.mValue;
        cell.mValue = null;
        cell.mSequence.lazySet(sequence + mCells.length);
        mConsumerSequence.lazySet(sequence + 1);
        return value;
    }

    /**
     * Approximate size for status and tests.  It is not a synchronization primitive.
     */
    public int size()
    {
        long size = mProducerSequence.get() - mConsumerSequence.get();
        return (int)Math.max(0, Math.min(mCells.length, size));
    }

    public int capacity()
    {
        return mCells.length;
    }

    /**
     * Clears values from the single consumer thread.
     */
    public void clear()
    {
        while(poll() != null)
        {
            // Drain.
        }
    }

    private static final class Cell<T>
    {
        private final AtomicLong mSequence;
        private volatile T mValue;

        private Cell(long sequence)
        {
            mSequence = new AtomicLong(sequence);
        }
    }
}
