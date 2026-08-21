/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.controller.channel;

import io.github.dsheirer.module.decode.dmr.DMRRestChannelHandoffRequest;
import io.github.dsheirer.util.concurrent.BoundedMpscReferenceQueue;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded, nonblocking ingress for DMR Capacity Plus rest-channel handoffs.
 *
 * <p>Decoder callbacks only offer immutable handoff requests.  One prestarted worker owns the potentially expensive
 * lifecycle work so a full or contended ingress never falls back to running that work on the producer thread.</p>
 */
final class DMRRestChannelHandoffCoordinator implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(DMRRestChannelHandoffCoordinator.class);
    private static final int DEFAULT_CAPACITY = 64;
    private static final long IDLE_PARK_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long CLOSE_JOIN_MILLISECONDS = TimeUnit.SECONDS.toMillis(2);
    private final BoundedMpscReferenceQueue<DMRRestChannelHandoffRequest> mIngress;
    private final Handler mHandler;
    private final Thread mWorker;
    private final AtomicInteger mActiveOffers = new AtomicInteger();
    private final AtomicLong mDroppedCount = new AtomicLong();
    private volatile boolean mAccepting = true;
    private volatile boolean mStopImmediately;
    private volatile boolean mClosed;

    DMRRestChannelHandoffCoordinator(Handler handler)
    {
        this(DEFAULT_CAPACITY, new ObserverThreadFactory("sdrtrunk DMR rest-channel handoff"), handler);
    }

    /**
     * Test seam for a smaller ingress and an observable worker thread.
     */
    DMRRestChannelHandoffCoordinator(int capacity, ThreadFactory threadFactory, Handler handler)
    {
        mIngress = new BoundedMpscReferenceQueue<>(capacity);
        mHandler = Objects.requireNonNull(handler, "handler cannot be null");
        Thread worker = Objects.requireNonNull(threadFactory, "thread factory cannot be null")
            .newThread(this::runWorker);
        mWorker = Objects.requireNonNull(worker, "thread factory returned a null thread");

        if(mWorker.getState() != Thread.State.NEW)
        {
            throw new IllegalArgumentException("thread factory must return an unstarted thread");
        }

        //Keep this functional lifecycle worker isolated from decoder callbacks and unable to hold application exit.
        mWorker.setDaemon(true);
        mWorker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        mWorker.start();
    }

    /**
     * Offers a handoff without waiting.  A full, contended, or closed coordinator rejects and counts the request.
     */
    boolean offer(DMRRestChannelHandoffRequest request)
    {
        Objects.requireNonNull(request, "request cannot be null");

        if(!mAccepting)
        {
            mDroppedCount.incrementAndGet();
            return false;
        }

        mActiveOffers.incrementAndGet();

        try
        {
            //Close the race where close begins after the initial fast-path check but before this producer registers.
            if(!mAccepting)
            {
                mDroppedCount.incrementAndGet();
                return false;
            }

            if(mIngress.offer(request))
            {
                signalWorker();
                return true;
            }

            mDroppedCount.incrementAndGet();
            return false;
        }
        finally
        {
            mActiveOffers.decrementAndGet();

            if(!mAccepting)
            {
                signalWorker();
            }
        }
    }

    long getDroppedCount()
    {
        return mDroppedCount.get();
    }

    boolean isAccepting()
    {
        return mAccepting;
    }

    boolean isClosed()
    {
        return mClosed;
    }

    @Override
    public synchronized void close()
    {
        if(mClosed)
        {
            return;
        }

        mAccepting = false;
        signalWorker();

        if(Thread.currentThread() == mWorker)
        {
            return;
        }

        joinWorker(CLOSE_JOIN_MILLISECONDS);

        if(mWorker.isAlive())
        {
            mStopImmediately = true;
            mWorker.interrupt();
            signalWorker();
            joinWorker(CLOSE_JOIN_MILLISECONDS);

            if(mWorker.isAlive())
            {
                mLog.warn("DMR rest-channel handoff worker did not stop after close");
            }
        }
    }

    private void joinWorker(long timeoutMilliseconds)
    {
        try
        {
            mWorker.join(timeoutMilliseconds);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void signalWorker()
    {
        LockSupport.unpark(mWorker);
    }

    private void runWorker()
    {
        try
        {
            while(!mStopImmediately && (mAccepting || mActiveOffers.get() > 0 || mIngress.size() > 0))
            {
                int drained = 0;
                DMRRestChannelHandoffRequest request;

                while(!mStopImmediately && (request = mIngress.poll()) != null)
                {
                    try
                    {
                        mHandler.handle(request);
                    }
                    catch(Exception exception)
                    {
                        mLog.error("Error processing DMR rest-channel handoff", exception);
                    }

                    drained++;
                }

                if(drained == 0 && !mStopImmediately)
                {
                    LockSupport.parkNanos(this, IDLE_PARK_NANOSECONDS);
                }
            }
        }
        finally
        {
            mAccepting = false;
            mIngress.clear();
            mClosed = true;
        }
    }

    @FunctionalInterface
    interface Handler
    {
        void handle(DMRRestChannelHandoffRequest request) throws Exception;
    }
}
