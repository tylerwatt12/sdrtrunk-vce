/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.usb;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.buffer.INativeBufferFactory;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.util.concurrent.BoundedSpscReferenceQueue;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-controller handoff that keeps native-buffer conversion and listeners off the USB event callback.  The USB
 * producer copies native memory into one of a fixed set of heap buffers, resubmits the native transfer, and then
 * publishes the copy to this worker.  Saturation drops the newest transfer instead of delaying libusb.
 *
 * <p>The generation metadata prevents queued buffers from an earlier streaming session from being broadcast after a
 * restart.  One delivery whose listener snapshot and generation were already accepted when a session stops is allowed
 * to finish; using one worker for the controller prevents it from overlapping another delivery.</p>
 */
final class UsbNativeBufferIngress implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(UsbNativeBufferIngress.class);
    static final int DEFAULT_CAPACITY = 32;

    record Snapshot(int capacity, int depth, int highWaterDepth, long saturationDroppedBuffers,
                    long saturationDroppedSamples, long copyFailures, long conversionFailures,
                    long listenerFailures, long lastQueueDelayNanoseconds, long worstQueueDelayNanoseconds)
    {
    }

    /** Preallocated transfer copy with primitive metadata, so the callback does not allocate a wrapper. */
    static final class TransferSamples
    {
        private final byte[] mSamples;
        private final ByteBuffer mView;
        private long mTimestamp;
        private long mGeneration;
        private long mQueuedAt;
        private int mSampleCount;

        private TransferSamples(int transferBufferSize)
        {
            mSamples = new byte[transferBufferSize];
            mView = ByteBuffer.wrap(mSamples);
        }
    }

    private final Broadcaster<INativeBuffer> mBroadcaster;
    private final BoundedSpscReferenceQueue<TransferSamples> mQueue;
    private final AtomicReferenceArray<TransferSamples> mPool;
    private final int mCapacity;
    private final Thread mWorker;
    private volatile long mSaturationDroppedBuffers;
    private volatile long mSaturationDroppedSamples;
    private volatile long mCopyFailures;
    private volatile long mConversionFailures;
    private volatile long mListenerFailures;
    private long mGenerationSequence;
    private volatile long mActiveGeneration;
    private volatile boolean mStarted;
    private volatile boolean mClosed;
    private volatile INativeBufferFactory mNativeBufferFactory;
    private volatile int mTransferBufferSize;
    private volatile int mSamplesPerBuffer;
    private volatile int mHighWaterDepth;
    private volatile long mLastQueueDelayNanoseconds;
    private volatile long mWorstQueueDelayNanoseconds;
    private int mPoolCursor;

    UsbNativeBufferIngress(String threadName, Broadcaster<INativeBuffer> broadcaster)
    {
        this(threadName, DEFAULT_CAPACITY, broadcaster);
    }

    UsbNativeBufferIngress(String threadName, int capacity, Broadcaster<INativeBuffer> broadcaster)
    {
        mBroadcaster = Objects.requireNonNull(broadcaster, "USB native-buffer ingress broadcaster cannot be null");
        mQueue = new BoundedSpscReferenceQueue<>(capacity);
        mPool = new AtomicReferenceArray<>(capacity);
        mCapacity = capacity;
        String name = threadName == null || threadName.isBlank() ? "sdrtrunk USB native-buffer ingress" : threadName;
        mWorker = new Thread(this::process, name);
        mWorker.setDaemon(true);
        mWorker.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 3));
    }

    /** Starts a new stream generation and returns its token for producer copies. */
    synchronized long start(int transferBufferSize, int samplesPerBuffer, INativeBufferFactory nativeBufferFactory)
    {
        if(mClosed)
        {
            throw new IllegalStateException("Cannot restart a closed USB native-buffer ingress");
        }

        if(transferBufferSize <= 0 || samplesPerBuffer <= 0)
        {
            throw new IllegalArgumentException("USB native-buffer ingress dimensions must be positive");
        }

        Objects.requireNonNull(nativeBufferFactory, "USB native-buffer factory cannot be null");

        if(!mStarted)
        {
            mTransferBufferSize = transferBufferSize;
            mSamplesPerBuffer = samplesPerBuffer;

            for(int index = 0; index < mCapacity; index++)
            {
                mPool.set(index, new TransferSamples(transferBufferSize));
            }

            mNativeBufferFactory = nativeBufferFactory;
            mStarted = true;
            mWorker.start();
        }
        else if(transferBufferSize != mTransferBufferSize || samplesPerBuffer != mSamplesPerBuffer)
        {
            throw new IllegalArgumentException("Cannot change USB native-buffer ingress dimensions after startup");
        }
        else
        {
            mNativeBufferFactory = nativeBufferFactory;
        }

        if(mActiveGeneration == 0)
        {
            mActiveGeneration = ++mGenerationSequence;
        }

        LockSupport.unpark(mWorker);
        return mActiveGeneration;
    }

    /** Stops accepting the active generation without waiting for a delayed listener. */
    synchronized void stop()
    {
        mActiveGeneration = 0;

        if(mStarted)
        {
            LockSupport.unpark(mWorker);
        }
    }

    long activeGeneration()
    {
        return mActiveGeneration;
    }

    /**
     * Copies native transfer memory into a preallocated slot.  This bounded memory copy is the only sample work that
     * remains ahead of libusb resubmission; conversion, DC analysis and listener delivery run on the worker.
     */
    TransferSamples copy(ByteBuffer transferBuffer, int actualLength, long timestamp, long generation)
    {
        int sampleCount = sampleCount(actualLength);

        if(generation <= 0 || generation != mActiveGeneration || mClosed)
        {
            return null;
        }

        TransferSamples transferSamples = acquire();

        if(transferSamples == null)
        {
            recordSaturationDrop(sampleCount);
            return null;
        }

        try
        {
            if(transferBuffer == null || transferBuffer.capacity() != mTransferBufferSize)
            {
                throw new IllegalArgumentException("Unexpected USB transfer buffer size");
            }

            copySamples(transferBuffer, transferSamples.mSamples);
        }
        catch(RuntimeException exception)
        {
            mCopyFailures++;
            release(transferSamples);
            return null;
        }

        transferSamples.mTimestamp = timestamp;
        transferSamples.mGeneration = generation;
        transferSamples.mQueuedAt = System.nanoTime();
        transferSamples.mSampleCount = sampleCount;
        return transferSamples;
    }

    /** Publishes a completed copy after the native transfer has been resubmitted. */
    boolean offer(TransferSamples transferSamples)
    {
        if(transferSamples == null)
        {
            return false;
        }

        long generation = transferSamples.mGeneration;

        if(generation <= 0 || generation != mActiveGeneration || mClosed)
        {
            release(transferSamples);
            return false;
        }

        if(!mQueue.offer(transferSamples))
        {
            //The pool and queue have the same capacity, so this is only possible if their ownership invariant breaks.
            recordSaturationDrop(transferSamples.mSampleCount);
            release(transferSamples);
            return false;
        }

        int depth = Math.max(1, mQueue.size());

        if(depth > mHighWaterDepth)
        {
            mHighWaterDepth = depth;
        }

        LockSupport.unpark(mWorker);
        return true;
    }

    /** Testable boundary for the one unavoidable native-to-heap transfer copy. */
    void copySamples(ByteBuffer source, byte[] destination)
    {
        source.get(destination);
    }

    Snapshot snapshot()
    {
        return new Snapshot(mCapacity, mQueue.size(), mHighWaterDepth, mSaturationDroppedBuffers,
            mSaturationDroppedSamples, mCopyFailures, mConversionFailures, mListenerFailures,
            mLastQueueDelayNanoseconds, mWorstQueueDelayNanoseconds);
    }

    private void process()
    {
        while(true)
        {
            TransferSamples transferSamples = mQueue.poll();

            if(transferSamples == null)
            {
                if(mClosed)
                {
                    return;
                }

                LockSupport.park(this);
                continue;
            }

            long startedAt = System.nanoTime();
            long generation = transferSamples.mGeneration;
            long queueDelay = Math.max(0, startedAt - transferSamples.mQueuedAt);
            mLastQueueDelayNanoseconds = queueDelay;

            if(queueDelay > mWorstQueueDelayNanoseconds)
            {
                mWorstQueueDelayNanoseconds = queueDelay;
            }

            try
            {
                if(mClosed || generation <= 0 || generation != mActiveGeneration)
                {
                    continue;
                }

                INativeBuffer nativeBuffer;

                try
                {
                    transferSamples.mView.clear();
                    nativeBuffer = mNativeBufferFactory.getBuffer(transferSamples.mView,
                        transferSamples.mTimestamp);
                }
                catch(Throwable throwable)
                {
                    rethrowFatal(throwable);
                    mConversionFailures++;
                    LOGGER.error("Error while converting copied USB tuner samples", throwable);
                    continue;
                }
                finally
                {
                    release(transferSamples);
                    transferSamples = null;
                }

                try
                {
                    int listenerFailures = mBroadcaster.broadcastIf(nativeBuffer,
                        () -> !mClosed && generation > 0 && generation == mActiveGeneration);

                    if(listenerFailures < 0)
                    {
                        continue;
                    }

                    mListenerFailures += listenerFailures;
                }
                catch(Throwable throwable)
                {
                    rethrowFatal(throwable);
                    mListenerFailures++;
                    LOGGER.error("Error while delivering USB tuner samples to receiver listeners", throwable);
                }
            }
            finally
            {
                if(transferSamples != null)
                {
                    release(transferSamples);
                }
            }
        }
    }

    private TransferSamples acquire()
    {
        for(int count = 0; count < mCapacity; count++)
        {
            int index = (mPoolCursor + count) % mCapacity;
            TransferSamples candidate = mPool.get(index);

            if(candidate != null && mPool.compareAndSet(index, candidate, null))
            {
                mPoolCursor = (index + 1) % mCapacity;
                return candidate;
            }
        }

        return null;
    }

    private void release(TransferSamples transferSamples)
    {
        for(int index = 0; index < mCapacity; index++)
        {
            if(mPool.compareAndSet(index, null, transferSamples))
            {
                return;
            }
        }

        throw new IllegalStateException("USB native-buffer ingress pool overflow");
    }

    private int sampleCount(int actualLength)
    {
        if(mTransferBufferSize <= 0 || mSamplesPerBuffer <= 0)
        {
            return 0;
        }

        return Math.max(0, Math.min(mSamplesPerBuffer,
            (int)Math.round(Math.max(0, actualLength) * (double)mSamplesPerBuffer / mTransferBufferSize)));
    }

    private void recordSaturationDrop(int sampleCount)
    {
        mHighWaterDepth = mCapacity;
        mSaturationDroppedBuffers++;
        mSaturationDroppedSamples += sampleCount;
    }

    private static void rethrowFatal(Throwable throwable)
    {
        if(throwable instanceof Error error && !(error instanceof AssertionError))
        {
            throw error;
        }
    }

    @Override
    public synchronized void close()
    {
        if(!mClosed)
        {
            mActiveGeneration = 0;
            mClosed = true;

            if(mStarted)
            {
                LockSupport.unpark(mWorker);
            }
        }
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException
    {
        Objects.requireNonNull(unit, "USB ingress termination time unit cannot be null");

        if(!mStarted)
        {
            return true;
        }

        long timeoutMilliseconds = Math.max(0, unit.toMillis(timeout));

        if(timeoutMilliseconds == 0)
        {
            return !mWorker.isAlive();
        }

        mWorker.join(timeoutMilliseconds);
        return !mWorker.isAlive();
    }
}
