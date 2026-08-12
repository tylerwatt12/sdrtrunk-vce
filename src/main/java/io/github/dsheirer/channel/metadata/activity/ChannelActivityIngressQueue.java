/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Preallocated bounded multi-producer/single-consumer command queue for channel activity observations.
 *
 * <p>The receiver-side offer path allocates nothing, never locks or waits, and makes a fixed number of attempts.
 * Payload references are projected only after the dedicated activity worker consumes them.</p>
 */
final class ChannelActivityIngressQueue
{
    private static final int MAXIMUM_OFFER_ATTEMPTS = 4;
    private final Cell[] mCells;
    private final int mMask;
    private final int mRegularLimit;
    private final AtomicLong mProducerSequence = new AtomicLong();
    private final AtomicLong mRegularCount = new AtomicLong();
    private long mConsumerSequence;

    ChannelActivityIngressQueue(int capacity, int lifecycleReserve)
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

    boolean offer(int operation, boolean lifecycle, Object first, Object second, Object third,
                  Object fourth, Object fifth, Object sixth, long value)
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
                    cell.mFirst = first;
                    cell.mSecond = second;
                    cell.mThird = third;
                    cell.mFourth = fourth;
                    cell.mFifth = fifth;
                    cell.mSixth = sixth;
                    cell.mValue = value;
                    cell.mSequence.lazySet(sequence + 1);
                    return true;
                }
            }
            else if(difference < 0)
            {
                if(!lifecycle)
                {
                    mRegularCount.decrementAndGet();
                }

                return false;
            }

            sequence = mProducerSequence.get();
        }

        if(!lifecycle)
        {
            mRegularCount.decrementAndGet();
        }

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

    Entry poll()
    {
        long sequence = mConsumerSequence;
        Cell cell = mCells[(int)sequence & mMask];

        if(cell.mSequence.get() - (sequence + 1) != 0)
        {
            return null;
        }

        Entry entry = new Entry(cell.mOperation, cell.mLifecycle, cell.mFirst, cell.mSecond, cell.mThird,
            cell.mFourth, cell.mFifth, cell.mSixth, cell.mValue);
        cell.mFirst = null;
        cell.mSecond = null;
        cell.mThird = null;
        cell.mFourth = null;
        cell.mFifth = null;
        cell.mSixth = null;

        if(!cell.mLifecycle)
        {
            mRegularCount.decrementAndGet();
        }

        cell.mSequence.lazySet(sequence + mCells.length);
        mConsumerSequence = sequence + 1;
        return entry;
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
            // Drain from the single consumer thread.
        }
    }

    record Entry(int operation, boolean lifecycle, Object first, Object second, Object third, Object fourth,
                 Object fifth, Object sixth, long value)
    {
    }

    private static final class Cell
    {
        private final AtomicLong mSequence;
        private int mOperation;
        private boolean mLifecycle;
        private Object mFirst;
        private Object mSecond;
        private Object mThird;
        private Object mFourth;
        private Object mFifth;
        private Object mSixth;
        private long mValue;

        private Cell(long sequence)
        {
            mSequence = new AtomicLong(sequence);
        }
    }
}
