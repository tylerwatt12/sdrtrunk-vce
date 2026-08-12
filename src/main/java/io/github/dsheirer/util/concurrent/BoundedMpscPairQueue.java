/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.util.concurrent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Preallocated bounded multi-producer/single-consumer queue for a pair of references.
 *
 * <p>The producer path is allocation-free, lock-free, uses a fixed maximum number of attempts, and rejects new
 * observations when full or highly contended.  The immutable
 * {@link Entry} returned by {@link #poll()} is allocated only on the observer worker.</p>
 */
public final class BoundedMpscPairQueue<A,B>
{
    private static final int MAXIMUM_OFFER_ATTEMPTS = 4;
    private final Cell<A,B>[] mCells;
    private final int mMask;
    private final AtomicLong mProducerSequence = new AtomicLong();
    private final AtomicLong mConsumerSequence = new AtomicLong();

    @SuppressWarnings("unchecked")
    public BoundedMpscPairQueue(int capacity)
    {
        if(capacity < 2 || Integer.bitCount(capacity) != 1)
        {
            throw new IllegalArgumentException("capacity must be a power of two greater than one");
        }

        mCells = (Cell<A,B>[])new Cell<?,?>[capacity];
        mMask = capacity - 1;

        for(int x = 0; x < capacity; x++)
        {
            mCells[x] = new Cell<>(x);
        }
    }

    /**
     * Offers a pair without allocating or waiting.
     *
     * @return true when published, or false when the bounded queue is full
     */
    public boolean offer(A first, B second)
    {
        Objects.requireNonNull(first, "first cannot be null");
        Objects.requireNonNull(second, "second cannot be null");
        long sequence = mProducerSequence.get();

        for(int attempt = 0; attempt < MAXIMUM_OFFER_ATTEMPTS; attempt++)
        {
            Cell<A,B> cell = mCells[(int)sequence & mMask];
            long available = cell.mSequence.get();
            long difference = available - sequence;

            if(difference == 0)
            {
                if(mProducerSequence.compareAndSet(sequence, sequence + 1))
                {
                    cell.mFirst = first;
                    cell.mSecond = second;
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
     * Polls the next pair from the single consumer thread.
     */
    public Entry<A,B> poll()
    {
        long sequence = mConsumerSequence.get();
        Cell<A,B> cell = mCells[(int)sequence & mMask];
        long available = cell.mSequence.get();

        if(available - (sequence + 1) != 0)
        {
            return null;
        }

        A first = cell.mFirst;
        B second = cell.mSecond;
        cell.mFirst = null;
        cell.mSecond = null;
        cell.mSequence.lazySet(sequence + mCells.length);
        mConsumerSequence.lazySet(sequence + 1);
        return new Entry<>(first, second);
    }

    public int size()
    {
        long size = mProducerSequence.get() - mConsumerSequence.get();
        return (int)Math.max(0, Math.min(mCells.length, size));
    }

    public int capacity()
    {
        return mCells.length;
    }

    public void clear()
    {
        while(poll() != null)
        {
            // Drain.
        }
    }

    public record Entry<A,B>(A first, B second)
    {
    }

    private static final class Cell<A,B>
    {
        private final AtomicLong mSequence;
        private volatile A mFirst;
        private volatile B mSecond;

        private Cell(long sequence)
        {
            mSequence = new AtomicLong(sequence);
        }
    }
}
