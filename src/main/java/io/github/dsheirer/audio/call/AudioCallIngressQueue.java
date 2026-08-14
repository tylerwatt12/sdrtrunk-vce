/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Preallocated bounded multi-producer/single-consumer queue for audio-call coordinator commands.
 *
 * <p>The decoder-side offer path allocates nothing, never locks or waits, and makes a fixed number of attempts.
 * Ordinary frame and metadata commands cannot consume the slots reserved for creation, completion, abort, and
 * watchdog commands.</p>
 */
final class AudioCallIngressQueue
{
    private static final int MAXIMUM_OFFER_ATTEMPTS = 4;
    private final Cell[] mCells;
    private final int mMask;
    private final int mRegularLimit;
    private final AtomicLong mProducerSequence = new AtomicLong();
    private final AtomicLong mRegularCount = new AtomicLong();
    private long mConsumerSequence;

    AudioCallIngressQueue(int capacity, int lifecycleReserve)
    {
        if(capacity < 2 || Integer.bitCount(capacity) != 1)
        {
            throw new IllegalArgumentException("capacity must be a power of two greater than one");
        }

        if(lifecycleReserve < 1 || lifecycleReserve >= capacity)
        {
            throw new IllegalArgumentException("lifecycle reserve must be between one and capacity");
        }

        mCells = new Cell[capacity];
        mMask = capacity - 1;
        mRegularLimit = capacity - lifecycleReserve;

        for(int x = 0; x < capacity; x++)
        {
            mCells[x] = new Cell(x);
        }
    }

    boolean offer(int operation, boolean lifecycle, Object payload, long value, long generation)
    {
        if(!lifecycle && !reserveRegularSlot())
        {
            return false;
        }

        long sequence = mProducerSequence.get();

        for(int attempt = 0; attempt < MAXIMUM_OFFER_ATTEMPTS; attempt++)
        {
            Cell cell = mCells[(int)sequence & mMask];
            long difference = cell.mSequence.get() - sequence;

            if(difference == 0)
            {
                if(mProducerSequence.compareAndSet(sequence, sequence + 1))
                {
                    cell.mOperation = operation;
                    cell.mLifecycle = lifecycle;
                    cell.mPayload = payload;
                    cell.mValue = value;
                    cell.mGeneration = generation;
                    cell.mSequence.lazySet(sequence + 1);
                    return true;
                }
            }
            else if(difference < 0)
            {
                releaseRegularSlot(lifecycle);
                return false;
            }

            sequence = mProducerSequence.get();
        }

        releaseRegularSlot(lifecycle);
        return false;
    }

    private boolean reserveRegularSlot()
    {
        long count = mRegularCount.get();

        for(int attempt = 0; attempt < MAXIMUM_OFFER_ATTEMPTS; attempt++)
        {
            if(count >= mRegularLimit)
            {
                return false;
            }

            if(mRegularCount.compareAndSet(count, count + 1))
            {
                return true;
            }

            count = mRegularCount.get();
        }

        return false;
    }

    private void releaseRegularSlot(boolean lifecycle)
    {
        if(!lifecycle)
        {
            mRegularCount.decrementAndGet();
        }
    }

    Entry poll()
    {
        long sequence = mConsumerSequence;
        Cell cell = mCells[(int)sequence & mMask];

        if(cell.mSequence.get() - (sequence + 1) != 0)
        {
            return null;
        }

        Entry entry = new Entry(cell.mOperation, cell.mLifecycle, cell.mPayload, cell.mValue,
            cell.mGeneration);
        cell.mPayload = null;

        if(!cell.mLifecycle)
        {
            mRegularCount.decrementAndGet();
        }

        cell.mSequence.lazySet(sequence + mCells.length);
        mConsumerSequence = sequence + 1;
        return entry;
    }

    int capacity()
    {
        return mCells.length;
    }

    int regularCapacity()
    {
        return mRegularLimit;
    }

    int size()
    {
        long size = mProducerSequence.get() - mConsumerSequence;
        return (int)Math.max(0, Math.min(mCells.length, size));
    }

    void clear()
    {
        while(poll() != null)
        {
            //Drain only from the single owner thread.
        }
    }

    record Entry(int operation, boolean lifecycle, Object payload, long value, long generation)
    {
    }

    private static final class Cell
    {
        private final AtomicLong mSequence;
        private int mOperation;
        private boolean mLifecycle;
        private Object mPayload;
        private long mValue;
        private long mGeneration;

        private Cell(long sequence)
        {
            mSequence = new AtomicLong(sequence);
        }
    }
}
