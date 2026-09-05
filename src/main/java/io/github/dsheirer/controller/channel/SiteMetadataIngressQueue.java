/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.controller.channel;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Preallocated bounded multi-producer/single-consumer queue for site-metadata observer handoff.
 * The offer path never waits, allocates a task, or falls back to running observer work on the producer.
 */
final class SiteMetadataIngressQueue
{
    private static final int MAXIMUM_OFFER_ATTEMPTS = 4;
    private final Cell[] mCells;
    private final int mMask;
    private final AtomicLong mProducerSequence = new AtomicLong();
    private volatile long mConsumerSequence;

    SiteMetadataIngressQueue(int capacity)
    {
        if(capacity < 2 || Integer.bitCount(capacity) != 1)
        {
            throw new IllegalArgumentException("capacity must be a power of two greater than one");
        }

        mCells = new Cell[capacity];
        mMask = capacity - 1;

        for(int x = 0; x < capacity; x++)
        {
            mCells[x] = new Cell(x);
        }
    }

    boolean offer(int type, Object event)
    {
        long sequence = mProducerSequence.get();

        for(int attempt = 0; attempt < MAXIMUM_OFFER_ATTEMPTS; attempt++)
        {
            Cell cell = mCells[(int)sequence & mMask];
            long difference = cell.mSequence.get() - sequence;

            if(difference == 0)
            {
                if(mProducerSequence.compareAndSet(sequence, sequence + 1))
                {
                    cell.mType = type;
                    cell.mEvent = event;
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

    Entry poll()
    {
        long sequence = mConsumerSequence;
        Cell cell = mCells[(int)sequence & mMask];

        if(cell.mSequence.get() - (sequence + 1) != 0)
        {
            return null;
        }

        Entry entry = new Entry(cell.mType, cell.mEvent);
        cell.mEvent = null;
        cell.mSequence.lazySet(sequence + mCells.length);
        mConsumerSequence = sequence + 1;
        return entry;
    }

    int size()
    {
        long size = mProducerSequence.get() - mConsumerSequence;
        return (int)Math.max(0, Math.min(mCells.length, size));
    }

    int capacity()
    {
        return mCells.length;
    }

    record Entry(int type, Object event)
    {
    }

    private static final class Cell
    {
        private final AtomicLong mSequence;
        private int mType;
        private Object mEvent;

        private Cell(long sequence)
        {
            mSequence = new AtomicLong(sequence);
        }
    }
}
