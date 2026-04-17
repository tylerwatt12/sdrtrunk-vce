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

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.heartbeat.HeartbeatManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final LinkedTransferQueue<E> mQueue = new LinkedTransferQueue<>();
    private final List<E> mDrainBuffer = new ArrayList<>();
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
     * @param threadName ignored (retained for call-site compatibility)
     * @param interval for processing each batch in milliseconds.
     * @param heartbeatManager to receive a heartbeat command at each processing interval.
     */
    public Dispatcher(String threadName, long interval, HeartbeatManager heartbeatManager)
    {
        this(threadName, interval);
        mHeartbeatManager = heartbeatManager;
    }

    /**
     * Constructs an instance that uses the shared dispatcher pool.  Use for channel sources and channel output
     * processors where thread-per-instance overhead is the primary concern.
     * @param threadName ignored (retained for call-site compatibility)
     * @param interval for processing each batch in milliseconds.
     */
    public Dispatcher(String threadName, long interval)
    {
        mInterval = interval;
        mExecutorType = ExecutorType.SHARED;
    }

    /**
     * Constructs an instance with the specified executor type.  Use {@link ExecutorType#PRIVATE} for I/O-bound
     * recorders and other users where slow tasks must not starve the shared channel-dispatch pool.
     * @param threadName ignored (retained for call-site compatibility)
     * @param interval for processing each batch in milliseconds.
     * @param executorType whether to use the shared pool or a private single-thread executor.
     */
    public Dispatcher(String threadName, long interval, ExecutorType executorType)
    {
        mInterval = interval;
        mExecutorType = executorType;
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
     * Primary input method for adding buffers to this processor.  Note: incoming buffers will be ignored if this
     * processor is in a stopped state.  You must invoke start() to allow incoming buffers and initiate buffer
     * processing.
     *
     * @param e to enqueue for distribution to a registered listener
     */
    public void receive(E e)
    {
        if(mRunning.get())
        {
            mQueue.add(e);
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

            mQueue.clear();
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
                mPrivateExecutor = Executors.newSingleThreadScheduledExecutor();
                executor = mPrivateExecutor;
            }

            Runnable r = (mHeartbeatManager != null ? new ProcessorWithHeartbeat() : new Processor());
            mScheduledFuture = executor.scheduleAtFixedRate(r, 0, mInterval, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops this buffer processor and waits up to two seconds for the processing thread to terminate.
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
                mQueue.clear();
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
     * Processes elements from the queue.  Note: this should only be invoked on the Processor thread.
     */
    private void process()
    {
        mQueue.drainTo(mDrainBuffer);

        for(E element: mDrainBuffer)
        {
            if(mRunning.get() && mListener != null)
            {
                try
                {
                    mListener.receive(element);
                }
                catch(Throwable t)
                {
                    mLog.error("Error while dispatching element [" + element.getClass() + "] to listener [" +
                            mListener.getClass() + "]", t);
                }
            }
        }

        mDrainBuffer.clear();
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
                process();
                mRunning.set(false);
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
                process();

                try
                {
                    mHeartbeatManager.broadcast();
                }
                catch(Throwable t)
                {
                    mLog.error("Error broadcasting heartbeat during Dispatcher processing interval", t);
                }

                mRunning.set(false);
            }
        }
    }
}
