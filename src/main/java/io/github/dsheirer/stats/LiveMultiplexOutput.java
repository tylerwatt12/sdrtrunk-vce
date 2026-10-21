/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static io.github.dsheirer.stats.LiveMultiplexFrame.*;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Bounded per-document writer. Each metadata topic has its own count- and byte-bounded FIFO so a burst in one
 * feature cannot evict another feature's events. Each dense diagnostic type has its own latest-value slot, while
 * authoritative recovery and diagnostic state use priority coalesced slots. Closing stops new work immediately;
 * connection capacity remains charged until a blocked network writer actually terminates. Receiver and diagnostic
 * producers never call this object directly.
 */
final class LiveMultiplexOutput implements AutoCloseable
{
    private static final int EVENT_CAPACITY_PER_TOPIC = 64;
    static final int EVENT_BYTE_CAPACITY_PER_TOPIC = 256 * 1024;
    private static final long MAXIMUM_EVENT_DROPS = 64;
    private static final int LATEST_LANES_PER_TOPIC = 4;
    private static final int LATEST_PRIMARY_LANE = 0;
    private static final int LATEST_CHANNEL_SYMBOL_LANE = 1;
    private static final int LATEST_SQUELCH_LANE = 2;
    private static final int LATEST_AUDIO_LANE = 3;
    private final OutputStream mOutputStream;
    private final boolean mGzipEnabled;
    private final long mWriteStallNanos;
    private final boolean mCloseStreamOnClose;
    private final Runnable mAfterEmptyStatePoll;
    private final Runnable mOnWriterTerminated;
    private final Object mPendingLock = new Object();
    private final AtomicReferenceArray<ArrayBlockingQueue<byte[]>> mEvents =
        new AtomicReferenceArray<>(TOPIC_MAXIMUM + 1);
    private final AtomicReferenceArray<byte[]> mStates = new AtomicReferenceArray<>(TOPIC_MAXIMUM + 1);
    private final AtomicReferenceArray<byte[]> mRecoveryFollowers =
        new AtomicReferenceArray<>(TOPIC_MAXIMUM + 1);
    private final AtomicReferenceArray<byte[]> mLatest =
        new AtomicReferenceArray<>((TOPIC_MAXIMUM + 1) * LATEST_LANES_PER_TOPIC);
    private final AtomicBoolean mOutputClosed = new AtomicBoolean();
    private final AtomicBoolean mFailed = new AtomicBoolean();
    private final AtomicBoolean mWriterStarted = new AtomicBoolean();
    private final AtomicBoolean mWriterTerminated = new AtomicBoolean();
    private final AtomicLong mLastProgressNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong mWriteStartedNanos = new AtomicLong();
    private final AtomicLong mEventDrops = new AtomicLong();
    private final AtomicLong mWrittenBytes = new AtomicLong();
    private final AtomicLong mWrittenFrames = new AtomicLong();
    private final AtomicLongArray mTopicEventDrops = new AtomicLongArray(TOPIC_MAXIMUM + 1);
    private final AtomicLongArray mRecentTopicEventDrops = new AtomicLongArray(TOPIC_MAXIMUM + 1);
    private final long[] mPendingEventBytes = new long[TOPIC_MAXIMUM + 1];
    private int mNextStateTopic = TOPIC_CHANNEL_ACTIVITY;
    private int mNextRecoveryFollowerTopic = TOPIC_CHANNEL_ACTIVITY;
    private int mNextEventTopic = TOPIC_CONTROL;
    private int mNextLatestSlot = TOPIC_CHANNEL_ACTIVITY * LATEST_LANES_PER_TOPIC;
    private boolean mPreferLatest;
    private Thread mWriter;

    LiveMultiplexOutput(OutputStream outputStream)
    {
        this(outputStream, Duration.ofSeconds(8), true, () -> {}, () -> {});
    }

    LiveMultiplexOutput(OutputStream outputStream, Duration writeStall)
    {
        this(outputStream, writeStall, true, () -> {}, () -> {});
    }

    LiveMultiplexOutput(OutputStream outputStream, Duration writeStall, Runnable afterEmptyStatePoll)
    {
        this(outputStream, writeStall, true, afterEmptyStatePoll, () -> {});
    }

    LiveMultiplexOutput(OutputStream outputStream, Duration writeStall, boolean closeStreamOnClose,
                    Runnable afterEmptyStatePoll)
    {
        this(outputStream, writeStall, closeStreamOnClose, afterEmptyStatePoll, () -> {});
    }

    LiveMultiplexOutput(OutputStream outputStream, Duration writeStall, boolean closeStreamOnClose,
                    Runnable afterEmptyStatePoll, Runnable onWriterTerminated)
    {
        this(outputStream, writeStall, closeStreamOnClose, afterEmptyStatePoll, onWriterTerminated, false);
    }

    LiveMultiplexOutput(OutputStream outputStream, Duration writeStall, boolean closeStreamOnClose,
                    Runnable afterEmptyStatePoll, Runnable onWriterTerminated, boolean gzip)
    {
        mOutputStream = Objects.requireNonNull(outputStream, "Multiplex output stream cannot be null");
        mGzipEnabled = gzip;
        mWriteStallNanos = Objects.requireNonNull(writeStall, "Write stall duration cannot be null").toNanos();
        mCloseStreamOnClose = closeStreamOnClose;
        mAfterEmptyStatePoll = Objects.requireNonNull(afterEmptyStatePoll,
            "Multiplex selection hook cannot be null");
        mOnWriterTerminated = Objects.requireNonNull(onWriterTerminated,
            "Multiplex writer termination callback cannot be null");

        if(mWriteStallNanos <= 0)
        {
            throw new IllegalArgumentException("Write stall duration must be positive");
        }

        for(int topic = TOPIC_CONTROL; topic <= TOPIC_MAXIMUM; topic++)
        {
            mEvents.set(topic, new ArrayBlockingQueue<>(EVENT_CAPACITY_PER_TOPIC));
        }
    }

    boolean gzipEnabled() { return mGzipEnabled; }
    long writtenBytes() { return mWrittenBytes.get(); }
    long writtenFrames() { return mWrittenFrames.get(); }

    void start()
    {
        mWriter = new Thread(this::writeLoop, "stats web multiplex writer");
        mWriter.setDaemon(true);
        mWriter.setPriority(Thread.NORM_PRIORITY - 1);
        mWriterStarted.set(true);

        try
        {
            mWriter.start();
        }
        catch(RuntimeException exception)
        {
            mWriterStarted.set(false);
            writerTerminated();
            throw exception;
        }
    }

    void offerEvent(int topic, byte[] envelope)
    {
        if(mOutputClosed.get() || !validTopic(topic))
        {
            return;
        }

        synchronized(mPendingLock)
        {
            if(mOutputClosed.get())
            {
                return;
            }

            ArrayBlockingQueue<byte[]> events = mEvents.get(topic);

            if(envelope.length > EVENT_BYTE_CAPACITY_PER_TOPIC)
            {
                recordEventDrop(topic);
                return;
            }

            while(!events.isEmpty() && (events.remainingCapacity() == 0 ||
                mPendingEventBytes[topic] + envelope.length > EVENT_BYTE_CAPACITY_PER_TOPIC))
            {
                byte[] dropped = events.poll();
                mPendingEventBytes[topic] -= dropped.length;
                recordEventDrop(topic);
            }

            if(events.offer(envelope))
            {
                mPendingEventBytes[topic] += envelope.length;
            }
            else
            {
                //The queue is mutated only under mPendingLock, but retain a bounded failure path if that changes.
                recordEventDrop(topic);
            }
        }
    }

    private void recordEventDrop(int topic)
    {
        mEventDrops.incrementAndGet();
        mTopicEventDrops.incrementAndGet(topic);
        mRecentTopicEventDrops.incrementAndGet(topic);
    }

    void offerRecovery(int topic, byte[] envelope)
    {
        if(mOutputClosed.get() || !validTopic(topic))
        {
            return;
        }

        synchronized(mPendingLock)
        {
            if(mOutputClosed.get())
            {
                return;
            }

            clearEventsLocked(topic);
            offerStateLocked(topic, envelope);
        }
    }

    /**
     * Atomically replaces one topic with two protected recovery frames that are written in order before any
     * subsequent metadata delta.  The bounded follower lane keeps a large authoritative snapshot out of the
     * lossy event FIFO while allowing a gap marker to establish uncertainty first.
     */
    void offerRecoverySequence(int topic, byte[] firstEnvelope, byte[] secondEnvelope)
    {
        if(mOutputClosed.get() || !validTopic(topic))
        {
            return;
        }

        synchronized(mPendingLock)
        {
            if(mOutputClosed.get())
            {
                return;
            }

            clearEventsLocked(topic);
            offerStateLocked(topic, firstEnvelope);
            mRecoveryFollowers.set(topic, secondEnvelope);
        }
    }

    void clearTopic(int topic)
    {
        if(validTopic(topic))
        {
            synchronized(mPendingLock)
            {
                clearEventsLocked(topic);
                mStates.set(topic, null);
                mRecoveryFollowers.set(topic, null);
                clearLatestLocked(topic);
            }
        }
    }

    void offerLatest(int topic, int frameType, byte[] envelope)
    {
        if(!mOutputClosed.get() && validTopic(topic) &&
            frameType >= DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL &&
            frameType <= DiagnosticStreamFrame.TYPE_AUDIO_PCM16)
        {
            synchronized(mPendingLock)
            {
                if(!mOutputClosed.get())
                {
                    mLatest.set(latestSlot(topic, latestLane(frameType)), envelope);
                }
            }
        }
    }

    void offerState(int topic, byte[] envelope)
    {
        if(!mOutputClosed.get() && topic >= 0 && topic < mStates.length())
        {
            synchronized(mPendingLock)
            {
                if(!mOutputClosed.get())
                {
                    offerStateLocked(topic, envelope);
                }
            }
        }
    }

    private void offerStateLocked(int topic, byte[] envelope)
    {
        //A state frame changes the meaning/layout of dense frames. Discard any prior-layout latest frame and
        //coalesce state independently from lossy metadata so viewport acknowledgement cannot be evicted.
        clearLatestLocked(topic);
        mRecoveryFollowers.set(topic, null);
        mStates.set(topic, envelope);
    }

    boolean isFailed()
    {
        return mFailed.get();
    }

    boolean isWriterStarted()
    {
        return mWriterStarted.get();
    }

    boolean isWriterTerminated()
    {
        return mWriterTerminated.get();
    }

    long eventDrops()
    {
        return mEventDrops.get();
    }

    long eventDrops(int topic)
    {
        return validTopic(topic) ? mTopicEventDrops.get(topic) : 0;
    }

    long pendingEventBytes(int topic)
    {
        synchronized(mPendingLock)
        {
            return validTopic(topic) ? mPendingEventBytes[topic] : 0;
        }
    }

    boolean isPersistentlySlow()
    {
        long recentDrops = 0;

        for(int topic = TOPIC_CONTROL; topic < mEvents.length(); topic++)
        {
            recentDrops += mRecentTopicEventDrops.get(topic);
        }

        if(recentDrops >= MAXIMUM_EVENT_DROPS)
        {
            return true;
        }

        long started = mWriteStartedNanos.get();
        return started > mLastProgressNanos.get() && System.nanoTime() - started >= mWriteStallNanos;
    }

    private void writeLoop()
    {
        try
        {
            while(!mOutputClosed.get())
            {
                byte[] envelope = pollPending();

                if(envelope == null)
                {
                    Thread.sleep(20);
                    continue;
                }

                mWriteStartedNanos.set(System.nanoTime());
                mOutputStream.write(envelope);
                mOutputStream.flush();
                mWrittenBytes.addAndGet(envelope.length);
                mWrittenFrames.incrementAndGet();
                mLastProgressNanos.set(System.nanoTime());
                mWriteStartedNanos.set(0);

                clearRecoveredDropWindows();
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
        catch(IOException exception)
        {
            if(!mOutputClosed.get())
            {
                mFailed.set(true);
            }
        }
        catch(RuntimeException exception)
        {
            if(!mOutputClosed.get())
            {
                mFailed.set(true);
            }
        }
        finally
        {
            mOutputClosed.set(true);
            closeStream();
            writerTerminated();
        }
    }

    private void writerTerminated()
    {
        if(mWriterTerminated.compareAndSet(false, true))
        {
            mOnWriterTerminated.run();
        }
    }

    private byte[] pollPending()
    {
        synchronized(mPendingLock)
        {
            byte[] envelope = pollState();

            if(envelope == null)
            {
                envelope = pollRecoveryFollower();
            }

            if(envelope == null)
            {
                mAfterEmptyStatePoll.run();
            }

            if(envelope == null && mPreferLatest)
            {
                envelope = pollLatest();
            }

            if(envelope == null)
            {
                envelope = pollEvent();
            }

            if(envelope == null)
            {
                envelope = pollLatest();
            }

            mPreferLatest = !mPreferLatest;
            return envelope;
        }
    }

    private byte[] pollState()
    {
        for(int count = TOPIC_CHANNEL_ACTIVITY; count < mStates.length(); count++)
        {
            int topic = mNextStateTopic++;

            if(mNextStateTopic >= mStates.length())
            {
                mNextStateTopic = TOPIC_CHANNEL_ACTIVITY;
            }

            byte[] envelope = mStates.getAndSet(topic, null);

            if(envelope != null)
            {
                return envelope;
            }
        }

        return null;
    }

    private byte[] pollRecoveryFollower()
    {
        for(int count = TOPIC_CHANNEL_ACTIVITY; count < mRecoveryFollowers.length(); count++)
        {
            int topic = mNextRecoveryFollowerTopic++;

            if(mNextRecoveryFollowerTopic >= mRecoveryFollowers.length())
            {
                mNextRecoveryFollowerTopic = TOPIC_CHANNEL_ACTIVITY;
            }

            byte[] envelope = mRecoveryFollowers.getAndSet(topic, null);

            if(envelope != null)
            {
                return envelope;
            }
        }

        return null;
    }

    private byte[] pollEvent()
    {
        for(int count = TOPIC_CONTROL; count < mEvents.length(); count++)
        {
            int topic = mNextEventTopic++;

            if(mNextEventTopic >= mEvents.length())
            {
                mNextEventTopic = TOPIC_CONTROL;
            }

            byte[] envelope = mEvents.get(topic).poll();

            if(envelope != null)
            {
                mPendingEventBytes[topic] -= envelope.length;
                return envelope;
            }
        }

        return null;
    }

    private byte[] pollLatest()
    {
        int firstSlot = TOPIC_CHANNEL_ACTIVITY * LATEST_LANES_PER_TOPIC;

        for(int count = firstSlot; count < mLatest.length(); count++)
        {
            int slot = mNextLatestSlot++;

            if(mNextLatestSlot >= mLatest.length())
            {
                mNextLatestSlot = firstSlot;
            }

            byte[] envelope = mLatest.getAndSet(slot, null);

            if(envelope != null)
            {
                return envelope;
            }
        }

        return null;
    }

    private void clearRecoveredDropWindows()
    {
        synchronized(mPendingLock)
        {
            for(int topic = TOPIC_CONTROL; topic < mEvents.length(); topic++)
            {
                if(mEvents.get(topic).size() < EVENT_CAPACITY_PER_TOPIC / 4)
                {
                    mRecentTopicEventDrops.set(topic, 0);
                }
            }
        }
    }

    private boolean validTopic(int topic)
    {
        return topic >= TOPIC_CONTROL && topic < mEvents.length();
    }

    private void clearEventsLocked(int topic)
    {
        mEvents.get(topic).clear();
        mPendingEventBytes[topic] = 0;
    }

    private static int latestSlot(int topic, int lane)
    {
        return topic * LATEST_LANES_PER_TOPIC + lane;
    }

    private static int latestLane(int frameType)
    {
        return switch(frameType)
        {
            case DiagnosticStreamFrame.TYPE_CHANNEL_SYMBOLS -> LATEST_CHANNEL_SYMBOL_LANE;
            case DiagnosticStreamFrame.TYPE_SQUELCH -> LATEST_SQUELCH_LANE;
            case DiagnosticStreamFrame.TYPE_AUDIO_PCM16 -> LATEST_AUDIO_LANE;
            default -> LATEST_PRIMARY_LANE;
        };
    }

    private void clearLatestLocked(int topic)
    {
        for(int lane = 0; lane < LATEST_LANES_PER_TOPIC; lane++)
        {
            mLatest.set(latestSlot(topic, lane), null);
        }
    }

    @Override
    public void close()
    {
        if(!mOutputClosed.compareAndSet(false, true))
        {
            return;
        }

        synchronized(mPendingLock)
        {
            for(int topic = 0; topic < mEvents.length(); topic++)
            {
                clearEventsLocked(topic);
                mStates.set(topic, null);
                mRecoveryFollowers.set(topic, null);
                clearLatestLocked(topic);
            }
        }

        Thread writer = mWriter;

        if(writer != null && writer != Thread.currentThread())
        {
            writer.interrupt();
        }

        if(mCloseStreamOnClose || !mWriterStarted.get())
        {
            closeStream();
        }
    }

    private void closeStream()
    {
        try
        {
            mOutputStream.close();
        }
        catch(IOException exception)
        {
            //The browser already disconnected.
        }
    }
}

/** One browser document's logical subscriptions, all sampled by its single HTTP handler thread. */
