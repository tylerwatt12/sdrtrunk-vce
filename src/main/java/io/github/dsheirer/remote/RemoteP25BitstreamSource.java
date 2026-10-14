/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.SampleType;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.util.concurrent.BoundedMpscReferenceQueue;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded decoded-bit source used by a host processing chain. Transport threads offer immutable packets without ever
 * waiting for the decoder. A private worker preserves packet order and marks the first packet after a sequence gap or
 * queue overflow as discontinuous so the decoder cannot join unrelated partial frames.
 */
public class RemoteP25BitstreamSource extends Source implements IP25RemoteBitstreamProvider
{
    public static final int DEFAULT_QUEUE_CAPACITY = 8;
    private static final long POLL_MILLISECONDS = 100L;
    private static final long HEARTBEAT_INTERVAL_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(POLL_MILLISECONDS);
    private static final int HEARTBEAT_QUEUE_CAPACITY = 256;
    private static final BoundedMpscReferenceQueue<SourceRun> HEARTBEAT_QUEUE =
        new BoundedMpscReferenceQueue<>(HEARTBEAT_QUEUE_CAPACITY);
    private static final Thread HEARTBEAT_WORKER = startHeartbeatWorker();
    private final AtomicLong mFrequency;
    private final double mSymbolRate;
    private final String mThreadName;
    private final ArrayBlockingQueue<QueuedPacket> mQueue;
    private final AtomicBoolean mAccepting = new AtomicBoolean(true);
    private final AtomicReference<SourceRun> mRun = new AtomicReference<>();
    private final AtomicBoolean mDiscontinuityPending = new AtomicBoolean(true);
    private final AtomicLong mDroppedPacketCount = new AtomicLong();
    private final AtomicLong mFrequencyEpoch = new AtomicLong();
    private final Listener<SourceEvent> mSourceEventReceiver = sourceEvent -> { };
    private volatile long mExpectedSequence = -1L;
    private volatile Listener<P25RemoteBitstreamPacket> mPacketListener;
    private volatile Listener<SourceEvent> mSourceEventListener;
    private volatile CompletableFuture<Void> mDrained = new CompletableFuture<>();

    public RemoteP25BitstreamSource(long frequency, P25RemotePhase phase, String threadName)
    {
        this(frequency, phase == P25RemotePhase.PHASE_2 ? 6000.0 : 4800.0, threadName,
            DEFAULT_QUEUE_CAPACITY);
    }

    /** Test seam for deterministic queue-saturation checks. */
    public RemoteP25BitstreamSource(long frequency, double symbolRate, String threadName, int queueCapacity)
    {
        if(frequency <= 0L || symbolRate <= 0.0 || queueCapacity <= 0)
        {
            throw new IllegalArgumentException("Invalid remote bitstream source configuration");
        }

        mFrequency = new AtomicLong(frequency);
        mSymbolRate = symbolRate;
        mThreadName = threadName == null || threadName.isBlank() ? "sdrtrunk remote P25 bitstream" : threadName;
        mQueue = new ArrayBlockingQueue<>(queueCapacity);
    }

    /**
     * Offers one packed-dibit packet without waiting. The byte array is defensively copied before this method returns.
     *
     * @return true when accepted, or false when closed or the bounded queue is full
     */
    public boolean offer(byte[] dibits, long captureTimestamp, long sequence)
    {
        if(!mAccepting.get())
        {
            return false;
        }

        P25RemoteBitstreamPacket packet =
            new P25RemoteBitstreamPacket(dibits, captureTimestamp, sequence, false);
        QueuedPacket queuedPacket = new QueuedPacket(packet, mFrequencyEpoch.get());

        if(mQueue.offer(queuedPacket))
        {
            return true;
        }

        mDroppedPacketCount.incrementAndGet();
        mDiscontinuityPending.set(true);
        broadcastOverflowState(true);
        return false;
    }

    /**
     * Stops accepting input and lets the worker drain already accepted packets. The returned future completes after
     * the final accepted packet has been delivered. Processing-chain teardown may then safely remove the source.
     */
    public CompletableFuture<Void> close()
    {
        mAccepting.set(false);
        SourceRun run = mRun.get();
        Thread worker = run != null ? run.worker : null;

        if(worker != null)
        {
            worker.interrupt();
        }
        else if(mQueue.isEmpty())
        {
            mDrained.complete(null);
        }

        return mDrained;
    }

    /** Clears queued and framing-order state so this source can be rebound before it is started again. */
    @Override
    public void reset()
    {
        mQueue.clear();
        mExpectedSequence = -1L;
        mDiscontinuityPending.set(true);
        mAccepting.set(true);
        mDrained = new CompletableFuture<>();
        SourceRun run = mRun.get();

        if(run != null)
        {
            //A control feed can reset while its source worker remains running between remote connections.
            run.drained = mDrained;
        }

        broadcastOverflowState(false);
    }

    public long getDroppedPacketCount()
    {
        return mDroppedPacketCount.get();
    }

    /**
     * Updates the live RF frequency without changing the saved remote channel configuration. Any packets still queued
     * for the previous carrier are discarded and the next accepted packet is marked discontinuous so decoder framing
     * cannot span the retune boundary.
     */
    public void updateFrequency(long frequency)
    {
        if(frequency <= 0L)
        {
            throw new IllegalArgumentException("Invalid remote bitstream frequency");
        }

        long previous = mFrequency.getAndSet(frequency);

        if(previous != frequency)
        {
            mFrequencyEpoch.incrementAndGet();
            int discarded = mQueue.size();
            mQueue.clear();
            mDroppedPacketCount.addAndGet(discarded);
            mExpectedSequence = -1L;
            mDiscontinuityPending.set(true);
            Listener<SourceEvent> listener = mSourceEventListener;

            if(listener != null)
            {
                listener.receive(SourceEvent.frequencyChange(this, frequency));
            }
        }
    }

    @Override
    public void start()
    {
        SourceRun run = new SourceRun(this, mDrained);

        if(mRun.compareAndSet(null, run))
        {
            Thread worker = new Thread(() -> run(run), mThreadName);
            worker.setDaemon(true);
            run.worker = worker;
            worker.start();
        }
    }

    @Override
    public void stop()
    {
        mAccepting.set(false);
        SourceRun run = mRun.getAndSet(null);
        Thread worker = run != null ? run.worker : null;

        if(worker != null)
        {
            worker.interrupt();
        }

        mQueue.clear();
        mDrained.complete(null);
        broadcastOverflowState(false);
    }

    private void run(SourceRun run)
    {
        long lastHeartbeat = System.nanoTime() - HEARTBEAT_INTERVAL_NANOSECONDS;

        try
        {
            while(mRun.get() == run)
            {
                if(!mAccepting.get() && mQueue.isEmpty())
                {
                    break;
                }

                try
                {
                    QueuedPacket packet = mQueue.poll(POLL_MILLISECONDS, TimeUnit.MILLISECONDS);

                    if(packet != null)
                    {
                        dispatch(packet, run);
                    }
                }
                catch(InterruptedException ignored)
                {
                    //Re-check running/closed state and continue draining accepted packets after close().
                }

                long now = System.nanoTime();

                if(now - lastHeartbeat >= HEARTBEAT_INTERVAL_NANOSECONDS)
                {
                    lastHeartbeat = now;
                    offerHeartbeat(run);
                }
            }
        }
        finally
        {
            mRun.compareAndSet(run, null);

            if(!mAccepting.get() && mQueue.isEmpty())
            {
                run.drained.complete(null);
            }
        }
    }

    /** One coalesced token per running source; a slow heartbeat observer never delays the remote decoder. */
    private void offerHeartbeat(SourceRun run)
    {
        if(mRun.get() != run || !run.heartbeatQueued.compareAndSet(false, true))
        {
            return;
        }

        if(HEARTBEAT_QUEUE.offer(run))
        {
            LockSupport.unpark(HEARTBEAT_WORKER);
        }
        else
        {
            //Drop this observer tick on saturation or contention. The next polling interval retries it.
            run.heartbeatQueued.set(false);
        }
    }

    private static Thread startHeartbeatWorker()
    {
        Thread worker = new Thread(() ->
        {
            while(!Thread.currentThread().isInterrupted())
            {
                SourceRun run = HEARTBEAT_QUEUE.poll();

                if(run != null)
                {
                    try
                    {
                        run.source.getHeartbeatManager().broadcastIf(() -> run.source.mRun.get() == run);
                    }
                    finally
                    {
                        run.heartbeatQueued.set(false);
                    }
                }
                else
                {
                    LockSupport.parkNanos(RemoteP25BitstreamSource.class, HEARTBEAT_INTERVAL_NANOSECONDS);
                }
            }
        }, "sdrtrunk-remote-heartbeat-1");
        worker.setDaemon(true);
        worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        worker.start();
        return worker;
    }

    private void dispatch(QueuedPacket queuedPacket, SourceRun run)
    {
        Listener<P25RemoteBitstreamPacket> listener = mPacketListener;

        if(mRun.get() != run)
        {
            return;
        }

        if(queuedPacket.frequencyEpoch() != mFrequencyEpoch.get())
        {
            mDroppedPacketCount.incrementAndGet();
            mDiscontinuityPending.set(true);
            return;
        }

        P25RemoteBitstreamPacket packet = queuedPacket.packet();
        long sequence = packet.sequence();

        if(mExpectedSequence >= 0L && sequence < mExpectedSequence)
        {
            mDroppedPacketCount.incrementAndGet();
            mDiscontinuityPending.set(true);
            return;
        }

        boolean discontinuity = mDiscontinuityPending.getAndSet(false) ||
            (mExpectedSequence >= 0L && sequence != mExpectedSequence);
        mExpectedSequence = sequence == Long.MAX_VALUE ? -1L : sequence + 1L;
        if(listener != null)
        {
            listener.receive(new P25RemoteBitstreamPacket(packet.dibits(), packet.captureTimestamp(), sequence,
                discontinuity));
        }

        broadcastOverflowState(false);
    }

    @Override
    public SampleType getSampleType()
    {
        return SampleType.BITSTREAM;
    }

    @Override
    public double getSampleRate()
    {
        return mSymbolRate;
    }

    @Override
    public long getFrequency()
    {
        return mFrequency.get();
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return mSourceEventReceiver;
    }

    @Override
    public void setSourceEventListener(Listener<SourceEvent> listener)
    {
        mSourceEventListener = listener;
    }

    @Override
    public void removeSourceEventListener()
    {
        mSourceEventListener = null;
    }

    @Override
    public void setRemoteBitstreamListener(Listener<P25RemoteBitstreamPacket> listener)
    {
        mPacketListener = Objects.requireNonNull(listener);
    }

    @Override
    public void removeRemoteBitstreamListener(Listener<P25RemoteBitstreamPacket> listener)
    {
        if(mPacketListener == listener)
        {
            mPacketListener = null;
        }
    }

    private record QueuedPacket(P25RemoteBitstreamPacket packet, long frequencyEpoch)
    {
    }

    private static final class SourceRun
    {
        private final RemoteP25BitstreamSource source;
        private volatile CompletableFuture<Void> drained;
        private final AtomicBoolean heartbeatQueued = new AtomicBoolean();
        private volatile Thread worker;

        private SourceRun(RemoteP25BitstreamSource source, CompletableFuture<Void> drained)
        {
            this.source = source;
            this.drained = drained;
        }
    }
}
