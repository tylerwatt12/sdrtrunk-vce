/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.controller.channel;

import io.github.dsheirer.module.decode.dmr.DMRRestChannelHandoffRequest;
import io.github.dsheirer.module.decode.dmr.DMRTrafficChannelManager;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nonblocking, latest-value ingress for DMR Capacity Plus rest-channel handoffs.
 *
 * <p>Each DMR traffic manager owns one authoritative pending handoff and one ingress-dirty bit. Decoder callbacks only
 * mark that fixed-size mailbox and unpark this prestarted worker. The worker obtains a lifecycle-owned snapshot of live
 * managers and drains their current values. Thus, saturation coalesces repeated or superseded targets per site without
 * allowing one site's one-shot target to overwrite another site's target.</p>
 */
final class DMRRestChannelHandoffCoordinator implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(DMRRestChannelHandoffCoordinator.class);
    private static final long IDLE_PARK_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long CLOSE_JOIN_MILLISECONDS = TimeUnit.SECONDS.toMillis(2);
    private final OwnerSupplier mOwnerSupplier;
    private final Handler mHandler;
    private final Thread mWorker;
    private final AtomicBoolean mWorkPending = new AtomicBoolean();
    private final AtomicInteger mActiveOffers = new AtomicInteger();
    private final AtomicLong mCoalescedCount = new AtomicLong();
    private final AtomicLong mDroppedCount = new AtomicLong();
    private volatile boolean mAccepting = true;
    private volatile boolean mStopImmediately;
    private volatile boolean mClosed;

    DMRRestChannelHandoffCoordinator(OwnerSupplier ownerSupplier, Handler handler)
    {
        this(new ObserverThreadFactory("sdrtrunk DMR rest-channel handoff"), ownerSupplier, handler);
    }

    /**
     * Test seam for an observable worker thread.
     */
    DMRRestChannelHandoffCoordinator(ThreadFactory threadFactory, OwnerSupplier ownerSupplier, Handler handler)
    {
        mOwnerSupplier = Objects.requireNonNull(ownerSupplier, "owner supplier cannot be null");
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
     * Signals a decoder/EventBus handoff without waiting. Repeated signals coalesce against the owner's latest
     * generation. A false result means only that the coordinator is closing or closed.
     */
    boolean offer(DMRRestChannelHandoffRequest request)
    {
        return signal(request, false);
    }

    /**
     * Signals an intentional lifecycle retry of a generation that the worker has already delivered once.
     */
    boolean offerRetry(DMRRestChannelHandoffRequest request)
    {
        return signal(request, true);
    }

    private boolean signal(DMRRestChannelHandoffRequest request, boolean retry)
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

            boolean ownerSignaled = retry ? request.owner().signalRestHandoffRetryIngress(request) :
                request.owner().signalRestHandoffIngress(request);

            if(ownerSignaled)
            {
                if(mWorkPending.getAndSet(true))
                {
                    mCoalescedCount.incrementAndGet();
                }

                signalWorker();
            }

            //A stale request with no current owner slot is already complete. It is not an ingress rejection.
            return true;
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

    long getCoalescedCount()
    {
        return mCoalescedCount.get();
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
            while(!mStopImmediately && (mAccepting || mActiveOffers.get() > 0 || mWorkPending.get()))
            {
                if(mWorkPending.getAndSet(false))
                {
                    drainLatestRequests();
                }
                else if(!mStopImmediately)
                {
                    LockSupport.parkNanos(this, IDLE_PARK_NANOSECONDS);
                }
            }
        }
        finally
        {
            mAccepting = false;
            mWorkPending.set(false);
            mClosed = true;
        }
    }

    private void drainLatestRequests()
    {
        try
        {
            Iterable<DMRTrafficChannelManager> owners = Objects.requireNonNull(mOwnerSupplier.getOwners(),
                "owner supplier returned null");

            for(DMRTrafficChannelManager owner: owners)
            {
                if(mStopImmediately)
                {
                    return;
                }

                if(owner == null)
                {
                    continue;
                }

                DMRRestChannelHandoffRequest request = owner.pollRestHandoffIngress();

                if(request != null)
                {
                    try
                    {
                        mHandler.handle(request);
                    }
                    catch(Exception exception)
                    {
                        mLog.error("Error processing DMR rest-channel handoff", exception);
                    }
                }
            }
        }
        catch(Exception exception)
        {
            //The manager mailboxes remain dirty. Retry the worker-side snapshot; decoder callbacks never wait.
            mLog.error("Error discovering pending DMR rest-channel handoffs", exception);
            mWorkPending.set(true);
            LockSupport.parkNanos(this, IDLE_PARK_NANOSECONDS);
        }
    }

    @FunctionalInterface
    interface OwnerSupplier
    {
        Iterable<DMRTrafficChannelManager> getOwners() throws Exception;
    }

    @FunctionalInterface
    interface Handler
    {
        void handle(DMRRestChannelHandoffRequest request) throws Exception;
    }
}
