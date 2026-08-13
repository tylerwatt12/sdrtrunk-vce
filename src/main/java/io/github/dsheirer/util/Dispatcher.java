/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.util;

import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.heartbeat.HeartbeatManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Threaded scheduled processor for receiving elements from a separate producer thread and forwarding those elements
 * to a registered listener on a consumer thread.
 *
 * <p>All inbound queues use {@link ConcurrentLinkedQueue}.  A bounded dispatcher applies its limit with an atomic
 * retained-element count and non-blocking drop-oldest polling; a receiver producer never waits for a queue lock.
 * One process-wide guard per dispatcher prevents callbacks from an old stopped run from overlapping a restarted run.
 * Each run has its own queue, so a producer racing Stop can clean its old run without touching a new run.</p>
 */
public class Dispatcher<E> implements Listener<E>
{
    public enum ExecutorType { SHARED, PRIVATE }

    private static final Logger mLog = LoggerFactory.getLogger(Dispatcher.class);
    private static final ScheduledExecutorService SHARED_POOL =
        Executors.newScheduledThreadPool(8, new ThreadFactory()
        {
            private final AtomicInteger mCount = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable)
            {
                Thread thread = new Thread(runnable, "sdrtrunk dispatcher thread " + mCount.getAndIncrement());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
        });

    private final String mThreadName;
    private final Consumer<E> mDiscardHandler;
    private final int mMaximumQueueSize;
    private final AtomicReference<RunState<E>> mRunState =
        new AtomicReference<>(new RunState<>(0, false));
    private final AtomicLong mRunEpoch = new AtomicLong();
    private final AtomicBoolean mProcessing = new AtomicBoolean();
    private final AtomicInteger mInFlightElementCount = new AtomicInteger();
    private final AtomicInteger mHighWaterElementCount = new AtomicInteger();
    private final AtomicLong mReceivedElementCount = new AtomicLong();
    private final AtomicLong mAcceptedElementCount = new AtomicLong();
    private final AtomicLong mProcessedElementCount = new AtomicLong();
    private final AtomicLong mDiscardedElementCount = new AtomicLong();
    private final AtomicLong mDroppedElementCount = new AtomicLong();
    private final AtomicLong mLastIngressNanos = new AtomicLong();
    private final AtomicLong mLastCompletionNanos = new AtomicLong();
    private final AtomicLong mCallbackStartedNanos = new AtomicLong();
    private final AtomicBoolean mCallbackActive = new AtomicBoolean();
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final ExecutorType mExecutorType;
    private final long mInterval;
    private Listener<E> mListener;
    private ScheduledFuture<?> mScheduledFuture;
    private ScheduledExecutorService mPrivateExecutor;
    private HeartbeatManager mHeartbeatManager;

    /**
     * Constructs an unbounded instance using the shared dispatcher pool.
     */
    public Dispatcher(String threadName, long interval, HeartbeatManager heartbeatManager)
    {
        this(threadName, interval, ExecutorType.SHARED);
        mHeartbeatManager = heartbeatManager;
    }

    /**
     * Constructs an optionally bounded instance using the shared dispatcher pool.
     */
    public Dispatcher(String threadName, long interval, HeartbeatManager heartbeatManager, int maximumQueueSize,
                      Consumer<E> discardHandler)
    {
        this(threadName, interval, ExecutorType.SHARED, maximumQueueSize, discardHandler);
        mHeartbeatManager = heartbeatManager;
    }

    /**
     * Constructs an unbounded instance using the shared dispatcher pool.
     */
    public Dispatcher(String threadName, long interval)
    {
        this(threadName, interval, ExecutorType.SHARED);
    }

    /**
     * Constructs an unbounded instance using the requested executor type.
     */
    public Dispatcher(String threadName, long interval, ExecutorType executorType)
    {
        this(threadName, interval, executorType, 0, null);
    }

    /**
     * Constructs an optionally bounded dispatcher.  When full, the oldest queued element is discarded.  A limit of
     * zero selects an unbounded queue.  Supply a discard handler when an element requires explicit release/recycling.
     */
    public Dispatcher(String threadName, long interval, ExecutorType executorType, int maximumQueueSize,
                      Consumer<E> discardHandler)
    {
        if(interval <= 0)
        {
            throw new IllegalArgumentException("Dispatcher interval must be greater than zero");
        }

        if(maximumQueueSize < 0)
        {
            throw new IllegalArgumentException("Dispatcher queue limit cannot be negative");
        }

        mThreadName = threadName != null && !threadName.isBlank() ? threadName : "sdrtrunk dispatcher";
        mInterval = interval;
        mExecutorType = executorType;
        mMaximumQueueSize = maximumQueueSize;
        mDiscardHandler = discardHandler;
    }

    public void setListener(Listener<E> listener)
    {
        mListener = listener;
    }

    /**
     * Non-blocking producer handoff.  This method does not acquire a queue lock, traverse the queue, or log overflow.
     */
    @Override
    public void receive(E element)
    {
        mReceivedElementCount.incrementAndGet();
        mLastIngressNanos.lazySet(System.nanoTime());
        RunState<E> state = mRunState.get();

        if(!isAccepting(state))
        {
            discard(element, false);
            return;
        }

        //Reserve before publishing so every poll has a corresponding count to decrement.  A briefly stalled producer
        //may reserve a bounded slot before it publishes, but no producer ever waits for that slot.
        state.mWaitingElementCount.incrementAndGet();
        state.mQueue.offer(element);
        mAcceptedElementCount.incrementAndGet();
        updateHighWater(state.mWaitingElementCount.get() + mInFlightElementCount.get());

        if(!isAccepting(state))
        {
            //A late producer cleans at most one publication.  Stop/the consumer owns bulk cleanup, so a real-time
            //producer can never inherit an arbitrarily large retain-all queue when it loses this lifecycle race.
            discardOneQueuedElement(state, false);
            return;
        }

        enforceMaximumQueueSize(state);

        //Stop can race bounded overflow cleanup.  Recheck after enforcing the limit, but still perform only one unit
        //of stale cleanup on this producer.
        if(!isAccepting(state))
        {
            discardOneQueuedElement(state, false);
        }
    }

    /**
     * Starts a new run generation.  Elements and scheduled callbacks from an older generation cannot enter this one.
     */
    public synchronized void start()
    {
        if(!mRunning.compareAndSet(false, true))
        {
            return;
        }

        RunState<E> previous = mRunState.get();
        previous.mAccepting.set(false);
        discardQueuedElements(previous, false);

        long epoch = mRunEpoch.incrementAndGet();
        RunState<E> state = new RunState<>(epoch, true);
        mRunState.set(state);

        if(mScheduledFuture != null)
        {
            mScheduledFuture.cancel(false);
            mScheduledFuture = null;
        }

        ScheduledExecutorService executor;

        if(mExecutorType == ExecutorType.SHARED)
        {
            executor = SHARED_POOL;
        }
        else
        {
            if(mPrivateExecutor != null)
            {
                mPrivateExecutor.shutdown();
            }

            mPrivateExecutor = Executors.newSingleThreadScheduledExecutor(new NamingThreadFactory(mThreadName));
            executor = mPrivateExecutor;
        }

        mScheduledFuture = executor.scheduleAtFixedRate(new Processor(state), 0, mInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops this run and releases queued work.  An already executing callback is allowed to return; the persistent
     * processing guard keeps a restarted run from invoking a callback until that old callback and its local drain list
     * have been fully accounted for.
     */
    public synchronized void stop()
    {
        RunState<E> state = transitionToStopped();

        if(state != null)
        {
            discardQueuedElements(state, false);
        }
    }

    /**
     * Stops and flushes queued elements only when no scheduled callback is active.  If a callback is active, queued
     * elements are released instead of invoking overlapping callbacks on the stopping thread.
     */
    public synchronized void flushAndStop()
    {
        RunState<E> state = transitionToStopped();

        if(state == null)
        {
            return;
        }

        if(mProcessing.compareAndSet(false, true))
        {
            try
            {
                process(state, true);
            }
            finally
            {
                mProcessing.set(false);
            }
        }
        else
        {
            discardQueuedElements(state, false);
        }
    }

    private RunState<E> transitionToStopped()
    {
        if(!mRunning.compareAndSet(true, false))
        {
            return null;
        }

        RunState<E> state = mRunState.get();
        state.mAccepting.set(false);
        mRunEpoch.incrementAndGet();

        if(mScheduledFuture != null)
        {
            mScheduledFuture.cancel(false);
            mScheduledFuture = null;
        }

        if(mPrivateExecutor != null)
        {
            mPrivateExecutor.shutdown();
            mPrivateExecutor = null;
        }

        return state;
    }

    public boolean isRunning()
    {
        return mRunning.get();
    }

    /**
     * Constant-time mirrored waiting count; this never invokes {@code ConcurrentLinkedQueue.size()}.
     */
    public int getQueueSize()
    {
        return Math.max(0, mRunState.get().mWaitingElementCount.get());
    }

    public long getDroppedElementCount()
    {
        return mDroppedElementCount.get();
    }

    /**
     * Returns a constant-time lock-free snapshot.  Independent atomics can move while it is assembled, so this is a
     * diagnostic observation rather than a transactional accounting boundary.
     */
    public Metrics getQueueMetrics()
    {
        RunState<E> state = mRunState.get();
        long snapshotNanos = System.nanoTime();
        return new Metrics(mThreadName, mMaximumQueueSize, Math.max(0, state.mWaitingElementCount.get()),
            Math.max(0, mInFlightElementCount.get()), mCallbackActive.get(), mReceivedElementCount.get(),
            mAcceptedElementCount.get(), mProcessedElementCount.get(), mDiscardedElementCount.get(),
            mDroppedElementCount.get(), Math.max(0, mHighWaterElementCount.get()), mLastIngressNanos.get(),
            mLastCompletionNanos.get(), mCallbackStartedNanos.get(), snapshotNanos, mRunning.get());
    }

    /**
     * Immutable dispatcher metrics snapshot.  Nanosecond values come from {@link System#nanoTime()} and are elapsed
     * time markers, not wall-clock timestamps.
     */
    public record Metrics(String name, int maximumQueueSize, int waitingCount, int inFlightCount,
                          boolean callbackActive, long receivedCount, long acceptedCount, long processedCount,
                          long discardedCount, long droppedCount, int highWaterCount, long lastIngressNanos,
                          long lastCompletionNanos, long callbackStartedNanos, long snapshotNanos, boolean running)
    {
        public int outstandingCount()
        {
            long outstanding = (long)waitingCount + inFlightCount;
            return outstanding > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)outstanding;
        }

        public boolean unbounded()
        {
            return maximumQueueSize == 0;
        }

        public long lastIngressAgeNanos()
        {
            return ageNanos(lastIngressNanos);
        }

        public long lastCompletionAgeNanos()
        {
            return ageNanos(lastCompletionNanos);
        }

        public long callbackAgeNanos()
        {
            return callbackActive ? ageNanos(callbackStartedNanos) : 0;
        }

        private long ageNanos(long eventNanos)
        {
            return eventNanos == 0 ? -1 : Math.max(0, snapshotNanos - eventNanos);
        }
    }

    private boolean isAccepting(RunState<E> state)
    {
        return mRunning.get() && state.mAccepting.get() && mRunState.get() == state &&
            mRunEpoch.get() == state.mEpoch;
    }

    private void enforceMaximumQueueSize(RunState<E> state)
    {
        if(mMaximumQueueSize == 0)
        {
            return;
        }

        while(state.mWaitingElementCount.get() > mMaximumQueueSize)
        {
            E dropped = state.mQueue.poll();

            if(dropped == null)
            {
                //Another producer has reserved but not yet published a slot.  That producer will enforce the limit
                //after it publishes, so this producer remains non-blocking.
                return;
            }

            state.mWaitingElementCount.decrementAndGet();
            discard(dropped, true);
        }
    }

    private void discardQueuedElements(RunState<E> state, boolean queueOverflow)
    {
        E element = state.mQueue.poll();

        while(element != null)
        {
            state.mWaitingElementCount.decrementAndGet();
            discard(element, queueOverflow);
            element = state.mQueue.poll();
        }
    }

    private void discardOneQueuedElement(RunState<E> state, boolean queueOverflow)
    {
        E element = state.mQueue.poll();

        if(element != null)
        {
            state.mWaitingElementCount.decrementAndGet();
            discard(element, queueOverflow);
        }
    }

    private void discard(E element, boolean queueOverflow)
    {
        if(element == null)
        {
            return;
        }

        mDiscardedElementCount.incrementAndGet();

        if(mDiscardHandler != null)
        {
            try
            {
                mDiscardHandler.accept(element);
            }
            catch(Throwable ignored)
            {
                //The producer must never fall back to logging or throw because an observer/cleanup callback failed.
            }
        }

        if(queueOverflow)
        {
            mDroppedElementCount.incrementAndGet();
        }
    }

    /**
     * Moves a finite snapshot of queued elements to a process-local list and accounts each exactly once.
     */
    private void process(RunState<E> state, boolean flushStoppedRun)
    {
        int target = Math.max(0, state.mWaitingElementCount.get());
        List<E> elements = new ArrayList<>(Math.min(target, 1024));

        for(int x = 0; x < target; x++)
        {
            E element = state.mQueue.poll();

            if(element == null)
            {
                break;
            }

            state.mWaitingElementCount.decrementAndGet();
            elements.add(element);
        }

        if(elements.isEmpty())
        {
            return;
        }

        int drained = elements.size();
        int inFlight = mInFlightElementCount.addAndGet(drained);
        updateHighWater(inFlight + Math.max(0, state.mWaitingElementCount.get()));

        for(E element: elements)
        {
            boolean callbackAllowed = flushStoppedRun || isAccepting(state);

            if(callbackAllowed && mListener != null && beginCallback(state, flushStoppedRun))
            {
                try
                {
                    mListener.receive(element);
                }
                catch(Throwable throwable)
                {
                    mLog.error("Error while dispatching element [{}] to listener [{}]", element.getClass(),
                        mListener.getClass(), throwable);
                }
                finally
                {
                    mCallbackActive.set(false);
                    mCallbackStartedNanos.lazySet(0);
                    mProcessedElementCount.incrementAndGet();
                    completeInFlightElement();
                }
            }
            else
            {
                discard(element, false);
                completeInFlightElement();
            }
        }
    }

    private boolean beginCallback(RunState<E> state, boolean flushStoppedRun)
    {
        if(!flushStoppedRun && !isAccepting(state))
        {
            return false;
        }

        mCallbackStartedNanos.lazySet(System.nanoTime());
        mCallbackActive.set(true);

        //Close the small race between the first epoch check and publishing callback-active state.  Stop never waits;
        //a callback that has not actually begun is cancelled and its element is released by the caller.
        if(!flushStoppedRun && !isAccepting(state))
        {
            mCallbackActive.set(false);
            mCallbackStartedNanos.lazySet(0);
            return false;
        }

        return true;
    }

    private void completeInFlightElement()
    {
        mInFlightElementCount.decrementAndGet();
        mLastCompletionNanos.lazySet(System.nanoTime());
    }

    private void updateHighWater(int candidate)
    {
        int highWater = mHighWaterElementCount.get();

        while(candidate > highWater && !mHighWaterElementCount.compareAndSet(highWater, candidate))
        {
            highWater = mHighWaterElementCount.get();
        }
    }

    private class Processor implements Runnable
    {
        private final RunState<E> mState;

        private Processor(RunState<E> state)
        {
            mState = state;
        }

        @Override
        public void run()
        {
            if(!mProcessing.compareAndSet(false, true))
            {
                return;
            }

            try
            {
                process(mState, false);

                if(mHeartbeatManager != null && isAccepting(mState))
                {
                    try
                    {
                        mHeartbeatManager.broadcast();
                    }
                    catch(Throwable throwable)
                    {
                        mLog.error("Error broadcasting heartbeat during Dispatcher processing interval", throwable);
                    }
                }
            }
            finally
            {
                mProcessing.set(false);
            }
        }
    }

    private static class RunState<T>
    {
        private final long mEpoch;
        private final ConcurrentLinkedQueue<T> mQueue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger mWaitingElementCount = new AtomicInteger();
        private final AtomicBoolean mAccepting;

        private RunState(long epoch, boolean accepting)
        {
            mEpoch = epoch;
            mAccepting = new AtomicBoolean(accepting);
        }
    }
}
