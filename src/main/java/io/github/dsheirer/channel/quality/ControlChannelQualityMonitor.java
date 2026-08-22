/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.quality;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.message.DroppedSamplesMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessage;
import io.github.dsheirer.module.decode.nxdn.NXDNMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.heartbeat.Heartbeat;
import io.github.dsheirer.source.heartbeat.IHeartbeatListener;
import io.github.dsheirer.util.concurrent.BoundedMpscReferenceQueue;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects low-cost trunked control-channel signal and decode measurements and publishes coalesced snapshots on a
 * bounded, quality-only worker.
 */
public class ControlChannelQualityMonitor extends Module implements IMessageListener, ISourceEventListener,
    IHeartbeatListener
{
    static final long PUBLISH_INTERVAL_MILLISECONDS = 1000;
    static final long ROLLING_WINDOW_MILLISECONDS = 30000;
    private static final int P25_PHASE_1_FRAME_BITS = 196;
    private static final int P25_PHASE_2_FRAME_BITS = 320;
    private static final int DMR_FRAME_BITS = 288;
    private static final int NXDN_FRAME_BITS = 384;
    private static final int PUBLICATION_QUEUE_CAPACITY = 256;
    private static final int MAX_PUBLICATIONS_PER_DRAIN = 4;
    private static final long IDLE_PARK_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final Logger mLog = LoggerFactory.getLogger(ControlChannelQualityMonitor.class);
    private static final BoundedMpscReferenceQueue<ControlChannelQualityMonitor> PUBLICATION_QUEUE =
        new BoundedMpscReferenceQueue<>(PUBLICATION_QUEUE_CAPACITY);
    private static final AtomicBoolean PUBLICATION_WORKER_BUSY = new AtomicBoolean();
    private static final Thread PUBLICATION_WORKER = startPublicationWorker();

    private final Channel mChannel;
    private final String mGuid;
    private final Consumer<ControlChannelQualitySnapshot> mConsumer;
    private final DecoderType mDecoderType;
    private final boolean mIgnoreDmrCrcChecksums;
    private final Listener<IMessage> mMessageListener = this::receive;
    private final Listener<SourceEvent> mSourceEventListener = this::receive;
    private final Listener<Heartbeat> mHeartbeatListener = heartbeat -> publishIfDue(System.currentTimeMillis());
    private final ReentrantLock mStateLock = new ReentrantLock();
    private final ReentrantLock mPublicationLock = new ReentrantLock();
    private final ReentrantLock mLifecycleLock = new ReentrantLock();
    private final AtomicReference<SnapshotPublication> mPendingInactivePublication = new AtomicReference<>();
    private final AtomicReference<SnapshotPublication> mPendingActivePublication = new AtomicReference<>();
    private final AtomicBoolean mPublicationQueued = new AtomicBoolean();
    private final Deque<Bucket> mBuckets = new ArrayDeque<>();
    private Bucket mCurrent = new Bucket();
    private long mFrequency;
    private long mLastPublish;
    private long mLastValidDecode;
    private long mLastLcchKey = Long.MIN_VALUE;
    private double mSignalDbfs = Double.NaN;
    private volatile long mStateGeneration;
    private volatile long mLifecycleGeneration;
    private volatile boolean mRunning;
    private volatile Runnable mBeforeAsyncPublication;

    private static Thread startPublicationWorker()
    {
        Thread worker = new Thread(() ->
        {
            while(!Thread.currentThread().isInterrupted())
            {
                ControlChannelQualityMonitor monitor = PUBLICATION_QUEUE.poll();

                if(monitor != null)
                {
                    PUBLICATION_WORKER_BUSY.set(true);

                    try
                    {
                        monitor.drainPublicationBatch();
                    }
                    catch(RuntimeException exception)
                    {
                        mLog.warn("Control-channel quality publication drain failed", exception);
                    }
                    finally
                    {
                        PUBLICATION_WORKER_BUSY.set(false);
                    }
                }
                else
                {
                    LockSupport.parkNanos(ControlChannelQualityMonitor.class, IDLE_PARK_NANOSECONDS);
                }
            }
        }, "sdrtrunk-control-quality-1");
        worker.setDaemon(true);
        worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        worker.start();
        return worker;
    }

    public ControlChannelQualityMonitor(Channel channel, long initialFrequency,
                                        Consumer<ControlChannelQualitySnapshot> consumer)
    {
        mChannel = channel;
        mGuid = channel != null && channel.isStandardChannel() ? channel.getRadresGuid() : null;
        mFrequency = initialFrequency;
        mConsumer = consumer;
        mDecoderType = channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        mIgnoreDmrCrcChecksums = channel != null &&
            channel.getDecodeConfiguration() instanceof DecodeConfigDMR config && config.getIgnoreCRCChecksums();
    }

    @Override
    public Listener<IMessage> getMessageListener()
    {
        return mMessageListener;
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return mSourceEventListener;
    }

    @Override
    public Listener<Heartbeat> getHeartbeatListener()
    {
        return mHeartbeatListener;
    }

    private void receive(SourceEvent event)
    {
        if(event == null || !mStateLock.tryLock())
        {
            return;
        }

        SnapshotPublication inactivePublication = null;

        try
        {
            if(!mRunning)
            {
                return;
            }

            switch(event.getEvent())
            {
                case NOTIFICATION_CHANNEL_POWER ->
                {
                    if(event.getValue() != null)
                    {
                        mSignalDbfs = event.getValue().doubleValue();
                        mCurrent.addPower(mSignalDbfs);
                    }
                }
                case NOTIFICATION_FREQUENCY_CHANGE, NOTIFICATION_FREQUENCY_ROTATION_SUCCESS ->
                {
                    if(event.getValue() != null && event.getValue().longValue() > 0 &&
                        event.getValue().longValue() != mFrequency)
                    {
                        mStateGeneration++;
                        inactivePublication = createPublication(false, System.currentTimeMillis());
                        mFrequency = event.getValue().longValue();
                        clearWindow();
                    }
                }
                default -> { }
            }
        }
        finally
        {
            mStateLock.unlock();
        }

        offerPublication(inactivePublication);
    }

    private void receive(IMessage message)
    {
        if(message == null || !mStateLock.tryLock())
        {
            return;
        }

        try
        {
            if(!mRunning)
            {
                return;
            }

            if(message instanceof TSBKMessage tsbk)
            {
                countFrame(tsbk.isValid(), Math.max(0, tsbk.getMessage().getCorrectedBitCount()), tsbk.getTimestamp());
            }
            else if(message instanceof MacMessage mac && mac.getDataUnitID().isLCCH())
            {
                long key = mac.getTimestamp() * 4 + mac.getTimeslot();

                if(key != mLastLcchKey)
                {
                    mLastLcchKey = key;
                    countFrame(mac.isValid(), Math.max(0, mac.getBitErrorCount()), mac.getTimestamp());
                }
            }
            else if(mDecoderType == DecoderType.DMR && message instanceof DataMessage data)
            {
                /*
                 * DataMessage is the physical DMR burst carrier.  Reassembled LC and packet messages are deliberately
                 * excluded so a burst is counted once.  Alternate RAS masks are resolved upstream and reflected in
                 * isValid(); Ignore CRC is a user-selected override.  Slot type integrity remains mandatory because an
                 * invalid slot type means the payload type itself is not trustworthy.
                 */
                boolean valid = data.getSlotType() != null && data.getSlotType().isValid() &&
                    (data.isValid() || mIgnoreDmrCrcChecksums);
                int correctedBits = Math.max(0, data.getMessage().getCorrectedBitCount()) +
                    (data.getSlotType() != null ? data.getSlotType().getCorrectedBitCount() : 0);
                countFrame(valid, correctedBits, data.getTimestamp());
            }
            else if(mDecoderType == DecoderType.NXDN && message instanceof NXDNMessage nxdn &&
                nxdn.isRfFrameQualityCarrier())
            {
                countFrame(nxdn.isRfFrameValid(), nxdn.getRfFrameCorrectedBitCount(), nxdn.getTimestamp());
            }
            else if(message instanceof SyncLossMessage syncLoss)
            {
                mCurrent.syncLossBits += Math.max(0, syncLoss.getBitsProcessed());
            }
            else if(message instanceof DroppedSamplesMessage dropped)
            {
                mCurrent.droppedBits += Math.max(0, dropped.getBitsDropped());
            }
        }
        finally
        {
            mStateLock.unlock();
        }
    }

    private void countFrame(boolean valid, int correctedBits, long timestamp)
    {
        if(valid)
        {
            mCurrent.validFrames++;
            mLastValidDecode = Math.max(mLastValidDecode, timestamp);
        }
        else
        {
            mCurrent.invalidFrames++;
        }

        mCurrent.correctedBits += correctedBits;
    }

    void publishIfDue(long now)
    {
        if(!mStateLock.tryLock())
        {
            return;
        }

        SnapshotPublication publication = null;

        try
        {
            if(!mRunning || now - mLastPublish < PUBLISH_INTERVAL_MILLISECONDS)
            {
                return;
            }

            mCurrent.endedAt = now;
            mBuckets.addLast(mCurrent);
            mCurrent = new Bucket();
            mLastPublish = now;
            long cutoff = now - ROLLING_WINDOW_MILLISECONDS;

            while(!mBuckets.isEmpty() && mBuckets.peekFirst().endedAt < cutoff)
            {
                mBuckets.removeFirst();
            }

            publication = createPublication(true, now);
        }
        finally
        {
            mStateLock.unlock();
        }

        offerPublication(publication);
    }

    /** Creates an immutable publication while the caller owns {@link #mStateLock}. */
    private SnapshotPublication createPublication(boolean active, long now)
    {
        if(mConsumer == null || mFrequency <= 0)
        {
            return null;
        }

        long powerCount = 0;
        double powerSum = 0;
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        long valid = 0;
        long invalid = 0;
        long corrected = 0;
        long syncLoss = 0;
        long dropped = 0;

        for(Bucket bucket: mBuckets)
        {
            powerCount += bucket.powerCount;
            powerSum += bucket.powerLinearSum;
            minimum = Math.min(minimum, bucket.minimumSignalDbfs);
            maximum = Math.max(maximum, bucket.maximumSignalDbfs);
            valid += bucket.validFrames;
            invalid += bucket.invalidFrames;
            corrected += bucket.correctedBits;
            syncLoss += bucket.syncLossBits;
            dropped += bucket.droppedBits;
        }

        int frameBits = P25_PHASE_1_FRAME_BITS;

        if(mDecoderType == DecoderType.P25_PHASE2)
        {
            frameBits = P25_PHASE_2_FRAME_BITS;
        }
        else if(mDecoderType == DecoderType.DMR)
        {
            frameBits = DMR_FRAME_BITS;
        }
        else if(mDecoderType == DecoderType.NXDN)
        {
            frameBits = NXDN_FRAME_BITS;
        }

        double attempted = valid + invalid + (double)(syncLoss + dropped) / frameBits;
        Double health = attempted > 0 ? Math.max(0.0, Math.min(100.0, 100.0 * valid / attempted)) : null;
        Double average = powerCount > 0 ? 10.0 * Math.log10(powerSum / powerCount) : null;
        Double min = powerCount > 0 ? minimum : null;
        Double max = powerCount > 0 ? maximum : null;
        Double current = Double.isFinite(mSignalDbfs) ? mSignalDbfs : null;
        ControlChannelQualitySnapshot snapshot = new ControlChannelQualitySnapshot(mChannel, mGuid, mFrequency, now,
            active, current, average, min, max, health, valid, invalid, corrected, syncLoss, dropped,
            mLastValidDecode);
        return new SnapshotPublication(snapshot, mStateGeneration, mLifecycleGeneration);
    }

    /** Offers the latest active/frequency observation without waiting or invoking an observer on the producer. */
    private void offerPublication(SnapshotPublication publication)
    {
        if(publication == null)
        {
            return;
        }

        if(publication.snapshot().active())
        {
            mPendingActivePublication.set(publication);
        }
        else
        {
            //Keep the oldest not-yet-drained frequency closure. Any intermediate active snapshot is still pending and
            //therefore cannot have made its frequency visible ahead of this required inactive observation.
            mPendingInactivePublication.compareAndSet(null, publication);
        }

        enqueuePublicationToken();
    }

    private void enqueuePublicationToken()
    {
        if(!mPublicationQueued.compareAndSet(false, true))
        {
            return;
        }

        if(PUBLICATION_QUEUE.offer(this))
        {
            LockSupport.unpark(PUBLICATION_WORKER);
        }
        else
        {
            //The fixed-attempt lock-free queue is full or contended. Drop only the coalesced active value and let a
            //later observation rearm this monitor. Keep a required old-frequency closure pending.
            mPublicationQueued.set(false);
            mPendingActivePublication.set(null);
        }
    }

    private void drainPublicationBatch()
    {
        try
        {
            SnapshotPublication publication;
            int drained = 0;

            while(drained < MAX_PUBLICATIONS_PER_DRAIN &&
                (publication = pollPendingPublication()) != null)
            {
                Runnable beforePublication = mBeforeAsyncPublication;

                if(beforePublication != null)
                {
                    beforePublication.run();
                }

                publishAsync(publication);
                drained++;
            }
        }
        finally
        {
            mPublicationQueued.set(false);

            //Close the offer-versus-drain-completion race and requeue at the tail after a bounded batch for fairness.
            if(hasPendingPublication())
            {
                enqueuePublicationToken();
            }
        }
    }

    private SnapshotPublication pollPendingPublication()
    {
        SnapshotPublication publication = mPendingInactivePublication.getAndSet(null);
        return publication != null ? publication : mPendingActivePublication.getAndSet(null);
    }

    private boolean hasPendingPublication()
    {
        return mPendingInactivePublication.get() != null || mPendingActivePublication.get() != null;
    }

    private void publishAsync(SnapshotPublication publication)
    {
        mPublicationLock.lock();

        try
        {
            boolean currentLifecycle = publication.lifecycleGeneration() == mLifecycleGeneration;
            boolean currentState = publication.stateGeneration() == mStateGeneration;

            if(mRunning && currentLifecycle && (!publication.snapshot().active() || currentState))
            {
                accept(publication.snapshot());
            }
        }
        finally
        {
            mPublicationLock.unlock();
        }
    }

    /** Lifecycle publication may wait, but never while holding the state lock used by real-time callbacks. */
    private void publishStopped(SnapshotPublication publication)
    {
        if(publication == null)
        {
            return;
        }

        mPublicationLock.lock();

        try
        {
            if(!mRunning && publication.lifecycleGeneration() == mLifecycleGeneration &&
                publication.stateGeneration() == mStateGeneration)
            {
                accept(publication.snapshot());
            }
        }
        finally
        {
            mPublicationLock.unlock();
        }
    }

    private void accept(ControlChannelQualitySnapshot snapshot)
    {
        try
        {
            mConsumer.accept(snapshot);
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Control-channel quality observer rejected a snapshot", exception);
        }
    }

    private void clearWindow()
    {
        mBuckets.clear();
        mCurrent = new Bucket();
        mLastValidDecode = 0;
        mLastLcchKey = Long.MIN_VALUE;
        mSignalDbfs = Double.NaN;
        mLastPublish = 0;
    }

    @Override
    public void reset()
    {
        mLifecycleLock.lock();

        try
        {
            clearPendingPublications();
            mStateLock.lock();

            try
            {
                mStateGeneration++;
                mLifecycleGeneration++;
                clearWindow();
            }
            finally
            {
                mStateLock.unlock();
            }
        }
        finally
        {
            mLifecycleLock.unlock();
        }
    }

    @Override
    public void start()
    {
        mLifecycleLock.lock();

        try
        {
            clearPendingPublications();
            mStateLock.lock();

            try
            {
                mStateGeneration++;
                mLifecycleGeneration++;
                mRunning = true;
                mLastPublish = System.currentTimeMillis();
            }
            finally
            {
                mStateLock.unlock();
            }
        }
        finally
        {
            mLifecycleLock.unlock();
        }
    }

    @Override
    public void stop()
    {
        SnapshotPublication inactivePublication = null;
        mLifecycleLock.lock();

        try
        {
            mStateLock.lock();

            try
            {
                if(mRunning)
                {
                    mRunning = false;
                    mStateGeneration++;
                    mLifecycleGeneration++;
                    inactivePublication = createPublication(false, System.currentTimeMillis());
                    clearWindow();
                }
            }
            finally
            {
                mStateLock.unlock();
            }

            //Discard queued nonterminal observations and serialize the exact terminal publication before returning.
            clearPendingPublications();
            publishStopped(inactivePublication);
        }
        finally
        {
            mLifecycleLock.unlock();
        }
    }

    /** Installs a deterministic pre-publication interleave for package tests. */
    void setBeforeAsyncPublicationForTest(Runnable beforePublication)
    {
        mBeforeAsyncPublication = beforePublication;
    }

    boolean isPublicationIdleForTest()
    {
        return !hasPendingPublication() && !mPublicationQueued.get();
    }

    private void clearPendingPublications()
    {
        mPendingInactivePublication.set(null);
        mPendingActivePublication.set(null);
    }

    static int getPublicationQueueCapacityForTest()
    {
        return PUBLICATION_QUEUE_CAPACITY;
    }

    static boolean isPublicationWorkerIdleForTest()
    {
        return !PUBLICATION_WORKER_BUSY.get() && PUBLICATION_QUEUE.size() == 0;
    }

    private record SnapshotPublication(ControlChannelQualitySnapshot snapshot, long stateGeneration,
                                       long lifecycleGeneration)
    {
    }

    private static class Bucket
    {
        private long endedAt;
        private long powerCount;
        private double powerLinearSum;
        private double minimumSignalDbfs = Double.POSITIVE_INFINITY;
        private double maximumSignalDbfs = Double.NEGATIVE_INFINITY;
        private long validFrames;
        private long invalidFrames;
        private long correctedBits;
        private long syncLossBits;
        private long droppedBits;

        private void addPower(double dbfs)
        {
            if(Double.isFinite(dbfs))
            {
                powerCount++;
                powerLinearSum += Math.pow(10.0, dbfs / 10.0);
                minimumSignalDbfs = Math.min(minimumSignalDbfs, dbfs);
                maximumSignalDbfs = Math.max(maximumSignalDbfs, dbfs);
            }
        }
    }
}
