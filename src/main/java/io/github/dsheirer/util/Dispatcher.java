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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Threaded scheduled processor for receiving elements from a separate producer thread and forwarding those buffers to a
 * registered listener on this consumer/dispatcher thread.
 *
 * Instances that use the shared-pool constructor share a fixed-size daemon thread pool rather than allocating one
 * thread per dispatcher.  Per-instance ordering is preserved by the Processor guard (an AtomicBoolean that prevents
 * concurrent re-entry on the same dispatcher).  Shutdown cancels the per-instance ScheduledFuture without touching
 * the shared pool.
 *
 * Instances that use the private-pool constructor (recorders and other I/O-heavy users) get their own executor so
 * that slow I/O cannot starve the shared channel-dispatch pool.
 */
public class Dispatcher<E> implements Listener<E>
{
    public enum ExecutorType { SHARED, PRIVATE }

    private static final Logger mLog = LoggerFactory.getLogger(Dispatcher.class);
    //Daemon threads: the pool must not prevent JVM shutdown.
    private static final ScheduledExecutorService SHARED_POOL =
        Executors.newScheduledThreadPool(8, new ThreadFactory()
        {
            private final AtomicInteger mCount = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r)
            {
                Thread t = new Thread(r, "sdrtrunk dispatcher thread " + mCount.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            }
        });
    private final String mThreadName;
    private final BlockingQueue<E> mQueue;
    private final List<E> mDrainBuffer = new ArrayList<>();
    private final Consumer<E> mDiscardHandler;
    private final int mMaximumQueueSize;
    private final AtomicLong mDroppedElementCount = new AtomicLong();
    private Listener<E> mListener;
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private ScheduledFuture<?> mScheduledFuture;
    private ScheduledExecutorService mPrivateExecutor;
    private final ExecutorType mExecutorType;
    private final long mInterval;
    private HeartbeatManager mHeartbeatManager;

    /**
     * Constructs an instance that uses the shared dispatcher pool.  Use for channel sources and channel output
     * processors where thread-per-instance overhead is the primary concern.
     * @param threadName used for diagnostics
     * @param interval for processing each batch in milliseconds.
     * @param heartbeatManager to receive a heartbeat command at each processing interval.
     */
    public Dispatcher(String threadName, long interval, HeartbeatManager heartbeatManager)
    {
        this(threadName, interval, ExecutorType.SHARED);
        mHeartbeatManager = heartbeatManager;
    }

    /**
     * Constructs a bounded dispatcher that uses the shared dispatcher pool and broadcasts heartbeats.  When full, the
     * oldest queued element is discarded and supplied to the discard handler.
     *
     * @param threadName used for diagnostics
     * @param interval for processing each batch in milliseconds
     * @param heartbeatManager to receive a heartbeat command at each processing interval
     * @param maximumQueueSize maximum queued elements
     * @param discardHandler cleanup callback for discarded elements
     */
    public Dispatcher(String threadName, long interval, HeartbeatManager heartbeatManager, int maximumQueueSize,
                      Consumer<E> discardHandler)
    {
        this(threadName, interval, ExecutorType.SHARED, maximumQueueSize, discardHandler);
        mHeartbeatManager = heartbeatManager;
    }

    /**
     * Constructs an instance that uses the shared dispatcher pool.  Use for channel sources and channel output
     * processors where thread-per-instance overhead is the primary concern.
     * @param threadName used for diagnostics
     * @param interval for processing each batch in milliseconds.
     */
    public Dispatcher(String threadName, long interval)
    {
        this(threadName, interval, ExecutorType.SHARED);
    }

    /**
     * Constructs an instance with the specified executor type.  Use {@link ExecutorType#PRIVATE} for I/O-bound
     * recorders and other users where slow tasks must not starve the shared channel-dispatch pool.
     * @param threadName used for private executor threads and diagnostics
     * @param interval for processing each batch in milliseconds.
     * @param executorType whether to use the shared pool or a private single-thread executor.
     */
    public Dispatcher(String threadName, long interval, ExecutorType executorType)
    {
        this(threadName, interval, executorType, 0, null);
    }

    /**
     * Constructs an optionally bounded dispatcher.  When full, the oldest queued element is discarded before the
     * new element is accepted.  Supply a discard handler when queued elements require explicit release or recycling.
     *
     * @param threadName name used for private executor threads and overflow diagnostics
     * @param interval processing interval in milliseconds
     * @param executorType shared or private executor
     * @param maximumQueueSize maximum queued elements, or zero for unbounded
     * @param discardHandler optional cleanup callback for discarded elements
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
        mQueue = maximumQueueSize > 0 ? new ArrayBlockingQueue<>(maximumQueueSize) : new LinkedTransferQueue<>();
    }

    /**
     * Sets or changes the listener to receive buffers from this processor.
     * @param listener to receive buffers
     */
    public void setListener(Listener<E> listener)
    {
        mListener = listener;
    }

    /**
     * Primary input method for adding buffers to this processor.  Incoming elements received while stopped are passed
     * to the discard handler, when configured.  You must invoke start() to queue elements and initiate processing.
     *
     * @param e to enqueue for distribution to a registered listener
     */
    public void receive(E e)
    {
        if(!mRunning.get())
        {
            discard(e, false);
            return;
        }

        boolean accepted = mQueue.offer(e);

        if(!accepted)
        {
            E dropped = mQueue.poll();

            if(dropped != null)
            {
                discard(dropped, true);
            }

            //Another producer can claim the freed slot.  Never block a real-time producer if that happens.
            accepted = mQueue.offer(e);

            if(!accepted)
            {
                discard(e, true);
            }
        }

        //Shutdown can race the initial running-state check.  Remove and clean up this element if stop() already
        //drained the queue before this producer completed its offer.
        if(accepted && !mRunning.get() && mQueue.remove(e))
        {
            discard(e, false);
        }
    }

    /**
     * Starts this buffer processor and allows queuing of incoming buffers.
     */
    public void start()
    {
        if(mRunning.compareAndSet(false, true))
        {
            if(mScheduledFuture != null)
            {
                //Note: this has to be false because downstream implementations may have acquired locks and they must
                //be able to release those locks or we'll get a deadlock situation.
                mScheduledFuture.cancel(false);
            }

            discardQueuedElements();
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

            Runnable r = (mHeartbeatManager != null ? new ProcessorWithHeartbeat() : new Processor());
            mScheduledFuture = executor.scheduleAtFixedRate(r, 0, mInterval, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops this buffer processor and releases any queued elements.
     */
    public void stop()
    {
        if(mRunning.compareAndSet(true, false))
        {
            if(mScheduledFuture != null)
            {
                //Note: this has to be false because downstream implementations may have acquired locks and they must
                //be able to release those locks or we'll get a deadlock situation.
                mScheduledFuture.cancel(false);
                mScheduledFuture = null;
                discardQueuedElements();
            }

            if(mPrivateExecutor != null)
            {
                mPrivateExecutor.shutdown();
                mPrivateExecutor = null;
            }
        }
    }

    /**
     * Stops this buffer processor and flushes the queue to the listener
     */
    public void flushAndStop()
    {
        if(mRunning.compareAndSet(true, false))
        {
            if(mScheduledFuture != null)
            {
                //Note: this has to be false because downstream implementations may have acquired locks and they must
                //be able to release those locks or we'll get a deadlock situation.
                mScheduledFuture.cancel(false);
                mScheduledFuture = null;
            }

            if(mPrivateExecutor != null)
            {
                mPrivateExecutor.shutdown();
                mPrivateExecutor = null;
            }

            List<E> elements = new ArrayList<>();

            mQueue.drainTo(elements);

            for(E element: elements)
            {
                if(mListener != null)
                {
                    try
                    {
                        mListener.receive(element);
                    }
                    catch(Throwable t)
                    {
                        mLog.error("Error while flusing and dispatching element [" + element.getClass() + "] to listener [" +
                                mListener.getClass() + "]", t);
                    }
                }
                else
                {
                    discard(element, false);
                }
            }
        }
    }

    /**
     * Indicates if this processor is currently running
     */
    public boolean isRunning()
    {
        return mRunning.get();
    }

    /**
     * Current number of queued elements awaiting processing.
     */
    public int getQueueSize()
    {
        return mQueue.size();
    }

    /**
     * Total number of elements discarded because this dispatcher's bounded queue was full.
     */
    public long getDroppedElementCount()
    {
        return mDroppedElementCount.get();
    }

    private void discardQueuedElements()
    {
        E element = mQueue.poll();

        while(element != null)
        {
            discard(element, false);
            element = mQueue.poll();
        }
    }

    private void discard(E element, boolean queueOverflow)
    {
        if(element == null)
        {
            return;
        }

        if(mDiscardHandler != null)
        {
            try
            {
                mDiscardHandler.accept(element);
            }
            catch(Throwable throwable)
            {
                mLog.error("Error discarding queued element for dispatcher [{}]", mThreadName, throwable);
            }
        }

        if(queueOverflow)
        {
            long dropped = mDroppedElementCount.incrementAndGet();

            if(dropped == 1 || dropped % 1000 == 0)
            {
                mLog.warn("Dispatcher [{}] dropped stale queued element at queue limit [{}], total dropped [{}]",
                    mThreadName, mMaximumQueueSize, dropped);
            }
        }
    }

    /**
     * Processes elements from the queue.  Note: this should only be invoked on the Processor thread.
     */
    private void process()
    {
        mQueue.drainTo(mDrainBuffer);

        try
        {
            for(E element: mDrainBuffer)
            {
                if(mRunning.get() && mListener != null)
                {
                    try
                    {
                        mListener.receive(element);
                    }
                    catch(Throwable throwable)
                    {
                        mLog.error("Error while dispatching element [" + element.getClass() + "] to listener [" +
                                mListener.getClass() + "]", throwable);
                    }
                }
                else
                {
                    discard(element, false);
                }
            }
        }
        finally
        {
            mDrainBuffer.clear();
        }
    }

    /**
     * Processor to service the buffer queue and distribute the buffers to the registered listener
     */
    class Processor implements Runnable
    {
        private final AtomicBoolean mRunning = new AtomicBoolean();

        @Override
        public void run()
        {
            if(mRunning.compareAndSet(false, true))
            {
                try
                {
                    process();
                }
                finally
                {
                    mRunning.set(false);
                }
            }
        }
    }

    /**
     * Processor to service the buffer queue and distribute the buffers to the registered listener.  Includes a
     * support for commanding a heart beat with each processing interval.
     */
    class ProcessorWithHeartbeat implements Runnable
    {
        private final AtomicBoolean mRunning = new AtomicBoolean();

        @Override
        public void run()
        {
            if(mRunning.compareAndSet(false, true))
            {
                try
                {
                    process();
                    mHeartbeatManager.broadcast();
                }
                catch(Throwable throwable)
                {
                    mLog.error("Error broadcasting heartbeat during Dispatcher processing interval", throwable);
                }
                finally
                {
                    mRunning.set(false);
                }
            }
        }
    }
}
