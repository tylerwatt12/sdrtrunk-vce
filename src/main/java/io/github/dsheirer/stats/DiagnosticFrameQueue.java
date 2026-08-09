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

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * One independently replaceable latest frame per dense diagnostic type.  Slow viewers drop obsolete frames and can
 * never apply backpressure to receiver or FFT work.
 */
final class DiagnosticFrameQueue implements AutoCloseable
{
    private static final int SLOT_COUNT = DiagnosticStreamFrame.TYPE_TUNER_FFT + 1;
    private final AtomicReferenceArray<DiagnosticStreamFrame> mFrames = new AtomicReferenceArray<>(SLOT_COUNT);
    private final Semaphore mAvailable = new Semaphore(0);
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private int mNextType = DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL;

    void offer(DiagnosticStreamFrame frame)
    {
        Objects.requireNonNull(frame, "Diagnostic frame cannot be null");
        int type = frame.type();

        if(type < DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL || type > DiagnosticStreamFrame.TYPE_TUNER_FFT)
        {
            throw new IllegalArgumentException("Diagnostic frame is not a queued data type");
        }

        if(mClosed.get())
        {
            return;
        }

        DiagnosticStreamFrame replaced = mFrames.getAndSet(type, frame);

        if(mClosed.get())
        {
            mFrames.compareAndSet(type, frame, null);
        }
        else if(replaced == null)
        {
            mAvailable.release();
        }
    }

    DiagnosticStreamFrame poll(Duration timeout) throws InterruptedException
    {
        Objects.requireNonNull(timeout, "Diagnostic poll timeout cannot be null");

        if(timeout.isNegative())
        {
            throw new IllegalArgumentException("Diagnostic poll timeout cannot be negative");
        }

        long timeoutNanos = timeout.toNanos();
        long started = System.nanoTime();

        while(true)
        {
            long remaining = timeoutNanos == 0 ? 0 : timeoutNanos - (System.nanoTime() - started);

            if(timeoutNanos != 0 && remaining <= 0)
            {
                return null;
            }

            boolean acquired = timeoutNanos == 0 ? mAvailable.tryAcquire() :
                mAvailable.tryAcquire(remaining, TimeUnit.NANOSECONDS);

            if(!acquired)
            {
                return null;
            }

            for(int count = 0; count < SLOT_COUNT; count++)
            {
                int type = mNextType++;

                if(mNextType >= SLOT_COUNT)
                {
                    mNextType = DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL;
                }

                if(type < DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL)
                {
                    continue;
                }

                DiagnosticStreamFrame frame = mFrames.getAndSet(type, null);

                if(frame != null)
                {
                    return frame;
                }
            }

            if(mClosed.get())
            {
                return null;
            }
        }
    }

    private void clear()
    {
        for(int type = DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL; type < SLOT_COUNT; type++)
        {
            mFrames.set(type, null);
        }

        mAvailable.drainPermits();
    }

    @Override
    public void close()
    {
        if(mClosed.compareAndSet(false, true))
        {
            clear();
            mAvailable.release();
        }
    }
}
